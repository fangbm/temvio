package dev.agenticscheduler.wear.agent

import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.permission.*

/** C8 is a local composition ceiling; it never persists a relaxed/rewritten shared policy. */
object WatchPermissionPolicy {
    fun effective(local: AgentPermissionPolicy): AgentPermissionPolicy = AgentPermissionPolicy.fromModes(
        AgentToolCapability.entries.associateWith { capability ->
            val mode = local.modeFor(capability)
            when (capability) {
                AgentToolCapability.READ, AgentToolCapability.PLAN_PREVIEW -> mode
                AgentToolCapability.BULK_CHANGE, AgentToolCapability.DESTRUCTIVE, AgentToolCapability.EXTERNAL_SIDE_EFFECT -> AgentPermissionMode.DENY
                else -> if (mode == AgentPermissionMode.DENY) mode else AgentPermissionMode.REQUIRE_CONFIRMATION
            }
        },
    )
}

class WatchAgentState(private val local: AgentStateRepository,
    private val newProposalAuthorized: suspend () -> Boolean = { true },
) : AgentStateRepository by local {
    override suspend fun permissionPolicy(): AgentPermissionPolicy {
        val ceiling = WatchPermissionPolicy.effective(local.permissionPolicy())
        if (newProposalAuthorized()) return ceiling
        return AgentPermissionPolicy.fromModes(AgentToolCapability.entries.associateWith {
            if (it == AgentToolCapability.READ || it == AgentToolCapability.PLAN_PREVIEW) ceiling.modeFor(it) else AgentPermissionMode.DENY
        })
    }
}
