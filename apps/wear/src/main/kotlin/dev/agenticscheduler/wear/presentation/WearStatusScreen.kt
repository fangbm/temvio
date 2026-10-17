package dev.agenticscheduler.wear.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.wear.agent.requiresHttpDisclosure
import dev.agenticscheduler.wear.capability.*

@Composable
fun WearDraftField(value: String, onChange: (String) -> Unit, tag: String) {
    BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline).padding(8.dp).testTag(tag)
            .semantics { contentDescription = if (tag == "agent-input") "Command draft" else "Speech language tag" })
}

@Composable
fun WearStatusScreen(readiness: WearReadiness, config: ProviderConfig?, onEnable: (Boolean) -> Unit, onRetry: () -> Unit,
    onLanguage: (String) -> Unit, onAgent: () -> Unit, onBack: () -> Unit,
    bindingChoices: TransformingLazyColumnScope.() -> Unit = {}, syncFailure: String? = null, onRetrySync: () -> Unit = {}) {
    var language by remember(readiness.capability.selectedLanguageTag) { mutableStateOf(readiness.capability.selectedLanguageTag.takeUnless { it == "und" }.orEmpty()) }
    WearPage("wear-status") {
        item { WearHeading("Status / setup") }
        item { WearCopy(readinessCopy(readiness.runtimeState), Modifier.testTag("readiness")) }
        item { WearAction(if (readiness.userEnabledAiEntry) "Disable Agent" else "Enable Agent", "entry-toggle",
            onClick = { onEnable(!readiness.userEnabledAiEntry) }) }
        item { WearCopy(if (readiness.aiEntrySupported) "Input supported" else "Input unsupported") }
        item { WearCopy(if (readiness.effectiveAiEntryEnabled) "Entry enabled on this Watch" else "Entry is not enabled") }
        item { WearCopy(if (readiness.providerReady) "Provider configured and locally approved" else "Provider setup is not ready") }
        if (readiness.provider.bindingExists) item { WearCopy(if (readiness.provider.exactBindingApproved) "Exact Provider binding approved" else "Local binding approval required") }
        if (readiness.provider.installBlocked) item { WearCopy("Provider installation or cleanup is blocked") }
        item { WearCopy(if (!readiness.provider.bindingExists) "Select a Provider before checking credential availability" else if (!readiness.provider.credentialRequired) "No credential required by this binding" else if (readiness.provider.matchingSecretAvailable) "Required credential available" else "Required credential unavailable") }
        item { WearCopy(if (readiness.networkReachable) "Selected Provider route available" else "Selected Provider route unavailable") }
        item { WearCopy(if (readiness.requestReady) "Ready for an explicit request" else "A new request is unavailable") }
        if (readiness.structuredToolsUnavailable) item { WearCopy("Chat only. Actions are unavailable with this Provider.", Modifier.testTag("chat-only")) }
        if (config == null) item { WearCopy(if (readiness.provider.bindingExists) "Provider details are not loaded" else "No Provider selected. Existing local configurations, if available, appear below.") }
        else {
            item { WearHeading(config.model) }
            if (requiresHttpDisclosure(config)) item { WearCopy("Requests may travel unencrypted over HTTP.", Modifier.testTag("http-warning")) }
        }
        item { WearAction("Retry readiness", "readiness-retry", onClick = onRetry) }
        item { WearHeading("Provider selection") }
        bindingChoices()
        item { WearCopy("Secure credential setup is not available on this Watch yet. Existing approved Providers can still be selected.") }
        item { WearHeading("Optional speech") }
        item { WearCopy(speechCopy(readiness.capability), Modifier.testTag("speech-status")) }
        item { WearCopy(when(readiness.capability.speechPermission) {
            SpeechPermission.NOT_REQUESTED -> "Microphone permission not requested"
            SpeechPermission.DENIED -> "Microphone permission denied"
            SpeechPermission.GRANTED -> "Microphone permission granted"
        }) }
        item { WearCopy("Language tag (for example, en-US). No language is selected until you choose it.") }
        item { WearDraftField(language, { language = it }, "speech-language") }
        item { WearAction("Select speech language", "speech-language-select", enabled = language.isNotBlank() && language != "und", onClick = { onLanguage(language) }) }
        if (syncFailure != null) {
            item { WearCopy("Sync stopped · $syncFailure") }
            item { WearAction("Retry sync", "sync-retry", onClick = onRetrySync) }
        }
        item { WearAction("Agent", "status-agent", onClick = onAgent) }
        item { WearAction("Back", "wear-back", onClick = onBack) }
    }
}
