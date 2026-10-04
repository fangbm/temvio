package dev.agenticscheduler.application.sync

import dev.agenticscheduler.sync.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProviderCredentialComparison(val source: DeviceId, val envelope: ProviderCredentialEnvelopeV1,
    val bindingDigest: String, val comparisonCode: String)

/** Hooks mark durable boundaries for deterministic crash tests; no hook grants user approval. */
fun interface ProviderInstallCheckpoint { suspend fun reached(phase: ProviderInstallPhase) }

class ProviderCredentialProvisioningService(
    private val repository: ProviderCredentialProvisioningRepository,
    private val secrets: PlatformProviderCredentialStore,
    private val hpke: PairingHpke,
    private val target: DeviceId,
    private val targetPrivateKey: PairingPrivateKeyMaterial,
    private val checkpoint: ProviderInstallCheckpoint = ProviderInstallCheckpoint {},
) {
    private val mutex = Mutex()

    /** Explicit local binding approval and selected ACTIVE source; caller publishes the returned reservation. */
    suspend fun reserveForExplicitUser(binding: WearProviderBindingMetadataV1, source: DeviceId): ProviderCredentialRevisionState = mutex.withLock {
        require(binding.credentialRequired)
        while (true) {
            val current = repository.openConfig(target, binding.providerConfigId)
            val revision = current.nextRevision()
            val next = current.copy(highestReservedRevision = revision, liveRevision = revision, selectedProvisioner = source,
                phase = ProviderReservationPhase.RESERVED, approvedBinding = binding, envelopeDigest = null, approvalBindingDigest = null, generation = current.generation + 1)
            if (repository.compareAndSet(current, next)) return@withLock next
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Fetch/decrypt does not import. It pins exactly this ciphertext and local binding for comparison. */
    suspend fun compare(source: DeviceId, envelope: ProviderCredentialEnvelopeV1): ProviderCredentialComparison = mutex.withLock {
        val current = requireState(envelope)
        validateReservation(current, source, envelope)
        val decrypted = decrypt(envelope)
        // The credential stays transient; the result exposes only ciphertext and comparison metadata.
        val transient = decodeCanonicalBase64Url(decrypted.credentialSecretBase64Url, null, "credential")
        transient.fill(0)
        val binding = requireNotNull(current.approvedBinding)
        val digest = providerBindingDigest(binding)
        val next = current.copy(phase = ProviderReservationPhase.AUTHENTICATED, envelopeDigest = providerEnvelopeDigest(envelope),
            approvalBindingDigest = digest, generation = current.generation + 1)
        if (!repository.compareAndSet(current, next)) reject(ProviderCredentialRejection.UNRESERVED_REVISION)
        ProviderCredentialComparison(source, envelope, digest, ProviderProvisioningSas.calculate(source, envelope, binding))
    }

    /** Separate from D8 pairing: this explicit action confirms the independently compared eight digits. */
    suspend fun approveComparisonByExplicitLocalUser(value: ProviderCredentialComparison, independentlyComparedCode: String) = mutex.withLock {
        val current = requireState(value.envelope)
        validateReservation(current, value.source, value.envelope)
        val binding = requireNotNull(current.approvedBinding)
        if (!Regex("[0-9]{8}").matches(independentlyComparedCode) || value.comparisonCode != independentlyComparedCode ||
            value.comparisonCode != ProviderProvisioningSas.calculate(value.source, value.envelope, binding) ||
            current.envelopeDigest != providerEnvelopeDigest(value.envelope) || current.approvalBindingDigest != value.bindingDigest ||
            value.bindingDigest != providerBindingDigest(binding) || current.phase != ProviderReservationPhase.AUTHENTICATED)
            reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
        val next = current.copy(phase = ProviderReservationPhase.USER_APPROVED, generation = current.generation + 1)
        if (!repository.compareAndSet(current, next)) reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
    }

    suspend fun installApproved(source: DeviceId, envelope: ProviderCredentialEnvelopeV1): ProviderCredentialAcknowledgementV1 = mutex.withLock {
        val current = requireState(envelope)
        if (envelope.credentialRevision == current.highestAcceptedRevision && current.activeReference != null && current.rejectionFloor < envelope.credentialRevision) {
            if (current.acceptedProvisioner != source || current.activeBinding == null) reject(ProviderCredentialRejection.WRONG_PROVISIONER)
            val incoming = decodeCanonicalBase64Url(decrypt(envelope).credentialSecretBase64Url, null, "credential")
            val equal = try {
                val stored = try { secrets.readProviderSecret(SecretReference(current.activeReference)) }
                    catch (_: SecureStoreUnavailableException) { reject(ProviderCredentialRejection.CREDENTIAL_UNAVAILABLE) }
                    ?: reject(ProviderCredentialRejection.CREDENTIAL_UNAVAILABLE)
                try { incoming.contentEquals(stored) } finally { stored.fill(0) }
            } finally { incoming.fill(0) }
            if (!equal) reject(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT)
            // CAS also validates locally owned config/binding and closes wipe races.
            val next = current.copy(generation = current.generation + 1)
            if (!repository.compareAndSet(current, next)) reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
            return@withLock ack(envelope, ProviderCredentialAcknowledgementResult.DUPLICATE)
        }
        validateReservation(current, source, envelope)
        if (current.phase != ProviderReservationPhase.USER_APPROVED || current.envelopeDigest != providerEnvelopeDigest(envelope) ||
            current.approvalBindingDigest != current.approvedBinding?.let(::providerBindingDigest)) reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED)
        val raw = decodeCanonicalBase64Url(decrypt(envelope).credentialSecretBase64Url, null, "credential")
        try {
            val slot = secrets.prepareProviderSlot()
            var journal = ProviderCredentialInstallJournal(target, current.providerConfigId, envelope.credentialRevision, slot.reference.value,
                slot.installIdentity, current.activeReference, current.activeInstallIdentity, ProviderInstallPhase.PREPARED_IMPORT)
            val preparedState = current.copy(generation = current.generation + 1)
            // If publication has an unknown result, recovery inspects durable ownership; no eager secret deletion.
            if (!repository.compareAndSet(current, preparedState, listOf(journal))) {
                secrets.deleteProviderSlot(slot)
                reject(ProviderCredentialRejection.UNRESERVED_REVISION)
            }
            checkpoint.reached(ProviderInstallPhase.PREPARED_IMPORT)
            secrets.importPreparedProviderSecret(slot, raw)
            val imported = journal.copy(phase = ProviderInstallPhase.SECRET_IMPORTED)
            if (!repository.updateJournal(journal, imported)) reject(ProviderCredentialRejection.UNRESERVED_REVISION)
            journal = imported
            checkpoint.reached(ProviderInstallPhase.SECRET_IMPORTED)
            val active = preparedState.copy(highestAcceptedRevision = envelope.credentialRevision, phase = ProviderReservationPhase.ACTIVE,
                activeReference = slot.reference.value, activeInstallIdentity = slot.installIdentity, activeBinding = current.approvedBinding,
                acceptedEnvelopeDigest = providerEnvelopeDigest(envelope), acceptedProvisioner = source, generation = preparedState.generation + 1)
            val committed = journal.copy(phase = ProviderInstallPhase.METADATA_COMMITTED)
            val retired = repository.journals(target).filter { it.installIdentity == current.activeInstallIdentity }
                .map { it.copy(phase = ProviderInstallPhase.CLEANUP_PENDING) }
            if (!repository.compareAndSet(preparedState, active, listOf(committed) + retired)) reject(ProviderCredentialRejection.UNRESERVED_REVISION)
            checkpoint.reached(ProviderInstallPhase.METADATA_COMMITTED)
            recoverLocked()
            ack(envelope, ProviderCredentialAcknowledgementResult.INSTALLED)
        } finally { raw.fill(0) }
    }

    /** Wipe/removal/revocation disables metadata first. Cleanup failure leaves durable work, never an active old binding. */
    suspend fun disableAndWipe(config: String) = mutex.withLock {
        while (true) {
            val current = repository.state(target, config) ?: reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
            val next = current.copy(rejectionFloor = current.nextRevision(), liveRevision = null, selectedProvisioner = null,
                phase = ProviderReservationPhase.DISABLED, approvedBinding = null, envelopeDigest = null, approvalBindingDigest = null,
                activeReference = null, activeInstallIdentity = null, activeBinding = null, generation = current.generation + 1)
            val journals = repository.journals(target).filter { it.providerConfigId == config && it.phase !in setOf(ProviderInstallPhase.REJECTED, ProviderInstallPhase.COMPLETED) }
                .map { it.copy(phase = ProviderInstallPhase.CLEANUP_PENDING) }
            if (repository.compareAndSet(current, next, journals)) break
        }
        recoverLocked()
    }

    /** Run before new operations after restart; handles both committed and uncommitted journal entries. */
    suspend fun recover() = mutex.withLock { recoverLocked() }
    private suspend fun recoverLocked() {
        for (journal in repository.journals(target)) {
            val state = repository.state(target, journal.providerConfigId) ?: reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
            if (state.activeReference == journal.preparedReference && state.activeInstallIdentity == journal.installIdentity) continue
            if (journal.phase == ProviderInstallPhase.COMPLETED) continue
            val rejected = journal.copy(phase = ProviderInstallPhase.REJECTED)
            if (journal != rejected && !repository.updateJournal(journal, rejected)) continue
            try {
                val slot = secrets.restoreProviderSlot(SecretReference(journal.preparedReference), journal.installIdentity)
                if (slot != null) secrets.deleteProviderSlot(slot)
                repository.updateJournal(rejected, rejected.copy(phase = ProviderInstallPhase.COMPLETED))
            } catch (_: SecureStoreUnavailableException) { /* Durable rejected ownership retries on next recovery. */ }
        }
    }
    private suspend fun requireState(e: ProviderCredentialEnvelopeV1): ProviderCredentialRevisionState {
        if (e.targetDeviceId != target) reject(ProviderCredentialRejection.TARGET_MISMATCH)
        return repository.state(target, e.providerConfigId) ?: reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
    }
    private fun validateReservation(s: ProviderCredentialRevisionState, source: DeviceId, e: ProviderCredentialEnvelopeV1) {
        if (e.credentialRevision <= s.rejectionFloor || e.credentialRevision < s.highestAcceptedRevision) reject(ProviderCredentialRejection.ROLLBACK_REJECTED)
        if (s.liveRevision != e.credentialRevision || s.phase == ProviderReservationPhase.DISABLED) reject(ProviderCredentialRejection.UNRESERVED_REVISION)
        if (s.selectedProvisioner != source) reject(ProviderCredentialRejection.WRONG_PROVISIONER)
    }
    private fun decrypt(e: ProviderCredentialEnvelopeV1): ProviderCredentialPlaintextV1 = when (val result = hpke.decryptProviderCredential(targetPrivateKey, e, target)) {
        is DecryptProviderCredentialResult.Authenticated -> result.plaintext
        is DecryptProviderCredentialResult.Rejected -> reject(result.reason)
    }
    private fun ack(e: ProviderCredentialEnvelopeV1, result: ProviderCredentialAcknowledgementResult) = ProviderCredentialAcknowledgementV1(
        targetDeviceId = target, providerConfigId = e.providerConfigId, credentialRevision = e.credentialRevision, envelopeDigestBase64Url = providerEnvelopeDigest(e), result = result)
    private fun reject(reason: ProviderCredentialRejection): Nothing = throw ProviderProvisioningException(reason)
}
