package dev.agenticscheduler.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import dev.agenticscheduler.ui.TemvioTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.platform.LocalWindowInfo
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.history.MutationCoordinator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.database.openDesktopDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomApplicationTransactionRunner
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomMutationJournalRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCreation
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.DesktopPlatformSecureStore
import dev.agenticscheduler.application.sync.TinkPairingHpke
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.application.history.HistoryQueryService
import dev.agenticscheduler.application.history.UndoService
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.permission.AgentSyncWriteGate
import dev.agenticscheduler.agent.provider.OpenAiCompatibleProvider
import dev.agenticscheduler.agent.provider.SecretStoreProviderCredentialResolver
import dev.agenticscheduler.agent.runtime.AgentClock
import dev.agenticscheduler.agent.runtime.AgentRunService
import dev.agenticscheduler.agent.tool.CalendarListTool
import dev.agenticscheduler.agent.tool.D7HistoryUndoApplication
import dev.agenticscheduler.agent.tool.EventCreateTool
import dev.agenticscheduler.agent.tool.EventUpdateTool
import dev.agenticscheduler.agent.tool.HistoryReadTools
import dev.agenticscheduler.agent.tool.HistoryUndoTool
import dev.agenticscheduler.agent.tool.PlannerApplyBranchTool
import dev.agenticscheduler.agent.tool.PlannerPreviewFullReplanTool
import dev.agenticscheduler.agent.tool.PlannerPreviewLocalReflowTool
import dev.agenticscheduler.agent.tool.PlanningProfileUpdateTool
import dev.agenticscheduler.agent.tool.TaskCreateTool
import dev.agenticscheduler.agent.tool.TaskGetTool
import dev.agenticscheduler.agent.tool.TaskListTool
import dev.agenticscheduler.agent.tool.TaskUpdateTool
import dev.agenticscheduler.agent.tool.DogfoodPlannerPreviewApplication
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomAgentHistoryExportSource
import dev.agenticscheduler.application.sync.AgentConversationSyncSettings
import dev.agenticscheduler.application.sync.AgentHistoryExplicitExport
import dev.agenticscheduler.database.repository.RoomAgentSyncPersistence
import dev.agenticscheduler.database.repository.RoomAgentSyncTransportPersistence
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.application.sync.AgentOutboundCompatibilityGate
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.sync.AccountId
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.time.Clock

