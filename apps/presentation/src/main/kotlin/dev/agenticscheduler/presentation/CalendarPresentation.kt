package dev.agenticscheduler.presentation

import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.ui.EntityKind
import dev.agenticscheduler.ui.ScheduleRow
import kotlinx.datetime.*
import kotlin.time.Instant

enum class CalendarMode { DAY, WEEK, MONTH }
enum class TaskFilter { OPEN, COMPLETED, ALL }
data class CalendarReadFrame(val viewport: CalendarViewport, val revision: Int, val projection: CalendarProjectionResult)

/** Presentation bounds only; membership and overlap truth stay in Application. */
fun calendarViewport(date: LocalDate, mode: CalendarMode, zone: TimeZone): CalendarViewport {
    val start = when (mode) {
        CalendarMode.DAY -> date
        CalendarMode.WEEK -> date.plus(-date.dayOfWeek.ordinal, DateTimeUnit.DAY)
        CalendarMode.MONTH -> LocalDate(date.year, date.month, 1).let { it.plus(-it.dayOfWeek.ordinal, DateTimeUnit.DAY) }
    }
    val end = when (mode) {
        CalendarMode.DAY -> start.plus(1, DateTimeUnit.DAY)
        CalendarMode.WEEK -> start.plus(7, DateTimeUnit.DAY)
        CalendarMode.MONTH -> LocalDate(date.year, date.month, 1).plus(1, DateTimeUnit.MONTH).let {
            if (it.dayOfWeek == DayOfWeek.MONDAY) it else it.plus(7 - it.dayOfWeek.ordinal, DateTimeUnit.DAY)
        }
    }
    return CalendarViewport(start, end, zone)
}

fun visibleDates(viewport: CalendarViewport): List<LocalDate> = buildList {
    var date = viewport.startDate
    while (date < viewport.endDateExclusive) { add(date); date = date.plus(1, DateTimeUnit.DAY) }
    require(size <= 42) { "Presentation viewport must remain bounded." }
}

data class CalendarCell(val date: LocalDate, val summaries: List<CalendarItem>, val overflow: Int)
fun calendarCells(projection: CalendarProjectionResult, viewport: CalendarViewport, limit: Int = 3): List<CalendarCell> {
    require(limit in 1..3)
    return visibleDates(viewport).map { date ->
        val items = projection.items.filter { it.intersectsLocalDate(date, viewport.displayTimeZone) }
        CalendarCell(date, items.take(limit), (items.size - limit).coerceAtLeast(0))
    }
}

fun calendarRow(item: CalendarItem, zone: TimeZone, focusTitle: String? = null): ScheduleRow {
    val kind = when (item.source) {
        is CalendarSourceRef.Event -> EntityKind.EVENT
        is CalendarSourceRef.CourseSession -> EntityKind.COURSE
        is CalendarSourceRef.Exam -> EntityKind.EXAM
        is CalendarSourceRef.FocusBlock -> EntityKind.FOCUS_BLOCK
    }
    val time = when (item) {
        is CalendarItem.Zoned -> "${item.originalRange.start.toLocalDateTime(zone)} – ${item.originalRange.endExclusive.toLocalDateTime(zone)}"
        is CalendarItem.AllDay -> "AllDay · ${item.range.startDate} – ${item.range.endDateExclusive} exclusive"
        is CalendarItem.DateOnly -> "DateOnly · ${item.date}"
        is CalendarItem.Floating -> "Floating · ${item.range.start} – ${item.range.endExclusive}"
    }
    val detail = when (item) {
        is CalendarItem.Zoned -> "${if (item.source is CalendarSourceRef.Exam) "Exact Exam / Zoned" else "Zoned"} · display ${zone.id} · source ${item.originalRange.timeZone.id}"
        is CalendarItem.AllDay -> "Calendar dates, not occupied instants"
        is CalendarItem.DateOnly -> "Date only, no invented time"
        is CalendarItem.Floating -> "Wall time, no timezone or instant"
    } + when (item.source) {
        is CalendarSourceRef.CourseSession -> " · derived CourseSession"
        is CalendarSourceRef.FocusBlock -> " · planned time, not completed work"
        else -> ""
    }
    return ScheduleRow(item.source.toString(), focusTitle ?: item.title, kind, time, detail)
}

/** Original range labels; no clipping, Floating-to-instant coercion or conflict logic. */
fun calendarCompactTime(item: CalendarItem, zone: TimeZone): String = when (item) {
    is CalendarItem.Zoned -> "${if (item.source is CalendarSourceRef.Exam) "Exact / Zoned" else "Zoned"} · ${item.originalRange.start.toLocalDateTime(zone).time} – ${item.originalRange.endExclusive.toLocalDateTime(zone).time}"
    is CalendarItem.Floating -> "Floating · ${item.range.start.time} – ${item.range.endExclusive.time}"
    is CalendarItem.AllDay -> "AllDay"
    is CalendarItem.DateOnly -> "DateOnly"
}

fun filteredTasks(tasks: List<Task>, filter: TaskFilter): List<Task> = tasks.filter {
    when (filter) { TaskFilter.OPEN -> it.status == TaskStatus.OPEN || it.status == TaskStatus.IN_PROGRESS; TaskFilter.COMPLETED -> it.status == TaskStatus.COMPLETED; TaskFilter.ALL -> true }
}.sortedWith(compareBy<Task> { it.title.lowercase() }.thenBy { it.id.value })

/** Explicit date/instant input, without DateOnly-to-instant coercion or urgency scores. */
fun deadlineLabel(task: Task, date: LocalDate, now: Instant): String = when (val deadline = task.deadline?.deadline) {
    null -> "No deadline"
    is Deadline.DateOnly -> "DateOnly ${deadline.date}" + when {
        deadline.date < date -> " · overdue date"; deadline.date == date -> " · due date"; else -> ""
    }
    is Deadline.Exact -> "Exact ${deadline.at.toLocalDateTime(deadline.timeZone)} · ${deadline.timeZone.id}" +
        if (deadline.at < now) " · overdue instant" else ""
}
