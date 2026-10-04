package dev.agenticscheduler.wear.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.wear.capability.*
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No execution logic: actions go to the existing shared runtime through the explicit session coordinator. */
@Composable
fun WearAgentScreen(
    session: WearAgentSessionState,
    readiness: WearReadiness,
    config: ProviderConfig?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onNew: () -> Unit,
    onConfirm: (AgentToolCallId, String, Boolean) -> Unit,
    onEnable: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSpeech: () -> Unit,
    onCancelSpeech: () -> Unit,
    onLanguage: (String) -> Unit,
    modifier: Modifier = Modifier,
    bindingChoices: @Composable () -> Unit = {},
) {
    var language by remember(readiness.capability.selectedLanguageTag) { mutableStateOf(readiness.capability.selectedLanguageTag.takeUnless { it == "und" }.orEmpty()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Agent · ${readiness.runtimeState}", Modifier.testTag("readiness"))
        Text("Input ${readiness.aiEntrySupported} · Enabled ${readiness.effectiveAiEntryEnabled}\nProvider ${readiness.providerReady} · Request ${readiness.requestReady}")
        Button(onClick = { onEnable(!readiness.userEnabledAiEntry) }, modifier = Modifier.testTag("entry-toggle")) {
            Text(if (readiness.userEnabledAiEntry) "Disable AI entry" else "Enable AI entry")
        }
        if (config == null) Text("Select and locally approve an existing Provider configuration below. Credentials must be provisioned through the approved secure install flow.")
        else {
            Text("${config.model}\n${config.baseUrl}")
            if (requiresHttpDisclosure(config)) Text("请求内容可能通过未加密 HTTP 传输", Modifier.testTag("http-warning"))
        }
        bindingChoices()
        Button(onClick = onRetry) { Text("Retry readiness") }
        Text("Conversation ${session.threadId?.value ?: "new"}")
        Button(onClick = onNew, enabled = !session.busy && session.pending == null, modifier = Modifier.testTag("new-conversation")) { Text("New conversation") }
        Text("Command draft")
        BasicTextField(session.draft, onDraft, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, MaterialTheme.colorScheme.onSurface).padding(8.dp).testTag("agent-input"))
        Button(onClick = onSend, enabled = readiness.requestReady && !session.busy && session.pending == null && session.draft.isNotBlank(), modifier = Modifier.testTag("agent-send")) { Text("Send") }
        Text("Optional on-device speech: ${readiness.capability.onDeviceSttAvailability} · ${readiness.capability.speechPermission}")
        Text("Speech language tag${if (readiness.capability.selectedLanguageTag == "und") " · not selected" else ""}")
        BasicTextField(language, { language = it }, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, MaterialTheme.colorScheme.onSurface).padding(8.dp).testTag("speech-language"))
        Button(onClick = { onLanguage(language) }, enabled = language.isNotBlank() && language != "und") { Text("Select speech language") }
        Button(onClick = onSpeech, enabled = !session.busy && language.isNotBlank() && language != "und" && readiness.capability.onDeviceSttAvailability !in setOf(OnDeviceSttAvailability.UNSUPPORTED, OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE), modifier = Modifier.testTag("speech-start")) { Text("Speech draft only") }
        Button(onClick = onCancelSpeech) { Text("Cancel speech") }
        Text("${session.phase}${session.redactedCode?.let { " · $it" }.orEmpty()}", Modifier.testTag("agent-status"))
        session.messages.forEach { Text("${it.role}: ${it.content}") }
        session.calls.forEach { Text("${it.name}: ${it.state}\n${it.previewJson}") }
        session.results.forEach { Text("Tool ${it.status}\n${it.resultJson}\nMutations: ${it.mutationIds.joinToString { id -> id.value }}", Modifier.testTag("tool-result")) }
        session.pending?.let { call ->
            Text("Exact pending action: ${call.name}\n${call.previewJson}", Modifier.testTag("confirmation-preview"))
            Button(onClick = { onConfirm(call.id, requireNotNull(call.previewJson), true) }, enabled = !session.busy, modifier = Modifier.testTag("agent-confirm")) { Text("Confirm on Watch") }
            Button(onClick = { onConfirm(call.id, requireNotNull(call.previewJson), false) }, enabled = !session.busy, modifier = Modifier.testTag("agent-deny")) { Text("Deny") }
        }
    }
}

fun requiresHttpDisclosure(config: ProviderConfig): Boolean {
    val uri = URI(config.baseUrl)
    val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    return uri.scheme.equals("http", true) && host !in setOf("localhost", "127.0.0.1", "::1")
}

@Composable
fun WearAgentRoute(composition: WearAgentRuntimeComposition) {
    val scope = rememberCoroutineScope()
    val session by composition.session.ui.collectAsState()
    val readiness by composition.readiness.readiness.collectAsState()
    var configs by remember { mutableStateOf(emptyList<ProviderConfig>()) }
    var selected by remember { mutableStateOf<ProviderConfig?>(null) }
    LaunchedEffect(composition) {
        composition.session.restoreRecentThread()
        configs = composition.state.providerConfigs()
        selected = composition.state.selectedProviderConfigId()?.let { composition.state.providerConfig(it) }
    }
    LaunchedEffect(session.busy) {
        while (session.busy) { composition.session.reload(); delay(100) }
    }
    // Provider choice is local, explicit and exact. No credential/plaintext input or inferred route approval.
    WearAgentScreen(session, readiness, selected, composition.session::editDraft,
            { scope.launch { composition.session.submitFromUserAction() } },
            { scope.launch { composition.session.newConversationFromUserAction() } },
            { call, preview, approved -> scope.launch { composition.session.confirmFromUserAction(call, preview, approved) } },
            composition.readiness.settings::setAiEntryFromExplicitUserAction,
            { scope.launch { composition.refreshSelectedBinding(); composition.readiness.refresh(true) } },
            { composition.readiness.capability.useSpeechFromExplicitUserAction(composition.session::speechCandidate) },
            composition.readiness.capability::cancel,
            { tag -> composition.readiness.settings.selectSpeechLanguageFromExplicitUserAction(tag); composition.readiness.capability.selectLanguageFromUserAction(tag) },
            bindingChoices = {
        configs.forEach { config ->
            if (config.credentialReference != null) Button(onClick = { scope.launch { composition.selectFromUserAction(config); selected = config } }) { Text("Select ${config.model}") }
            else WearEndpointRoute.entries.forEach { route -> Button(onClick = { scope.launch { composition.approveCredentialFreeFromUserAction(config, route); selected = config } }) { Text("Approve ${config.model}: $route") } }
        }
    })
}
