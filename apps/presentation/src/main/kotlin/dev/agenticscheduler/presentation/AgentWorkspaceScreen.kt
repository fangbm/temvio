package dev.agenticscheduler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.*
import dev.agenticscheduler.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AgentWorkspaceScreen(c: AgentWorkspaceCoordinator, scope: CoroutineScope, onProvider: () -> Unit,
    onPermissions: () -> Unit, onSync: () -> Unit) {
    LaunchedEffect(c) { c.refresh() }
    // Immutable frame captured before any deferred LazyColumn interval construction.
    val threads = c.threads; val messages = c.messages; val calls = c.calls; val results = c.results; val actions = c.actions
    BoxWithConstraints(Modifier.fillMaxSize().testTag(if (c.loaded) "agent-ready" else "agent-loading")) {
        val split = maxWidth >= 860.dp && maxHeight >= 480.dp && LocalDensity.current.fontScale < 1.6f
        val composerHeight = (maxHeight * 0.45f).coerceAtLeast(120.dp).coerceAtMost(maxHeight)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (split) Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                ThreadControls(c, scope, threads)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("agent-conversation"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SectionHeading(threads.firstOrNull { it.id == c.selectedThread }?.title ?: "New conversation", "Application-owned conversation")
                    if (!split) ActionButton(c::showThreads, enabled = !c.busy, role = ActionRole.SECONDARY) { Text("Conversations") }
                    AgentProviderSummary(c, scope, onProvider)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(onPermissions, role = ActionRole.TERTIARY) { Text("Agent permissions") }
                        ActionButton(onSync, role = ActionRole.TERTIARY) { Text("Sync / Security") }
                    }
                    Text("V2 business acknowledgement and V3 history consent are independent. Consent is not upload or catch-up completion.", style = MaterialTheme.typography.bodySmall)
                }
                if (messages.none { it.role != AgentMessageRole.TOOL }) item { Text("Ask an explicit question or propose a structured action. Prose never commits a business change.") }
                // A call's source message is the runtime's assistant record. Keep its event beside that record.
                items(messages.filter { it.role != AgentMessageRole.TOOL }, key = { it.id.value }) { message ->
                    Column(Modifier.widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (message.content.isNotBlank()) ConversationCard(ConversationRow(message.id.value,
                            if (message.role == AgentMessageRole.USER) "You" else "Agent", message.content))
                        calls.filter { it.sourceMessageId == message.id }.forEach { call ->
                            AgentToolSummary(call, results.lastOrNull { it.callId == call.id }, actions.filter { call.id in it.toolCallIds }, c, scope)
                        }
                    }
                }
                // Defensive orphan display does not manufacture a transcript or execute a Tool.
                items(calls.filter { call -> messages.none { it.id == call.sourceMessageId } }, key = { "call:${it.id.value}" }) { call ->
                    AgentToolSummary(call, results.lastOrNull { it.callId == call.id }, actions.filter { call.id in it.toolCallIds }, c, scope)
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max=composerHeight).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                // Keep the explicit submission action outside the focused draft's
                // scrolling/IME bring-into-view region, including short windows.
                Column(Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    c.pending?.let { ActionButton(c::reviewPending, enabled = !c.busy, role = ActionRole.SECONDARY) { Text("Review pending action") } }
                    c.status?.let { Text(it, modifier = Modifier.testTag("agent-run-status")) }
                    c.diagnostic?.let { code -> TechnicalDetails("Diagnostic details") { Text(code) } }
                    OutlinedTextField(c.command, c::editCommand, Modifier.fillMaxWidth().widthIn(max = 680.dp).testTag("agent-command"),
                        label = { Text("Command") }, enabled = c.loaded && !c.busy && c.pending == null && c.selectedThread != null,
                        minLines = 2, maxLines = 6)
                    Text("Send is explicit. Multiline Enter, navigation and appearance changes never submit.", style = MaterialTheme.typography.bodySmall)
                }
                ActionButton({ scope.launch { c.send() } }, enabled = c.canSend, modifier = Modifier.testTag("agent-send")) { Text(if (c.busy) "Working…" else "Send") }
                }
            }
        }
    }
    AgentOverlayHost(c, scope)
}

@Composable
private fun ThreadControls(c: AgentWorkspaceCoordinator, scope: CoroutineScope, threads: List<AgentThread>) {
    SectionHeading("Conversations", "Stored locally; Provider switching keeps history")
    ActionButton({ scope.launch { c.newConversation() } }, enabled = c.canSwitch, role = ActionRole.SECONDARY) { Text("New conversation") }
    threads.forEach { thread -> ActionButton({ scope.launch { c.selectThread(thread.id) } },
        enabled = c.canSwitch && thread.id != c.selectedThread, role = ActionRole.TERTIARY,
        modifier = Modifier.fillMaxWidth().testTag("agent-thread-${thread.id.value}")) {
        Text((if (thread.id == c.selectedThread) "Current · " else "") + (thread.title ?: "New conversation"))
    } }
    ActionButton(c::requestDeletion, enabled = c.loaded && !c.busy && c.selectedThread != null, role = ActionRole.TERTIARY) { Text("Delete conversation") }
}

