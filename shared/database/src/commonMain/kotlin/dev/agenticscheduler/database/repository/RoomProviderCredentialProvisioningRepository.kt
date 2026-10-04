package dev.agenticscheduler.database.repository

import androidx.room3.*
import androidx.sqlite.SQLiteStatement
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.sync.*
import kotlinx.serialization.json.*

/** All state CAS, journal ownership and ProviderConfig association changes share one Room transaction. */
class RoomProviderCredentialProvisioningRepository(private val database: AgenticSchedulerDatabase) : ProviderCredentialProvisioningRepository {
    private val json = Json { encodeDefaults = true }
    override suspend fun openConfig(target: DeviceId, config: String): ProviderCredentialRevisionState = database.withWriteTransaction {
        MutationId(config)
        if (!activeEnrollment(this, target)) throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
        val known = query("SELECT config_ids_json FROM provider_credential_target_identity WHERE target_device_id = ?", listOf(target.value)) { json.decodeFromString<List<String>>(it.getText(0)) }.singleOrNull()
            ?: throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
        stateOn(this, target, config)?.let { return@withWriteTransaction it }
        if (config in known) throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
        check(query("SELECT config_id FROM provider_config WHERE config_id = ?", listOf(config)) { it.getText(0) }.size == 1)
        val initial = ProviderCredentialRevisionState(target, config)
        execute("INSERT INTO provider_credential_revision(target_device_id, provider_config_id, state_json) VALUES (?, ?, ?)", listOf(target.value, config, json.encodeToString(initial)))
        execute("UPDATE provider_credential_target_identity SET config_ids_json = ? WHERE target_device_id = ?", listOf(json.encodeToString((known + config).sorted()), target.value))
        initial
    }
    override suspend fun state(target: DeviceId, config: String): ProviderCredentialRevisionState? = database.useReaderConnection { stateOn(it, target, config) }
    private suspend fun stateOn(c: PooledConnection, target: DeviceId, config: String) = c.query(
        "SELECT state_json FROM provider_credential_revision WHERE target_device_id = ? AND provider_config_id = ?", listOf(target.value, config),
    ) { json.decodeFromString<ProviderCredentialRevisionState>(it.getText(0)) }.singleOrNull()

    override suspend fun compareAndSet(expected: ProviderCredentialRevisionState, updated: ProviderCredentialRevisionState, journals: List<ProviderCredentialInstallJournal>): Boolean = database.withWriteTransaction {
        require(expected.targetDeviceId == updated.targetDeviceId && expected.providerConfigId == updated.providerConfigId && updated.generation == expected.generation + 1)
        if (stateOn(this, expected.targetDeviceId, expected.providerConfigId) != expected) return@withWriteTransaction false
        // An imported slot cannot publish after local enrollment was removed or became non-ACTIVE.
        // Disable/wipe must remain possible even after that loss.
        if (updated.phase != ProviderReservationPhase.DISABLED && !activeEnrollment(this, expected.targetDeviceId))
            throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_STATE_LOST)
        // Any active publication rechecks the current locally owned ProviderConfig values, not only a prior SAS.
        updated.approvedBinding?.let { binding ->
            val local = query("SELECT payload_json FROM provider_config WHERE config_id = ?", listOf(expected.providerConfigId)) { json.parseToJsonElement(it.getText(0)).jsonObject }.singleOrNull()
                ?: throw ProviderProvisioningException(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
            val expectedValues = json.parseToJsonElement(ProviderCredentialWireCodec.encodeBinding(binding).decodeToString()).jsonObject
            if (local.any { (key, value) -> expectedValues[key] != value }) throw ProviderProvisioningException(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH)
        }
        require(updated.highestReservedRevision >= expected.highestReservedRevision && updated.highestAcceptedRevision >= expected.highestAcceptedRevision && updated.rejectionFloor >= expected.rejectionFloor)
        journals.forEach { journal ->
            require(journal.targetDeviceId == expected.targetDeviceId && journal.providerConfigId == expected.providerConfigId)
            val old = query("SELECT journal_json FROM provider_credential_install_journal WHERE install_identity = ?", listOf(journal.installIdentity)) { json.decodeFromString<ProviderCredentialInstallJournal>(it.getText(0)) }.singleOrNull()
            require(old == null || old.copy(phase = journal.phase) == journal)
            require(old == null && journal.phase == ProviderInstallPhase.PREPARED_IMPORT || old != null && validJournalTransition(old.phase, journal.phase))
            execute("INSERT INTO provider_credential_install_journal(install_identity, target_device_id, provider_config_id, prepared_ref, journal_json) VALUES (?, ?, ?, ?, ?) ON CONFLICT(install_identity) DO UPDATE SET journal_json = excluded.journal_json",
                listOf(journal.installIdentity, journal.targetDeviceId.value, journal.providerConfigId, journal.preparedReference, json.encodeToString(journal)))
        }
        if (updated.activeReference != null) {
            val owner = query("SELECT journal_json FROM provider_credential_install_journal WHERE install_identity = ?", listOf(updated.activeInstallIdentity)) {
                json.decodeFromString<ProviderCredentialInstallJournal>(it.getText(0))
            }.singleOrNull()
            require(owner != null && owner.phase == ProviderInstallPhase.METADATA_COMMITTED &&
                owner.preparedReference == updated.activeReference && owner.targetDeviceId == updated.targetDeviceId &&
                owner.providerConfigId == updated.providerConfigId && owner.revision == updated.highestAcceptedRevision) { "PROVIDER_CREDENTIAL_JOURNAL_REQUIRED" }
        }
        execute("UPDATE provider_credential_revision SET state_json = ? WHERE target_device_id = ? AND provider_config_id = ?", listOf(json.encodeToString(updated), updated.targetDeviceId.value, updated.providerConfigId))
        if (expected.activeReference != updated.activeReference) {
            execute("UPDATE provider_config SET credential_secret_ref = ? WHERE config_id = ?", listOf(updated.activeReference, updated.providerConfigId))
        }
        true
    }

