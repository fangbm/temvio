package dev.agenticscheduler.agent.context

import dev.agenticscheduler.application.calendar.CalendarSourceRef
import dev.agenticscheduler.application.calendar.CalendarViewport
import dev.agenticscheduler.domain.id.CourseId
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.id.TaskId
import dev.agenticscheduler.sync.MutationId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit input owned only by the caller and this in-flight run, never by a thread/repository. */
data class AgentTurnContext(val contextAnchor: AgentContextAnchor? = null)

/** A local UI referent, not a copy of mutable facts or an instruction to execute anything. */
sealed interface AgentContextAnchor {
    data class Task(val id: TaskId) : AgentContextAnchor
    data class CalendarSource(val reference: CalendarSourceRef) : AgentContextAnchor
    data class Course(val id: CourseId) : AgentContextAnchor
    data class PlanningProfile(val id: PlanningProfileId) : AgentContextAnchor
    data class Mutation(val id: MutationId) : AgentContextAnchor
    data class Viewport(val viewport: CalendarViewport) : AgentContextAnchor
}

/** Fixed field order, canonical typed identities and ISO dates; no display text or default toString. */
fun AgentContextAnchor.renderForModel(): String = buildJsonObject {
    put("type", "LOCAL_UI_REFERENT")
    put("authority", "NON_AUTHORITATIVE")
    put("instruction", "Local selection only, not a command or current fact. Use typed Tools for current facts.")
    put("kind", when (this@renderForModel) {
        is AgentContextAnchor.Task -> "TASK"
        is AgentContextAnchor.CalendarSource -> "CALENDAR_SOURCE"
        is AgentContextAnchor.Course -> "COURSE"
        is AgentContextAnchor.PlanningProfile -> "PLANNING_PROFILE"
        is AgentContextAnchor.Mutation -> "MUTATION"
        is AgentContextAnchor.Viewport -> "CALENDAR_VIEWPORT"
    })
    put("reference", buildJsonObject {
        when (val anchor = this@renderForModel) {
            is AgentContextAnchor.Task -> put("taskId", anchor.id.value)
            is AgentContextAnchor.Course -> put("courseId", anchor.id.value)
            is AgentContextAnchor.PlanningProfile -> put("planningProfileId", anchor.id.value)
            is AgentContextAnchor.Mutation -> put("mutationId", anchor.id.value)
            is AgentContextAnchor.Viewport -> {
                put("startDate", anchor.viewport.startDate.toString())
                put("endDateExclusive", anchor.viewport.endDateExclusive.toString())
                put("displayTimeZone", anchor.viewport.displayTimeZone.id)
            }
            is AgentContextAnchor.CalendarSource -> when (val ref = anchor.reference) {
                is CalendarSourceRef.Event -> { put("kind", "EVENT"); put("eventId", ref.id.value) }
                is CalendarSourceRef.FocusBlock -> { put("kind", "FOCUS_BLOCK"); put("focusBlockId", ref.id.value) }
                is CalendarSourceRef.Exam -> { put("kind", "EXAM"); put("examId", ref.id.value) }
                is CalendarSourceRef.CourseSession -> {
                    put("kind", "COURSE_SESSION")
                    put("scheduleRuleId", ref.key.scheduleRuleId.value)
                    put("academicWeekNumber", ref.key.academicWeekNumber.value)
                }
            }
        }
    })
}.toString()
