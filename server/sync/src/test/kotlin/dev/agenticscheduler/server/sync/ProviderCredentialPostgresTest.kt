package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.*
import dev.agenticscheduler.sync.*
import org.junit.Before
import org.junit.After
import org.junit.Assume
import kotlinx.serialization.json.*
import java.sql.SQLException
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

/** Requires actual PostgreSQL. Missing environment is a skipped test, never acceptance evidence. */
class ProviderCredentialPostgresTest {
    private lateinit var ds: HikariDataSource
    private lateinit var repo: JdbcProviderCredentialMailbox
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    // Global expiry with a far-future clock must not retire another Gradle test worker's deliveries.
    private val schema = "credential_case_$suffix"
    private var schemaCreated = false
    private val account = "credential-$suffix"
    private val otherAccount = "credential-other-$suffix"
    private val target = "WearTarget:opaque-A_01-$suffix"
    private val source = "PhoneSource:opaque-B_02-$suffix"
    private val other = "OtherSource-$suffix"
    private val foreign = "ForeignSource-$suffix"
    private val config = "00000000-0000-7000-8000-000000000101"
    private val targetActor get() = AuthenticatedDevice(account, target)
    private val sourceActor get() = AuthenticatedDevice(account, source)
    private val request get() = ProviderCredentialReservationRequestV1(DeviceId(target), config, 1, DeviceId(source))
    private val clock = MutableClock(Instant.parse("2026-10-04T00:00:00Z"))
    @Before fun setup() {
        Assume.assumeTrue("Real PostgreSQL required", System.getenv("SYNC_TEST_DATABASE_URL") != null)
        dataSource().use { admin -> admin.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } } }
        schemaCreated = true
        ds = dataSource(schema)
        ServerSchemaMigrator(ds).migrate()
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO account(account_id) VALUES (?), (?)").use { s -> s.setString(1, account); s.setString(2, otherAccount); s.executeUpdate() }
            for ((device, acct) in listOf(target to account, source to account, other to account, foreign to otherAccount)) {
                c.prepareStatement("INSERT INTO device(device_id, account_id, credential_hash, hpke_public_key) VALUES (?, ?, ?, ?)").use { s ->
                    s.setString(1, device); s.setString(2, acct); s.setBytes(3, MessageDigest.getInstance("SHA-256").digest(device.toByteArray())); s.setBytes(4, ByteArray(32)); s.executeUpdate()
                }
            }
        }
        repo = JdbcProviderCredentialMailbox(ds, clock)
    }
    @After fun cleanup() {
        if (::ds.isInitialized) ds.close()
        if (schemaCreated) dataSource().use { admin -> admin.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } } }
    }
    private fun dataSource(schema: String? = null) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = System.getenv("SYNC_TEST_DATABASE_URL"); username = System.getenv("SYNC_TEST_DATABASE_USER") ?: "agentic"
        password = System.getenv("SYNC_TEST_DATABASE_PASSWORD") ?: "agentic-test"; maximumPoolSize = 5
        if (schema != null) connectionInitSql = "SET search_path TO $schema"
    })
    private fun envelope(revision: Long = 1, value: Int = 1) = ProviderCredentialEnvelopeV1(targetDeviceId = DeviceId(target), providerConfigId = config,
        credentialRevision = revision, encapsulatedKeyBase64Url = encodeCanonicalBase64Url(ByteArray(32)), ciphertextBase64Url = encodeCanonicalBase64Url(ByteArray(32) { value.toByte() }))
    private fun bytes(value: Int = 1) = ProviderCredentialWireCodec.encodeEnvelope(envelope(value = value))
    private fun fail(reason: CredentialMailboxFailure, action: () -> Unit) { assertEquals(reason, assertFailsWith<CredentialMailboxException>(block = action).reason) }
    private fun revoke(device: String) = ds.connection.use { c -> c.prepareStatement("UPDATE device SET revoked_at = CURRENT_TIMESTAMP WHERE device_id = ?").use { s -> s.setString(1, device); s.executeUpdate() } }
    private fun digest(value: ByteArray) = encodeCanonicalBase64Url(MessageDigest.getInstance("SHA-256").digest(value))
    private fun ack(value: ByteArray = bytes()) = ProviderCredentialAcknowledgementV1(targetDeviceId = DeviceId(target), providerConfigId = config, credentialRevision = 1,
        envelopeDigestBase64Url = digest(value), result = ProviderCredentialAcknowledgementResult.INSTALLED)
    private fun snapshot(): String = ds.connection.use { c -> c.prepareStatement("SELECT row_to_json(m)::text FROM provider_credential_mailbox m WHERE target_device_id = ? AND provider_config_id = ?").use { s ->
        s.setString(1, target); s.setString(2, config); s.executeQuery().use { r -> assertTrue(r.next()); r.getString(1).also { assertFalse(r.next()) } }
    } }
    private fun assertTombstone(revision: Long): String = snapshot().also { value ->
        val retained = Json.parseToJsonElement(value).jsonObject.filterValues { it != JsonNull }
        assertEquals(mapOf("account_id" to JsonPrimitive(account), "target_device_id" to JsonPrimitive(target),
            "provider_config_id" to JsonPrimitive(config), "credential_revision" to JsonPrimitive(revision),
            "delivery_state" to JsonPrimitive("DELIVERY_EXPIRED")), retained)
    }

    @Test fun `active same account request repeats without extending deadline and changing source conflicts`() {
        repo.publishCredentialRequest(targetActor, request)
        val initial = repo.assignedCredentialRequest(sourceActor)!!
        clock.now = clock.now.plusSeconds(600); repo.publishCredentialRequest(targetActor, request)
        assertEquals(initial.expiresAtEpochSeconds, repo.assignedCredentialRequest(sourceActor)!!.expiresAtEpochSeconds)
        fail(CredentialMailboxFailure.DELIVERY_CONFLICT) { repo.publishCredentialRequest(targetActor, request.copy(provisionerDeviceId = DeviceId(other))) }
        assertNull(repo.assignedCredentialRequest(AuthenticatedDevice(account, other)))
    }
    @Test fun `expiry maintenance purges ciphertext and ACK metadata even without ACTIVE clients`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes())
        revoke(source); revoke(target)
        clock.now = clock.now.plusSeconds(7 * 86400L)
        repo.expireCredentialDeliveries(); repo.expireCredentialDeliveries()
        val expired = assertTombstone(1)
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2)) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.fetchCredentialMailbox(targetActor, config) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.uploadCredentialEnvelope(sourceActor, bytes()) }
        clock.now = clock.now.plusSeconds(100L * 365 * 86400)
        repo = JdbcProviderCredentialMailbox(ds, clock); repo.expireCredentialDeliveries()
        assertEquals(expired, assertTombstone(1))
    }
    @Test fun `cross account source and non target request publication reject`() {
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(targetActor, request.copy(provisionerDeviceId = DeviceId(foreign))) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(sourceActor, request) }
        repo.publishCredentialRequest(targetActor, request)
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.uploadCredentialEnvelope(AuthenticatedDevice(otherAccount, foreign), bytes()) }
    }
    @Test fun `revoked source denied discovery upload and target fetch ACK`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes()); revoke(source)
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.assignedCredentialRequest(sourceActor) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.uploadCredentialEnvelope(sourceActor, bytes()) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.fetchCredentialMailbox(targetActor, config) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.acknowledgeCredential(targetActor, ack()) }
    }
    @Test fun `revoked target denied publication fetch ACK and source upload`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes()); revoke(target)
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(targetActor, request) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.fetchCredentialMailbox(targetActor, config) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.acknowledgeCredential(targetActor, ack()) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.uploadCredentialEnvelope(sourceActor, bytes()) }
        assertNull(repo.assignedCredentialRequest(sourceActor))
    }
    @Test fun `wrong source and higher unreserved upload reject`() {
        repo.publishCredentialRequest(targetActor, request)
        fail(CredentialMailboxFailure.NOT_ASSIGNED) { repo.uploadCredentialEnvelope(AuthenticatedDevice(account, other), bytes()) }
        fail(CredentialMailboxFailure.UNRESERVED_REVISION) { repo.uploadCredentialEnvelope(sourceActor, ProviderCredentialWireCodec.encodeEnvelope(envelope(2))) }
    }
    @Test fun `exact upload retries survive repository restart and changed bytes conflict`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes()); repo.uploadCredentialEnvelope(sourceActor, bytes())
        repo = JdbcProviderCredentialMailbox(ds, clock)
        repo.uploadCredentialEnvelope(sourceActor, bytes())
        fail(CredentialMailboxFailure.DELIVERY_CONFLICT) { repo.uploadCredentialEnvelope(sourceActor, bytes(2)) }
        assertContentEquals(bytes(), ProviderCredentialWireCodec.encodeEnvelope(repo.fetchCredentialMailbox(targetActor, config)!!.envelope!!))
    }
    @Test fun `only target fetches its mailbox and ACK removes ciphertext`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes())
        assertNull(repo.fetchCredentialMailbox(sourceActor, config))
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.acknowledgeCredential(sourceActor, ack()) }
        repo.acknowledgeCredential(targetActor, ack()); repo.acknowledgeCredential(targetActor, ack())
        val row = repo.fetchCredentialMailbox(targetActor, config)!!
        assertEquals(ProviderCredentialMailboxState.ACKNOWLEDGED, row.state); assertNull(row.envelope)
        repo.uploadCredentialEnvelope(sourceActor, bytes())
        assertNull(repo.fetchCredentialMailbox(targetActor, config)!!.envelope)
        ds.connection.use { c -> c.prepareStatement("SELECT canonical_envelope FROM provider_credential_mailbox WHERE target_device_id = ?").use { s -> s.setString(1, target); s.executeQuery().use { assertTrue(it.next()); assertNull(it.getBytes(1)) } } }
    }
    @Test fun `seven day expiry is explicit purges delivery metadata and requires higher revision`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes())
        clock.now = clock.now.plusSeconds(7 * 86400L - 1)
        assertNotNull(repo.fetchCredentialMailbox(targetActor, config)!!.envelope)
        clock.now = clock.now.plusSeconds(1)
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.fetchCredentialMailbox(targetActor, config) }
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.publishCredentialRequest(targetActor, request) }
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.uploadCredentialEnvelope(sourceActor, bytes()) }
        val expired = assertTombstone(1)
        repo.expireCredentialDeliveries(); assertEquals(expired, snapshot())
        clock.now = clock.now.plusSeconds(100L * 365 * 86400)
        repo = JdbcProviderCredentialMailbox(ds, clock)
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.publishCredentialRequest(targetActor, request) }
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.fetchCredentialMailbox(targetActor, config) }
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.acknowledgeCredential(targetActor, ack()) }
        assertEquals(expired, snapshot())
        val higherCreated = clock.now
        repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 3))
        val higher = repo.assignedCredentialRequest(sourceActor)!!
        assertEquals(3L, higher.request.credentialRevision)
        assertEquals(higherCreated.plusSeconds(7 * 86400L).epochSecond, higher.expiresAtEpochSeconds)
        ds.connection.use { c -> c.prepareStatement("SELECT created_at, expires_at FROM provider_credential_mailbox WHERE target_device_id = ?").use { s ->
            s.setString(1, target); s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(higherCreated, r.getTimestamp(1).toInstant()); assertEquals(higherCreated.plusSeconds(7 * 86400L), r.getTimestamp(2).toInstant()) }
        } }
        clock.now = higherCreated.plusSeconds(7 * 86400L)
        repo.expireCredentialDeliveries()
        val advanced = assertTombstone(3)
        fail(CredentialMailboxFailure.UNRESERVED_REVISION) { repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2)) }
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 3)) }
        repo.expireCredentialDeliveries(); assertEquals(advanced, snapshot())
    }
    @Test fun `requested and acknowledged delivery states both expire to the same minimal tombstone`() {
        repo.publishCredentialRequest(targetActor, request)
        clock.now = clock.now.plusSeconds(7 * 86400L); repo.expireCredentialDeliveries()
        assertTombstone(1)
        val higher = request.copy(credentialRevision = 2)
        repo.publishCredentialRequest(targetActor, higher)
        val cipher = ProviderCredentialWireCodec.encodeEnvelope(envelope(2))
        repo.uploadCredentialEnvelope(sourceActor, cipher)
        repo.acknowledgeCredential(targetActor, ack(cipher).copy(credentialRevision = 2))
        assertEquals(ProviderCredentialMailboxState.ACKNOWLEDGED, repo.fetchCredentialMailbox(targetActor, config)!!.state)
        clock.now = clock.now.plusSeconds(7 * 86400L); repo.expireCredentialDeliveries()
        val expired = assertTombstone(2)
        repo.expireCredentialDeliveries(); assertEquals(expired, snapshot())
    }
    @Test fun `expired marker survives source revocation and only eligible higher target reservations replace it`() {
        repo.publishCredentialRequest(targetActor, request)
        clock.now = clock.now.plusSeconds(7 * 86400L); repo.expireCredentialDeliveries()
        val expired = assertTombstone(1)
        revoke(source)
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.publishCredentialRequest(targetActor, request) }
        fail(CredentialMailboxFailure.DELIVERY_EXPIRED) { repo.publishCredentialRequest(targetActor, request.copy(provisionerDeviceId = DeviceId(other))) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2)) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2, provisionerDeviceId = DeviceId(foreign))) }
        fail(CredentialMailboxFailure.UNAUTHORIZED) { repo.publishCredentialRequest(AuthenticatedDevice(account, other), request.copy(credentialRevision = 2)) }
        assertEquals(expired, snapshot())
        repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2, provisionerDeviceId = DeviceId(other)))
        val replacement = repo.assignedCredentialRequest(AuthenticatedDevice(account, other))!!
        assertEquals(2L, replacement.request.credentialRevision)
        assertEquals(clock.now.plusSeconds(7 * 86400L).epochSecond, replacement.expiresAtEpochSeconds)
    }
    @Test fun `V10 CHECK constraints prohibit expired remnants and incomplete active delivery states`() {
        repo.publishCredentialRequest(targetActor, request)
        val original = snapshot()
        fun rejects(change: String) {
            val failure = assertFailsWith<SQLException> { ds.connection.use { c ->
                c.prepareStatement("UPDATE provider_credential_mailbox SET $change WHERE target_device_id = ? AND provider_config_id = ?").use { s ->
                    s.setString(1, target); s.setString(2, config); s.executeUpdate()
                }
            } }
            assertEquals("23514", failure.sqlState)
        }
        for (change in listOf("created_at = NULL", "expires_at = NULL", "provisioner_device_id = NULL", "expires_at = expires_at + INTERVAL '1 second'",
            "envelope_digest = decode(repeat('00', 32), 'hex')", "acknowledgement_result = 'INSTALLED'",
            "delivery_state = 'DELIVERED'", "delivery_state = 'ACKNOWLEDGED'", "delivery_state = 'DELIVERY_EXPIRED'")) {
            rejects(change); assertEquals(original, snapshot())
        }
        clock.now = clock.now.plusSeconds(7 * 86400L); repo.expireCredentialDeliveries()
        val expired = assertTombstone(1)
        for (change in listOf("created_at = CURRENT_TIMESTAMP", "expires_at = CURRENT_TIMESTAMP", "provisioner_device_id = '$source'",
            "canonical_envelope = decode('00', 'hex')", "envelope_digest = decode(repeat('00', 32), 'hex')", "acknowledgement_result = 'INSTALLED'",
            "delivery_state = 'REQUESTED'")) {
            rejects(change); assertEquals(expired, snapshot())
        }
    }
    @Test fun `new target reservation cancels old source without causal clock or server allocator`() {
        repo.publishCredentialRequest(targetActor, request)
        repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2, provisionerDeviceId = DeviceId(other)))
        fail(CredentialMailboxFailure.UNRESERVED_REVISION) { repo.uploadCredentialEnvelope(sourceActor, bytes()) }
        assertNull(repo.assignedCredentialRequest(sourceActor))
        assertEquals(2L, repo.assignedCredentialRequest(AuthenticatedDevice(account, other))!!.request.credentialRevision)
    }
    @Test fun `concurrent unequal uploads select no automatic winner after first immutable admission`() {
        repo.publishCredentialRequest(targetActor, request)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val results = (1..2).map { value -> executor.submit<String> { barrier.await(); try { repo.uploadCredentialEnvelope(sourceActor, bytes(value)); "STORED" } catch (e: CredentialMailboxException) { e.reason.name } } }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf("DELIVERY_CONFLICT", "STORED"), results.sorted())
            val accepted = repo.fetchCredentialMailbox(targetActor, config)!!.envelope!!
            assertTrue(accepted == envelope(value = 1) || accepted == envelope(value = 2))
        } finally { executor.shutdownNow() }
    }
    @Test fun `source revoked while waiting for account lock cannot publish ciphertext`() {
        repo.publishCredentialRequest(targetActor, request)
        val executor = Executors.newSingleThreadExecutor()
        try {
            ds.connection.use { c ->
                c.autoCommit = false
                c.prepareStatement("SELECT account_id FROM account WHERE account_id = ? FOR UPDATE").use { s -> s.setString(1, account); s.executeQuery().use { assertTrue(it.next()) } }
                val upload = executor.submit<CredentialMailboxFailure?> { try { repo.uploadCredentialEnvelope(sourceActor, bytes()); null } catch (e: CredentialMailboxException) { e.reason } }
                c.prepareStatement("UPDATE device SET revoked_at = CURRENT_TIMESTAMP WHERE device_id = ?").use { s -> s.setString(1, source); s.executeUpdate() }
                c.commit()
                assertEquals(CredentialMailboxFailure.UNAUTHORIZED, upload.get(10, TimeUnit.SECONDS))
            }
            assertNull(repo.assignedCredentialRequest(AuthenticatedDevice(account, other)))
        } finally { executor.shutdownNow() }
    }
    @Test fun `mailbox table contains only allowlisted routing and opaque fields`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes())
        ds.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'provider_credential_mailbox'").use { r ->
                val names = buildSet { while (r.next()) add(r.getString(1)) }
                assertEquals(setOf("target_device_id", "provider_config_id", "credential_revision", "account_id", "provisioner_device_id", "delivery_state", "canonical_envelope", "envelope_digest", "acknowledgement_result", "created_at", "expires_at"), names)
            } }
        }
    }
    @Test fun `real fresh V10 and V9 to V10 migrations are equivalent and preserve old data`() {
        val schemas = listOf("credential_fresh_$suffix", "credential_upgrade_$suffix")
        try {
            for ((index, schema) in schemas.withIndex()) {
                ds.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
                dataSource(schema).use { isolated ->
                    if (index == 1) isolated.connection.use { c ->
                        c.createStatement().use { it.execute("CREATE TABLE agentic_server_schema_version(version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)") }
                        for (m in ServerMigrationCatalog.migrations.filter { it.version <= 9 }) {
                            val sql = javaClass.classLoader.getResourceAsStream(m.resource)!!.bufferedReader().use { it.readText() }
                            c.createStatement().use { it.execute(sql); it.execute("INSERT INTO agentic_server_schema_version(version) VALUES (${m.version})") }
                        }
                        c.prepareStatement("INSERT INTO account(account_id) VALUES (?)").use { it.setString(1, "preserved-account"); it.executeUpdate() }
                    }
                    ServerSchemaMigrator(isolated).migrate(); ServerSchemaMigrator(isolated).migrate()
                    isolated.connection.use { c -> c.createStatement().use { s ->
                        s.executeQuery("SELECT max(version) FROM agentic_server_schema_version").use { assertTrue(it.next()); assertEquals(10, it.getInt(1)) }
                        s.executeQuery("SELECT count(*) FROM provider_credential_mailbox").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                        if (index == 1) s.executeQuery("SELECT account_id FROM account").use { assertTrue(it.next()); assertEquals("preserved-account", it.getString(1)) }
                    } }
                }
            }
            ds.connection.use { c -> c.prepareStatement("SELECT table_schema, column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'provider_credential_mailbox' AND table_schema IN (?, ?) ORDER BY table_schema, ordinal_position").use { s ->
                s.setString(1, schemas[0]); s.setString(2, schemas[1]); s.executeQuery().use { r ->
                    val result = mutableMapOf<String, MutableList<String>>()
                    while (r.next()) result.getOrPut(r.getString(1)) { mutableListOf() }.add((2..4).joinToString(":") { r.getString(it) })
                    assertEquals(result[schemas[0]], result[schemas[1]])
                }
            } }
            ds.connection.use { c -> c.prepareStatement("SELECT n.nspname, pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class t ON t.oid = k.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE t.relname = 'provider_credential_mailbox' AND n.nspname IN (?, ?) AND k.contype = 'c' ORDER BY n.nspname, pg_get_constraintdef(k.oid)").use { s ->
                s.setString(1, schemas[0]); s.setString(2, schemas[1]); s.executeQuery().use { r ->
                    val checks = mutableMapOf<String, MutableList<String>>()
                    while (r.next()) checks.getOrPut(r.getString(1)) { mutableListOf() }.add(r.getString(2))
                    assertEquals(checks[schemas[0]], checks[schemas[1]])
                    assertTrue(checks[schemas[0]]!!.any { "created_at IS NULL" in it && "expires_at IS NULL" in it })
                }
            } }
        } finally { ds.connection.use { c -> c.createStatement().use { s -> schemas.forEach { s.execute("DROP SCHEMA IF EXISTS $it CASCADE") } } } }
    }
    private class MutableClock(var now: Instant) : Clock() { override fun getZone(): ZoneId = ZoneOffset.UTC; override fun withZone(zone: ZoneId): Clock = this; override fun instant(): Instant = now }
}
