package dev.agenticscheduler.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Room v16 non-secret extension. No secret or secret fingerprint column, no workspace coupling. */
internal object ProviderCredentialProvisioningSchema {
    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS provider_credential_target_identity (target_device_id TEXT NOT NULL PRIMARY KEY, config_ids_json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS provider_credential_revision (target_device_id TEXT NOT NULL, provider_config_id TEXT NOT NULL, state_json TEXT NOT NULL, PRIMARY KEY(target_device_id, provider_config_id), FOREIGN KEY(target_device_id) REFERENCES provider_credential_target_identity(target_device_id) ON DELETE RESTRICT)",
        "CREATE TABLE IF NOT EXISTS provider_credential_install_journal (install_identity TEXT NOT NULL PRIMARY KEY, target_device_id TEXT NOT NULL, provider_config_id TEXT NOT NULL, prepared_ref TEXT NOT NULL UNIQUE, journal_json TEXT NOT NULL, FOREIGN KEY(target_device_id, provider_config_id) REFERENCES provider_credential_revision(target_device_id, provider_config_id) ON DELETE RESTRICT)",
        "CREATE INDEX IF NOT EXISTS provider_credential_journal_target_idx ON provider_credential_install_journal(target_device_id, provider_config_id)",
        "CREATE TABLE IF NOT EXISTS provider_credential_delivery_outbox (source_device_id TEXT NOT NULL, target_device_id TEXT NOT NULL, provider_config_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0), canonical_envelope_json TEXT NOT NULL, envelope_digest TEXT NOT NULL, delivery_state TEXT NOT NULL CHECK(delivery_state IN ('PREPARED','UPLOADED','ACKNOWLEDGED','DELIVERY_EXPIRED')), expires_at_epoch_seconds INTEGER NOT NULL CHECK(expires_at_epoch_seconds > 0), PRIMARY KEY(source_device_id, target_device_id, provider_config_id, revision))",
    )
    fun create(connection: SQLiteConnection) = statements.forEach { connection.prepare(it).use { s -> s.step() } }
    fun validate(connection: SQLiteConnection) {
        fun name(sql: String) = sql.substringAfter("EXISTS ").substringBefore(" (").substringBefore(' ')
        fun normalize(sql: String) = sql.replace(Regex("\\bIF\\s+NOT\\s+EXISTS\\b", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim().lowercase()
        val expected = statements.associate { name(it) to normalize(it) }
        val actual = connection.prepare("SELECT name, sql FROM sqlite_master WHERE type IN ('table','index') AND name LIKE 'provider_credential_%' AND sql IS NOT NULL").use { s ->
            buildMap { while (s.step()) put(s.getText(0), normalize(s.getText(1))) }
        }
        check(expected == actual) { "Provider credential schema incompatible" }
    }
    /** Called only for a genuinely new enrollment, or once by pre-provisioning v15 migration. */
    fun initializeExistingV15Enrollments(connection: SQLiteConnection) {
        connection.prepare("INSERT INTO provider_credential_target_identity(target_device_id, config_ids_json) SELECT device_id, '[]' FROM local_pairing_enrollment WHERE 1 ON CONFLICT(target_device_id) DO NOTHING").use { it.step() }
    }
}

internal object ProviderCredentialMigration15To16 : Migration(15, 16) {
    override suspend fun migrate(connection: SQLiteConnection) {
        ProviderCredentialProvisioningSchema.create(connection)
        ProviderCredentialProvisioningSchema.initializeExistingV15Enrollments(connection)
    }
}
