package dev.agenticscheduler.android

import dev.agenticscheduler.application.calendar.CalendarConflict
import dev.agenticscheduler.application.calendar.CalendarItem
import dev.agenticscheduler.application.calendar.CalendarProjectionIssue
import dev.agenticscheduler.application.calendar.CalendarProjectionResult
import dev.agenticscheduler.application.editing.EventTimeInput
import dev.agenticscheduler.application.editing.TaskDeadlineInput
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.planning.Deadline
import dev.agenticscheduler.domain.planning.DeadlinePolicy
import dev.agenticscheduler.domain.planning.Flexibility
import dev.agenticscheduler.domain.planning.OverflowPolicy
import dev.agenticscheduler.domain.planning.PinState
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.task.TaskPriority
import dev.agenticscheduler.domain.task.TaskStatus
import dev.agenticscheduler.domain.time.AllDayRange
import dev.agenticscheduler.domain.time.FloatingTimeRange
import dev.agenticscheduler.domain.time.ZonedTimeRange
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

internal enum class EventKind { ZONED, ALL_DAY, FLOATING }
internal enum class DeadlineKind { DATE_ONLY, EXACT }

internal fun Event?.eventKind(): EventKind = when (this?.time) {
    is ZonedTimeRange, null -> EventKind.ZONED
    is AllDayRange -> EventKind.ALL_DAY
    is FloatingTimeRange -> EventKind.FLOATING
}

internal fun Event?.startText(selectedDate: LocalDate): String = when (val time = this?.time) {
    is ZonedTimeRange -> time.start.toLocalDateTime(time.timeZone).toString()
    is AllDayRange -> time.startDate.toString()
    is FloatingTimeRange -> time.start.toString()
    null -> if (eventKind() == EventKind.ALL_DAY) selectedDate.toString() else ""
}

internal fun Event?.endText(selectedDate: LocalDate): String = when (val time = this?.time) {
    is ZonedTimeRange -> time.endExclusive.toLocalDateTime(time.timeZone).toString()
    is AllDayRange -> time.endDateExclusive.toString()
    is FloatingTimeRange -> time.endExclusive.toString()
    null -> if (eventKind() == EventKind.ALL_DAY) selectedDate.plus(1, DateTimeUnit.DAY).toString() else ""
}

internal fun Event?.timeZoneText(default: TimeZone): String = (this?.time as? ZonedTimeRange)?.timeZone?.id ?: default.id
internal fun EventKind.next(): EventKind = EventKind.entries[(ordinal + 1) % EventKind.entries.size]
internal fun Flexibility.next(): Flexibility = Flexibility.entries[(ordinal + 1) % Flexibility.entries.size]
internal fun PinState.next(): PinState = PinState.entries[(ordinal + 1) % PinState.entries.size]
internal fun TaskStatus.next(): TaskStatus = TaskStatus.entries[(ordinal + 1) % TaskStatus.entries.size]
internal fun TaskPriority.next(): TaskPriority = TaskPriority.entries[(ordinal + 1) % TaskPriority.entries.size]
internal fun DeadlinePolicy.next(): DeadlinePolicy = DeadlinePolicy.entries[(ordinal + 1) % DeadlinePolicy.entries.size]
internal fun OverflowPolicy.next(): OverflowPolicy = OverflowPolicy.entries[(ordinal + 1) % OverflowPolicy.entries.size]
internal fun DeadlineKind.next(): DeadlineKind = DeadlineKind.entries[(ordinal + 1) % DeadlineKind.entries.size]

internal fun parseEventTime(kind: EventKind, start: String, end: String, zone: String): EventTimeInput? = when (kind) {
    EventKind.ZONED -> runCatching { EventTimeInput.Zoned(LocalDateTime.parse(start), LocalDateTime.parse(end), TimeZone.of(zone)) }.getOrNull()
    EventKind.ALL_DAY -> runCatching { EventTimeInput.AllDay(LocalDate.parse(start), LocalDate.parse(end)) }.getOrNull()
    EventKind.FLOATING -> runCatching { EventTimeInput.Floating(LocalDateTime.parse(start), LocalDateTime.parse(end)) }.getOrNull()
}

internal fun Task?.deadlineKind(): DeadlineKind? = when (this?.deadline?.deadline) {
    is Deadline.DateOnly -> DeadlineKind.DATE_ONLY
    is Deadline.Exact -> DeadlineKind.EXACT
    null -> null
}

internal fun Task?.deadlineValue(): String = when (val deadline = this?.deadline?.deadline) {
    is Deadline.DateOnly -> deadline.date.toString()
    is Deadline.Exact -> deadline.at.toLocalDateTime(deadline.timeZone).toString()
    null -> ""
}

internal fun Task?.deadlineZone(): String = (this?.deadline?.deadline as? Deadline.Exact)?.timeZone?.id ?: ""
internal fun parseOptionalDuration(text: String): Duration? = if (text.isBlank()) null else runCatching { Duration.parse(text) }.getOrNull()
internal fun parseRequiredDuration(text: String): Duration? = runCatching { Duration.parse(text) }.getOrNull()

internal fun parseDeadline(enabled: Boolean, kind: DeadlineKind?, value: String, zone: String, policy: DeadlinePolicy, overflow: OverflowPolicy): TaskDeadlineInput? {
    if (!enabled) return null
    return when (kind) {
        DeadlineKind.DATE_ONLY -> runCatching { TaskDeadlineInput.DateOnly(LocalDate.parse(value), policy, overflow) }.getOrNull()
        DeadlineKind.EXACT -> runCatching { TaskDeadlineInput.Exact(LocalDateTime.parse(value), TimeZone.of(zone), policy, overflow) }.getOrNull()
        null -> null
    }
}

internal fun emptyProjection() = CalendarProjectionResult(
    emptyList<CalendarItem>().toImmutableList(),
    emptyList<CalendarConflict>().toImmutableList(),
    emptyList<CalendarProjectionIssue>().toImmutableList(),
)

/** One local database can only emit Agent-origin payload v2 after its sole ACTIVE replica opted in. */
