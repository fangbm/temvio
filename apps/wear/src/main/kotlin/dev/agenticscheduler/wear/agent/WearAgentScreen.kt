package dev.agenticscheduler.wear.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.wear.capability.*
import dev.agenticscheduler.wear.presentation.*
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Presentation intents delegate to the existing session; no second Agent runtime. */
@Composable
fun WearAgentScreen(
    session: WearAgentSessionState, readiness: WearReadiness, config: ProviderConfig?,
    onDraft: (String) -> Unit, onSend: () -> Unit, onNew: () -> Unit,
    onConfirm: (AgentToolCallId, String, Boolean) -> Unit,
    onEnable: (Boolean) -> Unit, onRetry: () -> Unit, onSpeech: () -> Unit,
    onCancelSpeech: () -> Unit, onLanguage: (String) -> Unit,
    modifier: Modifier = Modifier, bindingChoices: TransformingLazyColumnScope.() -> Unit = {},
    navigation: WearNavigation? = null, threadTitle: String? = null,
    onLeaveForeground: () -> Unit = {}, syncFailure: String? = null, onRetrySync: () -> Unit = {},
) {
    val nav = navigation ?: remember { WearNavigation(WearDestination.AGENT) }
    fun leave() { onLeaveForeground(); onCancelSpeech() }
    fun back() { leave(); nav.back() }
    fun open(value: WearDestination) { if (nav.destination == WearDestination.AGENT) leave(); nav.open(value) }
    BackHandler(nav.canGoBack) { back() }
    LaunchedEffect(session.pending?.id) {
        val pending = session.pending
        if (pending != null && nav.destination == WearDestination.AGENT) nav.review(pending)
        else if (pending == null) nav.closeConfirmation()
    }
    SwipeToDismissBox(onDismissed = { back() }, userSwipeEnabled = nav.canGoBack, modifier = modifier) { background ->
        if (!background) {
            val pending = nav.confirmation
            when {
                pending != null -> WearConfirmationScreen(pending, session.busy,
                    { approved -> onConfirm(pending.id, requireNotNull(pending.previewJson), approved) }, { back() })
                nav.destination == WearDestination.STATUS -> WearStatusScreen(readiness, config, onEnable, onRetry, onLanguage,
                    { open(WearDestination.AGENT) }, { back() }, bindingChoices, syncFailure, onRetrySync)
                else -> WearAgentConversation(session, readiness, config, threadTitle, onDraft, onSend, onNew,
                    { session.pending?.let(nav::review) }, { open(WearDestination.STATUS) }, { back() }, onSpeech, onCancelSpeech)
            }
        }
    }
}

@Composable
private fun WearAgentConversation(session: WearAgentSessionState, readiness: WearReadiness, config: ProviderConfig?,
    title: String?, onDraft: (String) -> Unit, onSend: () -> Unit, onNew: () -> Unit, onReview: () -> Unit,
    onSetup: () -> Unit, onBack: () -> Unit, onSpeech: () -> Unit, onCancelSpeech: () -> Unit) {
    WearPage("wear-agent") {
        item { WearHeading("Agent") }
        item { WearCopy(readinessCopy(readiness.runtimeState), Modifier.testTag("readiness")) }
        if (readiness.structuredToolsUnavailable) item { WearCopy("Chat only. Actions are unavailable with this Provider.", Modifier.testTag("chat-only")) }
        if (config != null && requiresHttpDisclosure(config)) item { WearCopy("Requests may travel unencrypted over HTTP.", Modifier.testTag("http-warning")) }
        item { WearHeading(title?.takeIf(String::isNotBlank) ?: if (session.threadId == null) "New conversation" else "Current conversation") }
        item { WearCopy(sessionStatusCopy(session), Modifier.testTag("agent-status")) }
        if (session.phase == WearAgentUiPhase.PROVIDER_UNAVAILABLE && session.results.any { it.status == AgentToolResultStatus.SUCCESS && it.mutationIds.isNotEmpty() })
            item { WearCopy("Recorded successful business changes remain committed. A Provider response error does not undo them.") }
        if (session.pending != null) item { WearAction("Review pending action", "review-pending", primary = true, onClick = onReview) }
        item { WearCopy("Command draft · only Send submits") }
        item { WearDraftField(session.draft, onDraft, "agent-input") }
        item { WearAction("Send", "agent-send", enabled = readiness.requestReady && readiness.runtimeState == WearProviderRuntimeState.READY &&
            !session.busy && session.pending == null && session.draft.isNotBlank(), primary = true, onClick = onSend) }
        val facts = readiness.capability
        val speechPossible = facts.selectedLanguageTag != "und" && facts.onDeviceSttAvailability !in
            setOf(OnDeviceSttAvailability.UNSUPPORTED, OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE)
        item { WearCopy(speechCopy(facts)) }
        item { WearAction("Speech draft only", "speech-start", enabled = speechPossible && !session.busy && session.pending == null, onClick = onSpeech) }
        if (speechPossible) item { WearAction("Cancel speech", "speech-cancel", onClick = onCancelSpeech) }
        // Failed structured results remain explicit even when subsequent assistant prose claims success.
        session.calls.takeLast(6).forEach { call ->
            val result = session.results.lastOrNull { it.callId == call.id }
            item { WearHeading(toolTitle(call.name)) }
            item { WearCopy(result?.let { toolResultCopy(it.status) } ?: toolStateCopy(call.state), Modifier.testTag("tool-result")) }
            if (result?.status == AgentToolResultStatus.SUCCESS) item {
                WearCopy(if (result.mutationIds.isEmpty()) "Tool completed. No business mutation recorded." else "Business change committed · audit retained")
            }
        }
        val messages = session.messages.filter { it.role != AgentMessageRole.TOOL }.takeLast(12)
        if (messages.isEmpty()) item { WearCopy("Start with an explicit text command. Speech is optional.") }
        messages.forEach { message ->
            item { WearHeading(if (message.role == AgentMessageRole.USER) "You" else "Assistant") }
            item { WearCopy(message.content) }
        }
        if (session.messages.count { it.role != AgentMessageRole.TOOL } > messages.size) item { WearCopy("Showing recent conversation. Earlier history remains stored.") }
        item { WearAction("New conversation", "new-conversation", enabled = !session.busy && session.pending == null, onClick = onNew) }
        item { WearAction("Status / setup", "open-status", onClick = onSetup) }
        item { WearAction("Today", "wear-back", onClick = onBack) }
    }
}

