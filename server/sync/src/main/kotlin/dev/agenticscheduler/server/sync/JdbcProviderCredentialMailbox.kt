package dev.agenticscheduler.server.sync

import dev.agenticscheduler.sync.*
import java.sql.Connection
import java.sql.Timestamp
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

enum class CredentialMailboxFailure { UNAUTHORIZED, NOT_ASSIGNED, DELIVERY_CONFLICT, DELIVERY_EXPIRED, UNRESERVED_REVISION, INVALID_CREDENTIAL_ENVELOPE }
class CredentialMailboxException(val reason: CredentialMailboxFailure) : IllegalStateException(reason.name)
interface ServerProviderCredentialMailbox {
    fun publishCredentialRequest(actor: AuthenticatedDevice, request: ProviderCredentialReservationRequestV1)
    fun assignedCredentialRequest(actor: AuthenticatedDevice): ProviderCredentialMailboxDeliveryV1?
    fun uploadCredentialEnvelope(actor: AuthenticatedDevice, canonicalBytes: ByteArray)
    fun fetchCredentialMailbox(actor: AuthenticatedDevice, config: String): ProviderCredentialMailboxDeliveryV1?
    fun acknowledgeCredential(actor: AuthenticatedDevice, ack: ProviderCredentialAcknowledgementV1)
}

