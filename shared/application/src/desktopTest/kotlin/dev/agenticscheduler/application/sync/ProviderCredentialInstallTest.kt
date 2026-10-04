package dev.agenticscheduler.application.sync

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.nio.file.Files
import kotlin.test.*

class ProviderCredentialInstallTest {
    private val target = DeviceId("WearTarget:opaque-A_01")
    private val source = DeviceId("PhoneSource:opaque-B_02")
    private val config = "00000000-0000-7000-8000-000000000101"
    private val binding = WearProviderBindingMetadataV1(providerConfigId = config, baseUrl = "https://provider.example/v1", model = "test-model",
        maxContextUnits = 32768, reservedOutputUnits = 4096, streamingSupported = true, toolCallingSupported = true, credentialRequired = true)
    private val secret = "PROVIDER_SECRET_CANARY_123"

    private inner class Harness : AutoCloseable {
        val path = Files.createTempFile("credential-install-", ".db")
        var db: AgenticSchedulerDatabase = openDesktopDatabase(path.toString())
        val backend = Backend()
        val hpke = TinkPairingHpke()
        val key = hpke.generateDeviceKeyPair()
        var slots = DesktopPlatformSecureStore(backend, hpke)
        var repo = RoomProviderCredentialProvisioningRepository(db)
        fun service(checkpoint: ProviderInstallCheckpoint = ProviderInstallCheckpoint {}) = ProviderCredentialProvisioningService(repo, slots, hpke, target, key.privateKey, checkpoint)
        suspend fun initialize() {
            RoomAgentStateRepository(db).saveProviderConfig(ProviderConfig(ProviderConfigId(config), binding.baseUrl, binding.model, binding.maxContextUnits,
                binding.reservedOutputUnits, true, true, null))
            RoomLocalEnrollmentRepository(db).saveActive(LocalEnrollmentState.Active(AccountId("account"), target, EnrollmentRequestId("request"), key.publicKey,
                SecretReference("test://hpke"), SyncSpaceId("space"), SecretReference("test://amk"), SecretReference("test://device-credential")))
        }
        fun reopen() { db.close(); db = openDesktopDatabase(path.toString()); repo = RoomProviderCredentialProvisioningRepository(db); slots = DesktopPlatformSecureStore(backend, hpke) }
        suspend fun reserveAndApprove(service: ProviderCredentialProvisioningService, value: String = secret): ProviderCredentialEnvelopeV1 {
            val state = service.reserveForExplicitUser(binding, source)
            val e = envelope(state.liveRevision!!, value)
            val comparison = service.compare(source, e)
            service.approveComparisonByExplicitLocalUser(comparison, ProviderProvisioningSas.calculate(source, e, binding))
            return e
        }
        fun envelope(revision: Long, value: String = secret) = hpke.encryptProviderCredential(key.publicKey,
            ProviderCredentialPlaintextV1(targetDeviceId = target, providerConfigId = config, credentialRevision = revision, credentialSecretBase64Url = encodeCanonicalBase64Url(value.encodeToByteArray())))
        override fun close() { db.close(); Files.deleteIfExists(path) }
    }
    private class Backend : DesktopSecureBackend {
        override val referencePrefix = "test-provider://"
        val values = mutableMapOf<String, ByteArray>()
        var deletionFails = false
        var readingFails = false
        override fun store(id: String, value: ByteArray) { values[id] = value.copyOf() }
        override fun read(id: String): ByteArray? {
            if (readingFails) throw SecureStoreUnavailableException("TEST_READ_FAILED")
            return values[id]?.copyOf()
        }
        override fun delete(id: String) { if (deletionFails) throw SecureStoreUnavailableException("TEST_DELETE_FAILED"); values.remove(id) }
    }
    private fun reject(reason: ProviderCredentialRejection, block: () -> Unit) { assertEquals(reason, assertFailsWith<ProviderProvisioningException>(block = block).reason) }