fun requiresHttpDisclosure(config: ProviderConfig): Boolean {
    val uri = runCatching { URI(config.baseUrl) }.getOrNull() ?: return false
    val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    return uri.scheme.equals("http", true) && host !in setOf("localhost", "127.0.0.1", "::1")
}

@Composable
fun WearAgentRoute(composition: WearAgentRuntimeComposition, navigation: WearNavigation,
    syncFailure: String? = null, onRetrySync: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val session by composition.session.ui.collectAsState()
    val readiness by composition.readiness.readiness.collectAsState()
    var configs by remember { mutableStateOf(emptyList<ProviderConfig>()) }
    var selected by remember { mutableStateOf<ProviderConfig?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf(false) }
    LaunchedEffect(composition) {
        composition.session.restoreRecentThread()
        configs = composition.state.providerConfigs()
        selected = composition.state.selectedProviderConfigId()?.let { composition.state.providerConfig(it) }
    }
    LaunchedEffect(session.threadId) { title = composition.state.threads().firstOrNull { it.id == session.threadId }?.title }
    LaunchedEffect(session.busy) { while (session.busy) { composition.session.reload(); delay(100) } }
    WearAgentScreen(session, readiness, selected, composition.session::editDraft,
        { scope.launch { composition.session.submitFromUserAction() } },
        { scope.launch { composition.session.newConversationFromUserAction() } },
        { call, preview, approved -> scope.launch { composition.session.confirmFromUserAction(call, preview, approved) } },
        composition.readiness.settings::setAiEntryFromExplicitUserAction,
        { scope.launch { composition.refreshSelectedBinding(); composition.readiness.refresh(true) } },
        { composition.readiness.capability.useSpeechFromExplicitUserAction(composition.session::speechCandidate) },
        composition.readiness.capability::cancel,
        { tag -> composition.readiness.settings.selectSpeechLanguageFromExplicitUserAction(tag); composition.readiness.capability.selectLanguageFromUserAction(tag) },
        navigation = navigation, threadTitle = title, syncFailure = syncFailure, onRetrySync = onRetrySync,
        onLeaveForeground = composition.session::cancelForegroundRequest,
        bindingChoices = {
            run {
                if (setupError) item { WearCopy("This binding could not be approved. Check its route and configuration.") }
                configs.forEach { config ->
                    fun choose(route: WearEndpointRoute?) { scope.launch {
                        try {
                            if (route == null) composition.selectFromUserAction(config) else composition.approveCredentialFreeFromUserAction(config, route)
                            selected = config; setupError = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { setupError = true }
                    } }
                    if (config.credentialReference != null) item { WearAction("Select ${config.model}", "binding-select-${config.id.value}", onClick = { choose(null) }) }
                    else WearEndpointRoute.entries.forEach { route ->
                        item { WearAction("Approve ${config.model} · ${if (route == WearEndpointRoute.PUBLIC_HTTPS) "Public HTTPS" else "Local, credential-free"}",
                            "binding-${route.name}-${config.id.value}", onClick = { choose(route) }) }
                    }
                }
            }
        })
}
