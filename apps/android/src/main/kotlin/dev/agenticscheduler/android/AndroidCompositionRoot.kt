package dev.agenticscheduler.android

import android.content.Context
import dev.agenticscheduler.agent.tool.*
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.history.UndoService
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.TinkPairingHpke
import dev.agenticscheduler.agent.provider.OpenAiCompatibleProvider
import dev.agenticscheduler.agent.provider.SecretStoreProviderCredentialResolver
import dev.agenticscheduler.agent.runtime.AgentClock
import dev.agenticscheduler.agent.runtime.AgentRunService
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomAgentHistoryExportSource
import dev.agenticscheduler.application.sync.AgentConversationSyncSettings
import dev.agenticscheduler.application.sync.AgentHistoryExplicitExport
import dev.agenticscheduler.database.repository.RoomAgentSyncPersistence
import dev.agenticscheduler.database.repository.RoomAgentSyncTransportPersistence
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlin.time.Clock

/** Manual platform service construction; feature UI receives Application services, never DAOs. */
internal class AndroidCompositionRoot(context: Context) {
    val database by lazy { openAndroidDatabase(context) }
    val events by lazy { RoomEventRepository(database) }
    val tasks by lazy { RoomTaskRepository(database) }
    val transactionRunner by lazy { RoomApplicationTransactionRunner(database) }
    val ids by lazy { productionUuidV7Generator() }
    val secureStore by lazy { AndroidKeystoreSecureStore(context) }
    val academics by lazy { RoomAcademicRepository(database) }
    val profiles by lazy { RoomPlanningProfileRepository(database) }
    val agentState by lazy { RoomAgentStateRepository(database) }
    val localEnrollments by lazy { RoomLocalEnrollmentRepository(database) }
    val conversationSettings by lazy {
        val transport = RoomAgentSyncTransportPersistence(database)
        val history = RoomAgentSyncPersistence(database)
        val exporter = AgentHistoryExplicitExport(localEnrollments, transport, history, history,
            RoomAgentHistoryExportSource(database, agentState), ids, EpochMillisecondsClock { Clock.System.now().toEpochMilliseconds() })
        AgentConversationSyncSettings(localEnrollments, transport, transport, history, exporter)
    }
    val agentWriteGate by lazy { AndroidActiveEnrollmentAgentWriteGate(localEnrollments, agentState) }
    val mutations by lazy {
        MutationCoordinator(
            transactionRunner,
            RoomMutationJournalRepository(database),
            ids,
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
            agentWriteGate,
        )
    }
    val d8RuntimeLazy = lazy {
        RoomD8RuntimeComposition(
            database,
            secureStore,
            TinkPairingHpke(),
            ids,
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
            agentOutboundGate = agentWriteGate,
        )
    }
    val d8Runtime by d8RuntimeLazy
    val reads by lazy { ConflictAwareSourceFactReadService(events, tasks, profiles, academics, d8Runtime.sourceFacts) }
    val eventEditor by lazy { EventEditingService(events, ids, mutations, d8Runtime.writePolicy) }
    val taskEditor by lazy { TaskEditingService(tasks, ids, mutations, d8Runtime.writePolicy) }
    val dogfoodPlanner by lazy { DogfoodPlannerService(tasks, events, profiles, academics, ids, mutations = mutations, conflictWritePolicy = d8Runtime.writePolicy, sourceFacts = d8Runtime.sourceFacts) }
    val profileSettings by lazy { PlanningProfileSettingsService(profiles, ids, mutations, d8Runtime.writePolicy) }
    val agentHttpClientLazy = lazy { HttpClient(Android) }
    val agentHttpClient by agentHttpClientLazy
    val providerProbes = ProviderProbePresentation()
    val agentRun by lazy {
        val journal = RoomMutationJournalRepository(database)
        val history = HistoryQueryService(journal)
        val undo = UndoService(mutations, journal, events, tasks, profiles, d8Runtime.writePolicy)
        val provider = OpenAiCompatibleProvider(agentHttpClient, SecretStoreProviderCredentialResolver(secureStore))
        AgentRunService(
            state = agentState,
            provider = provider,
            capabilityProbe = { config -> provider.probe(config).also { providerProbes.record(config, it) } },
            taskGet = TaskGetTool(tasks),
            taskCreate = TaskCreateTool(taskEditor),
            ids = ids,
            clock = AgentClock { Clock.System.now().toEpochMilliseconds() },
            taskList = TaskListTool(tasks),
            historyReads = HistoryReadTools(history),
            taskUpdate = TaskUpdateTool(taskEditor),
            calendarList = CalendarListTool(reads),
            eventCreate = EventCreateTool(eventEditor),
            eventUpdate = EventUpdateTool(eventEditor),
            plannerFullReplan = PlannerPreviewFullReplanTool(DogfoodPlannerPreviewApplication(dogfoodPlanner)),
            plannerLocalReflow = PlannerPreviewLocalReflowTool(DogfoodPlannerPreviewApplication(dogfoodPlanner)),
            historyUndo = HistoryUndoTool(D7HistoryUndoApplication(undo, history)),
            planningProfileUpdate = PlanningProfileUpdateTool(profileSettings),
            plannerApplyBranch = PlannerApplyBranchTool(dogfoodPlanner),
        )
    }

}
