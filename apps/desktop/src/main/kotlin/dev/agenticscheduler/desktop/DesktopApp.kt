package dev.agenticscheduler.desktop

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
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarViewport
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

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
) {
    val schedule = scheduleSession ?: remember(reads) {
        val zone = TimeZone.currentSystemDefault()
        DesktopScheduleScreenCoordinator(reads, Clock.System.now().toLocalDateTime(zone).date, zone)
    }
    val displayTimeZone = schedule.displayTimeZone
    var selectedDate by schedule.selectedDate
    var editingEvent by schedule.editingEvent
    var editingTask by schedule.editingTask
    var creatingEvent by schedule.creatingEvent
    var creatingTask by schedule.creatingTask
    val viewport = remember(selectedDate, displayTimeZone) { CalendarViewport(selectedDate, selectedDate.plus(1, DateTimeUnit.DAY), displayTimeZone) }
    // Capture one immutable frame value; deferred lazy content must not reread a moving delegate.
    val projectionRead = remember(reads, viewport) { schedule.calendar(viewport) }.collectAsState(initial = null).value
    val projection = projectionRead ?: emptyProjection()
    val taskRead by remember(reads) { schedule.tasks() }.collectAsState(ConflictAwareRead.Projected(emptyList<Task>().toImmutableList()))
    val focusRead by remember(reads) { schedule.focusBlocks() }.collectAsState(ConflictAwareRead.Projected(emptyList<dev.agenticscheduler.domain.task.FocusBlock>().toImmutableList()))
    val taskValues = (taskRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    val focusBlocks = (focusRead as? ConflictAwareRead.Projected)?.value.orEmpty().toImmutableList()
    val projectedSyncConflictRefs =
        (taskRead as? ConflictAwareRead.Projected<*>)?.syncConflictRefs.orEmpty() +
            (focusRead as? ConflictAwareRead.Projected<*>)?.syncConflictRefs.orEmpty()
    val syncConflictCount = (projection.syncConflictRefs + projectedSyncConflictRefs)
        .flatMap { it.conflictIds }
        .distinct()
        .size
    val dateItems = projection.items.filter { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }
    val timedItems = projection.items.filterNot { it is CalendarItem.AllDay || it is CalendarItem.DateOnly }

    val navigation = navigationSession ?: remember { DesktopNavigation() }
    val agentCoordinator = remember(agentState, agentRunService) { DesktopAgentScreenCoordinator(agentState, agentRunService) }
    val plannerCoordinator = remember { DesktopPlannerScreenCoordinator() }
    val featureScope = rememberCoroutineScope()
    agentCoordinator.scope = featureScope
    plannerCoordinator.scope = featureScope
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    var darkOverride by remember { mutableStateOf(initialDark) }
    val dark = darkOverride ?: systemDark
    TemvioTheme(dark) {
        DesktopAppShell(navigation, dark, { darkOverride = !dark }) { destination ->
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(if (projectionRead == null) "schedule-loading" else "schedule-ready"),
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (projectionRead == null && destination in listOf(DesktopDestination.TODAY, DesktopDestination.CALENDAR)) item { StatusMessage("Loading", "Reading authoritative local source facts…") }
                else when (destination) {
                    DesktopDestination.TODAY, DesktopDestination.CALENDAR -> {
                        if (syncStoppedReason != null) item { StatusMessage("Sync stopped", syncStoppedReason); Button(onClick = onRetrySync) { Text("Retry sync") } }
                        item { SectionHeading(if (destination == DesktopDestination.TODAY) "Your day, in focus" else "Agenda / Day", "$selectedDate · ${displayTimeZone.id}") }
                        if (destination == DesktopDestination.TODAY) {
                            item { StatusMessage("Agent", "Ask a question or start a deliberate command.") }
                            item { NavigationControl("Open Agent", false, { navigation.open(DesktopDestination.AGENT) }) }
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(role = ActionRole.TERTIARY, onClick = { selectedDate = selectedDate.plus(-1, DateTimeUnit.DAY) }) { Text("Previous day") }
                                Button(role = ActionRole.TERTIARY, onClick = { selectedDate = selectedDate.plus(1, DateTimeUnit.DAY) }) { Text("Next day") }
                                Button(onClick = { creatingEvent = true }) { Text("New Event") }
                                Button(role = ActionRole.SECONDARY, onClick = { creatingTask = true }) { Text("New Task") }
                            }
                        }
                        item { SectionHeading("All-day / date-only", "Dates retain their original semantic type.") }
                        if (dateItems.isEmpty()) item { StatusMessage("No date items", "No AllDay or DateOnly items in this viewport.") }
                        items(dateItems, key = { it.source.toString() }) { item -> CalendarRow(item, reads, focusBlocks.associate { it.id to (taskValues.firstOrNull { task -> task.id == it.taskId }?.title ?: it.taskId.value) }, displayTimeZone) { editingEvent = it } }
                        item { SectionHeading("Schedule", "Zoned and Floating times remain distinct.") }
                        if (timedItems.isEmpty()) item { StatusMessage("A clear schedule", "No timed items in this viewport. Add an Event or ask Agent to inspect your plans.") }
                        items(timedItems, key = { it.source.toString() }) { item -> CalendarRow(item, reads, focusBlocks.associate { it.id to (taskValues.firstOrNull { task -> task.id == it.taskId }?.title ?: it.taskId.value) }, displayTimeZone) { editingEvent = it } }
                        if (destination == DesktopDestination.TODAY) {
                            item { SectionHeading("Tasks", "Authoritative task state; no inferred urgency.") }
                            items(taskValues, key = { it.id.value }) { task -> TaskPresentation(task) { editingTask = task } }
                            if (taskValues.isEmpty() && taskRead is ConflictAwareRead.Projected) item { StatusMessage("No tasks yet", "Create a Task with explicit effort and deadline choices.") }
                        }
                        item {
                            if (projection.conflicts.isNotEmpty()) StatusMessage("Calendar overlap", "${projection.conflicts.size} overlap(s) in the current projection.")
                            if (syncConflictCount > 0) StatusMessage("Sync conflict", "$syncConflictCount conflict(s) require resolution.")
                            if (projection.issues.isNotEmpty()) StatusMessage("Projection issue", "${projection.issues.size} issue(s); some facts cannot be projected.")
                            if (taskRead is ConflictAwareRead.Unprojectable || focusRead is ConflictAwareRead.Unprojectable) Text("Sync conflict source facts require resolution before display.")
                        }
                    }
                    DesktopDestination.TASKS -> {
                        item { SectionHeading("Tasks", "Work to do. FocusBlocks are planned time, not completion.") }
                        item { Button(onClick = { creatingTask = true }) { Text("New Task") } }
                        items(taskValues, key = { it.id.value }) { task -> TaskPresentation(task) { editingTask = task } }
                        if (taskValues.isEmpty() && taskRead is ConflictAwareRead.Projected) item { StatusMessage("No tasks", "Your saved tasks appear here.") }
                        if (taskRead is ConflictAwareRead.Unprojectable) item { StatusMessage("Sync conflict", "Task source facts require resolution before display.") }
                    }
                    DesktopDestination.PLANNER -> plannerDogfoodItem { PlannerDogfoodPanel(reads, focusBlocks, dogfoodPlanner, profileSettings, plannerCoordinator) }
                    DesktopDestination.AGENT -> item(key = "agent") { AgentCommandPanel(agentRunService, agentState, secureStore, enrollments, ids, agentCoordinator, providerProbes) }
                    DesktopDestination.SETTINGS -> {
                        item { SectionHeading("Settings", "Device-local choices and secure sync status") }
                        item { DesktopConversationSyncControls(conversationSettings) }
                        item { NavigationControl("Provider settings", false, { navigation.open(DesktopDestination.AGENT); agentCoordinator.providerDialog.value = true }) }
                        if (syncStoppedReason != null) item { StatusMessage("Sync stopped", syncStoppedReason); Button(onClick = onRetrySync) { Text("Retry sync") } }
                    }
                    else -> item { StatusMessage("${destination.label}", "This view is not available yet. Your existing data remains unchanged.") }
                }
            }
        }
    if (creatingEvent) EventEditorDialog(null, selectedDate, displayTimeZone, eventEditor, { creatingEvent = false }, { creatingEvent = false })
    editingEvent?.let { EventEditorDialog(it, selectedDate, displayTimeZone, eventEditor, { editingEvent = null }, { editingEvent = null }) }
    if (creatingTask) TaskEditorDialog(null, taskEditor, { creatingTask = false }, { creatingTask = false })
    editingTask?.let { TaskEditorDialog(it, taskEditor, { editingTask = null }, { editingTask = null }) }
    }
}

/** Keeps the remembered preview state stable while rows above it change. */
internal fun LazyListScope.plannerDogfoodItem(content: @Composable () -> Unit) {
    item(key = "planner-dogfood") { content() }
}
