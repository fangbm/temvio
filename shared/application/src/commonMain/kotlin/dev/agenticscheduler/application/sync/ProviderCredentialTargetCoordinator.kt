package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

/** Public routing identity only; it contains neither a binding digest nor secret material. */
data class ProviderTargetFlow(val target: DeviceId, val config: String, val revision: Long, val provisioner: DeviceId)

/** Ephemeral display identity. An old screen/old coordinator cannot approve a replacement comparison. */
class ProviderTargetComparison internal constructor(
    val flow: ProviderTargetFlow, val providerLabel: String, val code: String,
) {
    override fun toString() = "ProviderTargetComparison(flow=$flow, code=<redacted>)"
}

enum class ProviderTargetEligibility {
    NO_ACTIVE_TARGET, MULTIPLE_ACTIVE_TARGETS, ACCOUNT_MISMATCH, TARGET_CHANGED,
    MISSING_DEVICE_CREDENTIAL, MISSING_TARGET_PRIVATE_KEY, INVALID_DIRECTORY,
    TARGET_NOT_ACTIVE, PROVISIONER_NOT_ACTIVE, PROVIDER_CONFIG_UNAVAILABLE, NO_CONFIG_SELECTED,
}
enum class ProviderTargetWork { RESUME, PUBLISH, FETCH, APPROVE, RECOVER, ACK }
enum class ProviderTargetTransportFailure { AUTHORIZATION, DELIVERY_CONFLICT, INVALID_RESPONSE, NETWORK, SERVER, REJECTED }
sealed interface ProviderTargetFailure {
    data class Protocol(val reason: ProviderCredentialRejection) : ProviderTargetFailure
    data class Transport(val reason: ProviderTargetTransportFailure) : ProviderTargetFailure
    data class Eligibility(val reason: ProviderTargetEligibility) : ProviderTargetFailure
    data object Internal : ProviderTargetFailure
}
enum class ProviderTargetPublication { UNCONFIRMED, PUBLISHED }
enum class ProviderTargetAckStatus { PENDING, CONFIRMED, EXPIRED, BLOCKED }
data class ProviderTargetAcknowledgement(val status: ProviderTargetAckStatus, val failure: ProviderTargetFailure? = null)

/** A sanitized projection of existing D9-03 state, never installation/approval authority. */
sealed interface ProviderTargetState {
    data object NotStarted : ProviderTargetState
    data class NotEligible(val reason: ProviderTargetEligibility) : ProviderTargetState
    data class Ready(val config: String, val providerLabel: String, val provisioners: List<DeviceId>) : ProviderTargetState
    data class Reserved(val flow: ProviderTargetFlow, val publication: ProviderTargetPublication,
        val comparisonRefreshRequired: Boolean) : ProviderTargetState
    data class ComparisonReady(val comparison: ProviderTargetComparison) : ProviderTargetState
    data class UserApproved(val flow: ProviderTargetFlow) : ProviderTargetState
    data class Installing(val flow: ProviderTargetFlow) : ProviderTargetState
    data class Installed(val flow: ProviderTargetFlow, val acknowledgement: ProviderTargetAcknowledgement,
        val duplicateDeliveryReady: Boolean = false) : ProviderTargetState
    data class Expired(val flow: ProviderTargetFlow) : ProviderTargetState
    data class RetryableFailure(val work: ProviderTargetWork, val flow: ProviderTargetFlow?, val failure: ProviderTargetFailure) : ProviderTargetState
    data class Blocked(val work: ProviderTargetWork, val flow: ProviderTargetFlow?, val failure: ProviderTargetFailure) : ProviderTargetState
    data class RecoveryRequired(val flow: ProviderTargetFlow?) : ProviderTargetState
}

/** Presentation consumes only this surface. Construction/security handles belong to a platform root. */
interface ProviderCredentialTarget {
    val state: StateFlow<ProviderTargetState>
    suspend fun resume(config: String)
    suspend fun reserveAndPublish(binding: WearProviderBindingMetadataV1, provisioner: DeviceId)
    suspend fun fetchDelivery()
    suspend fun approveComparison(displayed: ProviderTargetComparison, independentlyComparedCode: String)
    suspend fun installApproved()
    suspend fun retry()
    suspend fun recover()
}