/** D8 account row lock serializes provisioning with revoke/rotate; auth is rechecked inside each transaction. */
class JdbcProviderCredentialMailbox(private val dataSource: DataSource, private val clock: Clock = Clock.systemUTC()) : ServerProviderCredentialMailbox {
    override fun publishCredentialRequest(actor: AuthenticatedDevice, request: ProviderCredentialReservationRequestV1) = transaction(actor) { c, now ->
        if (request.targetDeviceId.value != actor.deviceId || !active(c, actor.accountId, request.provisionerDeviceId.value)) fail(CredentialMailboxFailure.UNAUTHORIZED)
        val old = row(c, request.targetDeviceId.value, request.providerConfigId)
        if (old != null) {
            if (request.credentialRevision < old.revision) fail(CredentialMailboxFailure.UNRESERVED_REVISION)
            if (request.credentialRevision == old.revision) {
                if (old.state == ProviderCredentialMailboxState.DELIVERY_EXPIRED) fail(CredentialMailboxFailure.DELIVERY_EXPIRED)
                if (old.source != request.provisionerDeviceId.value) fail(CredentialMailboxFailure.DELIVERY_CONFLICT)
                return@transaction
            }
        }
        c.prepareStatement("INSERT INTO provider_credential_mailbox(target_device_id, provider_config_id, credential_revision, account_id, provisioner_device_id, delivery_state, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?) ON CONFLICT(target_device_id, provider_config_id) DO UPDATE SET credential_revision = excluded.credential_revision, provisioner_device_id = excluded.provisioner_device_id, delivery_state = 'REQUESTED', canonical_envelope = NULL, envelope_digest = NULL, acknowledgement_result = NULL, created_at = excluded.created_at, expires_at = excluded.expires_at").use { s ->
            s.setString(1, request.targetDeviceId.value); s.setString(2, request.providerConfigId); s.setLong(3, request.credentialRevision)
            s.setString(4, actor.accountId); s.setString(5, request.provisionerDeviceId.value); s.setTimestamp(6, Timestamp.from(now)); s.setTimestamp(7, Timestamp.from(now.plusSeconds(7 * 86400L))); s.executeUpdate()
        }
        Unit
    }
    override fun assignedCredentialRequest(actor: AuthenticatedDevice): ProviderCredentialMailboxDeliveryV1? = transaction(actor) { c, _ ->
        c.prepareStatement("SELECT m.target_device_id, m.provider_config_id FROM provider_credential_mailbox m JOIN device d ON d.device_id = m.target_device_id AND d.account_id = m.account_id AND d.revoked_at IS NULL WHERE m.account_id = ? AND m.provisioner_device_id = ? AND m.delivery_state = 'REQUESTED' ORDER BY m.target_device_id, m.provider_config_id LIMIT 1").use { s ->
            s.setString(1, actor.accountId); s.setString(2, actor.deviceId)
            s.executeQuery().use { rs -> if (rs.next()) requireNotNull(row(c, rs.getString(1), rs.getString(2))).delivery() else null }
        }
    }
    override fun uploadCredentialEnvelope(actor: AuthenticatedDevice, canonicalBytes: ByteArray) {
        val decoded = ProviderCredentialWireCodec.decodeEnvelope(canonicalBytes)
        if (decoded !is ProviderCredentialDecodeResult.Accepted) fail(CredentialMailboxFailure.INVALID_CREDENTIAL_ENVELOPE)
        val envelope = decoded.value
        if (!canonicalBytes.contentEquals(ProviderCredentialWireCodec.encodeEnvelope(envelope))) fail(CredentialMailboxFailure.INVALID_CREDENTIAL_ENVELOPE)
        transaction(actor) { c, _ ->
            if (!active(c, actor.accountId, envelope.targetDeviceId.value)) fail(CredentialMailboxFailure.UNAUTHORIZED)
            val old = row(c, envelope.targetDeviceId.value, envelope.providerConfigId) ?: fail(CredentialMailboxFailure.UNRESERVED_REVISION)
            if (old.account != actor.accountId) fail(CredentialMailboxFailure.UNAUTHORIZED)
            if (old.revision != envelope.credentialRevision) fail(CredentialMailboxFailure.UNRESERVED_REVISION)
            if (old.state == ProviderCredentialMailboxState.DELIVERY_EXPIRED) fail(CredentialMailboxFailure.DELIVERY_EXPIRED)
            if (old.source != actor.deviceId) fail(CredentialMailboxFailure.NOT_ASSIGNED)
            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
            if (old.digest != null) {
                if (!old.digest.contentEquals(digest) || old.bytes != null && !old.bytes.contentEquals(canonicalBytes)) fail(CredentialMailboxFailure.DELIVERY_CONFLICT)
                return@transaction
            }
            c.prepareStatement("UPDATE provider_credential_mailbox SET canonical_envelope = ?, envelope_digest = ?, delivery_state = 'DELIVERED' WHERE target_device_id = ? AND provider_config_id = ?").use { s ->
                s.setBytes(1, canonicalBytes); s.setBytes(2, digest); s.setString(3, old.target); s.setString(4, old.config); s.executeUpdate()
            }
            Unit
        }
    }
    override fun fetchCredentialMailbox(actor: AuthenticatedDevice, config: String): ProviderCredentialMailboxDeliveryV1? = transaction(actor) { c, _ ->
        MutationId(config)
        val old = row(c, actor.deviceId, config) ?: return@transaction null
        if (old.account != actor.accountId) fail(CredentialMailboxFailure.UNAUTHORIZED)
        if (old.state == ProviderCredentialMailboxState.DELIVERY_EXPIRED) fail(CredentialMailboxFailure.DELIVERY_EXPIRED)
        if (!active(c, actor.accountId, requireNotNull(old.source))) fail(CredentialMailboxFailure.UNAUTHORIZED)
        old.delivery()
    }
    override fun acknowledgeCredential(actor: AuthenticatedDevice, ack: ProviderCredentialAcknowledgementV1) = transaction(actor) { c, _ ->
        if (ack.targetDeviceId.value != actor.deviceId) fail(CredentialMailboxFailure.UNAUTHORIZED)
        val old = row(c, actor.deviceId, ack.providerConfigId) ?: fail(CredentialMailboxFailure.UNRESERVED_REVISION)
        if (old.revision != ack.credentialRevision) fail(CredentialMailboxFailure.UNRESERVED_REVISION)
        if (old.state == ProviderCredentialMailboxState.DELIVERY_EXPIRED) fail(CredentialMailboxFailure.DELIVERY_EXPIRED)
        if (old.account != actor.accountId || !active(c, actor.accountId, requireNotNull(old.source))) fail(CredentialMailboxFailure.UNAUTHORIZED)
        if (old.digest == null || !old.digest.contentEquals(decodeCanonicalBase64Url(ack.envelopeDigestBase64Url, 32, "envelope digest"))) fail(CredentialMailboxFailure.DELIVERY_CONFLICT)
        c.prepareStatement("UPDATE provider_credential_mailbox SET canonical_envelope = NULL, delivery_state = 'ACKNOWLEDGED', acknowledgement_result = ? WHERE target_device_id = ? AND provider_config_id = ?").use { s ->
            s.setString(1, ack.result.name); s.setString(2, actor.deviceId); s.setString(3, ack.providerConfigId); s.executeUpdate()
        }
        Unit
    }

