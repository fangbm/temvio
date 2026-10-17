package dev.agenticscheduler.wear.presentation

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.calendar.*
import dev.agenticscheduler.application.history.ConflictAwareRead
import dev.agenticscheduler.wear.agent.WearAgentSessionState
import dev.agenticscheduler.wear.agent.WearAgentUiPhase
import dev.agenticscheduler.wear.capability.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

fun readinessCopy(value: WearProviderRuntimeState): String = when (value) {
    WearProviderRuntimeState.ENTRY_UNSUPPORTED -> "Agent input is not supported on this Watch"
    WearProviderRuntimeState.ENTRY_DISABLED -> "Agent is turned off on this Watch"
    WearProviderRuntimeState.NO_BINDING -> "Choose a Provider for this Watch"
    WearProviderRuntimeState.BINDING_APPROVAL_REQUIRED -> "Approve this Provider on the Watch"
    WearProviderRuntimeState.CREDENTIAL_UNAVAILABLE -> "Provider credential is unavailable"
    WearProviderRuntimeState.INSTALL_BLOCKED -> "Provider setup needs attention"
    WearProviderRuntimeState.OFFLINE -> "Watch is offline"
    WearProviderRuntimeState.READY -> "Ready"
    WearProviderRuntimeState.PROVIDER_UNAVAILABLE -> "Provider is unavailable"
}

fun phaseCopy(value: WearAgentUiPhase): String = when (value) {
    WearAgentUiPhase.IDLE -> "Ready for your command"
    WearAgentUiPhase.THINKING -> "Thinking…"
    WearAgentUiPhase.TOOL_PROPOSED -> "Action proposed"
    WearAgentUiPhase.TOOL_RUNNING -> "Action running"
    WearAgentUiPhase.CONFIRMATION_REQUIRED -> "Your confirmation is required"
    WearAgentUiPhase.TOOL_SUCCESS -> "Tool succeeded"
    WearAgentUiPhase.TOOL_FAILED -> "Tool failed"
    WearAgentUiPhase.PERMISSION_DENIED -> "Permission denied"
    WearAgentUiPhase.STALE -> "Proposal is stale"
    WearAgentUiPhase.CONFLICT -> "Action blocked by conflict"
    WearAgentUiPhase.INFEASIBLE -> "No feasible plan"
    WearAgentUiPhase.PROVIDER_UNAVAILABLE -> "Provider response unavailable"
}
fun sessionStatusCopy(session: WearAgentSessionState): String =
    if (!session.busy && session.phase in setOf(WearAgentUiPhase.THINKING,WearAgentUiPhase.TOOL_PROPOSED,WearAgentUiPhase.TOOL_RUNNING))
        "Request stopped. Review recorded action results."
    else phaseCopy(session.phase)

fun toolTitle(name: String): String = when (name) {
    "task.create" -> "Create task"; "task.update" -> "Update task"
    "event.create" -> "Create event"; "event.update" -> "Update event"
    "task.get" -> "Read task"; "task.list" -> "List tasks"; "calendar.list" -> "Read calendar"
    "planningProfile.update" -> "Update planning profile"
    "planner.previewFullReplan" -> "Full replan proposal"; "planner.previewLocalReflow" -> "Local reflow proposal"
    "planner.applyBranch" -> "Apply Planner proposal"; "history.undo" -> "Compensating Undo"
    "history.timeline" -> "Read history"; "history.getMutation" -> "Read mutation"
    "history.getEntityChanges" -> "Read entity changes"; else -> "Recorded action"
}
fun toolStateCopy(value: AgentToolCallState): String = when (value) {
    AgentToolCallState.PROPOSED -> "Proposed"; AgentToolCallState.WAITING_CONFIRMATION -> "Waiting confirmation"
    AgentToolCallState.RUNNING -> "Running"; AgentToolCallState.COMPLETED -> "Completed"
    AgentToolCallState.FAILED -> "Failed"; AgentToolCallState.DENIED -> "Denied"
}
fun toolResultCopy(value: AgentToolResultStatus): String = when (value) {
    AgentToolResultStatus.SUCCESS -> "Succeeded"; AgentToolResultStatus.INVALID_INPUT -> "Invalid input"
    AgentToolResultStatus.NOT_FOUND -> "Source not found"; AgentToolResultStatus.PERMISSION_DENIED -> "Permission denied"
    AgentToolResultStatus.STALE -> "Stale · request a new proposal"; AgentToolResultStatus.UNSUPPORTED -> "Unsupported action"
    AgentToolResultStatus.CONFLICT -> "Conflict · action blocked"; AgentToolResultStatus.INFEASIBLE -> "No feasible plan"
    AgentToolResultStatus.INFRASTRUCTURE_FAILURE -> "Tool infrastructure failure"
}