@Composable
private fun AgentProviderSummary(c: AgentWorkspaceCoordinator, scope: CoroutineScope, onProvider: () -> Unit) {
    val (title, detail) = when (c.providerAvailability) {
        AgentProviderAvailability.NONE -> "Provider not configured" to "Choose a device-local Provider before sending."
        AgentProviderAvailability.CHAT_ONLY -> "Chat only" to "Structured actions unavailable. Requests use zero Tool schemas; prose cannot mutate facts."
        AgentProviderAvailability.CONFIGURED -> "${c.providerLabel} · Provider configured" to "Availability and structured Tools are checked on explicit Send."
        AgentProviderAvailability.CREDENTIAL_UNAVAILABLE -> "Credential unavailable" to "Secure-store credential is missing or unreadable. No plaintext fallback."
        AgentProviderAvailability.INVALID_ENDPOINT -> "Invalid Provider endpoint" to "Credentialed requests require HTTPS. Correct the local configuration."
        AgentProviderAvailability.UNAVAILABLE -> "Provider unavailable" to "Last explicit request failed. Stored history remains."
    }
    StatusMessage(title, detail)
    if (c.plaintextTransport) Text("Credential-free HTTP: prompt and schedule content travels as plaintext in transit.", color = LocalTemvioColors.current.warning)
    ActionButton(onProvider, role = ActionRole.TERTIARY) { Text("Provider settings") }
    ActionButton({ scope.launch { c.refresh() } }, enabled = !c.busy, role = ActionRole.TERTIARY) { Text("Refresh local Agent state") }
    if (c.providerAvailability == AgentProviderAvailability.UNAVAILABLE)
        ActionButton({ scope.launch { c.retryAvailabilityFromUser() } }, enabled = !c.busy, role = ActionRole.SECONDARY) { Text("Allow explicit retry on next Send") }
}

