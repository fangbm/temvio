package dev.agenticscheduler.desktop

import dev.agenticscheduler.ui.*
import dev.agenticscheduler.domain.task.Task
import kotlinx.datetime.toLocalDateTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarSourceRef
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.id.FocusBlockId
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

@Composable
internal fun CalendarRow(item: CalendarItem, reads: ConflictAwareSourceFactReadService, focusTitles: Map<FocusBlockId, String>, displayTimeZone: kotlinx.datetime.TimeZone, onEdit: (Event) -> Unit) {
    val scope = rememberCoroutineScope()
    val source = item.source as? CalendarSourceRef.Event
    val kind = when (item.source) {
        is CalendarSourceRef.Event -> EntityKind.EVENT
        is CalendarSourceRef.FocusBlock -> EntityKind.FOCUS_BLOCK
        is CalendarSourceRef.CourseSession -> EntityKind.COURSE
        is CalendarSourceRef.Exam -> EntityKind.EXAM
    }
    val title = (item.source as? CalendarSourceRef.FocusBlock)?.let { focusTitles[it.id] } ?: item.title
    val time = when (item) {
        is CalendarItem.Zoned -> "${item.originalRange.start.toLocalDateTime(displayTimeZone).time} – ${item.originalRange.endExclusive.toLocalDateTime(displayTimeZone).time}"
        is CalendarItem.AllDay -> "AllDay"
        is CalendarItem.DateOnly -> "DateOnly"
        is CalendarItem.Floating -> "${item.range.start.time} – ${item.range.endExclusive.time} · Floating"
    }
    val detail = when (item) {
        is CalendarItem.Zoned -> "${if (kind == EntityKind.EVENT) "Zoned" else "Exact"} · ${displayTimeZone.id} · ${item.originalRange.start.toLocalDateTime(displayTimeZone).date}"
        is CalendarItem.AllDay -> "${item.range.startDate} through ${item.range.endDateExclusive} (exclusive)"
        is CalendarItem.DateOnly -> "${item.date} · date, not an exact instant"
        is CalendarItem.Floating -> "Wall time · no timezone or instant"
    } + if (kind == EntityKind.COURSE) " · derived CourseSession" else if (kind == EntityKind.FOCUS_BLOCK) " · planned time, not completed work" else ""
    ScheduleCard(ScheduleRow(item.source.toString(), title, kind, time, detail), source?.let { ref ->
        { scope.launch { (reads.event(ref.id) as? ConflictAwareRead.Projected)?.value?.let(onEdit) }; Unit }
    })
}

@Composable
internal fun TaskPresentation(task: Task, onEdit: () -> Unit) {
    ScheduleCard(ScheduleRow(task.id.value, task.title, EntityKind.TASK, task.status.name,
        "Priority ${task.priority} · remaining ${task.effort.remaining ?: "unknown"} · deadline ${task.deadline?.deadline ?: "none"}"), onEdit)
}
