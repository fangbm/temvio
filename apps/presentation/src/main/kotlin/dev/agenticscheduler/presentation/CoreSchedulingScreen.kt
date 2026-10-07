package dev.agenticscheduler.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.history.*
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.task.*
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch
import kotlinx.datetime.*
import kotlin.time.Instant

enum class CoreScreen { TODAY, CALENDAR, TASKS }
sealed interface CoreSelection {
    data class Calendar(val source: CalendarSourceRef) : CoreSelection
    data class TaskDetail(val id: TaskId) : CoreSelection
}
class CoreScreenCoordinator {
    var mode by mutableStateOf(CalendarMode.DAY)
    var listAlternative by mutableStateOf(false)
    var taskFilter by mutableStateOf(TaskFilter.OPEN)
    var selection by mutableStateOf<CoreSelection?>(null)
    var expandedDate by mutableStateOf<LocalDate?>(null)
    var detailEvent by mutableStateOf<ConflictAwareRead<Event?>?>(null)
    var detailError by mutableStateOf<String?>(null)
    var detailRevision by mutableStateOf(0)
    var refreshRevision by mutableStateOf(0)
    val readFailures = mutableStateMapOf<String, String>()
    fun <T> observe(channel: String, source: Flow<T>): Flow<T> = source.onStart { readFailures.remove(channel) }.catch { failure ->
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        readFailures[channel] = "$channel load failed. Displayed facts may be stale; refresh explicitly."
    }
}

