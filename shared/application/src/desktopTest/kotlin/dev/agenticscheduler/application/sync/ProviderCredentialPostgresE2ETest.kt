package dev.agenticscheduler.application.sync

import com.zaxxer.hikari.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.server.sync.*
import dev.agenticscheduler.sync.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.nio.file.Files
import java.util.UUID
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.serialization.json.*

/** HTTP application engine + actual PostgreSQL + Room + production secure store + existing Tink.
 * No real network/TLS or Wear UI claim. Platform secure-store CI runs with its actual native backend.
 */
class ProviderCredentialPostgresE2ETest {
    @Test fun `explicit source target compare install ACK and exact outbox retry with real PostgreSQL`() {
        Assume.assumeTrue("Real PostgreSQL required", System.getenv("SYNC_TEST_DATABASE_URL") != null)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val account = "credential-e2e-$suffix"
        val target = DeviceId("WearTarget:opaque-A_01-$suffix")
        val source = DeviceId("PhoneSource:opaque-B_02-$suffix")
        val targetConfigId = ProviderConfigId("00000000-0000-7000-8000-000000000101")
        val sourceConfigId = ProviderConfigId("00000000-0000-7000-8000-000000000199")
        val rawCredential = "provider-secret-canary-$suffix"
        val baseUrlCanary = "https://binding-$suffix.example/v1"
        val modelCanary = "binding-model-$suffix"
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL"); username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
            password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"; maximumPoolSize = 4
        })
        val targetFile = Files.createTempFile("provider-target-", ".db"); val sourceFile = Files.createTempFile("provider-source-", ".db")
        var targetDb = openDesktopDatabase(targetFile.toString()); var sourceDb = openDesktopDatabase(sourceFile.toString())
        val store = DesktopPlatformSecureStore()
        val ownedRefs = mutableListOf<SecretReference>()
        var ownedProviderSlot: PreparedProviderSecretSlot? = null
        try {
            ServerSchemaMigrator(ds).migrate()
            val server = JdbcOpaqueSyncRepository(ds)
            val targetAuth = DeviceCredential(encodeCanonicalBase64Url(pairingSha256((target.value + "auth").encodeToByteArray())))
            val sourceAuth = DeviceCredential(encodeCanonicalBase64Url(pairingSha256((source.value + "auth").encodeToByteArray())))
            val targetKey = runBlocking { store.generatePairingDeviceKey() }; ownedRefs += targetKey.privateKeyReference
            ds.connection.use { c ->
                c.prepareStatement("INSERT INTO account(account_id) VALUES (?)").use { it.setString(1, account); it.executeUpdate() }
                for ((device, auth) in listOf(target to targetAuth, source to sourceAuth)) c.prepareStatement("INSERT INTO device(device_id, account_id, credential_hash, hpke_public_key) VALUES (?, ?, ?, ?)").use { s ->
                    s.setString(1, device.value); s.setString(2, account); s.setBytes(3, decodeCanonicalBase64Url(DeviceCredentialHashing.sha256Base64Url(auth), 32, "auth hash"))
                    s.setBytes(4, decodeCanonicalBase64Url(targetKey.publicKey.value, 32, "HPKE public")); s.executeUpdate()
                }
            }
            testApplication {
                application { syncServerModule(server, SyncServerConfig(ds.jdbcUrl, ds.username, ds.password)) }
                val targetTransport = KtorProviderCredentialTransport(client, "https://localhost") { targetAuth }
                val sourceTransport = KtorProviderCredentialTransport(client, "https://localhost") { sourceAuth }
                val directory = KtorSyncLifecycleTransport(client, "https://localhost", { sourceAuth })
                val sourceRef = store.importSecret(object : PlatformSecretMaterial { override fun copyRawSecretBytesForSecureStore() = rawCredential.encodeToByteArray() }); ownedRefs += sourceRef
                val sourceConfig = ProviderConfig(sourceConfigId, baseUrlCanary, modelCanary, 32768, 4096, true, true, sourceRef)
                val targetConfig = sourceConfig.copy(id = targetConfigId, credentialReference = null)
                RoomAgentStateRepository(sourceDb).saveProviderConfig(sourceConfig)
                RoomAgentStateRepository(targetDb).saveProviderConfig(targetConfig)
                RoomLocalEnrollmentRepository(targetDb).saveActive(LocalEnrollmentState.Active(AccountId(account), target, EnrollmentRequestId("request-$suffix"), targetKey.publicKey,
                    targetKey.privateKeyReference, SyncSpaceId("space-$suffix"), SecretReference("test://amk"), SecretReference("test://device-credential")))
                var targetRepo = RoomProviderCredentialProvisioningRepository(targetDb)
                val targetHpke = TinkPairingHpke()
                val privateKey = checkNotNull(store.privateKey(targetKey.privateKeyReference))
                var targetService = ProviderCredentialProvisioningService(targetRepo, store, targetHpke, target, privateKey)
                val targetBinding = targetConfig.provisioningBinding(targetConfigId, true)
                val reserved = targetService.reserveForExplicitUser(targetBinding, source)
                val request = ProviderCredentialReservationRequestV1(target, targetConfigId.value, reserved.liveRevision!!, source)
                targetTransport.publish(request); targetTransport.publish(request)
                val assigned = checkNotNull(sourceTransport.assignedRequest())
                assertEquals(request, assigned.request)
                var sourceRepo = RoomProviderCredentialProvisioningRepository(sourceDb)
                val sourceService = ProviderCredentialSourceDeliveryService(sourceRepo, TinkPairingHpke(), source, sourceTransport, directory::activeDevices)
                val selected = RoomAgentStateRepository(sourceDb).providerConfig(sourceConfigId)!!
                val sourceBinding = selected.provisioningBinding(ProviderConfigId(request.providerConfigId), true)
                assertEquals(targetConfigId.value, sourceBinding.providerConfigId)
                assertNotEquals(selected.id.value, sourceBinding.providerConfigId)
                val secret = checkNotNull(store.readSecret(selected.credentialReference!!)).copyRawSecretBytesForSecureStore()
                val sourceComparison = try { sourceService.prepareByExplicitLocalUser(assigned, sourceBinding, secret) } finally { secret.fill(0) }
                val original = sourceRepo.delivery(source, target, targetConfigId.value, 1)!!
                // Simulate failed send/lost response: durable bytes survive close/reopen and encrypt is not invoked again.
                sourceDb.close(); sourceDb = openDesktopDatabase(sourceFile.toString()); sourceRepo = RoomProviderCredentialProvisioningRepository(sourceDb)
                val noEncryptRetry = object : PairingHpke by TinkPairingHpke() {
                    override fun encrypt(publicKey: HpkePublicKeyBase64Url, plaintext: ByteArray, contextInfo: ByteArray): HpkeCiphertextComponents = error("RETRY_MUST_NOT_ENCRYPT")
                }
                val retrySource = ProviderCredentialSourceDeliveryService(sourceRepo, noEncryptRetry, source, sourceTransport, directory::activeDevices)
                retrySource.uploadReserved(request); retrySource.uploadReserved(request)
                assertEquals(original.canonicalEnvelopeJson, sourceRepo.delivery(source, target, targetConfigId.value, 1)!!.canonicalEnvelopeJson)
                val fetched = targetTransport.fetch(targetConfigId.value)!!
                val envelope = fetched.envelope!!
                assertEquals(sourceComparison.envelope, envelope)
                val targetComparison = targetService.compare(source, envelope)
                assertEquals(sourceComparison.comparisonCode, targetComparison.comparisonCode)
                assertNull(RoomAgentStateRepository(targetDb).providerConfig(targetConfigId)!!.credentialReference, "Decrypt/SAS must not import or activate")
                targetService.approveComparisonByExplicitLocalUser(targetComparison, sourceComparison.comparisonCode)
                val installed = targetService.installApproved(source, envelope)
                assertEquals(ProviderCredentialAcknowledgementResult.INSTALLED, installed.result)
                val state = targetRepo.state(target, targetConfigId.value)!!
                ownedProviderSlot = store.restoreProviderSlot(SecretReference(state.activeReference!!), state.activeInstallIdentity!!)
                targetDb.close(); targetDb = openDesktopDatabase(targetFile.toString()); targetRepo = RoomProviderCredentialProvisioningRepository(targetDb)
                targetService = ProviderCredentialProvisioningService(targetRepo, store, targetHpke, target, privateKey)
                targetService.recover()
                assertEquals(rawCredential, checkNotNull(store.readProviderSecret(SecretReference(state.activeReference))).decodeToString())
                assertEquals(ProviderCredentialAcknowledgementResult.DUPLICATE, targetService.installApproved(source, envelope).result)
                targetTransport.acknowledge(installed)
                retrySource.receivedInformationalAcknowledgement(installed)
                assertNull(sourceRepo.delivery(source, target, targetConfigId.value, 1))
                assertEquals(ProviderCredentialMailboxState.ACKNOWLEDGED, targetTransport.fetch(targetConfigId.value)!!.state)
                assertNull(targetTransport.fetch(targetConfigId.value)!!.envelope)
                val acceptedBeforeExpiry = targetRepo.state(target, targetConfigId.value)!!
                val deliveryTiming = ds.connection.use { c -> c.prepareStatement("SELECT json_build_array(created_at, expires_at)::text, expires_at FROM provider_credential_mailbox WHERE target_device_id = ? AND provider_config_id = ?").use { s ->
                    s.setString(1, target.value); s.setString(2, targetConfigId.value); s.executeQuery().use { r ->
                        assertTrue(r.next()); Json.parseToJsonElement(r.getString(1)).jsonArray.map { it.jsonPrimitive.content } to r.getTimestamp(2).toInstant()
                    }
                } }
                val deliveryTimestampCanaries = deliveryTiming.first
                // Exercise the exact deadline with real SQL after informational ACK, without waiting seven days.
                // The wire uses whole seconds; SQL preserves microseconds. Inject the actual persisted cutoff.
                val deadline = deliveryTiming.second
                JdbcProviderCredentialMailbox(ds, Clock.fixed(deadline.minusNanos(1000), ZoneOffset.UTC)).expireCredentialDeliveries()
                assertEquals(ProviderCredentialMailboxState.ACKNOWLEDGED, targetTransport.fetch(targetConfigId.value)!!.state)
                val maintenance = JdbcProviderCredentialMailbox(ds, Clock.fixed(deadline, ZoneOffset.UTC))
                maintenance.expireCredentialDeliveries()
                val expiredFetch = assertFailsWith<ProviderCredentialTransportException> { targetTransport.fetch(targetConfigId.value) }
                assertEquals(410, expiredFetch.status); assertEquals("DELIVERY_EXPIRED", expiredFetch.code)
                val expiredRetry = assertFailsWith<ProviderCredentialTransportException> { targetTransport.publish(request) }
                assertEquals(410, expiredRetry.status); assertEquals("DELIVERY_EXPIRED", expiredRetry.code)
                fun tombstone(): String = ds.connection.use { c -> c.prepareStatement("SELECT row_to_json(m)::text FROM provider_credential_mailbox m WHERE target_device_id = ? AND provider_config_id = ?").use { s ->
                    s.setString(1, target.value); s.setString(2, targetConfigId.value); s.executeQuery().use { r -> assertTrue(r.next()); r.getString(1) }
                } }
                val expiredRow = tombstone()
                assertEquals(mapOf("account_id" to JsonPrimitive(account), "target_device_id" to JsonPrimitive(target.value),
                    "provider_config_id" to JsonPrimitive(targetConfigId.value), "credential_revision" to JsonPrimitive(1),
                    "delivery_state" to JsonPrimitive("DELIVERY_EXPIRED")), Json.parseToJsonElement(expiredRow).jsonObject.filterValues { it != JsonNull })
                JdbcProviderCredentialMailbox(ds, Clock.fixed(deadline.plusNanos(1000), ZoneOffset.UTC)).expireCredentialDeliveries()
                assertEquals(expiredRow, tombstone())
                maintenance.expireCredentialDeliveries(); assertEquals(expiredRow, tombstone())
                // Delivery retirement never changes local accepted counters, binding or installed credentials.
                assertEquals(acceptedBeforeExpiry, targetRepo.state(target, targetConfigId.value))
                assertEquals(rawCredential, store.readProviderSecret(SecretReference(state.activeReference))!!.decodeToString())
                // Every public table is scanned; secret/plaintext hash/binding JSON/digest/ref stay client-only.
                val bindingText = ProviderCredentialWireCodec.encodeBinding(targetBinding).decodeToString()
                val privateValues = listOf(rawCredential, baseUrlCanary, modelCanary, bindingText, providerBindingDigest(targetBinding), state.activeReference,
                    decodeCanonicalBase64Url(providerBindingDigest(targetBinding), 32, "local binding digest").joinToString("") { "%02x".format(it) },
                    encodeCanonicalBase64Url(pairingSha256(rawCredential.encodeToByteArray())), pairingSha256(rawCredential.encodeToByteArray()).joinToString("") { "%02x".format(it) },
                    original.canonicalEnvelopeJson, original.canonicalEnvelopeJson.encodeToByteArray().joinToString("") { "%02x".format(it) },
                    original.envelopeDigest, decodeCanonicalBase64Url(original.envelopeDigest, 32, "envelope digest").joinToString("") { "%02x".format(it) }) + deliveryTimestampCanaries
                // row_to_json represents BYTEA as hex and embedded JSON as escaped strings.
                val forbidden = privateValues.flatMap { value -> listOf(value, JsonPrimitive(value).toString(),
                    encodeCanonicalBase64Url(value.encodeToByteArray()), value.encodeToByteArray().joinToString("") { "%02x".format(it) }) }.distinct()
                ds.connection.use { c ->
                    // The D8 device directory legitimately retains source identity; the tombstone does not.
                    assertFalse(source.value in expiredRow)
                    for (field in listOf("provisioner_device_id", "canonical_envelope", "envelope_digest", "acknowledgement_result", "created_at", "expires_at"))
                        assertEquals(JsonNull, Json.parseToJsonElement(expiredRow).jsonObject[field])
                    val tables = c.createStatement().use { s -> s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
                    for (table in tables) {
                        require(Regex("[A-Za-z0-9_]+").matches(table))
                        c.createStatement().use { s -> s.executeQuery("SELECT row_to_json(t)::text FROM public.\"$table\" t").use { r ->
                            while (r.next()) for (canary in forbidden) assertFalse(canary in r.getString(1), "Private provisioning canary present in $table")
                        } }
                    }
                }
            }
        } finally {
            targetDb.close(); sourceDb.close(); Files.deleteIfExists(targetFile); Files.deleteIfExists(sourceFile)
            runBlocking { ownedProviderSlot?.let { store.deleteProviderSlot(it) }; ownedRefs.forEach { store.delete(it) } }
            ds.connection.use { c ->
                for (sql in listOf("DELETE FROM provider_credential_mailbox WHERE account_id = ?", "DELETE FROM device WHERE account_id = ?", "DELETE FROM account WHERE account_id = ?")) c.prepareStatement(sql).use { s -> s.setString(1, account); s.executeUpdate() }
            }
            ds.close()
        }
    }
}
