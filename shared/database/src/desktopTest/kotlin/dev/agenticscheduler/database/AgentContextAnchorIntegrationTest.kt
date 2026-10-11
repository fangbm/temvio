package dev.agenticscheduler.database

import androidx.room3.useReaderConnection
import dev.agenticscheduler.agent.context.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.ZERO

class AgentContextAnchorIntegrationTest {
    private val marker = id(900)
    private val anchor = AgentContextAnchor.Course(CourseId(marker))
    private val context = AgentTurnContext(anchor)
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @Test fun `two argument run preserves command transcript and provider request without anchor`() = runBlocking {
        Fixture().use { f ->
            val thread = f.runtime().createThread()
            assertIs<AgentRunResult.Completed>(f.runtime().run(thread, "Original command"))
            assertEquals(listOf("system", "user"), messages(f.requests.single()).map { it["role"]!!.jsonPrimitive.content })
            assertEquals("Original command", messages(f.requests.single()).last()["content"]!!.jsonPrimitive.content)
            assertEquals("Original command", f.state.messages(thread).first().content)
            assertFalse(f.requests.single().toString().contains("LOCAL_UI_REFERENT"))
        }
    }

    @Test fun `explicit anchor reaches real HTTP input as separate tagged context and not USER persistence`() = runBlocking {
        Fixture().use { f ->
            val thread = f.runtime().createThread()
            assertIs<AgentRunResult.Completed>(f.runtime().run(thread, "Discuss this", context))
            val input = messages(f.requests.single())
            assertEquals("Discuss this", input[1]["content"]!!.jsonPrimitive.content)
            assertEquals(anchor.renderForModel(), input[2]["content"]!!.jsonPrimitive.content)
            assertEquals(1, input.count { it["content"]?.jsonPrimitive?.content == anchor.renderForModel() })
            assertEquals("Discuss this", f.state.messages(thread).first { it.role == AgentMessageRole.USER }.content)
            assertNoStoredMarker(f.db)
            assertTrue(f.history.timeline().isEmpty())
        }
    }

    @Test fun `anchored read resolves typed task then preserves exact required Tool continuation`() = runBlocking {
        var step = 0
        Fixture(response = { body ->
            if (step++ == 0) {
                assertTrue(body.toString().contains("LOCAL_UI_REFERENT"))
                tool(AgentToolNames.TASK_GET, """{"taskId":"${id(50)}"}""")
            } else {
                val pair = messages(body).filter { it["role"]!!.jsonPrimitive.content in listOf("assistant", "tool") }
                assertEquals(2, pair.size)
                assertEquals("read-1", pair.last()["tool_call_id"]!!.jsonPrimitive.content)
                assertTrue(pair.last()["content"]!!.jsonPrimitive.content.contains("Authoritative task"))
                assertEquals(1, messages(body).count { it["content"]?.jsonPrimitive?.content == AgentContextAnchor.Task(TaskId(id(50))).renderForModel() })
                ProviderChatMessage("assistant", "Read completed")
            }
        }).use { f ->
            f.tasks.upsertTask(Task(TaskId(id(50)), "Authoritative task", TaskStatus.OPEN, TaskPriority.NORMAL, TaskEffort(null, ZERO, null), null))
            val thread = f.runtime().createThread()
            assertIs<AgentRunResult.Completed>(f.runtime().run(thread, "Read this task", AgentTurnContext(AgentContextAnchor.Task(TaskId(id(50))))))
            assertEquals(2, step)
            assertEquals(AgentToolResultStatus.SUCCESS, f.state.toolResults(thread).single().status)
            assertTrue(f.history.timeline().isEmpty())
            // IDs legitimately used by typed Tools remain history; the local context container does not.
            assertFalse(f.state.localHistoryTurns(thread).joinToString { it.members.toString() }.contains("LOCAL_UI_REFERENT"))
        }
    }