@Composable
fun CoreSchedulingScreen(
    screen: CoreScreen, session: CoreScreenCoordinator, selectedDate: LocalDate, zone: TimeZone,
    now: Instant, projection: CalendarProjectionResult?,
    taskRead: ConflictAwareRead<List<Task>>?, focusRead: ConflictAwareRead<List<FocusBlock>>?,
    reads: ConflictAwareSourceFactReadService, onDateChange: (LocalDate) -> Unit,
    onCreateEvent: () -> Unit, onCreateTask: () -> Unit, onEditEvent: (Event) -> Unit, onEditTask: (Task) -> Unit,
    onOpenAgent: () -> Unit, onOpenCourse: (CourseScheduleRuleId) -> Unit, onOpenExam: (ExamId) -> Unit,
    syncStoppedReason: String?, onRetrySync: () -> Unit,
    onOpenCalendar: () -> Unit = {}, onOpenTasks: () -> Unit = {},
) {
    val tasks = (taskRead as? ConflictAwareRead.Projected)?.value.orEmpty()
    val blocks = (focusRead as? ConflictAwareRead.Projected)?.value.orEmpty()
    val viewport = calendarViewport(selectedDate, if (screen == CoreScreen.CALENDAR) session.mode else CalendarMode.DAY, zone)
    val visibleTasks = filteredTasks(tasks, if (screen == CoreScreen.TODAY) TaskFilter.OPEN else session.taskFilter)
    val focusTitles = blocks.associate { it.id to (tasks.firstOrNull { task -> task.id == it.taskId }?.title ?: "Task ${it.taskId.value}") }
    val selected = session.selection
    val selectedItem = (selected as? CoreSelection.Calendar)?.let { s -> projection?.items?.firstOrNull { it.source == s.source } }
    val selectedTask = (selected as? CoreSelection.TaskDetail)?.let { s -> tasks.firstOrNull { it.id == s.id } }
    val gridScroll = rememberScrollState()
    LaunchedEffect(selected, selectedItem, session.detailRevision) {
        session.detailEvent = null; session.detailError = null
        val source = (selected as? CoreSelection.Calendar)?.source as? CalendarSourceRef.Event
        if (source != null) try { session.detailEvent = reads.event(source.id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { session.detailError = "Event detail could not be loaded. Close and retry explicitly." }
    }
    AdaptiveDetailWorkspace(selected != null, { session.selection = null }, {
        when {
            selectedTask != null -> {
                SectionHeading(selectedTask.title, "Task · ${selectedTask.status}")
                Text("Priority ${selectedTask.priority}")
                Text("Estimated ${selectedTask.effort.estimated ?: "unknown"} · completed ${selectedTask.effort.completed} · remaining ${selectedTask.effort.remaining ?: "unknown"}")
                Text(deadlineLabel(selectedTask, selectedDate, now))
                Text("Source ID ${selectedTask.id.value}", style = MaterialTheme.typography.bodySmall)
                ActionButton(role = ActionRole.SECONDARY, onClick = { onEditTask(selectedTask) }) { Text("Edit Task") }
                SectionHeading("Linked FocusBlocks", "Planned time does not complete this Task.")
                val linked = blocks.filter { it.taskId == selectedTask.id }
                when (focusRead) {
                    null -> Text("Loading FocusBlocks…")
                    is ConflictAwareRead.Unprojectable -> StatusMessage("FocusBlocks unavailable", focusRead.reason)
                    is ConflictAwareRead.Projected -> {
                        if (focusRead.syncConflictRefs.isNotEmpty()) StatusMessage("Provisional FocusBlocks", "OPEN Sync conflict remains unresolved.")
                        if (linked.isEmpty()) Text("No linked FocusBlocks.")
                    }
                }
                linked.forEach { Text("${it.time.start.toLocalDateTime(zone)} – ${it.time.endExclusive.toLocalDateTime(zone)} · ${zone.id}") }
            }
            selectedItem != null -> {
                val row = calendarRow(selectedItem, zone, (selectedItem.source as? CalendarSourceRef.FocusBlock)?.let { focusTitles[it.id] })
                SectionHeading(row.title, row.kind.label); Text(row.time); Text(row.detail)
                Text("Source ${selectedItem.source}", style = MaterialTheme.typography.bodySmall)
                if (projection?.conflicts?.any { it.first == selectedItem.source || it.second == selectedItem.source } == true) StatusMessage("Calendar overlap", "Authoritative projection reports overlapping occupied instants.")
                if (projection?.syncConflictRefs?.isNotEmpty() == true) StatusMessage("Sync conflict", "Provisional source facts are present; ordinary editing cannot resolve conflicts.")
                when (val source = selectedItem.source) {
                    is CalendarSourceRef.Event -> when (val eventRead = session.detailEvent) {
                        is ConflictAwareRead.Projected -> eventRead.value?.let { event ->
                            if (eventRead.syncConflictRefs.isNotEmpty()) Text("Provisional Event · OPEN Sync conflict")
                            ActionButton(role = ActionRole.SECONDARY, onClick = { onEditEvent(event) }) { Text("Edit Event") }
                        } ?: Text("Event no longer exists. Refresh source facts.")
                        is ConflictAwareRead.Unprojectable -> StatusMessage("Event unavailable", eventRead.reason)
                        null -> Text(session.detailError ?: "Loading Event detail…")
                    }
                    is CalendarSourceRef.CourseSession -> ActionButton(role = ActionRole.SECONDARY, onClick = { session.selection = null; onOpenCourse(source.key.scheduleRuleId) }) { Text("Open Course") }
                    is CalendarSourceRef.Exam -> ActionButton(role = ActionRole.SECONDARY, onClick = { session.selection = null; onOpenExam(source.id) }) { Text("Open Exam") }
                    is CalendarSourceRef.FocusBlock -> blocks.firstOrNull { it.id == source.id }?.let { focus ->
                        ActionButton(role = ActionRole.SECONDARY, onClick = { session.selection = CoreSelection.TaskDetail(focus.taskId) }) { Text("Open linked Task") }
                    }
                }
            }
            else -> StatusMessage("Source changed", "The selected fact is outside this viewport, missing, or withheld. Close detail and refresh; no raw fallback.")
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag(if (projection == null || taskRead == null || focusRead == null) "schedule-loading" else "schedule-ready"),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionHeading(when (screen) { CoreScreen.TODAY -> "Your day, in focus"; CoreScreen.CALENDAR -> "Calendar"; CoreScreen.TASKS -> "Tasks" }, "$selectedDate · ${zone.id}") }
            item { ActionButton(role = ActionRole.TERTIARY, onClick = { session.refreshRevision++; session.detailRevision++ }) { Text("Refresh source facts") } }
            session.readFailures.values.toList().forEach { message -> item { StatusMessage("Read error", message) } }
            if (syncStoppedReason != null) item { StatusMessage("Sync stopped", syncStoppedReason); ActionButton(onClick = onRetrySync) { Text("Retry sync") } }
            if (screen != CoreScreen.TASKS) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(role = ActionRole.TERTIARY, onClick = { onDateChange(selectedDate.plus(-1, DateTimeUnit.DAY)) }) { Text("Previous day") }
                ActionButton(role = ActionRole.TERTIARY, onClick = { onDateChange(selectedDate.plus(1, DateTimeUnit.DAY)) }) { Text("Next day") }
                ActionButton(onClick = onCreateEvent) { Text("New Event") }
                ActionButton(role = ActionRole.SECONDARY, onClick = onCreateTask) { Text("New Task") }
            } }
            if (screen == CoreScreen.TODAY) {
                item { PresentationCard {
                    SectionHeading("Up next", "From the current authoritative day projection")
                    val next = projection?.items?.filterIsInstance<CalendarItem.Zoned>()?.firstOrNull { it.originalRange.endExclusive > now }
                    Text(next?.let { "${it.title} · ${it.originalRange.start.toLocalDateTime(zone).time}" } ?: if (projection == null) "Loading schedule…" else "No upcoming exact-time item in this day.")
                    val focus = blocks.filter { it.time.endExclusive > now && CalendarItem.Zoned(CalendarSourceRef.FocusBlock(it.id), "", it.time).intersectsLocalDate(selectedDate, zone) }.minByOrNull { it.time.start }
                    Text(focus?.let { "Next FocusBlock · ${focusTitles[it.id]} · ${it.time.start.toLocalDateTime(zone).time} (planned)" } ?: when (focusRead) {
                        null -> "Loading FocusBlocks…"
                        is ConflictAwareRead.Unprojectable -> "FocusBlocks unavailable; no raw fallback."
                        is ConflictAwareRead.Projected -> "No upcoming FocusBlock in this day."
                    })
                    ActionButton(role = ActionRole.TERTIARY, onClick = onOpenAgent) { Text("Open Agent") }
                } }
            }
            if (screen == CoreScreen.CALENDAR) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarMode.entries.forEach { mode -> NavigationControl(mode.name.lowercase().replaceFirstChar { it.uppercase() }, session.mode == mode, { session.mode = mode; session.expandedDate = null; session.selection = null }, Modifier.testTag("calendar-mode-${mode.name}")) }
                ActionButton(role = ActionRole.TERTIARY, onClick = { session.listAlternative = !session.listAlternative }) { Text(if (session.listAlternative) "Grid view" else "Accessible list") }
                if (session.mode != CalendarMode.DAY) {
                    val amount = if (session.mode == CalendarMode.WEEK) 7 else 1
                    val unit = if (session.mode == CalendarMode.WEEK) DateTimeUnit.DAY else DateTimeUnit.MONTH
                    ActionButton(role = ActionRole.TERTIARY, onClick = { onDateChange(selectedDate.plus(-amount, unit)) }) { Text("Previous ${session.mode.name.lowercase()}") }
                    ActionButton(role = ActionRole.TERTIARY, onClick = { onDateChange(selectedDate.plus(amount, unit)) }) { Text("Next ${session.mode.name.lowercase()}") }
                }
            } }
            if (screen == CoreScreen.TODAY) {
                item { SectionHeading("Open tasks", "Open / In progress · explicit status, no inferred urgency") }
                when (taskRead) {
                    null -> item { Text("Loading tasks…") }
                    is ConflictAwareRead.Unprojectable -> item { StatusMessage("Tasks unavailable", taskRead.reason) }
                    is ConflictAwareRead.Projected -> {
                        if (taskRead.syncConflictRefs.isNotEmpty()) item { StatusMessage("Provisional Tasks", "OPEN Sync conflict remains unresolved.") }
                        if (visibleTasks.isEmpty()) item { Text("No open tasks.") }
                        items(visibleTasks.take(3), key = { "today-task:${it.id.value}" }) { task -> PresentationCard {
                            EntityBadge(EntityKind.TASK); Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text("${task.status} · ${deadlineLabel(task, selectedDate, now)}")
                            ActionButton(role = ActionRole.TERTIARY, onClick = { session.selection = CoreSelection.TaskDetail(task.id) }) { Text("View Task") }
                        } }
                        item { ActionButton(role = ActionRole.TERTIARY, onClick = onOpenTasks) { Text("All Tasks") } }
                    }
                }
            }
            if (screen != CoreScreen.TASKS) {
                when {
                    projection == null -> item { StatusMessage("Loading", "Reading authoritative local source facts…") }
                    screen == CoreScreen.CALENDAR && session.mode != CalendarMode.DAY && !session.listAlternative -> {
                        item { Text("${viewport.startDate} – ${viewport.endDateExclusive} exclusive · Monday start · swipe/scroll horizontally for all dates") }
                        val cells = calendarCells(projection, viewport)
                        items(cells.chunked(7), key = { it.first().date.toString() }) { week ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).horizontalScroll(gridScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                week.forEach { cell -> CalendarDateCell(cell, zone, { session.expandedDate = cell.date }, { session.selection = CoreSelection.Calendar(it.source) }) }
                            }
                        }
                    }
                    else -> {
                        val dates = if (screen == CoreScreen.CALENDAR) visibleDates(viewport) else listOf(selectedDate)
                        dates.forEach { date ->
                            val allDateItems = projection.items.filter { it.intersectsLocalDate(date, zone) }
                            val dateItems = if (screen == CoreScreen.TODAY) allDateItems.take(3) else allDateItems
                            item(key = "date-$date") { SectionHeading(date.toString(), if (screen == CoreScreen.TODAY) "Today's schedule · AllDay / DateOnly / Zoned / Floating" else "${date.dayOfWeek} · original semantic types retained") }
                            if (dateItems.isEmpty()) item(key = "empty-$date") { Text("No schedule items") }
                            items(dateItems, key = { "$date:${it.source}" }) { value -> CalendarSelectionCard(value, zone, (value.source as? CalendarSourceRef.FocusBlock)?.let { focusTitles[it.id] }) { session.selection = CoreSelection.Calendar(value.source) } }
                            if (screen == CoreScreen.TODAY) item { ActionButton(role = ActionRole.TERTIARY, onClick = onOpenCalendar) { Text("Full day schedule · ${allDateItems.size} items") } }
                        }
                    }
                }
                if (projection != null) {
                    if (projection.conflicts.isNotEmpty()) item { StatusMessage("Calendar overlap", "${projection.conflicts.size} occupied-time overlap(s). This is separate from Sync conflict.") }
                    if (projection.syncConflictRefs.isNotEmpty()) item { StatusMessage("Sync conflict · provisional", projection.syncConflictRefs.joinToString { "${it.entityKind}: ${it.conflictIds.joinToString()}" }) }
                    if (projection.issues.isNotEmpty()) item { StatusMessage("Projection issue", projection.issues.joinToString("\n")) }
                }
            }
            if (screen == CoreScreen.TASKS) {
                item { SectionHeading(if (screen == CoreScreen.TODAY) "Open tasks" else "Tasks", "Task status is authoritative; FocusBlock time is not completion.") }
                if (screen == CoreScreen.TASKS) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(onClick = onCreateTask) { Text("New Task") }
                    TaskFilter.entries.forEach { filter -> NavigationControl(filter.name.lowercase().replaceFirstChar { it.uppercase() }, session.taskFilter == filter, { session.taskFilter = filter }) }
                } }
                when (taskRead) {
                    null -> item { StatusMessage("Loading tasks", "Reading authoritative state…") }
                    is ConflictAwareRead.Unprojectable -> item { StatusMessage("Tasks unavailable", taskRead.reason) }
                    is ConflictAwareRead.Projected -> {
                        if (taskRead.syncConflictRefs.isNotEmpty()) item { StatusMessage("Provisional Tasks", "OPEN Sync conflicts remain unresolved.") }
                        if (visibleTasks.isEmpty()) item { StatusMessage("No matching tasks", "Choose All to inspect other statuses, or create a Task explicitly.") }
                        items(visibleTasks, key = { it.id.value }) { task -> PresentationCard {
                            EntityBadge(EntityKind.TASK); SectionHeading(task.title, "${task.status} · priority ${task.priority}")
                            Text("Remaining ${task.effort.remaining ?: "unknown"} · ${deadlineLabel(task, selectedDate, now)}")
                            ActionButton(role = ActionRole.TERTIARY, onClick = { session.selection = CoreSelection.TaskDetail(task.id) }, modifier = Modifier.testTag("task-select-${task.id.value}")) { Text("View Task") }
                        } }
                    }
                }
                if (focusRead is ConflictAwareRead.Unprojectable) item { StatusMessage("FocusBlocks unavailable", focusRead.reason) }
            }
        }
    }
    session.expandedDate?.let { date ->
        val dateItems = projection?.items.orEmpty().filter { it.intersectsLocalDate(date, zone) }
        AlertDialog(onDismissRequest = { session.expandedDate = null }, title = { Text("$date · all items") },
            text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dateItems.isEmpty()) item { Text("No items") }
                items(dateItems, key = { it.source.toString() }) { value -> CalendarSelectionCard(value, zone) { session.expandedDate = null; session.selection = CoreSelection.Calendar(value.source) } }
            } }, confirmButton = { ActionButton(role = ActionRole.TERTIARY, onClick = { session.expandedDate = null }) { Text("Close date list") } })
    }
}