data class WearPreviewFact(val label: String, val value: String)
/** Display availability only, never an execution/permission decision. */
fun hasReadablePreview(snapshot: String?): Boolean {
    val value = snapshot?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return false
    fun renderable(element: JsonElement, depth: Int): Boolean {
        if (depth > 16) return false
        return when(element) {
            is JsonObject -> element.all { (key,child) ->
                if(key.endsWith("ImageJson") && child is JsonPrimitive && child.isString) {
                    val nested=runCatching {Json.parseToJsonElement(child.content)}.getOrNull()
                    nested != null && renderable(nested,depth+1)
                } else renderable(child,depth+1)
            }
            is JsonArray -> element.all {renderable(it,depth+1)}
            else -> true
        }
    }
    return value.keys.any { it != "id" && !it.endsWith("Id") && !it.endsWith("Ids") } && renderable(value,0)
}
/** Display of the exact recorded snapshot. No parsing into a Tool input or legality decision. */
fun previewFacts(snapshot: String?): List<WearPreviewFact> {
    val root = snapshot?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?: return listOf(WearPreviewFact("Preview", "Recorded details are unavailable. Confirmation must be reviewed on this Watch."))
    val facts = mutableListOf<WearPreviewFact>()
    fun visit(element: JsonElement, path: String, depth: Int) {
        if (depth > 16) { facts += WearPreviewFact(path, "Nested details cannot be displayed on this Watch"); return }
        when (element) {
            is JsonObject -> element.forEach { (key, child) ->
                if (key == "id" || key.endsWith("Id") || key.endsWith("Ids")) return@forEach
                val label = key.removeSuffix("ImageJson").replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                val next = listOf(path, label).filter(String::isNotBlank).joinToString(" · ")
                val nested = if (key.endsWith("ImageJson") && child is JsonPrimitive && child.isString)
                    runCatching { Json.parseToJsonElement(child.content) }.getOrNull() else null
                visit(nested ?: child, next, depth + 1)
            }
            is JsonArray -> if (element.isEmpty()) facts += WearPreviewFact(path, "None")
                else element.forEachIndexed { index, child -> visit(child, "$path ${index + 1}", depth + 1) }
            JsonNull -> facts += WearPreviewFact(path, "None")
            is JsonPrimitive -> facts += WearPreviewFact(path.ifBlank { "Value" }, element.content)
        }
    }
    visit(root, "", 0)
    return facts.ifEmpty { listOf(WearPreviewFact("Preview", "Only identity references recorded; action details are unavailable")) }
}

/** Optional reference inspection only. Never a Tool input or Provider/credential debug viewer. */
fun previewReferenceFacts(snapshot: String?): List<WearPreviewFact> {
    val root=snapshot?.let {runCatching {Json.parseToJsonElement(it) as? JsonObject}.getOrNull()} ?: return emptyList()
    val labels=mapOf("id" to "Recorded identity","taskId" to "Task reference","focusBlockId" to "Focus block reference",
        "entityId" to "Entity reference","mutationId" to "Mutation reference","planBranchId" to "Planner proposal reference",
        "planningProfileId" to "Planning profile reference","profileId" to "Planning profile reference")
    val facts=mutableListOf<WearPreviewFact>()
    fun visit(value:JsonElement,path:String,depth:Int) {
        if(depth>16)return
        when(value){
            is JsonObject -> value.forEach { (key,child) ->
                val label=labels[key]
                if(label!=null && child is JsonPrimitive && child!=JsonNull) facts+=WearPreviewFact(listOf(path,label).filter(String::isNotBlank).joinToString(" · "),child.content)
                else if(key.endsWith("ImageJson") && child is JsonPrimitive && child.isString) {
                    runCatching {Json.parseToJsonElement(child.content)}.getOrNull()?.let{visit(it,key.removeSuffix("ImageJson"),depth+1)}
                } else if(child is JsonObject || child is JsonArray) visit(child,listOf(path,key).filter(String::isNotBlank).joinToString(" · "),depth+1)
            }
            is JsonArray -> value.forEachIndexed{index,child->visit(child,"$path ${index+1}",depth+1)}
            else -> Unit
        }
    }
    visit(root,"",0);return facts
}

