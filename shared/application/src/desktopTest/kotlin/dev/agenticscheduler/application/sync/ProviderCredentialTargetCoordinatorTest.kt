package dev.agenticscheduler.application.sync

import androidx.room3.useWriterConnection
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Real Room files, existing service, tracked platform-store implementation and Tink. Only network ports are fake. */
class ProviderCredentialTargetCoordinatorTest {
    private val account = AccountId("target-coordinator-account")
    private val target = DeviceId("WearTarget:opaque-A_01")
    private val source = DeviceId("PhoneSource:opaque-B_02")
    private val other = DeviceId("OtherSource:opaque-C_03")
    private val config = ProviderConfigId("00000000-0000-7000-8000-000000000101")
    private val binding = WearProviderBindingMetadataV1(providerConfigId = config.value, baseUrl = "https://provider.example/v1",
        model = "Explicit fixture model", maxContextUnits = 32768, reservedOutputUnits = 4096,
        streamingSupported = true, toolCallingSupported = true, credentialRequired = true)
    private val secret = "TARGET_COORDINATOR_SECRET_CANARY"

    private class Backend : DesktopSecureBackend {
        override val referencePrefix = "target-coordinator-test://"
        val values = mutableMapOf<String, ByteArray>()
        var providerUnavailable = false
        override fun store(id: String, value: ByteArray) { values[id] = value.copyOf() }
        override fun read(id: String): ByteArray? = values[id]?.copyOf()
        override fun readProvider(id: String): ByteArray? {
            if (providerUnavailable) throw SecureStoreUnavailableException("PRIVATE_BACKEND_DIAGNOSTIC")
            return values[id]?.copyOf()
        }
        override fun delete(id: String) { values.remove(id) }
    }
    private inner class Fixture : AutoCloseable {
        val path = Files.createTempFile("target-coordinator-", ".db")
        var db: AgenticSchedulerDatabase = openDesktopDatabase(path.toString())
        val backend = Backend()
        val hpke = TinkPairingHpke()
        var store = DesktopPlatformSecureStore(backend, hpke)
        var repo = RoomProviderCredentialProvisioningRepository(db)
        var local = RoomAgentStateRepository(db)
        var enrollments = RoomLocalEnrollmentRepository(db)
        lateinit var active: LocalEnrollmentState.Active
        var now = 1_800_000_000L
        var imports = 0
        var prepares = 0
        val mailbox = Mailbox()
        var devices = emptyList<ClientActiveDeviceDirectoryEntry>()
        var directoryFailure: Exception? = null
        val directory = object : RevocationRotationTransport {
            override suspend fun activeDevices(): List<ClientActiveDeviceDirectoryEntry> {
                directoryFailure?.let { throw it }; return devices
            }
            override suspend fun revokeDeviceAndRotate(deviceId: String, request: ClientAtomicRevocationRequest) = error("NOT_A_TARGET_INTENT")
        }
        private fun providerStore(): PlatformProviderCredentialStore {
            val current = store
            return object : PlatformProviderCredentialStore by current {
                override suspend fun prepareProviderSlot(): PreparedProviderSecretSlot { prepares++; return current.prepareProviderSlot() }
                override suspend fun importPreparedProviderSecret(slot: PreparedProviderSecretSlot, credential: ByteArray) {
                    imports++; current.importPreparedProviderSecret(slot, credential)
                }
            }
        }
        suspend fun initialize() {
            val key = store.generatePairingDeviceKey()
            val deviceCredential = store.store(DeviceCredential(encodeCanonicalBase64Url(ByteArray(32) { 7 })))
            active = LocalEnrollmentState.Active(account, target, EnrollmentRequestId("explicit-request"), key.publicKey,
                key.privateKeyReference, SyncSpaceId("target-space"), SecretReference("test://amk"), deviceCredential)
            enrollments.saveActive(active)
            local.saveProviderConfig(ProviderConfig(config, binding.baseUrl, binding.model, binding.maxContextUnits,
                binding.reservedOutputUnits, binding.streamingSupported, binding.toolCallingSupported, null))
            val sourceKey = hpke.generateDeviceKeyPair().publicKey.value
            devices = listOf(ClientActiveDeviceDirectoryEntry(target.value, key.publicKey.value),
                ClientActiveDeviceDirectoryEntry(source.value, sourceKey), ClientActiveDeviceDirectoryEntry(other.value, sourceKey))
        }
        fun coordinator(checkpoint: ProviderInstallCheckpoint = ProviderInstallCheckpoint {}, configuredAccount: AccountId = account) =
            ProviderCredentialTargetCoordinator(configuredAccount, enrollments, store, store, providerStore(), hpke, repo,
                mailbox, directory, bindingFor = { id -> local.providerConfig(ProviderConfigId(id))?.provisioningBinding(ProviderConfigId(id), true) },
                nowEpochSeconds = { now }, checkpoint = checkpoint)
        suspend fun directService() = ProviderCredentialProvisioningService(repo, providerStore(), hpke, target,
            requireNotNull(store.privateKey(active.hpkePrivateKeyReference)))
        fun reopen() {
            db.close(); db = openDesktopDatabase(path.toString()); repo = RoomProviderCredentialProvisioningRepository(db)
            local = RoomAgentStateRepository(db); enrollments = RoomLocalEnrollmentRepository(db)
            store = DesktopPlatformSecureStore(backend, hpke)
        }
        suspend fun compare(c: ProviderCredentialTargetCoordinator): ProviderTargetComparison {
            c.reserveAndPublish(binding, source); mailbox.deliver()
            c.fetchDelivery(); return assertIs<ProviderTargetState.ComparisonReady>(c.state.value).comparison
        }
        suspend fun approved(c: ProviderCredentialTargetCoordinator) {
            val value = compare(c); c.approveComparison(value, value.code)
            assertIs<ProviderTargetState.UserApproved>(c.state.value)
        }
        suspend fun assertUninstalled() {
            assertEquals(0, imports); assertEquals(0, prepares); assertTrue(repo.journals(target).isEmpty())
            assertNull(local.providerConfig(config)?.credentialReference); assertTrue(mailbox.acks.isEmpty())
        }
        suspend fun deleteEnrollment() { db.useWriterConnection { it.usePrepared("DELETE FROM local_pairing_enrollment") { s -> s.step() } } }
        inner class Mailbox : ProviderCredentialTransport {
            val publications = mutableListOf<ProviderCredentialReservationRequestV1>()
            val acks = mutableListOf<ProviderCredentialAcknowledgementV1>()
            var request: ProviderCredentialReservationRequestV1? = null
            var envelope: ProviderCredentialEnvelopeV1? = null
            var response: ProviderCredentialMailboxDeliveryV1? = null
            var deadline = now + 604800
            var publishFailure: Exception? = null
            var fetchFailure: Exception? = null
            var ackFailure: Exception? = null
            var publishStarted: CompletableDeferred<Unit>? = null
            var publishContinue: CompletableDeferred<Unit>? = null
            var fetchStarted: CompletableDeferred<Unit>? = null
            var fetchContinue: CompletableDeferred<Unit>? = null
            override suspend fun publish(request: ProviderCredentialReservationRequestV1) {
                publications += request
                if (this.request != request) { this.request = request; envelope = null; deadline = now + 604800 }
                publishStarted?.complete(Unit); publishContinue?.await(); publishFailure?.let { throw it }
            }
            override suspend fun fetch(config: String): ProviderCredentialMailboxDeliveryV1? {
                assertEquals(this@ProviderCredentialTargetCoordinatorTest.config.value, config)
                fetchStarted?.complete(Unit); fetchContinue?.await(); fetchFailure?.let { throw it }
                if (now >= deadline) throw ProviderCredentialTransportException(410, "DELIVERY_EXPIRED")
                response?.let { return it }
                return request?.let { ProviderCredentialMailboxDeliveryV1(it,
                    if (envelope != null) ProviderCredentialMailboxState.DELIVERED else ProviderCredentialMailboxState.REQUESTED, deadline, envelope) }
            }
            override suspend fun acknowledge(value: ProviderCredentialAcknowledgementV1) {
                acks += value; ackFailure?.let { throw it }
                envelope = null
            }
            fun deliver(value: String = secret, revision: Long = requireNotNull(request).credentialRevision) {
                envelope = hpke.encryptProviderCredential(active.hpkePublicKey, ProviderCredentialPlaintextV1(targetDeviceId = target,
                    providerConfigId = this@ProviderCredentialTargetCoordinatorTest.config.value, credentialRevision = revision,
                    credentialSecretBase64Url = encodeCanonicalBase64Url(value.encodeToByteArray())))
            }
            override suspend fun assignedRequest(): ProviderCredentialMailboxDeliveryV1? = error("SOURCE_FLOW_NOT_ALLOWED")
            override suspend fun uploadExact(canonicalEnvelope: ByteArray): Unit = error("SOURCE_FLOW_NOT_ALLOWED")
        }
        override fun close() { db.close(); Files.deleteIfExists(path) }
    }