/**
 * One lifecycle-owned coordinator serializes the target's selected-config interaction, including recovery.
 * Callers cancel/join outstanding intents before replacing it. Repository CAS remains the durable authority.
 * It owns no scope, timer, database, counter allocator, crypto algorithm or secret-import implementation.
 */
class ProviderCredentialTargetCoordinator(
    private val account: AccountId,
    private val enrollments: LocalEnrollmentRepository,
    private val privateKeys: PlatformPairingPrivateKeyStore,
    private val credentials: PlatformDeviceCredentialStore,
    private val secrets: PlatformProviderCredentialStore,
    private val hpke: PairingHpke,
    private val repository: ProviderCredentialProvisioningRepository,
    private val transport: ProviderCredentialTransport,
    private val directory: RevocationRotationTransport,
    private val bindingFor: suspend (String) -> WearProviderBindingMetadataV1?,
    private val nowEpochSeconds: () -> Long,
    private val checkpoint: ProviderInstallCheckpoint = ProviderInstallCheckpoint {},
) : ProviderCredentialTarget {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ProviderTargetState>(ProviderTargetState.NotStarted)
    override val state = mutableState.asStateFlow()
    private var config: String? = null
    private var pinnedTarget: LocalEnrollmentState.Active? = null
    private var published: ProviderCredentialReservationRequestV1? = null
    private var expired: ProviderTargetFlow? = null
    private var comparison: ProviderCredentialComparison? = null
    private var displayed: ProviderTargetComparison? = null
    private var duplicate: ProviderCredentialComparison? = null
    private var receipt: ProviderCredentialAcknowledgementV1? = null
    private var confirmed: ProviderCredentialAcknowledgementV1? = null
    private var work = ProviderTargetWork.RESUME
    private var flow: ProviderTargetFlow? = null

    private class Ineligible(val reason: ProviderTargetEligibility) : Exception()
    private data class Context(val active: LocalEnrollmentState.Active, val service: ProviderCredentialProvisioningService,
        val binding: WearProviderBindingMetadataV1, val devices: List<ClientActiveDeviceDirectoryEntry>)

    override suspend fun resume(config: String) = intent(ProviderTargetWork.RESUME) {
        select(config); resumeCurrent()
    }
    override suspend fun recover() = intent(ProviderTargetWork.RECOVER) { resumeCurrent() }

    private suspend fun resumeCurrent() {
        try { project(context()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Offline/unauthorized directory refresh cannot erase a recovered local commit.
            // This is display-only recovery; ACK progression still requires fresh eligibility.
            if (problem(failure).first !is ProviderTargetFailure.Transport) throw failure
            val target = pinnedTarget ?: throw failure
            val row = repository.state(target.deviceId, requireNotNull(config)) ?: throw failure
            if (row.phase != ProviderReservationPhase.ACTIVE) throw failure
            if (enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()?.let { sameIdentity(it, target) } != true)
                throw Ineligible(ProviderTargetEligibility.TARGET_CHANGED)
            if (row.activeBinding != bindingFor(requireNotNull(config))) reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
            readableCommitted(row); committedReceipt(row)
            flow = identity(row); work = ProviderTargetWork.ACK
            val (reason, retryable) = problem(failure)
            mutableState.value = ProviderTargetState.Installed(requireNotNull(flow), ProviderTargetAcknowledgement(
                if (retryable) ProviderTargetAckStatus.PENDING else ProviderTargetAckStatus.BLOCKED, reason))
        }
    }

    override suspend fun reserveAndPublish(binding: WearProviderBindingMetadataV1, provisioner: DeviceId) = intent(ProviderTargetWork.PUBLISH) {
        select(binding.providerConfigId)
        val ctx = context()
        if (!binding.credentialRequired || ctx.binding != binding) reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
        requireSource(ctx, provisioner)
        val old = repository.state(ctx.active.deviceId, binding.providerConfigId)
        val reusable = old?.takeIf { it.phase in setOf(ProviderReservationPhase.RESERVED, ProviderReservationPhase.AUTHENTICATED, ProviderReservationPhase.USER_APPROVED) &&
            it.liveRevision != null && it.liveRevision > it.rejectionFloor && it.selectedProvisioner == provisioner && it.approvedBinding == binding && identity(it) != expired }
        val reserved = reusable ?: ctx.service.reserveForExplicitUser(binding, provisioner).also { invalidateComparison(); expired = null }
        flow = identity(reserved)
        publishCurrent(ctx, reserved)
    }
    override suspend fun fetchDelivery() = intent(ProviderTargetWork.FETCH) { fetchCurrent(context()) }

    override suspend fun approveComparison(displayed: ProviderTargetComparison, independentlyComparedCode: String) = intent(ProviderTargetWork.APPROVE) {
        // Code equality alone cannot distinguish an old revision/source/binding or an eight-digit collision.
        if (displayed !== this.displayed) reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
        val ctx = context(); val row = current(ctx)
        val value = comparison ?: reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
        validateCompared(row, value, ctx)
        ctx.service.approveComparisonByExplicitLocalUser(value, independentlyComparedCode)
        this.displayed = null
        project(ctx)
    }

    override suspend fun installApproved() = intent(ProviderTargetWork.RECOVER) {
        var ctx = context(); var row = current(ctx)
        if (row.phase == ProviderReservationPhase.ACTIVE && duplicate == null) {
            // Repeated clicks/ACK recovery never call installApproved on a committed credential.
            acknowledgeCommitted(row); return@intent
        }
        if (row.phase != ProviderReservationPhase.USER_APPROVED && row.phase != ProviderReservationPhase.ACTIVE)
            reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
        if (comparison == null && duplicate == null) {
            work = ProviderTargetWork.FETCH
            fetchCurrent(ctx)
            if (mutableState.value !is ProviderTargetState.UserApproved && duplicate == null) return@intent
            ctx = context(); row = current(ctx)
        }
        val value = duplicate ?: comparison ?: reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
        if (row.phase == ProviderReservationPhase.USER_APPROVED) validateCompared(row, value, ctx)
        else validateDeliveryIdentity(request(row), value.source, value.envelope)
        flow = identity(row); work = ProviderTargetWork.RECOVER
        mutableState.value = ProviderTargetState.Installing(requireNotNull(flow))
        // This is the only call site for import/install, and requires this explicit public user intent.
        receipt = ctx.service.installApproved(value.source, value.envelope)
        confirmed = null; invalidateComparison()
        mutableState.value = ProviderTargetState.Installed(requireNotNull(flow), ProviderTargetAcknowledgement(ProviderTargetAckStatus.PENDING))
        acknowledgeCommitted(current(ctx))
    }

    override suspend fun retry() = intent(work) {
        if (work == ProviderTargetWork.ACK) {
            // Network eligibility failures belong to ACK state, never erase the local commit fact.
            val target = pinnedTarget ?: throw Ineligible(ProviderTargetEligibility.NO_ACTIVE_TARGET)
            val row = repository.state(target.deviceId, requireNotNull(config)) ?: reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
            acknowledgeCommitted(row); return@intent
        }
        val ctx = context()
        when (work) {
            ProviderTargetWork.PUBLISH -> publishCurrent(ctx, current(ctx))
            ProviderTargetWork.FETCH -> fetchCurrent(ctx)
            // Retrying an interrupted install only recovers/projects. It never repeats approval/import.
            else -> project(ctx)
        }
    }

    private fun select(value: String) {
        MutationId(value)
        if (config != value) { invalidateComparison(); published = null; expired = null; receipt = null; confirmed = null; flow = null }
        config = value
    }
    private suspend fun context(): Context {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        if (active.isEmpty()) throw Ineligible(ProviderTargetEligibility.NO_ACTIVE_TARGET)
        if (active.size != 1) throw Ineligible(ProviderTargetEligibility.MULTIPLE_ACTIVE_TARGETS)
        val target = active.single()
        if (target.accountId != account) throw Ineligible(ProviderTargetEligibility.ACCOUNT_MISMATCH)
        pinnedTarget?.let { if (!sameIdentity(it, target)) throw Ineligible(ProviderTargetEligibility.TARGET_CHANGED) }
        pinnedTarget = target
        if (credentials.load(target.deviceCredentialReference) == null) throw Ineligible(ProviderTargetEligibility.MISSING_DEVICE_CREDENTIAL)
        val key = privateKeys.privateKey(target.hpkePrivateKeyReference) ?: throw Ineligible(ProviderTargetEligibility.MISSING_TARGET_PRIVATE_KEY)
        val selected = config ?: throw Ineligible(ProviderTargetEligibility.NO_CONFIG_SELECTED)
        val binding = bindingFor(selected) ?: throw Ineligible(ProviderTargetEligibility.PROVIDER_CONFIG_UNAVAILABLE)
        if (binding.providerConfigId != selected || !binding.credentialRequired) reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
        val service = ProviderCredentialProvisioningService(repository, secrets, hpke, target.deviceId, key, checkpoint)
        service.recover()
        val devices = directory.activeDevices()
        if (devices.map { it.deviceId }.distinct().size != devices.size || devices.any {
            runCatching { DeviceId(it.deviceId); HpkePublicKeyBase64Url(it.hpkePublicKeyBase64Url) }.isFailure
        }) throw Ineligible(ProviderTargetEligibility.INVALID_DIRECTORY)
        val self = devices.singleOrNull { it.deviceId == target.deviceId.value }
            ?: throw Ineligible(ProviderTargetEligibility.TARGET_NOT_ACTIVE)
        if (self.hpkePublicKeyBase64Url != target.hpkePublicKey.value) throw Ineligible(ProviderTargetEligibility.INVALID_DIRECTORY)
        // A network response cannot bless a locally replaced/revoked identity.
        if (enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()?.let { sameIdentity(it, target) } != true)
            throw Ineligible(ProviderTargetEligibility.TARGET_CHANGED)
        return Context(target, service, binding, devices)
    }
    private fun sameIdentity(a: LocalEnrollmentState.Active, b: LocalEnrollmentState.Active) =
        a.accountId == b.accountId && a.deviceId == b.deviceId && a.hpkePublicKey == b.hpkePublicKey &&
            a.hpkePrivateKeyReference == b.hpkePrivateKeyReference && a.deviceCredentialReference == b.deviceCredentialReference

    private suspend fun current(ctx: Context): ProviderCredentialRevisionState {
        val row = repository.state(ctx.active.deviceId, requireNotNull(config)) ?: reject(ProviderCredentialRejection.UNRESERVED_REVISION)
        flow = identity(row)
        val expected = if (row.phase == ProviderReservationPhase.ACTIVE) row.activeBinding else row.approvedBinding
        if (expected != null && expected != ctx.binding) { invalidateComparison(); reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH) }
        row.selectedProvisioner?.let { requireSource(ctx, it) }
        return row
    }
    private fun requireSource(ctx: Context, source: DeviceId) {
        if (ctx.devices.none { it.deviceId == source.value }) throw Ineligible(ProviderTargetEligibility.PROVISIONER_NOT_ACTIVE)
    }
    private fun identity(row: ProviderCredentialRevisionState): ProviderTargetFlow? =
        row.liveRevision?.let { revision -> row.selectedProvisioner?.let { ProviderTargetFlow(row.targetDeviceId, row.providerConfigId, revision, it) } }
    private fun request(row: ProviderCredentialRevisionState): ProviderCredentialReservationRequestV1 {
        val value = identity(row) ?: reject(ProviderCredentialRejection.UNRESERVED_REVISION)
        if (row.phase == ProviderReservationPhase.DISABLED || value.revision <= row.rejectionFloor) reject(ProviderCredentialRejection.ROLLBACK_REJECTED)
        return ProviderCredentialReservationRequestV1(value.target, value.config, value.revision, value.provisioner)
    }
    private suspend fun publishCurrent(ctx: Context, row: ProviderCredentialRevisionState) {
        val value = request(row); work = ProviderTargetWork.PUBLISH
        if (published != value) { transport.publish(value); published = value }
        project(context())
    }
    private suspend fun fetchCurrent(ctx: Context) {
        // A new fetch replaces the displayed comparison even if its response is rejected.
        invalidateComparison()
        val before = current(ctx); val expected = request(before); work = ProviderTargetWork.FETCH
        val delivery = transport.fetch(expected.providerConfigId)
        val fresh = context(); val row = current(fresh)
        if (request(row) != expected) { invalidateComparison(); reject(ProviderCredentialRejection.UNRESERVED_REVISION) }
        if (delivery == null) { project(fresh); return }
        if (delivery.request.provisionerDeviceId != expected.provisionerDeviceId) reject(ProviderCredentialRejection.WRONG_PROVISIONER)
        if (delivery.request != expected) reject(ProviderCredentialRejection.UNRESERVED_REVISION)
        published = expected
        if (delivery.state == ProviderCredentialMailboxState.DELIVERY_EXPIRED || delivery.expiresAtEpochSeconds <= nowEpochSeconds()) {
            expired = identity(row); invalidateComparison(); mutableState.value = ProviderTargetState.Expired(requireNotNull(expired)); return
        }
        when (delivery.state) {
            ProviderCredentialMailboxState.DELIVERED -> {
                val envelope = requireNotNull(delivery.envelope)
                validateDeliveryIdentity(expected, expected.provisionerDeviceId, envelope)
                when (row.phase) {
                    ProviderReservationPhase.USER_APPROVED -> {
                        val restored = ProviderCredentialComparison(expected.provisionerDeviceId, envelope, providerBindingDigest(fresh.binding),
                            ProviderProvisioningSas.calculate(expected.provisionerDeviceId, envelope, fresh.binding))
                        validateCompared(row, restored, fresh)
                        comparison = restored; displayed = null // Do not call compare(): it would erase durable USER_APPROVED.
                    }
                    ProviderReservationPhase.ACTIVE -> {
                        duplicate = ProviderCredentialComparison(expected.provisionerDeviceId, envelope, providerBindingDigest(fresh.binding),
                            ProviderProvisioningSas.calculate(expected.provisionerDeviceId, envelope, fresh.binding))
                    }
                    else -> {
                        comparison = fresh.service.compare(expected.provisionerDeviceId, envelope)
                        displayed = ProviderTargetComparison(requireNotNull(identity(row)), fresh.binding.model, requireNotNull(comparison).comparisonCode)
                    }
                }
            }
            ProviderCredentialMailboxState.ACKNOWLEDGED -> {
                if (row.phase != ProviderReservationPhase.ACTIVE) reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
                // Relay status alone never establishes installation or a matching digest receipt.
            }
            else -> Unit
        }
        project(fresh)
    }
    private fun validateDeliveryIdentity(expected: ProviderCredentialReservationRequestV1, source: DeviceId, envelope: ProviderCredentialEnvelopeV1) {
        if (source != expected.provisionerDeviceId) reject(ProviderCredentialRejection.WRONG_PROVISIONER)
        if (envelope.targetDeviceId != expected.targetDeviceId) reject(ProviderCredentialRejection.TARGET_MISMATCH)
        if (envelope.providerConfigId != expected.providerConfigId) reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
        if (envelope.credentialRevision != expected.credentialRevision) reject(ProviderCredentialRejection.UNRESERVED_REVISION)
    }
    private fun validateCompared(row: ProviderCredentialRevisionState, value: ProviderCredentialComparison, ctx: Context) {
        validateDeliveryIdentity(request(row), value.source, value.envelope)
        if (row.envelopeDigest != providerEnvelopeDigest(value.envelope) || row.approvalBindingDigest != value.bindingDigest ||
            value.bindingDigest != providerBindingDigest(ctx.binding)) reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
    }
    private suspend fun project(ctx: Context) {
        if (repository.state(ctx.active.deviceId, requireNotNull(config)) == null) {
            mutableState.value = ProviderTargetState.Ready(requireNotNull(config), ctx.binding.model, ctx.devices.map { DeviceId(it.deviceId) }.sortedBy { it.value }); return
        }
        val row = current(ctx)
        if (row.phase == ProviderReservationPhase.NONE) {
            mutableState.value = ProviderTargetState.Ready(requireNotNull(config), ctx.binding.model, ctx.devices.map { DeviceId(it.deviceId) }.sortedBy { it.value }); return
        }
        if (row.phase == ProviderReservationPhase.DISABLED) { invalidateComparison(); reject(ProviderCredentialRejection.ROLLBACK_REJECTED) }
        val identity = requireNotNull(identity(row)); flow = identity
        if (identity == expired) { mutableState.value = ProviderTargetState.Expired(identity); return }
        mutableState.value = when (row.phase) {
            ProviderReservationPhase.ACTIVE -> {
                readableCommitted(row)
                val ack = committedReceipt(row)
                work = ProviderTargetWork.ACK
                ProviderTargetState.Installed(identity, ProviderTargetAcknowledgement(if (confirmed == ack) ProviderTargetAckStatus.CONFIRMED else ProviderTargetAckStatus.PENDING), duplicate != null)
            }
            ProviderReservationPhase.USER_APPROVED -> ProviderTargetState.UserApproved(identity)
            ProviderReservationPhase.AUTHENTICATED -> {
                val public = displayed
                val value = comparison
                work = ProviderTargetWork.FETCH
                if (public != null && value != null) {
                    validateCompared(row, value, ctx); ProviderTargetState.ComparisonReady(public)
                } else ProviderTargetState.Reserved(identity, publication(row), true)
            }
            else -> {
                val publication = publication(row)
                work = if (publication == ProviderTargetPublication.UNCONFIRMED) ProviderTargetWork.PUBLISH else ProviderTargetWork.FETCH
                ProviderTargetState.Reserved(identity, publication, false)
            }
        }
    }
    private fun publication(row: ProviderCredentialRevisionState) = if (published == request(row)) ProviderTargetPublication.PUBLISHED else ProviderTargetPublication.UNCONFIRMED
    private suspend fun readableCommitted(row: ProviderCredentialRevisionState) {
        val bytes = row.activeReference?.let { secrets.readProviderSecret(SecretReference(it)) } ?: reject(ProviderCredentialRejection.CREDENTIAL_UNAVAILABLE)
        bytes.fill(0)
    }
    private fun committedReceipt(row: ProviderCredentialRevisionState): ProviderCredentialAcknowledgementV1 {
        if (row.phase != ProviderReservationPhase.ACTIVE || row.activeReference == null || row.highestAcceptedRevision <= row.rejectionFloor)
            reject(ProviderCredentialRejection.UNRESERVED_REVISION)
        receipt?.takeIf { it.targetDeviceId == row.targetDeviceId && it.providerConfigId == row.providerConfigId && it.credentialRevision == row.highestAcceptedRevision }?.let { return it }
        // Accepted metadata is sufficient for an informational duplicate receipt after a lost response/restart.
        return ProviderCredentialAcknowledgementV1(targetDeviceId = row.targetDeviceId, providerConfigId = row.providerConfigId,
            credentialRevision = row.highestAcceptedRevision, envelopeDigestBase64Url = row.acceptedEnvelopeDigest ?: reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST),
            result = ProviderCredentialAcknowledgementResult.DUPLICATE).also { receipt = it }
    }
    private suspend fun acknowledgeCommitted(row: ProviderCredentialRevisionState) {
        val id = requireNotNull(identity(row)); readableCommitted(row)
        val ack = committedReceipt(row); work = ProviderTargetWork.ACK
        mutableState.value = ProviderTargetState.Installed(id, ProviderTargetAcknowledgement(ProviderTargetAckStatus.PENDING))
        try {
            val fresh = context(); val active = current(fresh)
            if (active.phase != ProviderReservationPhase.ACTIVE || active.highestAcceptedRevision != ack.credentialRevision)
                reject(ProviderCredentialRejection.UNRESERVED_REVISION)
            if (confirmed == ack) {
                mutableState.value = ProviderTargetState.Installed(id, ProviderTargetAcknowledgement(ProviderTargetAckStatus.CONFIRMED)); return
            }
            transport.acknowledge(ack); confirmed = ack
            mutableState.value = ProviderTargetState.Installed(id, ProviderTargetAcknowledgement(ProviderTargetAckStatus.CONFIRMED))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val expired = failure is ProviderCredentialTransportException && failure.status == 410
            val (problem, retryable) = problem(failure)
            mutableState.value = ProviderTargetState.Installed(id, ProviderTargetAcknowledgement(
                if (expired) ProviderTargetAckStatus.EXPIRED else if (retryable) ProviderTargetAckStatus.PENDING else ProviderTargetAckStatus.BLOCKED, problem))
        }
    }
    private fun invalidateComparison() { comparison = null; displayed = null; duplicate = null }
    private suspend fun intent(initial: ProviderTargetWork, body: suspend () -> Unit) = mutex.withLock {
        work = initial
        try { body() }
        catch (cancelled: CancellationException) {
            invalidateComparison()
            if (mutableState.value !is ProviderTargetState.Installed) mutableState.value = ProviderTargetState.RecoveryRequired(flow)
            throw cancelled
        } catch (failure: Ineligible) {
            invalidateComparison(); mutableState.value = ProviderTargetState.NotEligible(failure.reason)
        } catch (failure: Exception) {
            if (failure is ProviderCredentialTransportException && failure.status == 410 && flow != null) {
                expired = flow; invalidateComparison(); mutableState.value = ProviderTargetState.Expired(requireNotNull(flow))
            } else {
                val (problem, retryable) = problem(failure)
                mutableState.value = if (retryable) ProviderTargetState.RetryableFailure(work, flow, problem) else ProviderTargetState.Blocked(work, flow, problem)
            }
        }
    }
    private fun problem(failure: Exception): Pair<ProviderTargetFailure, Boolean> = when (failure) {
        is Ineligible -> ProviderTargetFailure.Eligibility(failure.reason) to false
        is ProviderProvisioningException -> ProviderTargetFailure.Protocol(failure.reason) to false
        is SecureStoreUnavailableException -> ProviderTargetFailure.Protocol(ProviderCredentialRejection.SECURE_STORE_UNAVAILABLE) to false
        is HttpRequestTimeoutException, is IOException -> ProviderTargetFailure.Transport(ProviderTargetTransportFailure.NETWORK) to true
        is ProviderCredentialTransportException -> ProviderTargetFailure.Transport(when {
            failure.status in setOf(401,403) -> ProviderTargetTransportFailure.AUTHORIZATION
            failure.status == 409 -> ProviderTargetTransportFailure.DELIVERY_CONFLICT
            failure.retryable -> ProviderTargetTransportFailure.SERVER
            failure.status == 0 -> ProviderTargetTransportFailure.INVALID_RESPONSE
            else -> ProviderTargetTransportFailure.REJECTED
        }) to failure.retryable
        is SyncTransportException -> {
            val status = failure.message?.removePrefix("HTTP_")?.toIntOrNull()
            ProviderTargetFailure.Transport(when {
                status in setOf(401,403) || failure.message == "MISSING_DEVICE_CREDENTIAL" -> ProviderTargetTransportFailure.AUTHORIZATION
                status != null && (status in setOf(408,429) || status >= 500) -> ProviderTargetTransportFailure.SERVER
                else -> ProviderTargetTransportFailure.INVALID_RESPONSE
            }) to (status != null && (status in setOf(408,429) || status >= 500))
        }
        else -> ProviderTargetFailure.Internal to false
    }
    private fun reject(reason: ProviderCredentialRejection): Nothing = throw ProviderProvisioningException(reason)
}
