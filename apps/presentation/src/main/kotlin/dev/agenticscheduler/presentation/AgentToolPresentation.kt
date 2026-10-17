package dev.agenticscheduler.presentation

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.*
import kotlinx.serialization.json.*

data class AgentDisplayFact(val label: String, val value: String)

/** Rendering the existing normalized snapshot, never constructing input or recomputing legality. */
fun agentSnapshotFacts(snapshot: String?): List<AgentDisplayFact> {
    if (snapshot == null) return listOf(AgentDisplayFact("Preview", "No normalized preview recorded"))
    val element = runCatching { Json.parseToJsonElement(snapshot) }.getOrNull()
        ?: return listOf(AgentDisplayFact("Details", "Snapshot cannot be displayed. Technical detail remains available."))
    val facts = mutableListOf<AgentDisplayFact>()
    fun visit(value: JsonElement, path: String) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                if (key.endsWith("ImageJson") && child is JsonPrimitive && child.isString) {
                    val nested = runCatching { Json.parseToJsonElement(child.content) }.getOrNull()
                    if (nested != null) { visit(nested, listOf(path, key.removeSuffix("ImageJson")).filter { it.isNotBlank() }.joinToString(" · ")); return@forEach }
                }
                if (key != "id" && !key.endsWith("Id") && !key.endsWith("Ids"))
                    visit(child, listOf(path, readableAgentLabel(key)).filter { it.isNotBlank() }.joinToString(" · "))
            }
            is JsonArray -> if (value.isEmpty()) facts += AgentDisplayFact(path, "None") else
                value.forEachIndexed { index, child -> visit(child, "$path ${index + 1}") }
            JsonNull -> facts += AgentDisplayFact(path, "None")
            is JsonPrimitive -> facts += AgentDisplayFact(path.ifBlank { "Value" }, value.content)
        }
    }
    visit(element, "")
    return facts.ifEmpty { listOf(AgentDisplayFact("Details", "Identity references are available in technical details.")) }
}

fun readableAgentLabel(value: String): String = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ')
    .lowercase().replaceFirstChar { it.uppercase() }

fun agentToolTitle(name: String): String = when (name) {
    "task.get" -> "Read task"; "task.list" -> "List tasks"; "calendar.list" -> "Read calendar"
    "task.create" -> "Create task"; "task.update" -> "Update task"
    "event.create" -> "Create event"; "event.update" -> "Update event"
    "planningProfile.update" -> "Update planning profile"
    "planner.previewFullReplan" -> "Full replan proposal"; "planner.previewLocalReflow" -> "Local reflow proposal"
    "planner.applyBranch" -> "Apply Planner proposal"; "history.undo" -> "Compensating Undo"
    "history.timeline" -> "Read mutation history"; "history.getMutation" -> "Read mutation"
    "history.getEntityChanges" -> "Read entity changes"; else -> name
}

fun agentToolCapability(name: String): AgentToolCapability? = when (name) {
    "task.get", "task.list", "calendar.list", "history.timeline", "history.getMutation", "history.getEntityChanges" -> AgentToolCapability.READ
    "task.create", "event.create" -> AgentToolCapability.LOW_RISK_CREATE
    "task.update", "event.update" -> AgentToolCapability.SOURCE_FACT_UPDATE
    "planningProfile.update" -> AgentToolCapability.PLANNING_PROFILE_CHANGE
    "planner.previewFullReplan", "planner.previewLocalReflow" -> AgentToolCapability.PLAN_PREVIEW
    "planner.applyBranch" -> AgentToolCapability.SCHEDULE_APPLY
    "history.undo" -> AgentToolCapability.UNDO
    else -> null
}

fun agentResultCopy(status: AgentToolResultStatus): String = when (status) {
    AgentToolResultStatus.SUCCESS -> "Tool completed. Only recorded mutation links below prove a business commit."
    AgentToolResultStatus.INVALID_INPUT -> "Invalid input. Correct the proposal; no successful write is recorded."
    AgentToolResultStatus.NOT_FOUND -> "Requested source fact was not found."
    AgentToolResultStatus.PERMISSION_DENIED -> "Permission denied. This Tool performed no business write."
    AgentToolResultStatus.STALE -> "Preview is stale. Request a new proposal; do not force apply."
    AgentToolResultStatus.UNSUPPORTED -> "This action is unsupported by the existing capability."
    AgentToolResultStatus.CONFLICT -> "An unresolved conflict blocks this action."
    AgentToolResultStatus.INFEASIBLE -> "Planner could not produce a feasible proposal."
    AgentToolResultStatus.INFRASTRUCTURE_FAILURE -> "Infrastructure failure. No successful commit is implied; inspect recorded audit facts."
}
