package dev.agenticscheduler.desktop

import dev.agenticscheduler.presentation.*
import dev.agenticscheduler.application.academic.AcademicAuthoringService
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.agenticscheduler.ui.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import dev.agenticscheduler.application.editing.EventEditingService
import dev.agenticscheduler.application.editing.TaskEditingService
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.planner.DogfoodPlannerService
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.runtime.AgentRunService
import dev.agenticscheduler.application.sync.AgentConversationSyncSettings
import dev.agenticscheduler.application.sync.PlatformSecretStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Composable
internal fun DesktopApp(
    reads: ConflictAwareSourceFactReadService,
    dogfoodPlanner: DogfoodPlannerService,
    profileSettings: PlanningProfileSettingsService,
    eventEditor: EventEditingService,
    taskEditor: TaskEditingService,
    syncStoppedReason: String?,
    onRetrySync: () -> Unit,
    agentRunService: AgentRunService,
    agentState: AgentStateRepository,
    secureStore: PlatformSecretStore,
    enrollments: LocalEnrollmentRepository,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
    conversationSettings: AgentConversationSyncSettings,
    scheduleSession: DesktopScheduleScreenCoordinator? = null,
    navigationSession: DesktopNavigation? = null,
    initialDark: Boolean? = null,
    providerProbes: ProviderProbePresentation? = null,
    academicService: AcademicAuthoringService? = null,
    coreSession: CoreScreenCoordinator? = null,
    academicSession: AcademicScreenCoordinator? = null,
    presentationNow: kotlin.time.Instant? = null,
    historyQueries: dev.agenticscheduler.application.history.HistoryQueryService? = null,
    undoService: dev.agenticscheduler.application.history.UndoService? = null,
    conflictQueries: dev.agenticscheduler.application.history.SyncConflictQueryService? = null,
    syncConfigured: Boolean = false,
    securityServices: SecurityWorkflowServices? = null,
    applicationActionScope: kotlinx.coroutines.CoroutineScope? = null,
    plannerWorkspaceSession: PlannerWorkspaceCoordinator? = null,
    historySession: HistoryScreenCoordinator? = null,
    syncSession: SyncSecurityScreenCoordinator? = null,
) {
    val schedule = scheduleSession ?: remember(reads) {
        val zone = TimeZone.currentSystemDefault()
        DesktopScheduleScreenCoordinator(reads, Clock.System.now().toLocalDateTime(zone).date, zone)
    }
    val core = coreSession ?: remember { CoreScreenCoordinator() }
    val academic = academicSession ?: academicService?.let { remember(it) { AcademicScreenCoordinator(it, eventEditor) } }
    val navigation = navigationSession ?: remember { DesktopNavigation() }
    val now = presentationNow ?: Clock.System.now()
    LaunchedEffect(navigation.current) { core.selection = null; core.expandedDate = null }
    val displayTimeZone = schedule.displayTimeZone
    var selectedDate by schedule.selectedDate
    var editingEvent by schedule.editingEvent
    var editingTask by schedule.editingTask
    var creatingEvent by schedule.creatingEvent
    var creatingTask by schedule.creatingTask
    val viewport = remember(selectedDate, displayTimeZone, core.mode, navigation.current) { calendarViewport(selectedDate, if (navigation.current == DesktopDestination.CALENDAR) core.mode else CalendarMode.DAY, displayTimeZone) }
    // Capture one immutable frame value; deferred lazy content must not reread a moving delegate.
    val revision = core.refreshRevision
    val frame = remember(reads, viewport, revision) { core.observe("Calendar", schedule.calendar(viewport)).map { CalendarReadFrame(viewport, revision, it) } }.collectAsState(initial = null).value
    val projectionRead = frame?.takeIf { it.viewport == viewport && it.revision == revision }?.projection
    val taskRead = remember(reads, core.refreshRevision) { core.observe("Tasks", schedule.tasks()) }.collectAsState(initial = null).value
    val focusRead = remember(reads, core.refreshRevision) { core.observe("FocusBlocks", schedule.focusBlocks()) }.collectAsState(initial = null).value
    val focusBlocks = (focusRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    val agentCoordinator = remember(agentState, agentRunService) { DesktopAgentScreenCoordinator(agentState, agentRunService) }
    val featureScope = applicationActionScope ?: rememberCoroutineScope()
    val plannerWorkspace = plannerWorkspaceSession ?: remember(reads,dogfoodPlanner,profileSettings) { PlannerWorkspaceCoordinator(reads,dogfoodPlanner,profileSettings) }
    val historyScreen = historySession ?: if(historyQueries != null && undoService != null) remember(historyQueries,undoService) { HistoryScreenCoordinator(historyQueries,undoService) } else null
    val syncScreen = syncSession ?: conflictQueries?.let { remember(enrollments,it,agentState) { SyncSecurityScreenCoordinator(enrollments,it,agentState) } }
    val securityScreen = securityServices?.let {remember(it) {SecurityWorkflowCoordinator(it)}}
    var settingsSection by remember {mutableStateOf(SettingsSection.GENERAL)}
    agentCoordinator.scope = featureScope
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    var darkOverride by remember { mutableStateOf(initialDark) }
    val dark = darkOverride ?: systemDark
    TemvioTheme(dark) {
        DesktopAppShell(navigation, dark, { darkOverride = !dark }) { destination ->
            if (destination in listOf(DesktopDestination.TODAY, DesktopDestination.CALENDAR, DesktopDestination.TASKS)) {
                CoreSchedulingScreen(when (destination) { DesktopDestination.TODAY -> CoreScreen.TODAY; DesktopDestination.CALENDAR -> CoreScreen.CALENDAR; else -> CoreScreen.TASKS },
                    core, selectedDate, displayTimeZone, now, projectionRead, taskRead, focusRead, reads,
                    { selectedDate = it }, { creatingEvent = true }, { creatingTask = true }, { editingEvent = it }, { editingTask = it },
                    { navigation.open(DesktopDestination.AGENT) },
                    { ruleId -> featureScope.launch { academic?.refresh(); academic?.facts?.courseScheduleRules?.firstOrNull { it.id == ruleId }?.let { rule ->
                        academic.requestedCourse = rule.courseId; navigation.open(DesktopDestination.COURSES)
                    } } },
                    { examId -> academic?.requestedExam = examId; navigation.open(DesktopDestination.EXAMS) }, syncStoppedReason, onRetrySync, { navigation.open(DesktopDestination.CALENDAR) }, { navigation.open(DesktopDestination.TASKS) })
            } else if (destination in listOf(DesktopDestination.COURSES, DesktopDestination.EXAMS) && academic != null) {
                AcademicScreen(academic, if (destination == DesktopDestination.EXAMS) AcademicKind.EXAM else AcademicKind.COURSE)
            } else if (destination == DesktopDestination.PLANNER) {
                PlannerWorkspaceScreen(plannerWorkspace,featureScope)
            } else if (destination == DesktopDestination.HISTORY && historyScreen != null) {
                HistoryScreen(historyScreen,featureScope)
            } else if (destination == DesktopDestination.SETTINGS) {
                SettingsHub(settingsSection,{settingsSection=it},dark,{darkOverride=!dark},
                    planning={PlanningProfiles(plannerWorkspace,featureScope);NewProfileDialogHost(plannerWorkspace,featureScope)},
                    sync={syncScreen?.let {SyncSecurityScreen(it,featureScope,SyncRuntimeDisplay(syncConfigured,syncStoppedReason),onRetrySync,{DesktopConversationSyncControls(conversationSettings)},securityScreen)}},
                    onProvider={navigation.open(DesktopDestination.AGENT); agentCoordinator.providerDialog.value=true})
            } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(if (projectionRead == null) "schedule-loading" else "schedule-ready"),
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (projectionRead == null && destination in listOf(DesktopDestination.TODAY, DesktopDestination.CALENDAR)) item { StatusMessage("Loading", "Reading authoritative local source facts…") }
                else when (destination) {
                    DesktopDestination.AGENT -> item(key = "agent") { AgentCommandPanel(agentRunService, agentState, secureStore, enrollments, ids, agentCoordinator, providerProbes) }
                    else -> item { StatusMessage("${destination.label}", "This view is not available yet. Your existing data remains unchanged.") }
                }
            }
            }
        }
    academic?.let { AcademicEditor(it) }
    if (creatingEvent) EventEditorDialog(null, selectedDate, displayTimeZone, eventEditor, { creatingEvent = false }, { creatingEvent = false })
    editingEvent?.let { event -> EventEditorDialog(event, selectedDate, displayTimeZone, eventEditor, { editingEvent = null; core.detailRevision++ }, { editingEvent = null }, onReload = {
        val fresh = try { reads.event(event.id) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } as? ConflictAwareRead.Projected
        fresh?.value?.let { editingEvent = it; true } ?: false
    }) }
    if (creatingTask) TaskEditorDialog(null, taskEditor, { creatingTask = false }, { creatingTask = false })
    editingTask?.let { task -> TaskEditorDialog(task, taskEditor, { editingTask = null }, { editingTask = null }, onReload = {
        val fresh = try { reads.observeTasks().first() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } as? ConflictAwareRead.Projected
        fresh?.value?.firstOrNull { it.id == task.id }?.let { editingTask = it; true } ?: false
    }) }
    }
}

/** Keeps the remembered preview state stable while rows above it change. */
internal fun LazyListScope.plannerDogfoodItem(content: @Composable () -> Unit) {
    item(key = "planner-dogfood") { content() }
}