    private fun <T> transaction(actor: AuthenticatedDevice, block: (Connection, Instant) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement("SELECT account_id FROM account WHERE account_id = ? FOR UPDATE").use { s ->
                s.setString(1, actor.accountId); s.executeQuery().use { if (!it.next()) fail(CredentialMailboxFailure.UNAUTHORIZED) }
            }
            if (!active(c, actor.accountId, actor.deviceId)) fail(CredentialMailboxFailure.UNAUTHORIZED)
            val now = clock.instant()
            // Retain only the latest routing revision/expiry marker to reject deadline-reset retries.
            // Ciphertext, source assignment, digest and ACK metadata are purged at the frozen deadline.
            c.prepareStatement("UPDATE provider_credential_mailbox SET delivery_state = 'DELIVERY_EXPIRED', canonical_envelope = NULL, envelope_digest = NULL, acknowledgement_result = NULL, provisioner_device_id = NULL WHERE account_id = ? AND expires_at <= ? AND delivery_state <> 'DELIVERY_EXPIRED'").use { s ->
                s.setString(1, actor.accountId); s.setTimestamp(2, Timestamp.from(now)); s.executeUpdate()
            }
            // Expiry is committed even if the requested operation reports DELIVERY_EXPIRED.
            val result = try { block(c, now) } catch (e: CredentialMailboxException) { c.commit(); throw e }
            c.commit(); result
        } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true }
    }
    private fun active(c: Connection, account: String, device: String): Boolean = c.prepareStatement("SELECT 1 FROM device WHERE account_id = ? AND device_id = ? AND revoked_at IS NULL").use { s ->
        s.setString(1, account); s.setString(2, device); s.executeQuery().use { it.next() }
    }
    private data class Row(val target: String, val config: String, val revision: Long, val account: String, val source: String?, val state: ProviderCredentialMailboxState, val bytes: ByteArray?, val digest: ByteArray?, val expires: Long) {
        fun delivery() = ProviderCredentialMailboxDeliveryV1(ProviderCredentialReservationRequestV1(DeviceId(target), config, revision, DeviceId(requireNotNull(source))), state, expires,
            bytes?.let { (ProviderCredentialWireCodec.decodeEnvelope(it) as ProviderCredentialDecodeResult.Accepted).value })
    }
    private fun row(c: Connection, target: String, config: String): Row? = c.prepareStatement("SELECT * FROM provider_credential_mailbox WHERE target_device_id = ? AND provider_config_id = ? FOR UPDATE").use { s ->
        s.setString(1, target); s.setString(2, config); s.executeQuery().use { r -> if (!r.next()) null else Row(target, config, r.getLong("credential_revision"), r.getString("account_id"), r.getString("provisioner_device_id"), ProviderCredentialMailboxState.valueOf(r.getString("delivery_state")), r.getBytes("canonical_envelope"), r.getBytes("envelope_digest"), r.getTimestamp("expires_at").toInstant().epochSecond) }
    }
    private fun fail(reason: CredentialMailboxFailure): Nothing = throw CredentialMailboxException(reason)
}
