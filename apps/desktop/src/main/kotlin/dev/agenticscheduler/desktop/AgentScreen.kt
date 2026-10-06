package dev.agenticscheduler.desktop

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.agenticscheduler.ui.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.AgentThread
import dev.agenticscheduler.agent.history.AgentToolCallState
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.agent.runtime.AgentRunResult
import dev.agenticscheduler.agent.runtime.AgentRunService
import dev.agenticscheduler.application.sync.PlatformSecretStore
import dev.agenticscheduler.agent.history.AgentMessageRole
import kotlinx.coroutines.launch

@Composable
internal fun AgentCommandPanel(
    runService: AgentRunService,
    state: AgentStateRepository,
    secureStore: PlatformSecretStore,
    enrollments: LocalEnrollmentRepository,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
    coordinator: DesktopAgentScreenCoordinator,
    providerProbes: ProviderProbePresentation? = null,
) {
    val scope = checkNotNull(coordinator.scope)
    var providers by coordinator.providers
    var threads by coordinator.threads
    var selectedThread by coordinator.selectedThread
    var messages by coordinator.messages
    var calls by coordinator.calls
    var results by coordinator.results
    var selectedProviderId by coordinator.selectedProviderId
    var command by coordinator.command
    var busy by coordinator.busy
    var providerDialog by coordinator.providerDialog
    var confirmThreadDeletion by coordinator.confirmThreadDeletion
    var editingProvider by coordinator.editingProvider
    var runState by coordinator.runState
    var activeEnrollment by coordinator.activeEnrollment
    var allowSyncedAgentWrites by coordinator.allowSyncedAgentWrites
    var effectivePolicy by coordinator.effectivePolicy



    LaunchedEffect(runService, state) {
        coordinator.refreshThread(selectedThread)
        activeEnrollment = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()
        allowSyncedAgentWrites = activeEnrollment?.let { state.syncAgentOriginEnabled(it.syncSpaceId) } ?: false
        val policy = state.permissionPolicy()
        effectivePolicy = AgentToolCapability.entries.map { capability -> capability to policy.modeFor(capability).name }
    }

    Column(Modifier.testTag(if (coordinator.historyLoaded.value) "agent-ready" else "agent-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!coordinator.historyLoaded.value) Text("Loading conversation…")
        SectionHeading("Universal command", "Application-owned conversation")
        Text("Ask a question, inspect schedule and history, propose typed changes, or request a Planner preview. All business writes remain behind the local Permission Engine.")
        var showPolicy by remember { mutableStateOf(false) }
        TextButton(onClick = { showPolicy = !showPolicy }) { Text(if (showPolicy) "Hide Tool policy" else "View Tool policy") }
        if (showPolicy) {
        Text("Effective device-local Tool policy")
        effectivePolicy.forEach { (capability, mode) -> Text("${capability.name}: $mode") }
        }
        val pending = calls.lastOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }
        val threadSwitchEnabled = !busy && pending == null
        val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedProvider == null) Text("Provider not configured") else Text("Provider: ${selectedProvider.model}")
            Button(onClick = { editingProvider = selectedProvider; providerDialog = true }) { Text(if (selectedProvider == null) "Configure provider" else "Provider settings") }
            Button(enabled = threadSwitchEnabled, onClick = {
                if (busy || calls.any { it.state == AgentToolCallState.WAITING_CONFIRMATION }) return@Button
                scope.launch {
                    if (busy || calls.any { it.state == AgentToolCallState.WAITING_CONFIRMATION }) return@launch
                    val id = coordinator.newConversation()
                    coordinator.refreshThread(id)
                    runState = "New AgentThread created."
                }
            }) { Text("New conversation") }
            Button(enabled = !busy && selectedThread != null, onClick = { confirmThreadDeletion = true }) {
                Text("Delete conversation")
            }
        }
        var showProviderDetails by remember { mutableStateOf(false) }
        TextButton(onClick = { showProviderDetails = !showProviderDetails }) { Text("Provider details") }
        if (showProviderDetails) {
        providers.forEach { provider ->
            Button(onClick = { scope.launch {
                state.selectProviderConfig(provider.id)
                selectedProviderId = provider.id
                runState = "Using ${provider.model} for this AgentThread."
            } }) { Text(if (provider.id == selectedProviderId) "Selected provider: ${provider.model}" else "Use provider: ${provider.model}") }
        }
        selectedProvider?.let { config ->
            Text("Context capacity: ${config.maxContextUnits} units; output reserve: ${config.reservedOutputUnits} units. Streaming: ${config.streamingSupported}; structured tools: ${config.toolCallingSupported}.")
        }
        }
        ProviderCapabilityStatus(selectedProvider, providerProbes)
        if (threads.size > 1) {
            Text("Conversations")
            threads.forEach { thread ->
                Button(
                    enabled = threadSwitchEnabled && thread.id != selectedThread,
                    onClick = {
                        if (busy || calls.any { it.state == AgentToolCallState.WAITING_CONFIRMATION }) return@Button
                        scope.launch {
                            if (busy || calls.any { it.state == AgentToolCallState.WAITING_CONFIRMATION }) return@launch
                            coordinator.refreshThread(thread.id)
                            runState = null
                        }
                    },
                ) {
                    Text(if (thread.id == selectedThread) "Current: ${thread.title ?: thread.id.value}" else thread.title ?: thread.id.value)
                }
            }
        }
        activeEnrollment?.let { active ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = allowSyncedAgentWrites,
                    onCheckedChange = { enabled -> scope.launch {
                        state.setSyncAgentOriginEnabled(active.syncSpaceId, enabled)
                        allowSyncedAgentWrites = state.syncAgentOriginEnabled(active.syncSpaceId)
                    } },
                )
                Text("I confirm every enrolled device is upgraded for Agent-origin sync writes")
            }
            Text(if (allowSyncedAgentWrites) "Agent-origin sync writes are enabled for this SyncSpace." else "Agent-origin writes stay local until you enable this acknowledgement.")
        } ?: Text("No single active SyncSpace is selected. Agent writes remain local-only.")

        if (messages.isEmpty()) Text("Start a conversation with an explicit request.")
        messages.takeLast(16).forEach { message ->
            when (message.role) {
                dev.agenticscheduler.agent.history.AgentMessageRole.USER -> ConversationCard(ConversationRow(message.id.value, "You", message.content))
                dev.agenticscheduler.agent.history.AgentMessageRole.ASSISTANT -> ConversationCard(ConversationRow(message.id.value, "Agent", message.content))
                dev.agenticscheduler.agent.history.AgentMessageRole.TOOL -> Unit
            }
        }
        calls.takeLast(12).forEach { call ->
            val result = results.lastOrNull { it.callId == call.id }
            ToolCard(ToolRow(call.id.value, call.name, call.state.userLabel(), result?.let { "${it.status.userLabel()} — ${it.resultJson}" }))
            if (result?.mutationIds?.isNotEmpty() == true) Text("Committed · ${result.mutationIds.size} recorded business mutation(s)")
        }
        runState?.let { Text(it) }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth().testTag("agent-command"),
            label = { Text("Command") },
            enabled = !busy && pending == null,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && pending == null && command.isNotBlank() && selectedProvider != null, onClick = {
                val threadId = selectedThread ?: return@Button
                val request = command
                busy = true
                runState = "Thinking / waiting for provider response…"
                scope.launch {
                    try {
                        when (val result = coordinator.send(threadId, request)) {
                            is AgentRunResult.Completed -> runState = "Turn completed."
                            is AgentRunResult.AwaitingConfirmation -> runState = "A typed write requires your confirmation."
                            is AgentRunResult.Failed -> runState = failureLabel(result.redactedCode)
                        }
                        command = ""
                        coordinator.refreshThread(threadId)
                    } catch (_: Exception) {
                        runState = "Provider or local Agent runtime is unavailable."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Thinking…" else "Send") }
        }
    }

    pendingOrNull(calls)?.takeUnless { confirmThreadDeletion }?.let { call ->
        AlertDialog(
            onDismissRequest = { /* A pending preview must be explicitly confirmed or denied. */ },
            title = { Text("Confirm ${call.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Review the normalized before/after preview. The operation is revalidated against current state when confirmed."); Text(call.previewJson ?: "No preview was recorded.") } },
            confirmButton = { Button(enabled = !busy, onClick = {
                val threadId = selectedThread ?: return@Button
                busy = true
                runState = "Revalidating confirmation against current state…"
                scope.launch {
                    try {
                        when (val result = coordinator.confirm(threadId, call.id, true)) {
                            is AgentRunResult.Completed -> runState = "Confirmed Tool flow completed."
                            is AgentRunResult.AwaitingConfirmation -> runState = "Another Tool requires confirmation."
                            is AgentRunResult.Failed -> runState = failureLabel(result.redactedCode)
                        }
                        coordinator.refreshThread(threadId)
                    } catch (_: Exception) { runState = "Agent confirmation failed. Check the structured Tool result below." }
                    finally { busy = false }
                }
            }) { Text("Confirm") } },
            dismissButton = { Row {
                Button(enabled = !busy, onClick = {
                    val threadId = selectedThread ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            when (val result = coordinator.confirm(threadId, call.id, false)) {
                                is AgentRunResult.Completed -> runState = "Denied. No write was performed."
                                is AgentRunResult.AwaitingConfirmation -> runState = "Another Tool requires confirmation."
                                is AgentRunResult.Failed -> runState = failureLabel(result.redactedCode)
                            }
                            coordinator.refreshThread(threadId)
                        } catch (_: Exception) { runState = "Denial could not be recorded." }
                        finally { busy = false }
                    }
                }) { Text("Deny") }
                Button(enabled = !busy, onClick = { confirmThreadDeletion = true }) { Text("Delete conversation") }
            } },
        )
    }

    if (confirmThreadDeletion) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmThreadDeletion = false },
            title = { Text("Delete this conversation?") },
            text = { Text("This removes its local messages, Tool calls and results, and summaries. Committed AgentAction and ChangeLog audit facts remain. Deletion does not erase every historical encrypted copy.") },
            confirmButton = { Button(enabled = !busy, onClick = {
                val threadId = selectedThread ?: return@Button
                busy = true
                scope.launch {
                    try {
                        state.deleteThread(threadId)
                        confirmThreadDeletion = false
                        runState = "Conversation deleted. Committed audit facts remain."
                        coordinator.refreshThread(null, createIfMissing = false)
                    } catch (_: Exception) {
                        runState = "Conversation could not be deleted."
                    } finally { busy = false }
                }
            }) { Text("Delete") } },
            dismissButton = { Button(enabled = !busy, onClick = { confirmThreadDeletion = false }) { Text("Cancel") } },
        )
    }

    if (providerDialog) ProviderConfigurationDialog(
        existing = editingProvider,
        state = state,
        secureStore = secureStore,
        ids = ids,
        onDismiss = { providerDialog = false },
        onSaved = { scope.launch {
            val saved = state.providerConfigs().sortedBy { it.model }
            providers = saved
            selectedProviderId = state.selectedProviderConfigId()
            providerDialog = false
            runState = "Provider metadata saved. Credential material is kept only in the platform secure store."
        } },
    )
}