@Composable
private fun AgentToolSummary(call: AgentToolCall, result: AgentToolResult?, actions: List<AgentAction>, c: AgentWorkspaceCoordinator, scope: CoroutineScope) {
    PresentationCard {
        Text(agentToolTitle(call.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("Tool · ${readableAgentLabel(call.state.name)}", modifier = Modifier.testTag("agent-call-state"))
        agentToolCapability(call.name)?.let { capability -> c.policy?.let { Text("Local permission · ${readableAgentLabel(it.modeFor(capability).name)}") } }
        result?.let {
            Text("Result · ${readableAgentLabel(it.status.name)}", modifier = Modifier.testTag("agent-result-status"))
            Text(agentResultCopy(it.status))
            agentSnapshotFacts(it.resultJson).take(5).forEach { fact -> Text("${fact.label}: ${fact.value}",style=MaterialTheme.typography.bodySmall) }
            if (it.mutationIds.isNotEmpty()) Text("Committed · ${it.mutationIds.size} recorded business mutation(s)")
        }
        if (call.name.startsWith("planner.preview") || call.name == "planner.applyBranch")
            Text("Planner proposal is not Active Calendar. Apply revalidates the session-local branch; after process loss obtain a fresh preview.")
        if (call.name == "history.undo") Text("Undo creates a new compensating mutation; it does not delete history.")
        if (actions.isNotEmpty()) Text("${actions.size} retained AgentAction audit record(s)", style = MaterialTheme.typography.bodySmall)
        ActionButton({ c.inspect(call.id) }, role = ActionRole.TERTIARY) { Text("Inspect ${agentToolTitle(call.name).lowercase()}") }
        if (call.state == AgentToolCallState.WAITING_CONFIRMATION) ActionButton(c::reviewPending, enabled = !c.busy, role = ActionRole.SECONDARY) { Text("Review pending action") }
    }
}

@Composable
fun AgentOverlayHost(c: AgentWorkspaceCoordinator, scope: CoroutineScope) {
    val pending = c.pending
    // Dialog content has its own composition. Capture availability in this frame so
    // a completed repository refresh replaces its callbacks instead of retaining a busy frame.
    val busy = c.busy
    when (c.overlay) {
        AgentOverlay.THREADS -> AlertDialog(onDismissRequest = { c.closeOverlay() }, title = { Text("Conversations") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ThreadControls(c, scope, c.threads) } },
            confirmButton = { ActionButton({ c.closeOverlay() }, role = ActionRole.TERTIARY) { Text("Close conversations") } })
        AgentOverlay.CONFIRMATION -> pending?.let { call -> AlertDialog(onDismissRequest = { c.closeOverlay() },
            title = { Text("Confirm ${agentToolTitle(call.name).lowercase()}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This exact pending action is revalidated against current facts on Confirm. A stale or conflicting proposal cannot be forced.")
                Text("Permission · confirmation required")
                SnapshotFacts(call.previewJson)
                TechnicalDetails { Text("ToolCallId · ${call.id.value}"); Text("ThreadId · ${call.threadId.value}"); Text(call.previewJson ?: "No preview") }
            } }, confirmButton = { ActionButton({ scope.launch { c.confirm(call.id, true) } }, enabled = !busy && call.previewJson != null, modifier = Modifier.testTag("agent-confirm")) { Text("Confirm") } },
            dismissButton = { FlowRow { ActionButton({ scope.launch { c.confirm(call.id, false) } }, enabled = !busy, role = ActionRole.SECONDARY, modifier = Modifier.testTag("agent-deny")) { Text("Deny") }
                ActionButton({ c.closeOverlay() }, enabled = !busy, role = ActionRole.TERTIARY) { Text("Review later") } } }) }
        AgentOverlay.DELETE -> AlertDialog(onDismissRequest = { c.closeOverlay() }, title = { Text("Delete this conversation?") },
            text = { Text("This removes local messages, Tool calls/results, summaries and raw provenance snapshots. Committed AgentAction / ChangeLog audit and business changes remain. Synchronized or historical encrypted copies are not promised erased. This does not Undo any business change.") },
            confirmButton = { ActionButton({ scope.launch { c.deleteSelected() } }, enabled = !busy) { Text("Delete") } },
            dismissButton = { ActionButton({ c.closeOverlay() }, enabled = !busy, role = ActionRole.TERTIARY) { Text("Cancel") } })
        AgentOverlay.TOOL -> c.calls.firstOrNull { it.id == c.inspectedCall }?.let { call ->
            val result = c.results.lastOrNull { it.callId == call.id }
            AlertDialog(onDismissRequest = { c.closeOverlay() }, title = { Text(agentToolTitle(call.name)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tool state · ${readableAgentLabel(call.state.name)}")
                    result?.let { Text("Result · ${readableAgentLabel(it.status.name)}"); Text(agentResultCopy(it.status)); SnapshotFacts(it.resultJson) }
                    call.previewJson?.let { Text("Normalized proposal"); SnapshotFacts(it) }
                    TechnicalDetails {
                        Text("ToolCallId · ${call.id.value}"); Text("Arguments · ${call.argumentsJson}")
                        call.previewJson?.let { Text("Preview · $it") }; result?.let { Text("Result · ${it.resultJson}"); it.mutationIds.forEach { id -> Text("MutationId · ${id.value}") } }
                        c.actions.filter { call.id in it.toolCallIds }.forEach { Text("AgentAction · ${it.id.value} · ${it.status}") }
                    }
                } }, confirmButton = { ActionButton({ c.closeOverlay() }, role = ActionRole.TERTIARY) { Text("Close Tool detail") } })
        }
        null -> Unit
    }
}

@Composable
private fun SnapshotFacts(snapshot: String?) { agentSnapshotFacts(snapshot).forEach { Text("${it.label}: ${it.value}") } }

@Composable
private fun TechnicalDetails(label: String = "Technical details", content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ActionButton({ expanded = !expanded }, role = ActionRole.TERTIARY) { Text(if (expanded) "Hide $label" else label) }
    if (expanded) content()
}

@Composable
fun AgentPermissionSettings(c: AgentWorkspaceCoordinator, scope: CoroutineScope) {
    LaunchedEffect(c) { c.refresh() }
    val policy = c.policy
    LazyColumn(Modifier.fillMaxSize().padding(20.dp).testTag("agent-permissions"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading("Agent permissions", "Device-local, user-owned, not synchronized or accessible to Agent Tools") }
        items(AgentToolCapability.entries) { capability ->
            PresentationCard {
                Text(readableAgentLabel(capability.name), style = MaterialTheme.typography.titleMedium)
                Text("Current · ${policy?.modeFor(capability)?.let { readableAgentLabel(it.name) } ?: "Loading"}")
                val modes = legalPermissionModes(capability)
                if (modes.size == 1) Text("Deny only — no reviewed v1 capability permits elevation")
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { modes.forEach { mode ->
                    ActionButton({ scope.launch { c.setPermission(capability, mode) } }, enabled = !c.busy && policy != null && policy.modeFor(capability) != mode,
                        role = ActionRole.SECONDARY, modifier = Modifier.testTag("permission-${capability.name}-${mode.name}")) { Text(readableAgentLabel(mode.name)) }
                } }
            }
        }
        item { c.status?.let { Text(it) } }
    }
}