@Composable
internal fun androidx.compose.ui.window.ApplicationScope.DesktopCompositionRoot() {
    val startupConfiguration = remember { runCatching { desktopD8RuntimeConfigurationOrNull() } }
    val initialStartupState = when {
        startupConfiguration.isFailure -> D8StartupState.Blocked
        else -> D8StartupState.Activating
    }
    val startupState = remember { mutableStateOf(initialStartupState) }
    val databaseFile = remember { File(System.getProperty("user.home"), ".agentic-scheduler/agentic-scheduler.db").also { it.parentFile.mkdirs() } }
    val database = remember(databaseFile) { openDesktopDatabase(databaseFile.absolutePath) }
    val secureStore = remember { DesktopPlatformSecureStore() }
    val agentState = remember(database) { RoomAgentStateRepository(database) }
    val enrollments = remember(database) { RoomLocalEnrollmentRepository(database) }
    val ids = remember { productionUuidV7Generator() }
    val conversationSettings = remember(database, enrollments) {
        val transport = RoomAgentSyncTransportPersistence(database)
        val history = RoomAgentSyncPersistence(database)
        val exporter = AgentHistoryExplicitExport(enrollments, transport, history, history,
            RoomAgentHistoryExportSource(database, agentState), ids, EpochMillisecondsClock { Clock.System.now().toEpochMilliseconds() })
        AgentConversationSyncSettings(enrollments, transport, transport, history, exporter)
    }
    val journal = remember(database) { RoomMutationJournalRepository(database) }
    val agentWriteGate = remember(agentState, enrollments) { ActiveEnrollmentAgentWriteGate(enrollments, agentState) }
    val events = remember(database) { RoomEventRepository(database) }
    val tasks = remember(database) { RoomTaskRepository(database) }
    val academics = remember(database) { RoomAcademicRepository(database) }
    val profiles = remember(database) { RoomPlanningProfileRepository(database) }
    val transactions = remember(database) { RoomApplicationTransactionRunner(database) }
    val mutations = remember(transactions, journal, ids, agentWriteGate) { MutationCoordinator(transactions, journal, ids, MutationWallClock { Clock.System.now().toEpochMilliseconds() }, agentWriteGate) }
    val d8Runtime = remember(database, ids, secureStore) {
        RoomD8RuntimeComposition(
            database,
            secureStore,
            TinkPairingHpke(),
            ids,
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
            agentOutboundGate = agentWriteGate,
        )
    }
    val d8Scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val d8ShutdownScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val d8SyncTrigger = remember { mutableStateOf<ActiveSyncCatchUpTrigger?>(null) }
    LaunchedEffect(Unit) {
        val configuration = startupConfiguration.getOrElse {
            startupState.value = D8StartupState.Blocked
            return@LaunchedEffect
        }
        try {
            val creation = configuration?.let { d8Runtime.activate(it) }
                ?: d8Runtime.activateWithoutConfiguration()
            when (creation) {
                is ActiveSyncRuntimeCreation.Active -> {
                    val trigger = d8Runtime.newCatchUpTrigger(
                        d8Scope,
                        pollingIntervalMillis = ActiveSyncCatchUpTrigger.DESKTOP_ANDROID_POLL_INTERVAL_MILLIS,
                        onUnexpectedFailure = { System.err.println("D8 catch-up failed unexpectedly; transient retry remains scheduled.") },
                        onNonRetryableFailure = { reason -> System.err.println("Automatic sync stopped: $reason. Check account credentials or sync integrity before retrying.") },
                    )
                    d8SyncTrigger.value = trigger
                    trigger.start()
                    startupState.value = D8StartupState.Ready
                }
                is ActiveSyncRuntimeCreation.ActiveEnrollmentOffline -> startupState.value = D8StartupState.Ready
                ActiveSyncRuntimeCreation.NoEnrollment -> startupState.value = D8StartupState.Ready
                is ActiveSyncRuntimeCreation.MultipleActiveEnrollments -> startupState.value = D8StartupState.Blocked
                ActiveSyncRuntimeCreation.EnrollmentNotActive,
                is ActiveSyncRuntimeCreation.ActiveEnrollmentAccountMismatch,
                is ActiveSyncRuntimeCreation.MissingDeviceCredential,
                -> startupState.value = D8StartupState.Blocked
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            startupState.value = D8StartupState.Blocked
        }
    }
    val reads = remember(events, tasks, profiles, academics, d8Runtime) {
        ConflictAwareSourceFactReadService(events, tasks, profiles, academics, d8Runtime.sourceFacts)
    }
    val planner = remember(tasks, events, profiles, academics, ids, mutations, d8Runtime) {
        DogfoodPlannerService(tasks, events, profiles, academics, ids, mutations = mutations, conflictWritePolicy = d8Runtime.writePolicy, sourceFacts = d8Runtime.sourceFacts)
    }
    val profileSettings = remember(profiles, ids, mutations, d8Runtime) { PlanningProfileSettingsService(profiles, ids, mutations, d8Runtime.writePolicy) }
    val academicService = remember(academics, ids, mutations, d8Runtime) { dev.agenticscheduler.application.academic.AcademicAuthoringService(academics, ids, mutations, d8Runtime.writePolicy, d8Runtime.sourceFacts) }
    val eventEditor = remember(events, ids, mutations, d8Runtime) { EventEditingService(events, ids, mutations, d8Runtime.writePolicy) }
    val taskEditor = remember(tasks, ids, mutations, d8Runtime) { TaskEditingService(tasks, ids, mutations, d8Runtime.writePolicy) }
    val providerProbes = remember { ProviderProbePresentation() }
    val agentHttpClient = remember { HttpClient(CIO) }
    val agentRunService = remember(database, agentState, agentHttpClient, secureStore, reads, planner, profileSettings, eventEditor, taskEditor, mutations, journal, ids, d8Runtime) {
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
            plannerFullReplan = PlannerPreviewFullReplanTool(DogfoodPlannerPreviewApplication(planner)),
            plannerLocalReflow = PlannerPreviewLocalReflowTool(DogfoodPlannerPreviewApplication(planner)),
            historyUndo = HistoryUndoTool(D7HistoryUndoApplication(undo, history)),
            planningProfileUpdate = PlanningProfileUpdateTool(profileSettings),
            plannerApplyBranch = PlannerApplyBranchTool(planner),
        )
    }
    Window(onCloseRequest = {
        d8SyncTrigger.value?.close()
        d8Scope.cancel()
        d8ShutdownScope.launch {
            try {
                d8Runtime.deactivate()
                withContext(Dispatchers.Main) { exitApplication() }
            } finally {
                d8ShutdownScope.cancel()
            }
        }
    }, title = "Agentic Scheduler") {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo.isWindowFocused, d8SyncTrigger.value) {
            d8SyncTrigger.value?.setForeground(windowInfo.isWindowFocused)
        }
        val currentStartupState by startupState
        val activeSyncTrigger = d8SyncTrigger.value
        val syncStoppedReason by remember(activeSyncTrigger) {
            activeSyncTrigger?.stoppedReason ?: flowOf<String?>(null)
        }.collectAsState(initial = null)
        TemvioTheme(androidx.compose.foundation.isSystemInDarkTheme()) {
            Surface {
                when (currentStartupState) {
                    D8StartupState.Ready -> DesktopApp(
                        reads,
                        planner,
                        profileSettings,
                        eventEditor,
                        taskEditor,
                        syncStoppedReason,
                        onRetrySync = { activeSyncTrigger?.retryNow() },
                        agentRunService = agentRunService,
                        agentState = agentState,
                        secureStore = secureStore,
                        enrollments = enrollments,
                        ids = ids,
                        conversationSettings = conversationSettings,
                        providerProbes = providerProbes,
                        academicService = academicService,
                    )
                    D8StartupState.Activating -> D8StartupStatus("Connecting to your secure sync space…")
                    D8StartupState.Blocked -> D8StartupStatus("Sync setup is unavailable. Restore the device credential or check the configured account and server.")
                }
            }
        }
    }
    DisposableEffect(agentHttpClient) { onDispose { agentHttpClient.close() } }
}