    override suspend fun journals(target: DeviceId): List<ProviderCredentialInstallJournal> = database.useReaderConnection {
        it.query("SELECT journal_json FROM provider_credential_install_journal WHERE target_device_id = ? ORDER BY install_identity", listOf(target.value)) { row -> json.decodeFromString<ProviderCredentialInstallJournal>(row.getText(0)) }
    }
    override suspend fun updateJournal(expected: ProviderCredentialInstallJournal, updated: ProviderCredentialInstallJournal): Boolean = database.withWriteTransaction {
        require(expected.copy(phase = updated.phase) == updated)
        require(validJournalTransition(expected.phase, updated.phase))
        val old = query("SELECT journal_json FROM provider_credential_install_journal WHERE install_identity = ?", listOf(expected.installIdentity)) { json.decodeFromString<ProviderCredentialInstallJournal>(it.getText(0)) }.singleOrNull()
        if (old != expected) return@withWriteTransaction false
        execute("UPDATE provider_credential_install_journal SET journal_json = ? WHERE install_identity = ?", listOf(json.encodeToString(updated), updated.installIdentity))
        true
    }

    override suspend fun saveDelivery(value: ProviderCredentialDeliveryOutbox): ProviderCredentialDeliveryOutbox = database.withWriteTransaction {
        require(ProviderCredentialWireCodec.encodeEnvelope(value.envelope).decodeToString() == value.canonicalEnvelopeJson && providerEnvelopeDigest(value.envelope) == value.envelopeDigest)
        require(value.expiresAtEpochSeconds > 0)
        val e = value.envelope
        val old = deliveryOn(this, value.sourceDeviceId, e.targetDeviceId, e.providerConfigId, e.credentialRevision)
        if (old != null) {
            if (old.copy(state = value.state) != value) throw ProviderProvisioningException(ProviderCredentialRejection.CREDENTIAL_INTEGRITY_CONFLICT)
            require(value.state.ordinal >= old.state.ordinal)
        }
        execute("INSERT INTO provider_credential_delivery_outbox(source_device_id, target_device_id, provider_config_id, revision, canonical_envelope_json, envelope_digest, delivery_state, expires_at_epoch_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(source_device_id, target_device_id, provider_config_id, revision) DO UPDATE SET delivery_state = excluded.delivery_state",
            listOf(value.sourceDeviceId.value, e.targetDeviceId.value, e.providerConfigId, e.credentialRevision, value.canonicalEnvelopeJson, value.envelopeDigest, value.state.name, value.expiresAtEpochSeconds))
        value
    }
    override suspend fun delivery(source: DeviceId, target: DeviceId, config: String, revision: Long): ProviderCredentialDeliveryOutbox? = database.useReaderConnection { deliveryOn(it, source, target, config, revision) }
    override suspend fun markDeliveryUploaded(expected: ProviderCredentialDeliveryOutbox): Boolean = database.withWriteTransaction {
        val e = expected.envelope
        val current = deliveryOn(this, expected.sourceDeviceId, e.targetDeviceId, e.providerConfigId, e.credentialRevision)
        if (current != expected) return@withWriteTransaction false
        execute("UPDATE provider_credential_delivery_outbox SET delivery_state = 'UPLOADED' WHERE source_device_id = ? AND target_device_id = ? AND provider_config_id = ? AND revision = ?",
            listOf(expected.sourceDeviceId.value, e.targetDeviceId.value, e.providerConfigId, e.credentialRevision))
        true
    }
    override suspend fun removeDelivery(source: DeviceId, target: DeviceId, config: String, revision: Long) = database.withWriteTransaction {
        execute("DELETE FROM provider_credential_delivery_outbox WHERE source_device_id = ? AND target_device_id = ? AND provider_config_id = ? AND revision = ?", listOf(source.value, target.value, config, revision))
    }
    private suspend fun deliveryOn(c: PooledConnection, source: DeviceId, target: DeviceId, config: String, revision: Long) = c.query(
        "SELECT canonical_envelope_json, envelope_digest, delivery_state, expires_at_epoch_seconds FROM provider_credential_delivery_outbox WHERE source_device_id = ? AND target_device_id = ? AND provider_config_id = ? AND revision = ?", listOf(source.value, target.value, config, revision),
    ) { row ->
        val text = row.getText(0)
        val decoded = ProviderCredentialWireCodec.decodeEnvelope(text.encodeToByteArray()) as ProviderCredentialDecodeResult.Accepted
        ProviderCredentialDeliveryOutbox(source, decoded.value, text, row.getText(1), ProviderDeliveryOutboxState.valueOf(row.getText(2)), row.getLong(3))
    }.singleOrNull()