    @Test fun `provider failure never persists local anchor or overwrites explicit command`() = runBlocking {
        Fixture(status = HttpStatusCode.Unauthorized).use { f ->
            val thread = f.runtime().createThread()
            assertEquals(AgentRunResult.Failed("HTTP_401"), f.runtime().run(thread, "Failure command", context))
            assertTrue(marker in f.requests.single().toString())
            assertEquals("Failure command", f.state.messages(thread).single().content)
            assertNoStoredMarker(f.db)
        }
    }

    @Test fun `impossible mandatory anchor fails before actual capability probe or Provider IO`() = runBlocking {
        Fixture().use { f ->
            f.state.saveProviderConfig(f.config.copy(maxContextUnits = 300, reservedOutputUnits = 60))
            f.state.selectProviderConfig(f.config.id)
            val runtime = f.runtime(actualProbe = true)
            val thread = runtime.createThread()
            assertEquals(AgentRunResult.Failed("CONTEXT_TOO_LARGE"), runtime.run(thread, "Discuss", context))
            assertEquals(0, f.probes)
            assertTrue(f.requests.isEmpty())
            assertEquals("Discuss", f.state.messages(thread).single().content)
            assertNoStoredMarker(f.db)
        }
    }

    @Test fun `anchor A is not carried into turn B with another anchor or later no anchor turn`() = runBlocking {
        Fixture().use { f ->
            val runtime = f.runtime(); val thread = runtime.createThread()
            runtime.run(thread, "A", context)
            val b = AgentContextAnchor.PlanningProfile(PlanningProfileId(id(901)))
            runtime.run(thread, "B", AgentTurnContext(b))
            assertFalse(marker in f.requests.last().toString())
            assertTrue(b.renderForModel() in messages(f.requests.last()).mapNotNull { it["content"]?.jsonPrimitive?.content })
            runtime.run(thread, "C")
            assertFalse("LOCAL_UI_REFERENT" in f.requests.last().toString())
            assertNoStoredMarker(f.db)
        }
    }

