package dev.agenticscheduler.fixtures

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.id.UuidV7Generator
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.domain.planning.*
import dev.agenticscheduler.domain.task.TaskPriority
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.hours

/** Synthetic fixtures mount the real Application/Room/Agent path, without network or secrets. */
class D10FixtureGraph(val db: AgenticSchedulerDatabase, onProbe: (ProviderConfig, ProviderProbeResult) -> Unit = { _, _ -> }) {
    val date = LocalDate(2026, 10, 6)
    val zone = TimeZone.UTC
    private var idCounter = 100
    val ids = object : UuidV7Generator { override fun next() = "00000000-0000-7000-8000-${(idCounter++).toString().padStart(12, '0')}" }
    val events = RoomEventRepository(db)
    val tasks = RoomTaskRepository(db)
    val academics = RoomAcademicRepository(db)
    val profiles = RoomPlanningProfileRepository(db)
    val agent = RoomAgentStateRepository(db)
    val enrollments = RoomLocalEnrollmentRepository(db)
    val journal = RoomMutationJournalRepository(db)
    val mutations = MutationCoordinator(RoomApplicationTransactionRunner(db), journal, ids, MutationWallClock { 1791244800000 })
    val reads = ConflictAwareSourceFactReadService(events, tasks, profiles, academics, NoActiveSyncSpaceSourceFactQuery)
    val eventEditor = EventEditingService(events, ids, mutations, NoActiveSyncSpaceWritePolicy)
    val taskEditor = TaskEditingService(tasks, ids, mutations, NoActiveSyncSpaceWritePolicy)
    val planner = DogfoodPlannerService(tasks, events, profiles, academics, ids, mutations = mutations,
        conflictWritePolicy = NoActiveSyncSpaceWritePolicy, sourceFacts = NoActiveSyncSpaceSourceFactQuery)
    val profileSettings = PlanningProfileSettingsService(profiles, ids, mutations, NoActiveSyncSpaceWritePolicy)
    val transport = RoomAgentSyncTransportPersistence(db)
    val history = RoomAgentSyncPersistence(db)
    val conversationSettings = AgentConversationSyncSettings(enrollments, transport, transport, history)
    val secrets = object : PlatformSecretStore {
        override suspend fun importSecret(material: PlatformSecretMaterial): SecretReference = error("Fixture imports no secrets")
        override suspend fun readSecret(reference: SecretReference): PlatformSecretMaterial? = null
        override suspend fun delete(reference: SecretReference) = Unit
    }
    var providerRequests = 0
    var sentToolSchemas = -1
    val client = HttpClient(MockEngine {
        providerRequests++
        val body = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
        sentToolSchemas = (body["tools"] as? JsonArray)?.size ?: 0
        respond("""{"choices":[{"message":{"role":"assistant","content":"No change was made. Prose is not a mutation."}}]}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
    val run = AgentRunService(agent, provider,
        TaskGetTool(tasks), TaskCreateTool(taskEditor), ids, AgentClock { 1791244800000 },
        taskList = TaskListTool(tasks), calendarList = CalendarListTool(reads),
        eventCreate = EventCreateTool(eventEditor), taskUpdate = TaskUpdateTool(taskEditor),
        capabilityProbe = { config -> provider.probe(config).also { onProbe(config, it) } })
    val threadId = AgentThreadId("00000000-0000-7000-8000-000000000001")

    suspend fun seed() {
        check(eventEditor.create(CreateEventInput("Design review", EventTimeInput.Zoned(LocalDateTime(2026,10,6,9,30), LocalDateTime(2026,10,6,10,30), zone), Flexibility.HARD, PinState.UNPINNED)) is EditingResult.Success)
        check(eventEditor.create(CreateEventInput("Campus day", EventTimeInput.AllDay(date, date.plus(1, DateTimeUnit.DAY)), Flexibility.HARD, PinState.UNPINNED)) is EditingResult.Success)
        check(eventEditor.create(CreateEventInput("Evening reading", EventTimeInput.Floating(LocalDateTime(2026,10,6,18,0), LocalDateTime(2026,10,6,19,0)), Flexibility.SOFT, PinState.UNPINNED)) is EditingResult.Success)
        check(taskEditor.create(CreateTaskInput("Prepare research notes", TaskPriority.NORMAL, 2.hours, 2.hours, null)) is EditingResult.Success)
        val config = ProviderConfig(ProviderConfigId("00000000-0000-7000-8000-000000000002"), "http://localhost", "Synthetic chat", 4096, 512, false, false, null)
        agent.saveProviderConfig(config); agent.selectProviderConfig(config.id)
        agent.saveThread(AgentThread(threadId, "Schedule questions", 1791244800000))
        agent.appendMessage(AgentMessage(AgentMessageId("00000000-0000-7000-8000-000000000003"), threadId, 0, AgentMessageRole.USER, "What is on my schedule?", 1791244800000))
        agent.appendMessage(AgentMessage(AgentMessageId("00000000-0000-7000-8000-000000000004"), threadId, 1, AgentMessageRole.ASSISTANT, "Use a structured Calendar read to inspect authoritative facts. Nothing has been changed.", 1791244800000))
    }
}
