package dev.agenticscheduler.database

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.AgentPermissionMode
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.sync.MutationId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProviderCredentialMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(Path.of("schemas"), Files.createTempFile("provider-v15-v16-", ".db"),
        BundledSQLiteDriver(), AgenticSchedulerDatabase::class, { AgenticSchedulerDatabaseConstructor.initialize() })
    private fun id(i: Int) = "00000000-0000-7000-8000-${i.toString().padStart(12, '0')}"

    @Test fun `populated v15 to v16 preserves D9 and D9-02 bytes and fresh schema parity`() = runBlocking {
        val old = migrations.createDatabase(15)
        AgentSchema.create(old); AgentSyncSchema.create(old); AgentSyncTransportSchema.create(old); AgentHistoryProvenanceSchema.create(old)
        val thread = AgentThread(AgentThreadId(id(1)), "migration thread", 10)
        val message = AgentMessage(AgentMessageId(id(2)), thread.id, 0, AgentMessageRole.USER, "kept raw content", 11)
        val call = AgentToolCall(AgentToolCallId(id(3)), thread.id, message.id, 0, "task.create", "{\"title\":\"task\"}", AgentToolCallState.WAITING_CONFIRMATION, "pending preview")
        val result = AgentToolResult(AgentToolResultId(id(4)), thread.id, call.id, 0, AgentToolResultStatus.SUCCESS, "{\"ok\":true}", listOf(MutationId(id(7))))
        val action = AgentAction(AgentActionId(id(5)), thread.id, message.id, ProviderConfigId(id(8)), "chosen-model", listOf(call.id), listOf(result.id),
            AgentPermissionMode.REQUIRE_CONFIRMATION, call.id.value, null, result.mutationIds, AgentActionStatus.WAITING_CONFIRMATION)
        val summary = ContextSummary(ContextSummaryId(id(6)), thread.id, message.id, message.id, 1, "local summary", 12, null, null)
        old.exec("INSERT INTO agent_thread VALUES (?, ?, ?)", id(1), 10L, Json.encodeToString(AgentThread.serializer(), thread))
        old.exec("INSERT INTO agent_message VALUES (?, ?, ?, ?)", id(2), id(1), 0L, Json.encodeToString(AgentMessage.serializer(), message))
        old.exec("INSERT INTO agent_tool_call VALUES (?, ?, ?, ?)", id(3), id(1), 0L, Json.encodeToString(AgentToolCall.serializer(), call))
        old.exec("INSERT INTO agent_tool_result VALUES (?, ?, ?, ?, ?)", id(4), id(1), id(3), 0L, Json.encodeToString(AgentToolResult.serializer(), result))
        old.exec("INSERT INTO agent_action VALUES (?, ?, ?)", id(5), id(1), Json.encodeToString(AgentAction.serializer(), action))
        old.exec("INSERT INTO context_summary VALUES (?, ?, ?, ?)", id(6), id(1), 12L, Json.encodeToString(ContextSummary.serializer(), summary))
        old.exec("INSERT INTO agent_permission_policy VALUES ('LOW_RISK_CREATE', 'REQUIRE_CONFIRMATION')")
        old.exec("INSERT INTO provider_config VALUES (?, 1, ?, ?)", id(8), "secure://provider/legacy", "{\"baseUrl\":\"https://provider.example/v1\",\"model\":\"chosen-model\",\"maxContextUnits\":8192,\"reservedOutputUnits\":1024,\"streamingSupported\":true,\"toolCallingSupported\":true}")
        old.exec("INSERT INTO agent_local_thread_provenance VALUES (?, 'TRACKED', 'migration thread', 10)", id(1))
        old.exec("INSERT INTO agent_local_turn_provenance(thread_id, turn_id, parent_turn_ids_json, ancestry_verified, lifecycle) VALUES (?, ?, '[]', 1, 'AWAITING_CONFIRMATION')", id(1), id(10))
        old.exec("INSERT INTO agent_local_turn_member VALUES (?, ?, 0, 'MESSAGE', ?, 1, ?)", id(1), id(10), id(2), Json.encodeToString(AgentMessage.serializer(), message))
        old.exec("INSERT INTO agent_sync_space_state VALUES ('space', ?, 8, 1)", id(12))
        old.exec("INSERT INTO agent_sync_dvv_frontier VALUES ('space', ?, 8)", id(12))
        old.exec("INSERT INTO agent_sync_backfill_state VALUES ('space', 42, 'INCOMPLETE', 12, 100)")
        old.exec("INSERT INTO sync_space_cursor VALUES ('space', 99)")
        old.exec("INSERT INTO local_pairing_enrollment VALUES ('account', 'WearTarget:opaque-A_01', 'request', ?, 'secure://hpke', 'ACTIVE', 'space', 'secure://amk', 'secure://credential')", "A".repeat(43))
        val queries = listOf(
            "SELECT payload_json FROM agent_thread", "SELECT payload_json FROM agent_message", "SELECT payload_json FROM agent_tool_call", "SELECT payload_json FROM agent_tool_result",
            "SELECT payload_json FROM agent_action", "SELECT payload_json FROM context_summary", "SELECT mode FROM agent_permission_policy",
            "SELECT payload_json || credential_secret_ref || selected FROM provider_config", "SELECT snapshot_json FROM agent_local_turn_member",
            "SELECT lifecycle || turn_id FROM agent_local_turn_provenance", "SELECT local_counter FROM agent_sync_space_state",
            "SELECT counter FROM agent_sync_dvv_frontier", "SELECT cursor || ':' || earliest_quarantined_cursor FROM agent_sync_backfill_state", "SELECT server_cursor FROM sync_space_cursor",
        )
        val before = queries.associateWith { old.text(it) }
        old.close()
        val migrated = migrations.runMigrationsAndValidate(16, listOf(ProviderCredentialMigration15To16))
        try {
            assertEquals(before, queries.associateWith { migrated.text(it) })
            ProviderCredentialProvisioningSchema.validate(migrated)
            assertEquals("[]", migrated.text("SELECT config_ids_json FROM provider_credential_target_identity"))
            listOf("provider_credential_revision", "provider_credential_install_journal", "provider_credential_delivery_outbox").forEach { assertEquals("0", migrated.text("SELECT count(*) FROM $it")) }
            val expected = catalog(migrated)
            val fresh = openInMemoryDesktopDatabase()
            try {
                val actual = fresh.useReaderConnection { c -> c.usePrepared("SELECT name, sql FROM sqlite_master WHERE name LIKE 'provider_credential_%' AND sql IS NOT NULL ORDER BY name") { s ->
                    buildList { while (s.step()) add(s.getText(0) + ":" + normalize(s.getText(1))) }
                } }
                assertEquals(expected, actual)
            } finally { fresh.close() }
        } finally { migrated.close() }
    }
    @Test fun `catalog validator rejects altered uniqueness or missing index`() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            ProviderCredentialProvisioningSchema.create(connection); ProviderCredentialProvisioningSchema.validate(connection)
            connection.exec("DROP INDEX provider_credential_journal_target_idx")
            assertFailsWith<IllegalStateException> { ProviderCredentialProvisioningSchema.validate(connection) }
        } finally { connection.close() }
    }
    private fun catalog(c: SQLiteConnection) = c.prepare("SELECT name, sql FROM sqlite_master WHERE name LIKE 'provider_credential_%' AND sql IS NOT NULL ORDER BY name").use { s -> buildList { while (s.step()) add(s.getText(0) + ":" + normalize(s.getText(1))) } }
    private fun normalize(s: String) = s.replace(Regex("\\bIF\\s+NOT\\s+EXISTS\\b", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim().lowercase()
    private fun SQLiteConnection.text(sql: String) = prepare(sql).use { s -> check(s.step()); s.getText(0) }
    private fun SQLiteConnection.exec(sql: String, vararg args: Any) = prepare(sql).use { s -> args.forEachIndexed { i, v -> when (v) { is String -> s.bindText(i + 1, v); is Long -> s.bindLong(i + 1, v); else -> error("bad test argument") } }; s.step(); Unit }
}