    @Test fun `two independent concurrent threads never share local anchors`() = runBlocking {
        Fixture().use { f ->
            val runtime = f.runtime(); val a = runtime.createThread(); val b = runtime.createThread()
            val other = AgentContextAnchor.Course(CourseId(id(901)))
            val first = async { runtime.run(a, "Thread A", context) }
            val second = async { runtime.run(b, "Thread B", AgentTurnContext(other)) }
            assertIs<AgentRunResult.Completed>(first.await()); assertIs<AgentRunResult.Completed>(second.await())
            f.requests.forEach { body ->
                val input = messages(body)
                val isA = input[1]["content"]!!.jsonPrimitive.content == "Thread A"
                assertEquals(if (isA) anchor.renderForModel() else other.renderForModel(), input[2]["content"]!!.jsonPrimitive.content)
                assertFalse(if (isA) id(901) in body.toString() else marker in body.toString())
            }
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun `Provider switch keeps thread continuity without owning the previous UI anchor`() = runBlocking {
        Fixture().use { f ->
            val runtime = f.runtime(); val thread = runtime.createThread()
            runtime.run(thread, "A", context)
            val changed = f.config.copy(id = ProviderConfigId(id(801)), model = "another-model")
            f.state.saveProviderConfig(changed); f.state.selectProviderConfig(changed.id)
            runtime.run(thread, "B")
            assertEquals("another-model", f.requests.last()["model"]!!.jsonPrimitive.content)
            assertEquals(1, f.state.threads().size)
            assertEquals(listOf("A", "B"), f.state.messages(thread).filter { it.role == AgentMessageRole.USER }.map { it.content })
            assertFalse(marker in f.requests.last().toString())
        }
    }

    @Test fun `chat only remains zero schemas and prose cannot execute mutations with an anchor`() = runBlocking {
        Fixture().use { f ->
            f.probeResult = ProviderProbeResult.Unsupported
            val runtime = f.runtime(); val thread = runtime.createThread()
            assertIs<AgentRunResult.Completed>(runtime.run(thread, "Create something", context))
            assertNull(f.requests.single()["tools"])
            assertTrue(f.state.toolCalls(thread).isEmpty())
            assertTrue(f.state.actions(thread).isEmpty())
            assertTrue(f.history.timeline().isEmpty())
            assertNoStoredMarker(f.db)
        }
    }

    @Test fun `compaction only sees persisted conversation not the transient anchor`() = runBlocking {
        Fixture(response = { body ->
            if (messages(body).first()["content"]!!.jsonPrimitive.content.startsWith("Summarize earlier")) {
                assertFalse(marker in body.toString())
                ProviderChatMessage("assistant", "Synthetic non-authoritative summary")
            } else ProviderChatMessage("assistant", "Done")
        }).use { f ->
            val runtime = f.runtime(); val thread = runtime.createThread()
            repeat(40) { ordinal ->
                f.state.appendMessage(AgentMessage(AgentMessageId(f.ids.next()), thread, ordinal.toLong(),
                    if (ordinal % 2 == 0) AgentMessageRole.USER else AgentMessageRole.ASSISTANT, "Old synthetic text ".repeat(20), 1))
            }
            assertIs<AgentRunResult.Completed>(runtime.run(thread, "Now", context))
            assertTrue(f.state.summaries(thread).isNotEmpty())
            assertEquals(2, f.requests.size)
            assertTrue(marker in f.requests.last().toString())
            assertNoStoredMarker(f.db)
        }
    }

    @Test fun `pending confirmation survives database restart without recovering anchor and commits exact call`() = runBlocking {
        val path = Files.createTempFile("d1004f-pending-", ".db")
        val ids = SequenceIds()
        try {
            var thread: AgentThreadId? = null
            lateinit var pending: AgentRunResult.AwaitingConfirmation
            Fixture(openDesktopDatabase(path.toString()), ids, response = { tool(AgentToolNames.TASK_CREATE, """{"title":"Pending task","priority":"NORMAL","estimatedMinutes":30,"remainingMinutes":30,"deadline":null}""") }).use { f ->
                val runtime = f.runtime(); thread = runtime.createThread()
                pending = assertIs(runtime.run(requireNotNull(thread), "Create task", context))
                assertNoStoredMarker(f.db)
                assertEquals(AgentToolCallState.WAITING_CONFIRMATION, f.state.toolCalls(requireNotNull(thread)).single().state)
            }
            Fixture(openDesktopDatabase(path.toString()), ids).use { f ->
                val runtime = f.runtime()
                assertIs<AgentRunResult.Completed>(runtime.confirm(requireNotNull(thread), pending.callId, true))
                assertFalse("LOCAL_UI_REFERENT" in f.requests.single().toString())
                assertFalse(marker in f.requests.single().toString())
                assertEquals(AgentToolResultStatus.SUCCESS, f.state.toolResults(requireNotNull(thread)).single().status)
                assertEquals(1, f.history.timeline().size)
                assertEquals(1, f.tasks.observeTasks().first().size)
                assertNoStoredMarker(f.db)
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `real historical export and restart retain no local referent in V3 mappings or outbox`() = runBlocking {
        val path = Files.createTempFile("d1004f-export-", ".db")
        val ids = SequenceIds(); val space = SyncSpaceId("anchor-export")
        try {
            var thread: AgentThreadId? = null
            Fixture(openDesktopDatabase(path.toString()), ids).use { f ->
                val runtime = f.runtime(); thread = runtime.createThread()
                runtime.run(requireNotNull(thread), "Ordinary command", context)
                val sync = RoomAgentSyncPersistence(f.db)
                val transport = RoomAgentSyncTransportPersistence(f.db)
                sync.provisionLocalReplica(space, AgentReplicaId(id(950)))
                transport.setConversationConsent(space, true, true)
                val exporter = AgentHistoryExplicitExport(Enrollment(space), transport, sync, sync,
                    RoomAgentHistoryExportSource(f.db, f.state), ids, EpochMillisecondsClock { 1000 })
                assertEquals(1, exporter.availability(space).eligibleTurns)
                assertTrue(exporter.exportFromUser(space).newlyQueuedFacts > 0)
                val mappings = sync.historicalExportMappings(space)
                assertTrue(mappings.isNotEmpty())
                val peerDb = openInMemoryDesktopDatabase()
                try {
                    val peer = RoomAgentSyncPersistence(peerDb)
                    peer.provisionLocalReplica(space, AgentReplicaId(id(951)))
                    val peerState = RoomAgentSyncTransportPersistence(peerDb)
                    peerState.setConversationConsent(space, true, true)
                    val transactions = RoomApplicationTransactionRunner(peerDb)
                    val history = RoomMutationJournalRepository(peerDb)
                    val receive = RoomSyncReceiveRepository(peerDb)
                    val clock = MutationWallClock { 1000 }
                    val engine = SyncEngine(transactions, history, history, receive, RoomEventRepository(peerDb),
                        RoomTaskRepository(peerDb), RoomPlanningProfileRepository(peerDb), RoomAcademicRepository(peerDb), SequenceIds(), clock)
                    val integration = AgentHistoryReceiveIntegration(peer, peerState, transactions, receive, history, clock)
                    mappings.forEachIndexed { index, mapping ->
                        val payload = SyncPayloadV3(operation = sync.operation(space, mapping.operationId.value)!!)
                        val encoded = AgentSyncWireCodec.encodePayload(payload)
                        assertFalse(marker in encoded)
                        assertFalse("LOCAL_UI_REFERENT" in encoded)
                        assertFalse("contextAnchor" in encoded)
                        val received = assertIs<AgentPayloadDecodeResult.Supported>(
                            AgentSyncWireCodec.decodePayload(encoded, payload.operation.operationId))
                        assertEquals(payload, received.payload)
                        assertIs<EncryptedSyncReceiveResult.AgentHandled>(integration.receive(
                            DecryptedPayloadReceipt(space, payload.operation.operationId.value, index.toLong() + 1, encoded), engine))
                    }
                    val projection = peer.threadHistoryProjection(space, AgentThreadSyncId(requireNotNull(thread).value))
                    assertEquals(1, projection.turns.size)
                    assertEquals(listOf("Ordinary command", "Done"), projection.turns.single().members.mapNotNull {
                        (it.agentEvent as? MessageAppended)?.content
                    })
                    assertNoStoredMarker(peerDb)
                } finally { peerDb.close() }
                assertNoStoredMarker(f.db) // includes raw provenance, summaries, D7 journal, V3 mappings/outbox and ProviderConfig
            }
            Fixture(openDesktopDatabase(path.toString()), ids).use { f ->
                assertNoStoredMarker(f.db)
                assertTrue(RoomAgentSyncPersistence(f.db).historicalExportMappings(space).isNotEmpty())
                assertIs<AgentRunResult.Completed>(f.runtime().run(requireNotNull(thread), "After restart"))
                assertFalse(marker in f.requests.single().toString())
                assertFalse("LOCAL_UI_REFERENT" in f.requests.single().toString())
            }
        } finally { Files.deleteIfExists(path) }
    }

    private fun messages(body: JsonObject) = body["messages"]!!.jsonArray.map { it.jsonObject }
    private fun tool(name: String, arguments: String): ProviderChatMessage {
        // Exercise the actual adapter's existing external-name boundary, not canonical names on HTTP.
        val wireName = "d9_" + name.encodeToByteArray().joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${"0123456789abcdef"[value ushr 4]}${"0123456789abcdef"[value and 0xf]}"
        }
        return ProviderChatMessage("assistant", toolCalls = listOf(ProviderToolCall("read-1", function = ProviderFunctionCall(wireName, arguments))))
    }

    private suspend fun assertNoStoredMarker(db: AgenticSchedulerDatabase) {
        db.useReaderConnection { connection ->
            val tables = connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") { s ->
                buildList { while (s.step()) add(s.getText(0)) }
            }
            fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
            for (table in tables) {
                val columns = connection.usePrepared("PRAGMA table_info(${quote(table)})") { s ->
                    buildList { while (s.step()) if (s.getText(2).uppercase().contains("TEXT")) add(s.getText(1)) }
                }
                if (columns.isEmpty()) continue
                connection.usePrepared("SELECT ${columns.joinToString(",", transform = ::quote)} FROM ${quote(table)}") { s ->
                    while (s.step()) columns.forEachIndexed { index, name -> if (!s.isNull(index)) {
                        assertFalse(marker in s.getText(index), "Local referent leaked into $table.$name")
                        assertFalse("LOCAL_UI_REFERENT" in s.getText(index), "Context container leaked into $table.$name")
                    } }
                }
            }
        }
    }

    private inner class Fixture(
        val db: AgenticSchedulerDatabase = openInMemoryDesktopDatabase(),
        val ids: UuidV7Generator = SequenceIds(),
        status: HttpStatusCode = HttpStatusCode.OK,
        response: (JsonObject) -> ProviderChatMessage = { ProviderChatMessage("assistant", "Done") },
    ) : AutoCloseable {
        val state = RoomAgentStateRepository(db)
        val tasks = RoomTaskRepository(db)
        val history = RoomMutationJournalRepository(db)
        val requests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        var probes = 0
        var probeResult: ProviderProbeResult = ProviderProbeResult.Supported
        val config = ProviderConfig(ProviderConfigId(id(800)), "https://synthetic.invalid/v1", "fixture-model", 8192, 2048, false, true, null)
        private val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            requests += body
            val reply = if (status == HttpStatusCode.OK) buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", json.encodeToJsonElement(response(body))) }) })
            }.toString() else "Private failure body must remain redacted"
            respond(reply, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } } }
        private val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
        suspend fun runtime(actualProbe: Boolean = false): AgentRunService {
            if (state.providerConfig(config.id) == null) { state.saveProviderConfig(config); state.selectProviderConfig(config.id) }
            val coordinator = MutationCoordinator(RoomApplicationTransactionRunner(db), history, ids, MutationWallClock { 5 }, AgentOriginWriteGate { true })
            return AgentRunService(state, provider, TaskGetTool(tasks), TaskCreateTool(TaskEditingService(tasks, ids, coordinator, NoActiveSyncSpaceWritePolicy)),
                ids, AgentClock { 6 }, capabilityProbe = { probes++; if (actualProbe) provider.probe(it) else probeResult })
        }
        override fun close() { client.close(); db.close() }
    }

    private class SequenceIds : UuidV7Generator { private val n = AtomicInteger(100); override fun next() = id(n.getAndIncrement()) }
    private class Enrollment(space: SyncSpaceId) : LocalEnrollmentRepository {
        private val active = LocalEnrollmentState.Active(AccountId("fixture"), DeviceId("local"), EnrollmentRequestId("request"),
            HpkePublicKeyBase64Url("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), SecretReference("secure://fixture-hpke"), space,
            SecretReference("secure://fixture-master"), SecretReference("secure://fixture-credential"))
        override suspend fun state(accountId: AccountId) = active.takeIf { it.accountId == accountId }
        override suspend fun states(): List<LocalEnrollmentState> = listOf(active)
        override suspend fun savePending(value: LocalEnrollmentState.Pending) = Unit
        override suspend fun saveActive(value: LocalEnrollmentState.Active) = Unit
    }
    private companion object { fun id(value: Int) = "00000000-0000-7000-8000-${value.toString().padStart(12, '0')}" }
}