internal enum class D8StartupState { Activating, Ready, Blocked }
@Composable
internal fun D8StartupStatus(message: String) {
    Column { Text(message) }
}

internal fun desktopD8RuntimeConfigurationOrNull(): ActiveSyncRuntimeConfiguration? {
    val baseUrl = System.getProperty("agenticScheduler.sync.baseUrl")?.trim().orEmpty()
    val accountId = System.getProperty("agenticScheduler.sync.accountId")?.trim().orEmpty()
    if (baseUrl.isEmpty() && accountId.isEmpty()) return null
    require(baseUrl.isNotEmpty() && accountId.isNotEmpty()) { "D8 sync desktop configuration requires both -DagenticScheduler.sync.baseUrl and -DagenticScheduler.sync.accountId." }
    return ActiveSyncRuntimeConfiguration(AccountId(accountId), baseUrl)
}

internal class ActiveEnrollmentAgentWriteGate(
    private val enrollments: LocalEnrollmentRepository,
    private val state: AgentStateRepository,
) : AgentOriginWriteGate, AgentOutboundCompatibilityGate {
    override suspend fun mayCommit(): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        return when (active.size) {
            0 -> true // No enrollment means this write cannot be emitted to a SyncSpace.
            1 -> AgentSyncWriteGate(active.single().accountId, enrollments, state).mayCommit()
            else -> false
        }
    }

    override suspend fun enabled(syncSpaceId: SyncSpaceId): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        val selected = active.singleOrNull { it.syncSpaceId == syncSpaceId } ?: return false
        if (active.size != 1) return false
        return AgentSyncWriteGate(selected.accountId, enrollments, state).enabled(syncSpaceId)
    }
}