    private suspend fun activeEnrollment(c: PooledConnection, target: DeviceId): Boolean =
        c.query("SELECT 1 FROM local_pairing_enrollment WHERE device_id = ? AND status = 'ACTIVE'", listOf(target.value)) { it.getLong(0) }.size == 1
}

private fun validJournalTransition(from: ProviderInstallPhase, to: ProviderInstallPhase): Boolean = from == to || when (from) {
    ProviderInstallPhase.PREPARED_IMPORT -> to in setOf(ProviderInstallPhase.SECRET_IMPORTED, ProviderInstallPhase.REJECTED, ProviderInstallPhase.CLEANUP_PENDING)
    ProviderInstallPhase.SECRET_IMPORTED -> to in setOf(ProviderInstallPhase.METADATA_COMMITTED, ProviderInstallPhase.REJECTED, ProviderInstallPhase.CLEANUP_PENDING)
    ProviderInstallPhase.METADATA_COMMITTED -> to == ProviderInstallPhase.CLEANUP_PENDING
    ProviderInstallPhase.CLEANUP_PENDING -> to == ProviderInstallPhase.REJECTED
    ProviderInstallPhase.REJECTED -> to == ProviderInstallPhase.COMPLETED
    ProviderInstallPhase.COMPLETED -> false
}

private suspend fun PooledConnection.execute(sql: String, args: List<Any?>) = usePrepared(sql) { it.bindProvisioning(args); it.step(); Unit }
private suspend fun <T> PooledConnection.query(sql: String, args: List<Any?>, decode: (SQLiteStatement) -> T): List<T> = usePrepared(sql) { s ->
    s.bindProvisioning(args); buildList { while (s.step()) add(decode(s)) }
}
private fun SQLiteStatement.bindProvisioning(args: List<Any?>) = args.forEachIndexed { i, value ->
    when (value) { null -> bindNull(i + 1); is Long -> bindLong(i + 1, value); is String -> bindText(i + 1, value); else -> error("Invalid provisioning SQL argument") }
}
