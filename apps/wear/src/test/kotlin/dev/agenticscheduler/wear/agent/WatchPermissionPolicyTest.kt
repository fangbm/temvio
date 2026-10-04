package dev.agenticscheduler.wear.agent

import dev.agenticscheduler.agent.permission.*
import kotlin.test.*

class WatchPermissionPolicyTest {
    @Test fun ceilingNeverLoosensAnyLocalMode() {
        for (mode in AgentPermissionMode.entries) {
            val dangerous = setOf(AgentToolCapability.BULK_CHANGE, AgentToolCapability.DESTRUCTIVE, AgentToolCapability.EXTERNAL_SIDE_EFFECT)
            val local = AgentPermissionPolicy.fromModes(AgentToolCapability.entries.associateWith { if (it in dangerous) AgentPermissionMode.DENY else mode })
            val effective = WatchPermissionPolicy.effective(local)
            for (capability in AgentToolCapability.entries) {
                assertEquals(if (capability in dangerous) AgentPermissionMode.DENY else mode, local.modeFor(capability), "No persistent/shared policy rewrite")
                val expected = when (capability) {
                    AgentToolCapability.READ, AgentToolCapability.PLAN_PREVIEW -> mode
                    AgentToolCapability.BULK_CHANGE, AgentToolCapability.DESTRUCTIVE, AgentToolCapability.EXTERNAL_SIDE_EFFECT -> AgentPermissionMode.DENY
                    else -> if (mode == AgentPermissionMode.DENY) mode else AgentPermissionMode.REQUIRE_CONFIRMATION
                }
                assertEquals(expected, effective.modeFor(capability), "$capability / $mode")
            }
        }
    }
}
