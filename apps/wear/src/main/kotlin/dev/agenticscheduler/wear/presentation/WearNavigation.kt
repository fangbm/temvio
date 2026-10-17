package dev.agenticscheduler.wear.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.agenticscheduler.agent.history.AgentToolCall

enum class WearDestination { TODAY, AGENT, STATUS }

/** Presentation only. Closing an overlay never changes its persisted Tool. */
class WearNavigation(initial: WearDestination = WearDestination.TODAY) {
    var destination by mutableStateOf(initial); private set
    var confirmation by mutableStateOf<AgentToolCall?>(null); private set
    private var statusReturn = WearDestination.TODAY
    fun open(value: WearDestination) {
        if (value == WearDestination.STATUS && destination != value) statusReturn = destination
        confirmation = null
        destination = value
    }
    fun review(call: AgentToolCall) { confirmation = call }
    fun closeConfirmation() { confirmation = null }
    val canGoBack get() = confirmation != null || destination != WearDestination.TODAY
    fun back(): Boolean = when {
        confirmation != null -> { closeConfirmation(); true }
        destination == WearDestination.STATUS -> { destination = statusReturn; true }
        destination == WearDestination.AGENT -> { destination = WearDestination.TODAY; true }
        else -> false
    }
}
