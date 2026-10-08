package dev.agenticscheduler.fixtures

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.presentation.AgentWorkspaceCoordinator
import dev.agenticscheduler.application.editing.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.application.planner.*
import dev.agenticscheduler.agent.permission.AgentSyncWriteGate
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.sync.AccountId
import kotlinx.coroutines.flow.first
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Actual Agent -> typed Tool -> Application -> Room. Only the HTTP peer is synthetic. */
class D10AgentFixtureGraph(db: AgenticSchedulerDatabase) {
    val product = D10ProductFixtureGraph(db)
    val base = product.base
    val mutations = MutationCoordinator(RoomApplicationTransactionRunner(db),base.journal,base.ids,MutationWallClock {1791244800000},
        AgentSyncWriteGate(AccountId("synthetic-local-account"),base.enrollments,base.agent))
    val taskEditor = TaskEditingService(base.tasks,base.ids,mutations,product.policy)
    val eventEditor = EventEditingService(base.events,base.ids,mutations,product.policy)
    val profileSettings = PlanningProfileSettingsService(base.profiles,base.ids,mutations,product.policy)
    val planner = DogfoodPlannerService(base.tasks,base.events,base.profiles,base.academics,base.ids,mutations=mutations,
        conflictWritePolicy=product.policy,sourceFacts=dev.agenticscheduler.application.history.NoActiveSyncSpaceSourceFactQuery)
    val undo = UndoService(mutations,base.journal,base.events,base.tasks,base.profiles,product.policy)
    var probeResult: ProviderProbeResult = ProviderProbeResult.Supported
    var observation: Pair<ProviderConfig, ProviderProbeResult>? = null
    var requests = 0
    var lastToolCount = -1
    var httpStatus = HttpStatusCode.OK
    var requestGate: CompletableDeferred<Unit>? = null
    var requestEntered: CompletableDeferred<Unit>? = null
    private val replies = ArrayDeque<ProviderChatMessage>()
    val bodies = mutableListOf<String>()
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    val client = HttpClient(MockEngine {
        requests++
        requestEntered?.complete(Unit); requestGate?.await()
        val body = it.body.toByteArray().decodeToString(); bodies += body
        lastToolCount = (Json.parseToJsonElement(body).jsonObject["tools"] as? JsonArray)?.size ?: 0
        val message = if (replies.isEmpty()) ProviderChatMessage("assistant", "The structured result records what actually happened. Prose alone changes nothing.") else replies.removeFirst()
        val wireMessage = message.copy(toolCalls = message.toolCalls?.map { call ->
            call.copy(function = call.function.copy(name = "d9_" + call.function.name.encodeToByteArray().joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }))
        })
        respond(buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("message", json.encodeToJsonElement(wireMessage)) }) }) }.toString(),
            httpStatus, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { null })
    fun runtime() = AgentRunService(base.agent, provider, TaskGetTool(base.tasks), TaskCreateTool(taskEditor), base.ids,
        AgentClock { 1791244800000 }, taskList = TaskListTool(base.tasks), historyReads = HistoryReadTools(product.queries),
        calendarList = CalendarListTool(base.reads), taskUpdate = TaskUpdateTool(taskEditor), eventCreate = EventCreateTool(eventEditor),
        eventUpdate = EventUpdateTool(eventEditor), plannerFullReplan = PlannerPreviewFullReplanTool(DogfoodPlannerPreviewApplication(planner)),
        plannerLocalReflow = PlannerPreviewLocalReflowTool(DogfoodPlannerPreviewApplication(planner)), historyUndo = HistoryUndoTool(D7HistoryUndoApplication(undo, product.queries)),
        planningProfileUpdate = PlanningProfileUpdateTool(profileSettings), plannerApplyBranch = PlannerApplyBranchTool(planner),
        capabilityProbe = { config -> probeResult.also { observation = config to it } })
    val run = runtime()
    fun workspace() = AgentWorkspaceCoordinator(base.agent, run, base.secrets)
    suspend fun seed() {
        product.seed()
        val config = base.agent.providerConfigs().single()
        base.agent.saveProviderConfig(config.copy(baseUrl = "https://synthetic.example/v1", model = "Synthetic structured Provider", maxContextUnits = 65536, reservedOutputUnits = 4096, toolCallingSupported = true))
    }
    fun propose(name: String, arguments: String) {
        replies.addLast(ProviderChatMessage("assistant", "Please review the structured action.", toolCalls = listOf(
            ProviderToolCall("synthetic-${requests + 1}", function = ProviderFunctionCall(name, arguments)))))
    }
    fun chat(text: String) { replies.addLast(ProviderChatMessage("assistant", text)) }
    fun createInput(title: String = "Synthetic proposed task") = """{"title":"$title","priority":"NORMAL","estimatedMinutes":30,"remainingMinutes":30,"deadline":null}"""
    suspend fun prepareScene(scene: String, c: AgentWorkspaceCoordinator) {
        c.refresh()
        when (scene) {
            "confirmation", "denied", "tool-result" -> {
                propose("task.create",createInput("Prepare the synthetic seminar notes"));c.editCommand("Propose a 30-minute task for seminar notes.");c.send()
                if(scene!="confirmation") c.confirm(c.pending!!.id,scene=="tool-result")
            }
            "stale" -> {
                val task=base.tasks.observeTasks().first().single()
                propose("task.update","""{"taskId":"${task.id.value}","title":"Agent proposed notes","status":"OPEN","priority":"NORMAL","estimatedMinutes":120,"completedMinutes":0,"remainingMinutes":120,"deadline":null}""")
                c.editCommand("Update the notes task.");c.send()
                check(base.taskEditor.update(UpdateTaskInput(task.id,"Newer explicit user edit",task.status,task.priority,task.effort.estimated,task.effort.completed,task.effort.remaining,null),expectedBefore=task) is EditingResult.Success)
                c.confirm(c.pending!!.id,true)
            }
            "chat-only" -> {probeResult=ProviderProbeResult.Unsupported;chat("I can discuss your question, but this Provider cannot perform structured actions.");c.editCommand("Can we discuss a study plan?");c.send();observation!!.let {c.observeProbe(it.first,it.second)}}
            "threads" -> {c.newConversation();c.showThreads()}
            "permissions", "provider-status" -> Unit
            else -> {chat("Your calendar and tasks remain authoritative. Ask me to read them with a structured Tool, or request a proposal that you can review before any write.");c.editCommand("Help me think about today's study session.");c.send()}
        }
    }
    fun close() { client.close(); base.client.close() }
}