    @Test fun `happy path uses exact opaque identity real comparison explicit approval install and ACK`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); c.resume(config.value)
            assertEquals(binding.model, assertIs<ProviderTargetState.Ready>(c.state.value).providerLabel)
            val comparison = h.compare(c)
            assertEquals(target, comparison.flow.target); assertEquals(source, comparison.flow.provisioner)
            assertEquals(ProviderProvisioningSas.calculate(source, requireNotNull(h.mailbox.envelope), binding), comparison.code)
            assertEquals(8, comparison.code.length); h.assertUninstalled()
            c.approveComparison(comparison, comparison.code); assertIs<ProviderTargetState.UserApproved>(c.state.value); h.assertUninstalled()
            c.installApproved()
            assertEquals(ProviderTargetAckStatus.CONFIRMED, assertIs<ProviderTargetState.Installed>(c.state.value).acknowledgement.status)
            assertEquals(1, h.imports); assertEquals(1L, h.repo.state(target, config.value)?.highestAcceptedRevision)
            assertEquals(ProviderCredentialAcknowledgementResult.INSTALLED, h.mailbox.acks.single().result)
            assertEquals(secret, h.store.readProviderSecret(requireNotNull(h.local.providerConfig(config)?.credentialReference))?.decodeToString())
        }
    }
    @Test fun `wrong code never approves installs or ACKs`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val value = h.compare(c)
            c.approveComparison(value, if(value.code == "00000000") "00000001" else "00000000")
            assertEquals(ProviderTargetFailure.Protocol(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED), assertIs<ProviderTargetState.Blocked>(c.state.value).failure)
            c.installApproved(); h.assertUninstalled()
            assertEquals(ProviderReservationPhase.AUTHENTICATED, h.repo.state(target, config.value)?.phase)
        }
    }
    @Test fun `cancelled navigation after comparison has no approval import ACK or automatic replay`(): Unit = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val old = h.compare(c)
            h.mailbox.fetchStarted = CompletableDeferred(); h.mailbox.fetchContinue = CompletableDeferred()
            val pending = launch { c.fetchDelivery() }; h.mailbox.fetchStarted!!.await(); pending.cancelAndJoin()
            assertIs<ProviderTargetState.RecoveryRequired>(c.state.value); h.assertUninstalled()
            assertEquals(ProviderReservationPhase.AUTHENTICATED, h.repo.state(target, config.value)?.phase)
            h.reopen(); val resumed = h.coordinator(); resumed.resume(config.value)
            resumed.approveComparison(old, old.code); h.assertUninstalled()
            assertIs<ProviderTargetState.Blocked>(resumed.state.value)
        }
    }
    @Test fun `reservation recreation retries identical publication without allocating another revision`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); c.reserveAndPublish(binding, source)
            val first = h.mailbox.publications.single(); h.reopen()
            val resumed = h.coordinator(); resumed.resume(config.value)
            assertEquals(ProviderTargetPublication.UNCONFIRMED, assertIs<ProviderTargetState.Reserved>(resumed.state.value).publication)
            resumed.retry()
            assertEquals(listOf(first, first), h.mailbox.publications)
            assertEquals(1L, h.repo.state(target, config.value)?.highestReservedRevision); h.assertUninstalled()
        }
    }
    @Test fun `transient publish failure preserves reservation and exact request bytes through recreation`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); h.mailbox.publishFailure = ProviderCredentialTransportException(503, "DO_NOT_EXPOSE_BODY")
            val c = h.coordinator(); c.reserveAndPublish(binding, source)
            assertIs<ProviderTargetState.RetryableFailure>(c.state.value); val first = h.mailbox.publications.single()
            h.reopen(); h.mailbox.publishFailure = null
            val resumed = h.coordinator(); resumed.resume(config.value); resumed.retry()
            assertContentEquals(ProviderCredentialMailboxWireCodec.encodeRequest(first), ProviderCredentialMailboxWireCodec.encodeRequest(h.mailbox.publications.last()))
            assertEquals(1L, h.repo.state(target, config.value)?.highestReservedRevision); h.assertUninstalled()
        }
    }
    @Test fun `expiry is explicit and only another explicit reservation allocates a higher revision`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); c.reserveAndPublish(binding, source); h.mailbox.deliver()
            h.now = h.mailbox.deadline; c.fetchDelivery(); assertIs<ProviderTargetState.Expired>(c.state.value)
            c.retry(); assertEquals(1L, h.repo.state(target, config.value)?.highestReservedRevision); h.assertUninstalled()
            c.reserveAndPublish(binding, source); assertEquals(2L, h.repo.state(target, config.value)?.liveRevision)
        }
    }
    @Test fun `wrong source and wrong revision deliveries fail closed`() = runBlocking {
        for (wrongSource in listOf(true, false)) Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); c.reserveAndPublish(binding, source)
            h.mailbox.deliver(revision = if(wrongSource) 1 else 2)
            val request = requireNotNull(h.mailbox.request).copy(provisionerDeviceId = if(wrongSource) other else source, credentialRevision = if(wrongSource) 1 else 2)
            h.mailbox.response = ProviderCredentialMailboxDeliveryV1(request, ProviderCredentialMailboxState.DELIVERED, h.mailbox.deadline, h.mailbox.envelope)
            c.fetchDelivery(); val failure = assertIs<ProviderTargetState.Blocked>(c.state.value).failure
            assertEquals(ProviderTargetFailure.Protocol(if(wrongSource) ProviderCredentialRejection.WRONG_PROVISIONER else ProviderCredentialRejection.UNRESERVED_REVISION), failure)
            h.assertUninstalled()
        }
    }
    @Test fun `USER_APPROVED recreation preserves exact durable comparison and never installs automatically`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c)
            val approved = h.repo.state(target, config.value); h.reopen()
            val resumed = h.coordinator(); resumed.resume(config.value); resumed.recover(); resumed.retry()
            assertIs<ProviderTargetState.UserApproved>(resumed.state.value)
            assertEquals(approved, h.repo.state(target, config.value)); h.assertUninstalled()
            resumed.fetchDelivery(); assertEquals(ProviderReservationPhase.USER_APPROVED, h.repo.state(target, config.value)?.phase)
            resumed.installApproved(); assertIs<ProviderTargetState.Installed>(resumed.state.value); assertEquals(1, h.imports)
        }
    }
    @Test fun `crash recreation at each existing install checkpoint recovers ownership without automatic import`() = runBlocking {
        for (phase in listOf(ProviderInstallPhase.PREPARED_IMPORT, ProviderInstallPhase.SECRET_IMPORTED, ProviderInstallPhase.METADATA_COMMITTED)) Fixture().use { h ->
            h.initialize(); val c = h.coordinator(ProviderInstallCheckpoint { if(it == phase) throw CancellationException("PROCESS_STOP") })
            h.approved(c); assertFailsWith<CancellationException> { c.installApproved() }
            val before = h.imports; h.reopen(); val resumed = h.coordinator(); resumed.resume(config.value)
            assertEquals(before, h.imports)
            if(phase == ProviderInstallPhase.METADATA_COMMITTED) {
                val ref = h.local.providerConfig(config)?.credentialReference; assertNotNull(ref)
                assertIs<ProviderTargetState.Installed>(resumed.state.value); resumed.retry()
                assertEquals(ref, h.local.providerConfig(config)?.credentialReference); assertEquals(before, h.imports)
            } else {
                assertIs<ProviderTargetState.UserApproved>(resumed.state.value); assertNull(h.local.providerConfig(config)?.credentialReference)
                assertTrue(h.repo.journals(target).all { it.phase == ProviderInstallPhase.COMPLETED })
            }
        }
    }
    @Test fun `ACK failure and recreation preserve installed truth and retry ACK only`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c)
            h.mailbox.ackFailure = ProviderCredentialTransportException(503, "PRIVATE_SERVER_BODY")
            c.installApproved(); val installed = assertIs<ProviderTargetState.Installed>(c.state.value)
            assertEquals(ProviderTargetAckStatus.PENDING, installed.acknowledgement.status)
            val accepted = h.repo.state(target, config.value)!!; c.retry()
            assertIs<ProviderTargetState.Installed>(c.state.value); assertEquals(1, h.imports)
            h.reopen(); val resumed = h.coordinator(); resumed.resume(config.value)
            assertIs<ProviderTargetState.Installed>(resumed.state.value); h.mailbox.ackFailure = null; resumed.retry()
            assertEquals(ProviderTargetAckStatus.CONFIRMED, assertIs<ProviderTargetState.Installed>(resumed.state.value).acknowledgement.status)
            assertEquals(accepted, h.repo.state(target, config.value)); assertEquals(1, h.imports)
            assertEquals(ProviderCredentialAcknowledgementResult.DUPLICATE, h.mailbox.acks.last().result)
        }
    }
    @Test fun `directory failure during ACK retry cannot relabel a committed install as failed`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c)
            h.mailbox.ackFailure = ProviderCredentialTransportException(503, "unavailable"); c.installApproved()
            h.directoryFailure = SyncTransportException("HTTP_503"); c.retry()
            assertEquals(ProviderTargetAckStatus.PENDING, assertIs<ProviderTargetState.Installed>(c.state.value).acknowledgement.status)
            assertEquals(1, h.imports)
        }
    }
    @Test fun `same accepted credential rerandomized delivery preserves existing DUPLICATE semantics`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c); c.installApproved()
            val ref = h.local.providerConfig(config)?.credentialReference
            h.mailbox.deliver(); c.fetchDelivery()
            assertTrue(assertIs<ProviderTargetState.Installed>(c.state.value).duplicateDeliveryReady)
            c.installApproved()
            assertEquals(ProviderCredentialAcknowledgementResult.DUPLICATE, h.mailbox.acks.last().result)
            assertEquals(ref, h.local.providerConfig(config)?.credentialReference); assertEquals(1, h.imports); assertEquals(1, h.prepares)
        }
    }
    @Test fun `restart with unavailable or unauthorized directory preserves installed truth without importing`() = runBlocking {
        for (status in listOf(401, 503)) Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c); c.installApproved()
            val committed = h.repo.state(target, config.value); h.reopen()
            h.directoryFailure = SyncTransportException("HTTP_$status")
            val resumed = h.coordinator(); resumed.resume(config.value)
            val installed = assertIs<ProviderTargetState.Installed>(resumed.state.value)
            assertEquals(if(status == 503) ProviderTargetAckStatus.PENDING else ProviderTargetAckStatus.BLOCKED, installed.acknowledgement.status)
            assertEquals(committed, h.repo.state(target, config.value)); assertEquals(1, h.imports)
            h.directoryFailure = null; resumed.retry()
            assertEquals(ProviderTargetAckStatus.CONFIRMED, assertIs<ProviderTargetState.Installed>(resumed.state.value).acknowledgement.status)
            assertEquals(1, h.imports)
        }
    }
    @Test fun `authorization or expiry during ACK never rolls back or relabels local install`() = runBlocking {
        for (status in listOf(401, 410)) Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c)
            h.mailbox.ackFailure = ProviderCredentialTransportException(status, "PRIVATE_BODY")
            c.installApproved(); c.retry()
            val installed = assertIs<ProviderTargetState.Installed>(c.state.value)
            assertEquals(if(status == 410) ProviderTargetAckStatus.EXPIRED else ProviderTargetAckStatus.BLOCKED, installed.acknowledgement.status)
            assertEquals(1, h.imports); assertEquals(ProviderReservationPhase.ACTIVE, h.repo.state(target, config.value)?.phase)
            assertNotNull(h.local.providerConfig(config)?.credentialReference)
        }
    }
    @Test fun `different fetched envelope invalidates old comparison and cannot reuse durable user approval`() = runBlocking {
        for (approved in listOf(false, true)) Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val old = h.compare(c)
            if(approved) c.approveComparison(old, old.code)
            h.mailbox.deliver("replacement-before-install"); c.fetchDelivery()
            if(!approved) {
                assertIs<ProviderTargetState.ComparisonReady>(c.state.value)
                c.approveComparison(old, old.code)
            }
            assertIs<ProviderTargetState.Blocked>(c.state.value)
            c.installApproved(); h.assertUninstalled()
        }
    }
    @Test fun `AUTHENTICATED recreation retry refreshes comparison but never reuses old display identity`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val old = h.compare(c); h.reopen()
            val resumed = h.coordinator(); resumed.resume(config.value)
            assertTrue(assertIs<ProviderTargetState.Reserved>(resumed.state.value).comparisonRefreshRequired)
            resumed.retry(); assertIs<ProviderTargetState.ComparisonReady>(resumed.state.value)
            resumed.approveComparison(old, old.code); assertIs<ProviderTargetState.Blocked>(resumed.state.value)
            h.assertUninstalled()
        }
    }
    @Test fun `lost durable reservation cannot reset counters or accept relay ACK as installation`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); c.reserveAndPublish(binding, source)
            h.mailbox.response = ProviderCredentialMailboxDeliveryV1(requireNotNull(h.mailbox.request), ProviderCredentialMailboxState.ACKNOWLEDGED, h.mailbox.deadline, null)
            c.fetchDelivery()
            assertEquals(ProviderTargetFailure.Protocol(ProviderCredentialRejection.CREDENTIAL_STATE_LOST), assertIs<ProviderTargetState.Blocked>(c.state.value).failure)
            h.db.useWriterConnection { connection -> connection.usePrepared("DELETE FROM provider_credential_revision") { it.step() } }
            h.reopen(); val resumed = h.coordinator(); resumed.resume(config.value); resumed.reserveAndPublish(binding, source)
            assertEquals(ProviderTargetFailure.Protocol(ProviderCredentialRejection.CREDENTIAL_STATE_LOST), assertIs<ProviderTargetState.Blocked>(resumed.state.value).failure)
            assertEquals(1, h.mailbox.publications.size); h.assertUninstalled()
        }
    }
    @Test fun `unequal accepted credential remains an integrity conflict and does not replace the secret`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c); c.installApproved(); val ref = h.local.providerConfig(config)?.credentialReference!!
            h.mailbox.deliver("DIFFERENT_SECRET"); c.fetchDelivery(); c.installApproved()
            assertEquals(ProviderTargetFailure.Protocol(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT), assertIs<ProviderTargetState.Blocked>(c.state.value).failure)
            assertEquals(secret, h.store.readProviderSecret(ref)?.decodeToString()); assertEquals(1, h.imports)
        }
    }
    @Test fun `wipe racing import rejects late activation and stale delivery through restart`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(ProviderInstallCheckpoint { if(it == ProviderInstallPhase.SECRET_IMPORTED) h.directService().disableAndWipe(config.value) })
            h.approved(c); c.installApproved(); assertIs<ProviderTargetState.Blocked>(c.state.value)
            h.reopen(); val resumed = h.coordinator(); resumed.resume(config.value); resumed.fetchDelivery()
            assertNull(h.local.providerConfig(config)?.credentialReference); assertEquals(2L, h.repo.state(target, config.value)?.rejectionFloor?.toLongExact())
            assertEquals(0L, h.repo.state(target, config.value)?.highestAcceptedRevision); assertTrue(h.mailbox.acks.isEmpty())
        }
    }
    @Test fun `lost ACTIVE enrollment before or during import fails closed`() = runBlocking {
        for (duringImport in listOf(false, true)) Fixture().use { h ->
            h.initialize(); val c = h.coordinator(ProviderInstallCheckpoint { if(duringImport && it == ProviderInstallPhase.SECRET_IMPORTED) h.deleteEnrollment() })
            h.approved(c); if(!duringImport) h.deleteEnrollment(); c.installApproved()
            if(duringImport) assertIs<ProviderTargetState.Blocked>(c.state.value) else assertIs<ProviderTargetState.NotEligible>(c.state.value)
            assertNull(h.local.providerConfig(config)?.credentialReference); assertTrue(h.mailbox.acks.isEmpty())
        }
    }
    @Test fun `directory revocation invalidates comparison and blocks security sensitive progression`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val comparison = h.compare(c)
            h.devices = h.devices.filterNot { it.deviceId == source.value }
            c.approveComparison(comparison, comparison.code)
            assertEquals(ProviderTargetEligibility.PROVISIONER_NOT_ACTIVE, assertIs<ProviderTargetState.NotEligible>(c.state.value).reason)
            h.assertUninstalled()
        }
    }
    @Test fun `two rapid reserves and installs are serialized without duplicate revision or import`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val imported = CompletableDeferred<Unit>(); val continueInstall = CompletableDeferred<Unit>()
            val c = h.coordinator(ProviderInstallCheckpoint { if(it == ProviderInstallPhase.SECRET_IMPORTED) { imported.complete(Unit); continueInstall.await() } })
            h.mailbox.publishStarted = CompletableDeferred(); h.mailbox.publishContinue = CompletableDeferred()
            val first = async { c.reserveAndPublish(binding, source) }; h.mailbox.publishStarted!!.await()
            val second = async { c.reserveAndPublish(binding, source) }; h.mailbox.publishContinue!!.complete(Unit)
            awaitAll(first, second); assertEquals(1L, h.repo.state(target, config.value)?.highestReservedRevision)
            assertEquals(1, h.mailbox.publications.size)
            h.mailbox.deliver(); c.fetchDelivery(); val comparison = assertIs<ProviderTargetState.ComparisonReady>(c.state.value).comparison
            c.approveComparison(comparison, comparison.code)
            val install1 = async { c.installApproved() }; imported.await(); val install2 = async { c.installApproved() }
            continueInstall.complete(Unit); awaitAll(install1, install2)
            assertEquals(1, h.imports); assertEquals(1, h.prepares); assertEquals(1, h.repo.journals(target).size)
        }
    }
    @Test fun `old comparison from a different source revision or coordinator cannot authorize new selection`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val old = h.compare(c)
            c.reserveAndPublish(binding, other); c.approveComparison(old, old.code)
            assertIs<ProviderTargetState.Blocked>(c.state.value); assertEquals(2L, h.repo.state(target, config.value)?.liveRevision)
            assertEquals(ProviderReservationPhase.RESERVED, h.repo.state(target, config.value)?.phase); h.assertUninstalled()
        }
    }
    @Test fun `binding edit invalidates approval and cannot silently select source metadata`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val old = h.compare(c)
            h.local.saveProviderConfig(requireNotNull(h.local.providerConfig(config)).copy(model = "Explicitly changed model"))
            c.approveComparison(old, old.code); c.installApproved()
            assertIs<ProviderTargetState.Blocked>(c.state.value); h.assertUninstalled()
            assertTrue(h.repo.state(target, config.value)!!.rejectionFloor >= 2)
        }
    }
    @Test fun `missing target key credential or account fails closed without a request`() = runBlocking {
        for (missing in listOf("key", "credential", "account", "enrollment", "directory")) Fixture().use { h ->
            h.initialize()
            when(missing) {
                "key" -> h.store.delete(h.active.hpkePrivateKeyReference)
                "credential" -> h.store.delete(h.active.deviceCredentialReference)
                "enrollment" -> h.deleteEnrollment()
                "directory" -> h.devices = h.devices.filterNot { it.deviceId == target.value }
            }
            val c = h.coordinator(configuredAccount = if(missing == "account") AccountId("wrong-account") else account)
            c.reserveAndPublish(binding, source); assertIs<ProviderTargetState.NotEligible>(c.state.value)
            assertTrue(h.mailbox.publications.isEmpty()); h.assertUninstalled()
        }
    }
    @Test fun `ambiguous ACTIVE identities and duplicate directory entries fail closed`() = runBlocking {
        Fixture().use { h ->
            h.initialize()
            h.db.useWriterConnection { c -> c.usePrepared("INSERT INTO local_pairing_enrollment SELECT 'other-account', 'other-target', enrollment_request_id, hpke_public_key_base64url, hpke_private_key_secret_ref, status, sync_space_id, account_master_key_secret_ref, device_credential_secret_ref FROM local_pairing_enrollment") { it.step() } }
            val c = h.coordinator(); c.reserveAndPublish(binding, source)
            assertEquals(ProviderTargetEligibility.MULTIPLE_ACTIVE_TARGETS, assertIs<ProviderTargetState.NotEligible>(c.state.value).reason); h.assertUninstalled()
        }
        Fixture().use { h ->
            h.initialize(); h.devices = h.devices + h.devices.first(); val c = h.coordinator(); c.reserveAndPublish(binding, source)
            assertEquals(ProviderTargetEligibility.INVALID_DIRECTORY, assertIs<ProviderTargetState.NotEligible>(c.state.value).reason); h.assertUninstalled()
        }
    }
    @Test fun `errors and sanitized display state cannot expose transport bodies secret references or digests`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); val comparison = h.compare(c)
            val row = h.repo.state(target, config.value)!!
            val text = c.state.value.toString()
            listOf(secret, comparison.code, h.active.hpkePrivateKeyReference.value, h.active.deviceCredentialReference.value,
                requireNotNull(row.approvalBindingDigest), requireNotNull(row.envelopeDigest)).forEach { assertFalse(text.contains(it)) }
            h.mailbox.fetchFailure = ProviderCredentialTransportException(401, "RAW_PRIVATE_BODY_$secret"); c.fetchDelivery()
            assertEquals(ProviderTargetFailure.Transport(ProviderTargetTransportFailure.AUTHORIZATION), assertIs<ProviderTargetState.Blocked>(c.state.value).failure)
            assertFalse(c.state.value.toString().contains(secret))
            h.mailbox.fetchFailure = kotlinx.io.IOException("PRIVATE_NETWORK_$secret"); c.fetchDelivery()
            assertIs<ProviderTargetState.RetryableFailure>(c.state.value); assertFalse(c.state.value.toString().contains(secret))
            h.mailbox.fetchFailure = null; c.fetchDelivery()
            val refreshed = assertIs<ProviderTargetState.ComparisonReady>(c.state.value).comparison
            c.approveComparison(refreshed, refreshed.code); c.installApproved()
            assertIs<ProviderTargetState.Installed>(c.state.value)
            h.backend.providerUnavailable = true; c.recover()
            assertEquals(ProviderTargetFailure.Protocol(ProviderCredentialRejection.SECURE_STORE_UNAVAILABLE), assertIs<ProviderTargetState.Blocked>(c.state.value).failure)
        }
    }
    @Test fun `real Room reopen retains only references and never credential plaintext or its hash`() = runBlocking {
        Fixture().use { h ->
            h.initialize(); val c = h.coordinator(); h.approved(c); c.installApproved()
            val accepted = h.repo.state(target, config.value); h.reopen()
            val resumed = h.coordinator(); resumed.resume(config.value)
            assertIs<ProviderTargetState.Installed>(resumed.state.value); assertEquals(accepted, h.repo.state(target, config.value))
            for (file in listOf(h.path, h.path.resolveSibling(h.path.fileName.toString() + "-wal"), h.path.resolveSibling(h.path.fileName.toString() + "-shm")).filter { Files.exists(it) }) {
                val sqlite = Files.readAllBytes(file).decodeToString()
                for (canary in listOf(secret, encodeCanonicalBase64Url(secret.encodeToByteArray()), encodeCanonicalBase64Url(pairingSha256(secret.encodeToByteArray()))))
                    assertFalse(sqlite.contains(canary), "Credential material must not enter SQLite or its sidecars")
            }
        }
    }
}