@Composable
private fun CalendarSelectionCard(value: CalendarItem, zone: TimeZone, focusTitle: String? = null, select: () -> Unit) {
    val row = calendarRow(value, zone, focusTitle)
    PresentationCard {
        EntityBadge(row.kind); Text(row.title, style = MaterialTheme.typography.titleMedium)
        Text(row.time); Text(row.detail, color = LocalTemvioColors.current.secondary)
        ActionButton(role = ActionRole.TERTIARY, onClick = select) { Text("View ${row.kind.label}") }
    }
}

@Composable
private fun CalendarDateCell(cell: CalendarCell, zone: TimeZone, openDate: () -> Unit, select: (CalendarItem) -> Unit) {
    Surface(Modifier.width(136.dp).fillMaxHeight(), color = LocalTemvioColors.current.container, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(role = ActionRole.TERTIARY, onClick = openDate, modifier = Modifier.semantics { contentDescription = "${cell.date}, ${cell.summaries.size + cell.overflow} schedule items" }) { Text("${cell.date.day} ${cell.date.dayOfWeek.name.take(3)}") }
            cell.summaries.forEach { item ->
                val row = calendarRow(item, zone)
                ActionButton(role = ActionRole.SECONDARY, onClick = { select(item) }, modifier = Modifier.fillMaxWidth()) {
                    Column { Text(if (item.source is CalendarSourceRef.CourseSession) "CourseSession" else row.kind.label, style = MaterialTheme.typography.labelSmall); Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(calendarCompactTime(item, zone), style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (cell.overflow > 0) ActionButton(role = ActionRole.TERTIARY, onClick = openDate) { Text("+${cell.overflow} more") }
        }
    }
}
