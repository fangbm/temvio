package dev.agenticscheduler.desktop

import androidx.compose.runtime.*
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.provider.ProviderProbeResult
import dev.agenticscheduler.ui.StatusMessage

/** Last explicit request's typed observation. It never decides request/Tool legality. */
internal class ProviderProbePresentation {
    var observation by mutableStateOf<Pair<ProviderConfig, ProviderProbeResult>?>(null)
        private set
    fun record(config: ProviderConfig, result: ProviderProbeResult) { observation = config to result }
}

@Composable
internal fun ProviderCapabilityStatus(config: ProviderConfig?, probes: ProviderProbePresentation?) {
    val result = probes?.observation?.takeIf { it.first == config }?.second
    when {
        result == ProviderProbeResult.Unsupported || config?.toolCallingSupported == false ->
            StatusMessage("Chat only", "Structured Tools unavailable. Prose never authorizes a business write.")
        result is ProviderProbeResult.Unavailable ->
            StatusMessage("Provider unavailable", "Last explicit request: ${result.redactedCode}. Retry remains explicit.")
        result == ProviderProbeResult.Supported ->
            StatusMessage("Structured Tools", "Supported on the last explicit request. Permission and confirmation still govern every write.")
        else -> Unit
    }
}