    @Test fun `first reservation explicit approval install duplicate and replacement persist across restart`() = runBlocking {
        Harness().use { h ->
            h.initialize(); val service = h.service()
            val e = h.reserveAndApprove(service)
            assertEquals(1L, e.credentialRevision)
            assertEquals(ProviderCredentialAcknowledgementResult.INSTALLED, service.installApproved(source, e).result)
            val first = h.repo.state(target, config)!!
            assertEquals(secret, h.slots.readProviderSecret(SecretReference(first.activeReference!!))!!.decodeToString())
            assertEquals(first.activeReference, RoomAgentStateRepository(h.db).providerConfig(ProviderConfigId(config))!!.credentialReference!!.value)
            h.reopen(); h.service().recover()
            assertEquals(ProviderCredentialAcknowledgementResult.DUPLICATE, h.service().installApproved(source, e).result)
            assertEquals(ProviderCredentialAcknowledgementResult.DUPLICATE, h.service().installApproved(source, h.envelope(1)).result)
            reject(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT) { runBlocking { h.service().installApproved(source, h.envelope(1, "other-secret")) } }
            val replacement = h.reserveAndApprove(h.service(), "new-secret")
            assertEquals(2L, replacement.credentialRevision)
            h.service().installApproved(source, replacement)
            assertNull(h.slots.readProviderSecret(SecretReference(first.activeReference)))
            assertEquals(2L, h.repo.state(target, config)!!.highestAcceptedRevision)
        }
    }
    @Test fun `unreserved wrong source cancelled rollback and missing approval reject`() = runBlocking {
        Harness().use { h ->
            h.initialize(); val service = h.service(); val first = service.reserveForExplicitUser(binding, source)
            reject(ProviderCredentialRejection.UNRESERVED_REVISION) { runBlocking { service.compare(source, h.envelope(2)) } }
            reject(ProviderCredentialRejection.WRONG_PROVISIONER) { runBlocking { service.compare(DeviceId("OtherSource"), h.envelope(1)) } }
            reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED) { runBlocking { service.installApproved(source, h.envelope(1)) } }
            val comparison = service.compare(source, h.envelope(1))
            reject(ProviderCredentialRejection.PROVISIONING_APPROVAL_REQUIRED) { runBlocking { service.approveComparisonByExplicitLocalUser(comparison, "00000000") } }
            val second = service.reserveForExplicitUser(binding, DeviceId("OtherSource"))
            assertEquals(first.liveRevision!! + 1, second.liveRevision)
            reject(ProviderCredentialRejection.UNRESERVED_REVISION) { runBlocking { service.approveComparisonByExplicitLocalUser(comparison, comparison.comparisonCode) } }
            service.disableAndWipe(config)
            val wiped = h.repo.state(target, config)!!
            assertEquals(3L, wiped.rejectionFloor)
            assertNull(wiped.activeReference)
            reject(ProviderCredentialRejection.ROLLBACK_REJECTED) { runBlocking { service.compare(source, h.envelope(1)) } }
            h.reopen(); assertEquals(4L, h.service().reserveForExplicitUser(binding, source).liveRevision)
        }
    }
    @Test fun `every crash boundary recovers from durable ownership without losing prior credential`() = runBlocking {
        for (phase in listOf(ProviderInstallPhase.PREPARED_IMPORT, ProviderInstallPhase.SECRET_IMPORTED, ProviderInstallPhase.METADATA_COMMITTED)) {
            Harness().use { h ->
                h.initialize(); val original = h.reserveAndApprove(h.service(), "old-secret"); h.service().installApproved(source, original)
                val old = h.repo.state(target, config)!!.activeReference!!
                val crashing = h.service(ProviderInstallCheckpoint { if (it == phase) error("SIMULATED_PROCESS_STOP") })
                val replacement = h.reserveAndApprove(crashing, "new-secret")
                assertFails { crashing.installApproved(source, replacement) }
                val prepared = h.repo.journals(target).single { it.revision == 2L }.preparedReference
                h.reopen(); h.service().recover()
                val state = h.repo.state(target, config)!!
                if (phase == ProviderInstallPhase.METADATA_COMMITTED) {
                    assertEquals(prepared, state.activeReference); assertEquals(2L, state.highestAcceptedRevision)
                    assertEquals("new-secret", h.slots.readProviderSecret(SecretReference(prepared))!!.decodeToString())
                    assertNull(h.slots.readProviderSecret(SecretReference(old)))
                } else {
                    assertEquals(old, state.activeReference); assertEquals(1L, state.highestAcceptedRevision)
                    assertNull(h.slots.restoreProviderSlot(SecretReference(prepared), h.repo.journals(target).single { it.revision == 2L }.installIdentity))
                    assertEquals("old-secret", h.slots.readProviderSecret(SecretReference(old))!!.decodeToString())
                }
            }
        }
    }
    @Test fun `failed old cleanup does not roll back new credential and durable retry completes`() = runBlocking {
        Harness().use { h ->
            h.initialize(); val first = h.reserveAndApprove(h.service()); h.service().installApproved(source, first)
            val old = h.repo.state(target, config)!!.activeReference!!
            val second = h.reserveAndApprove(h.service(), "new-secret"); h.backend.deletionFails = true
            h.service().installApproved(source, second)
            assertEquals(2L, h.repo.state(target, config)!!.highestAcceptedRevision)
            assertNotNull(h.slots.readProviderSecret(SecretReference(old)))
            h.reopen(); h.backend.deletionFails = false; h.service().recover()
            assertNull(h.slots.readProviderSecret(SecretReference(old)))
            assertEquals(ProviderCredentialAcknowledgementResult.DUPLICATE, h.service().installApproved(source, second).result)
        }
    }
    @Test fun `unavailable Provider read retains cleanup ownership through restart instead of assuming absent slot`() = runBlocking {
        Harness().use { h ->
            h.initialize()
            val crashing = h.service(ProviderInstallCheckpoint { if (it == ProviderInstallPhase.SECRET_IMPORTED) error("SIMULATED_PROCESS_STOP") })
            val e = h.reserveAndApprove(crashing)
            assertFails { crashing.installApproved(source, e) }
            val imported = h.repo.journals(target).single()
            h.backend.readingFails = true
            h.reopen(); h.service().recover()
            assertEquals(ProviderInstallPhase.REJECTED, h.repo.journals(target).single().phase)
            assertEquals(1, h.backend.values.size)
            assertNull(h.repo.state(target, config)!!.activeReference)
            h.backend.readingFails = false
            h.reopen(); h.service().recover()
            assertEquals(ProviderInstallPhase.COMPLETED, h.repo.journals(target).single().phase)
            assertNull(h.slots.restoreProviderSlot(SecretReference(imported.preparedReference), imported.installIdentity))
            assertTrue(h.backend.values.isEmpty())
        }
    }
    @Test fun `wipe between import and publish rejects late install and cleans journal slots`() = runBlocking {
        Harness().use { h ->
            h.initialize()
            val racing = h.service(ProviderInstallCheckpoint { if (it == ProviderInstallPhase.SECRET_IMPORTED) h.service().disableAndWipe(config) })
            val e = h.reserveAndApprove(racing)
            reject(ProviderCredentialRejection.UNRESERVED_REVISION) { runBlocking { racing.installApproved(source, e) } }
            h.reopen(); h.service().recover()
            assertNull(h.repo.state(target, config)!!.activeReference)
            assertTrue(h.backend.values.isEmpty())
            assertNull(RoomAgentStateRepository(h.db).providerConfig(ProviderConfigId(config))!!.credentialReference)
        }
    }
    @Test fun `publish rechecks ACTIVE enrollment after import and observed revocation wipe retains the floor`() = runBlocking {
        Harness().use { h ->
            h.initialize()
            val racing = h.service(ProviderInstallCheckpoint { if (it == ProviderInstallPhase.SECRET_IMPORTED) {
                h.db.useWriterConnection { c -> c.usePrepared("DELETE FROM local_pairing_enrollment") { it.step() } }
            } })
            val e = h.reserveAndApprove(racing)
            reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST) { runBlocking { racing.installApproved(source, e) } }
            assertNull(h.repo.state(target, config)!!.activeReference)
            h.service().disableAndWipe(config)
            h.reopen(); h.service().recover()
            assertEquals(2L, h.repo.state(target, config)!!.rejectionFloor)
            assertTrue(h.backend.values.isEmpty())
            reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST) { runBlocking { h.service().reserveForExplicitUser(binding, source) } }
        }
    }
    @Test fun `caller binding mismatch rejects rather than looping CAS or silently approving source metadata`() = runBlocking {
        Harness().use { h ->
            h.initialize()
            reject(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH) { runBlocking { h.service().reserveForExplicitUser(binding.copy(model = "unapproved-model"), source) } }
            assertEquals(0L, h.repo.state(target, config)!!.highestReservedRevision)
            assertTrue(h.backend.values.isEmpty())
            assertEquals(1L, h.service().reserveForExplicitUser(binding, source).liveRevision)
        }
    }
    @Test fun `Room cannot publish an arbitrary ref without same transaction committed journal ownership`() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.service().reserveForExplicitUser(binding, source)
            val before = h.repo.state(target, config)!!
            assertFailsWith<IllegalArgumentException> { h.repo.compareAndSet(before, before.copy(highestAcceptedRevision = 1,
                activeReference = "test://arbitrary-secret", activeInstallIdentity = "forged-owner", generation = before.generation + 1)) }
            assertEquals(before, h.repo.state(target, config))
            assertNull(RoomAgentStateRepository(h.db).providerConfig(ProviderConfigId(config))!!.credentialReference)
        }
    }
    @Test fun `retained enrolled identity with missing known config or whole state fails closed`() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.service().reserveForExplicitUser(binding, source)
            h.db.useWriterConnection { c -> c.usePrepared("DELETE FROM provider_credential_revision") { it.step() } }
            h.reopen()
            reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST) { runBlocking { h.service().reserveForExplicitUser(binding, source) } }
            h.db.useWriterConnection { c -> c.usePrepared("DELETE FROM provider_credential_target_identity") { it.step() } }
            h.reopen()
            reject(ProviderCredentialRejection.CREDENTIAL_STATE_LOST) { runBlocking { h.service().reserveForExplicitUser(binding, source) } }
        }
    }
    @Test fun `revision exhaustion rejects without reset and secret never enters SQLite`() = runBlocking {
        Harness().use { h ->
            h.initialize(); val e = h.reserveAndApprove(h.service()); h.service().installApproved(source, e)
            val s = h.repo.state(target, config)!!
            assertTrue(h.repo.compareAndSet(s, s.copy(highestReservedRevision = Long.MAX_VALUE, generation = s.generation + 1)))
            reject(ProviderCredentialRejection.REVISION_OVERFLOW) { runBlocking { h.service().reserveForExplicitUser(binding, source) } }
            val dump = h.db.useReaderConnection { c -> c.usePrepared("SELECT state_json FROM provider_credential_revision UNION ALL SELECT journal_json FROM provider_credential_install_journal") { row -> buildString { while (row.step()) append(row.getText(0)) } } }
            assertFalse(secret in dump)
            assertFalse(encodeCanonicalBase64Url(pairingSha256(secret.encodeToByteArray())) in dump)
            h.db.close()
            assertFalse(secret in Files.readAllBytes(h.path).decodeToString())
            h.db = openDesktopDatabase(h.path.toString())
        }
    }
    @Test fun `slot purposes one install and ownership cannot overwrite D8 material`() = runBlocking {
        val backend = Backend(); val store = DesktopPlatformSecureStore(backend, TinkPairingHpke())
        val material = object : PairingEphemeralKeyMaterial, ImportedContentKeyMaterial { override fun copyRawKeyBytesForPairing() = ByteArray(32) { it.toByte() } }
        val references = listOf(store.generateAccountMasterKey(), store.generateContentKey().reference, store.generatePairingDeviceKey().privateKeyReference,
            store.store(DeviceCredential(encodeCanonicalBase64Url(ByteArray(32) { 1 }))), store.importSecret(material))
        val saved = backend.values.mapValues { it.value.toList() }
        for (ref in references) {
            assertNull(store.restoreProviderSlot(ref, "00000000-0000-0000-0000-000000000001"))
            assertFails { store.importPreparedProviderSecret(PreparedProviderSecretSlot(ref, "00000000-0000-0000-0000-000000000001"), secret.encodeToByteArray()) }
        }
        val slot = store.prepareProviderSlot(); assertNull(store.readProviderSecret(slot.reference))
        store.importPreparedProviderSecret(slot, secret.encodeToByteArray())
        assertFails { store.importPreparedProviderSecret(slot, "overwrite".encodeToByteArray()) }
        val recreated = DesktopPlatformSecureStore(backend, TinkPairingHpke())
        assertNull(recreated.restoreProviderSlot(slot.reference, "wrong-owner"))
        recreated.deleteProviderSlot(assertNotNull(recreated.restoreProviderSlot(slot.reference, slot.installIdentity)))
        assertNull(recreated.readProviderSecret(slot.reference))
        assertEquals(saved, backend.values.mapValues { it.value.toList() })
    }
    @Test fun `local binding edit invalidates approval raises floor clears credential and journals cleanup`() = runBlocking {
        Harness().use { h ->
            h.initialize(); val e = h.reserveAndApprove(h.service()); h.service().installApproved(source, e)
            val agent = RoomAgentStateRepository(h.db)
            val configValue = agent.providerConfig(ProviderConfigId(config))!!
            val oldRef = configValue.credentialReference!!
            agent.saveProviderConfig(configValue.copy(baseUrl = "https://changed.example/v1"))
            assertNull(agent.providerConfig(ProviderConfigId(config))!!.credentialReference)
            val invalidated = h.repo.state(target, config)!!
            assertEquals(ProviderReservationPhase.DISABLED, invalidated.phase)
            assertEquals(2L, invalidated.rejectionFloor)
            reject(ProviderCredentialRejection.ROLLBACK_REJECTED) { runBlocking { h.service().installApproved(source, e) } }
            h.reopen(); h.service().recover()
            assertNull(h.slots.readProviderSecret(oldRef))
            assertEquals(3L, h.service().reserveForExplicitUser(binding.copy(baseUrl = "https://changed.example/v1"), source).liveRevision)
        }
    }
    @Test fun `source failed upload and restart retry exact canonical bytes without encryption or secret reselection`() = runBlocking {
        Harness().use { h ->
            h.initialize(); h.service().reserveForExplicitUser(binding, source)
            val request = ProviderCredentialReservationRequestV1(target, config, 1, source)
            val delivery = ProviderCredentialMailboxDeliveryV1(request, ProviderCredentialMailboxState.REQUESTED, 604800, null)
            val sent = mutableListOf<List<Byte>>()
            var fails = true
            val transport = object : ProviderCredentialTransport {
                override suspend fun uploadExact(canonicalEnvelope: ByteArray) {
                    sent += canonicalEnvelope.toList()
                    if (fails) throw ProviderCredentialTransportException(503, "HTTP_503")
                }
                override suspend fun assignedRequest(): ProviderCredentialMailboxDeliveryV1? = null
                override suspend fun fetch(config: String): ProviderCredentialMailboxDeliveryV1? = null
                override suspend fun publish(request: ProviderCredentialReservationRequestV1) = Unit
                override suspend fun acknowledge(value: ProviderCredentialAcknowledgementV1) = Unit
            }
            val directory = suspend { listOf(ClientActiveDeviceDirectoryEntry(target.value, h.key.publicKey.value), ClientActiveDeviceDirectoryEntry(source.value, h.key.publicKey.value)) }
            val first = ProviderCredentialSourceDeliveryService(h.repo, h.hpke, source, transport, directory, { 0 })
            val comparison = first.prepareByExplicitLocalUser(delivery, binding, secret.encodeToByteArray())
            assertFailsWith<ProviderCredentialTransportException> { first.uploadReserved(request) }
            assertEquals(ProviderDeliveryOutboxState.PREPARED, h.repo.delivery(source, target, config, 1)!!.state)
            h.reopen(); fails = false
            val noEncrypt = object : PairingHpke by h.hpke {
                override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents = error("NO_REENCRYPT")
            }
            val retry = ProviderCredentialSourceDeliveryService(h.repo, noEncrypt, source, transport, directory, { 0 })
            assertEquals(comparison, retry.comparisonForPendingDelivery(request, binding))
            retry.uploadReserved(request); retry.uploadReserved(request)
            assertEquals(3, sent.size); assertTrue(sent.all { it == sent[0] })
            assertEquals(ProviderDeliveryOutboxState.UPLOADED, h.repo.delivery(source, target, config, 1)!!.state)
            reject(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT) { runBlocking {
                retry.prepareByExplicitLocalUser(delivery, binding, "different-selected-secret".encodeToByteArray())
            } }
        }
    }
    @Test fun `actual Room rollback cleans imported slot while unknown commit preserves published ownership`() = runBlocking {
        for (commitUnknown in listOf(false, true)) Harness().use { h ->
            h.initialize(); val first = h.reserveAndApprove(h.service(), "old-secret"); h.service().installApproved(source, first)
            val old = h.repo.state(target, config)!!.activeReference!!
            val delegate = h.repo
            val faulting = object : ProviderCredentialProvisioningRepository by delegate {
                override suspend fun compareAndSet(expected: ProviderCredentialRevisionState, updated: ProviderCredentialRevisionState, journals: List<ProviderCredentialInstallJournal>): Boolean {
                    if (journals.any { it.phase == ProviderInstallPhase.METADATA_COMMITTED }) {
                        if (commitUnknown) { assertTrue(delegate.compareAndSet(expected, updated, journals)); error("DB_COMMIT_RESPONSE_LOST") }
                        h.db.withWriteTransaction { assertTrue(delegate.compareAndSet(expected, updated, journals)); error("REAL_ROOM_TRANSACTION_ROLLBACK") }
                    }
                    return delegate.compareAndSet(expected, updated, journals)
                }
            }
            val service = ProviderCredentialProvisioningService(faulting, h.slots, h.hpke, target, h.key.privateKey)
            val second = h.reserveAndApprove(service, "new-secret")
            assertFails { service.installApproved(source, second) }
            val prepared = h.repo.journals(target).single { it.revision == 2L }.preparedReference
            h.reopen(); h.service().recover()
            val state = h.repo.state(target, config)!!
            if (commitUnknown) {
                assertEquals(prepared, state.activeReference); assertEquals(2L, state.highestAcceptedRevision)
                assertNotNull(h.slots.readProviderSecret(SecretReference(prepared))); assertNull(h.slots.readProviderSecret(SecretReference(old)))
            } else {
                assertEquals(old, state.activeReference); assertEquals(1L, state.highestAcceptedRevision)
                assertNull(h.slots.readProviderSecret(SecretReference(prepared))); assertNotNull(h.slots.readProviderSecret(SecretReference(old)))
            }
        }
    }
    @Test fun `two source reservations serialize through Room CAS and only the latest is live`() = runBlocking {
        Harness().use { h ->
            h.initialize()
            val states = listOf(source, DeviceId("OtherSource")).map { selected ->
                async { h.service().reserveForExplicitUser(binding, selected) }
            }.awaitAll()
            assertEquals(listOf(1L, 2L), states.map { it.liveRevision!! }.sorted())
            val live = h.repo.state(target, config)!!
            assertEquals(2L, live.liveRevision)
            assertEquals(states.single { it.liveRevision == 2L }.selectedProvisioner, live.selectedProvisioner)
            val old = states.single { it.liveRevision == 1L }
            reject(ProviderCredentialRejection.UNRESERVED_REVISION) { runBlocking { h.service().compare(old.selectedProvisioner!!, h.envelope(1)) } }
        }
    }
    @Test fun `missing or unreadable accepted secret fails closed and only a fresh higher reservation can replace it`() = runBlocking {
        Harness().use { h ->
            h.initialize(); val e = h.reserveAndApprove(h.service()); h.service().installApproved(source, e)
            val accepted = h.repo.state(target, config)!!
            h.backend.readingFails = true
            reject(ProviderCredentialRejection.CREDENTIAL_UNAVAILABLE) { runBlocking { h.service().installApproved(source, e) } }
            h.backend.readingFails = false
            val slot = assertNotNull(h.slots.restoreProviderSlot(SecretReference(accepted.activeReference!!), accepted.activeInstallIdentity!!))
            h.slots.deleteProviderSlot(slot)
            h.reopen(); h.service().recover()
            reject(ProviderCredentialRejection.CREDENTIAL_UNAVAILABLE) { runBlocking { h.service().installApproved(source, e) } }
            assertEquals(accepted, h.repo.state(target, config))
            val replacement = h.reserveAndApprove(h.service(), "replaced-explicitly")
            assertEquals(2L, replacement.credentialRevision)
            h.service().installApproved(source, replacement)
            assertEquals(2L, h.repo.state(target, config)!!.highestAcceptedRevision)
        }
    }
    @Test fun `source expiry cancellation receipt and in flight cancellation purge exact retry bytes without target authority`() = runBlocking {
        for (terminal in listOf("expired", "relay-expired", "cancel", "ack", "cancel-in-flight")) Harness().use { h ->
            h.initialize(); h.service().reserveForExplicitUser(binding, source)
            val request = ProviderCredentialReservationRequestV1(target, config, 1, source)
            val assigned = ProviderCredentialMailboxDeliveryV1(request, ProviderCredentialMailboxState.REQUESTED, 604800, null)
            var now = 0L
            var sends = 0
            val transport = object : ProviderCredentialTransport {
                override suspend fun uploadExact(canonicalEnvelope: ByteArray) {
                    sends++
                    if (terminal == "relay-expired") throw ProviderCredentialTransportException(410, "DELIVERY_EXPIRED")
                    if (terminal == "cancel-in-flight") h.repo.removeDelivery(source, target, config, 1)
                }
                override suspend fun assignedRequest(): ProviderCredentialMailboxDeliveryV1? = null
                override suspend fun fetch(config: String): ProviderCredentialMailboxDeliveryV1? = null
                override suspend fun publish(request: ProviderCredentialReservationRequestV1) = Unit
                override suspend fun acknowledge(value: ProviderCredentialAcknowledgementV1) = Unit
            }
            val delivery = ProviderCredentialSourceDeliveryService(h.repo, h.hpke, source, transport,
                { listOf(ClientActiveDeviceDirectoryEntry(target.value, h.key.publicKey.value), ClientActiveDeviceDirectoryEntry(source.value, h.key.publicKey.value)) }, { now })
            val comparison = delivery.prepareByExplicitLocalUser(assigned, binding, secret.encodeToByteArray())
            val targetBefore = h.repo.state(target, config)
            when (terminal) {
                "expired" -> {
                    now = 604800
                    assertEquals("DELIVERY_EXPIRED", assertFailsWith<ProviderCredentialTransportException> { delivery.uploadReserved(request) }.code)
                    assertEquals(0, sends)
                    assertFailsWith<ProviderCredentialTransportException> { delivery.prepareByExplicitLocalUser(assigned, binding, secret.encodeToByteArray()) }
                }
                "relay-expired" -> assertFailsWith<ProviderCredentialTransportException> { delivery.uploadReserved(request) }
                "cancel" -> delivery.cancelByExplicitLocalUser(request)
                "ack" -> {
                    val receipt = ProviderCredentialAcknowledgementV1(targetDeviceId = target, providerConfigId = config, credentialRevision = 1,
                        envelopeDigestBase64Url = providerEnvelopeDigest(comparison.envelope), result = ProviderCredentialAcknowledgementResult.INSTALLED)
                    reject(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT) { runBlocking {
                        delivery.receivedInformationalAcknowledgement(receipt.copy(envelopeDigestBase64Url = encodeCanonicalBase64Url(ByteArray(32))))
                    } }
                    assertNotNull(h.repo.delivery(source, target, config, 1))
                    delivery.receivedInformationalAcknowledgement(receipt)
                }
                "cancel-in-flight" -> delivery.uploadReserved(request)
            }
            h.reopen()
            assertNull(h.repo.delivery(source, target, config, 1))
            assertEquals(targetBefore, h.repo.state(target, config), "Outbox receipt/expiry never authorizes installation or advances counters")
        }
    }
}