data class WearScheduleRow(val source: CalendarSourceRef, val title: String, val kind: String, val time: String)
fun scheduleRow(item: CalendarItem, zone: TimeZone): WearScheduleRow {
    val kind = when (item.source) {
        is CalendarSourceRef.Event -> "Event"; is CalendarSourceRef.FocusBlock -> "Focus"
        is CalendarSourceRef.CourseSession -> "Course"; is CalendarSourceRef.Exam -> "Exam"
    }
    val time = when (item) {
        is CalendarItem.Zoned -> {
            val start = item.originalRange.start.toLocalDateTime(zone)
            val end = item.originalRange.endExclusive.toLocalDateTime(zone)
            "${start.date} · ${start.time}–${if (start.date != end.date) "${end.date} · " else ""}${end.time} · ${zone.id}"
        }
        is CalendarItem.AllDay -> "All day · ${item.range.startDate}–${item.range.endDateExclusive} exclusive"
        is CalendarItem.Floating -> "Floating · ${item.range.start}–${item.range.endExclusive}"
        is CalendarItem.DateOnly -> "Date only · ${item.date}"
    }
    return WearScheduleRow(item.source, item.title, kind, time)
}
data class WearTodayModel(val today: LocalDate, val rows: List<WearScheduleRow>, val moreCount: Int,
    val overlapCount: Int, val syncConflictCount: Int, val issueCount: Int)
fun readConflictIds(read: ConflictAwareRead<*>?): List<String> = when(read) {
    is ConflictAwareRead.Projected<*> -> read.syncConflictRefs.flatMap { it.conflictIds }
    is ConflictAwareRead.Unprojectable -> read.conflictIds
    null -> emptyList()
}
fun todayModel(today: LocalDate, zone: TimeZone, projection: CalendarProjectionResult, additionalConflictIds: List<String> = emptyList()): WearTodayModel {
    // Preserve authoritative projection order. The existing query owns recurrence and overlap truth.
    val ordered = projection.items.filter { it.intersectsLocalDate(today, zone) } + projection.items.filterNot { it.intersectsLocalDate(today, zone) }
    return WearTodayModel(today, ordered.take(3).map { scheduleRow(it, zone) }, (ordered.size - 3).coerceAtLeast(0),
        projection.conflicts.size, (projection.syncConflictRefs.flatMap { it.conflictIds } + additionalConflictIds +
            projection.issues.filterIsInstance<CalendarProjectionIssue.SyncConflictUnprojectable>().flatMap { it.conflictIds }).distinct().size, projection.issues.size)
}

fun speechCopy(facts: WearCapabilityFacts): String = when {
    facts.selectedLanguageTag == "und" -> "Choose a speech language. Text input is available independently."
    facts.speechPermission == SpeechPermission.DENIED -> "Microphone permission denied. Text input is unaffected."
    facts.onDeviceSttAvailability == OnDeviceSttAvailability.UNSUPPORTED -> "On-device speech service or language is unavailable. Use text input."
    facts.onDeviceSttAvailability == OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE -> "On-device speech is temporarily unavailable. Use text input."
    facts.speechPermission == SpeechPermission.NOT_REQUESTED -> "Microphone permission not requested. Only a speech action can request it."
    facts.onDeviceSttAvailability == OnDeviceSttAvailability.LANGUAGE_UNVERIFIED -> "Speech language is unverified. An explicit speech attempt may fail."
    else -> "On-device speech available for ${facts.selectedLanguageTag}"
}
