package dev.agenticscheduler.desktop

import androidx.compose.runtime.mutableStateOf
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.domain.event.Event
import dev.agenticscheduler.domain.task.Task
import kotlinx.datetime.*

/** Finite viewport and local editor selection. Authoritative facts come from Application. */
internal class DesktopScheduleScreenCoordinator(
    val reads: ConflictAwareSourceFactReadService,
    initialDate: LocalDate,
    val displayTimeZone: TimeZone,
) {
    val selectedDate = mutableStateOf(initialDate)
    val editingEvent = mutableStateOf<Event?>(null)
    val editingTask = mutableStateOf<Task?>(null)
    val creatingEvent = mutableStateOf(false)
    val creatingTask = mutableStateOf(false)
    fun calendar(viewport: CalendarViewport) = reads.observe(viewport)
    fun tasks() = reads.observeTasks()
    fun focusBlocks() = reads.observeFocusBlocks()
}
