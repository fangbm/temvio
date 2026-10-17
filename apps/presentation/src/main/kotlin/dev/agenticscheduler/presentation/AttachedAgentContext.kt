package dev.agenticscheduler.presentation

import dev.agenticscheduler.agent.context.AgentContextAnchor
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.domain.id.*
import dev.agenticscheduler.domain.task.Task
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.sync.MutationId

/** Ephemeral app presentation; the label is never part of the runtime referent. */
data class AttachedAgentContext(val anchor: AgentContextAnchor, val label: String)

fun taskAgentContext(id: TaskId, title: String = "Selected Task") =
    AttachedAgentContext(AgentContextAnchor.Task(id), "Task · $title")

fun calendarAgentContext(source: CalendarSourceRef, title: String? = null): AttachedAgentContext {
    val kind = when (source) {
        is CalendarSourceRef.Event -> "Event"
        is CalendarSourceRef.FocusBlock -> "FocusBlock"
        is CalendarSourceRef.CourseSession -> "CourseSession"
        is CalendarSourceRef.Exam -> "Exam"
    }
    return AttachedAgentContext(AgentContextAnchor.CalendarSource(source), title?.let { "$kind · $it" } ?: "Selected $kind")
}

fun viewportAgentContext(viewport: CalendarViewport) = AttachedAgentContext(
    AgentContextAnchor.Viewport(viewport), "Calendar · ${viewport.startDate} – ${viewport.endDateExclusive} exclusive · ${viewport.displayTimeZone.id}")

/** The platform root supplies only its current screen's existing selection and finite viewport. */
fun coreAgentContext(selection: CoreSelection?, viewport: CalendarViewport?, tasks: List<Task>, projection: CalendarProjectionResult?): AttachedAgentContext? =
    when (selection) {
        is CoreSelection.TaskDetail -> taskAgentContext(selection.id, tasks.firstOrNull { it.id == selection.id }?.title ?: "Selected Task")
        is CoreSelection.Calendar -> calendarAgentContext(selection.source, projection?.items?.firstOrNull { it.source == selection.source }?.title)
        null -> viewport?.let(::viewportAgentContext)
    }

fun AcademicRecord.agentContext(): AttachedAgentContext? = when (this) {
    is AcademicRecord.Subject -> AttachedAgentContext(AgentContextAnchor.Course(value.id), "Course · ${value.name}")
    is AcademicRecord.Assessment -> calendarAgentContext(CalendarSourceRef.Exam(value.id), value.title)
    else -> null // No approved anchor for AcademicYear, Semester, PeriodTemplate or schedule-rule selection.
}

fun planningProfileAgentContext(profile: PlanningProfile) =
    AttachedAgentContext(AgentContextAnchor.PlanningProfile(profile.id), "Planning profile · ${profile.name}")

fun PlannerWorkspaceCoordinator.agentContext(): AttachedAgentContext? = profiles.firstOrNull { it.id == draft.profileId }?.let(::planningProfileAgentContext)

fun HistoryDetail.agentContext() =
    AttachedAgentContext(AgentContextAnchor.Mutation(MutationId(mutation.operation.mutationId)), "History · Selected mutation")

fun HistoryScreenCoordinator.agentContext(): AttachedAgentContext? = detail?.agentContext()
