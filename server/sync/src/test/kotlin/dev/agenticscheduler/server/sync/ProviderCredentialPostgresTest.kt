package dev.agenticscheduler.server.sync

import com.zaxxer.hikari.*
import dev.agenticscheduler.sync.*
import org.junit.Before
import org.junit.After
import org.junit.Assume
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
        ds = dataSource()
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
        if (!::ds.isInitialized) return
        ds.connection.use { c ->
            for (sql in listOf("DELETE FROM provider_credential_mailbox WHERE account_id IN (?, ?)", "DELETE FROM device WHERE account_id IN (?, ?)", "DELETE FROM account WHERE account_id IN (?, ?)"))
                c.prepareStatement(sql).use { s -> s.setString(1, account); s.setString(2, otherAccount); s.executeUpdate() }
        }
        ds.close()
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

    @Test fun `active same account request repeats without extending deadline and changing source conflicts`() {
        repo.publishCredentialRequest(targetActor, request)
        val initial = repo.assignedCredentialRequest(sourceActor)!!
        clock.now = clock.now.plusSeconds(600); repo.publishCredentialRequest(targetActor, request)
        assertEquals(initial.expiresAtEpochSeconds, repo.assignedCredentialRequest(sourceActor)!!.expiresAtEpochSeconds)
        fail(CredentialMailboxFailure.DELIVERY_CONFLICT) { repo.publishCredentialRequest(targetActor, request.copy(provisionerDeviceId = DeviceId(other))) }
        assertNull(repo.assignedCredentialRequest(AuthenticatedDevice(account, other)))
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
        ds.connection.use { c -> c.prepareStatement("SELECT canonical_envelope, envelope_digest, provisioner_device_id, acknowledgement_result FROM provider_credential_mailbox WHERE target_device_id = ?").use { s -> s.setString(1, target); s.executeQuery().use { assertTrue(it.next()); (1..4).forEach { column -> assertNull(it.getObject(column)) } } } }
        repo.publishCredentialRequest(targetActor, request.copy(credentialRevision = 2))
        assertEquals(2L, repo.assignedCredentialRequest(sourceActor)!!.request.credentialRevision)
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
    @Test fun `public mailbox table contains only allowlisted routing and opaque fields`() {
        repo.publishCredentialRequest(targetActor, request); repo.uploadCredentialEnvelope(sourceActor, bytes())
        ds.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'provider_credential_mailbox'").use { r ->
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
        } finally { ds.connection.use { c -> c.createStatement().use { s -> schemas.forEach { s.execute("DROP SCHEMA IF EXISTS $it CASCADE") } } } }
    }
    private class MutableClock(var now: Instant) : Clock() { override fun getZone(): ZoneId = ZoneOffset.UTC; override fun withZone(zone: ZoneId): Clock = this; override fun instant(): Instant = now }
}
