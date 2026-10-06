package dev.agenticscheduler.android

import androidx.compose.ui.platform.testTag

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.agenticscheduler.ui.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.PlatformSecretMaterial
import dev.agenticscheduler.application.sync.PlatformSecretStore
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.agent.runtime.AgentRunService
import dev.agenticscheduler.agent.runtime.AgentRunResult
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.launch

@Composable
internal fun AndroidAgentPanel(
    state: AgentStateRepository,
    runService: AgentRunService,
    secureStore: PlatformSecretStore,
    enrollments: LocalEnrollmentRepository,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
    coordinator: AndroidAgentScreenCoordinator,
    showProvider: Boolean = false,
    providerProbes: ProviderProbePresentation? = null,
) {
    val scope = checkNotNull(coordinator.scope)
    val configChoices = coordinator.configChoices
    val messages = coordinator.messages
    val toolCalls = coordinator.toolCalls
    val toolResults = coordinator.toolResults
    var configId by coordinator.configId
    var selectedConfigId by coordinator.selectedConfigId
    var threadChoices by coordinator.threadChoices
    var threadId by coordinator.threadId
    var baseUrl by coordinator.baseUrl
    var model by coordinator.model
    var maxContext by coordinator.maxContext
    var reservedOutput by coordinator.reservedOutput
    var toolCalling by coordinator.toolCalling
    var streaming by coordinator.streaming
    var credential by coordinator.credential
    var removeSavedCredential by coordinator.removeSavedCredential
    var command by coordinator.command
    var status by coordinator.status
    var pendingConfirmation by coordinator.pendingConfirmation
    var confirmThreadDeletion by coordinator.confirmThreadDeletion
    var busy by coordinator.busy
    var configurationError by coordinator.configurationError
    var effectivePolicy by coordinator.effectivePolicy
    var activeEnrollment by coordinator.activeEnrollment
    var allowSyncedAgentWrites by coordinator.allowSyncedAgentWrites




    LaunchedEffect(state) { coordinator.reloadConfig() }
    LaunchedEffect(selectedConfigId, configChoices.size) {
        if (coordinator.appliedConfigId == selectedConfigId) return@LaunchedEffect
        coordinator.appliedConfigId = selectedConfigId
        val selected = configChoices.firstOrNull { it.id == selectedConfigId }
        if (selected != null) {
            configId = selected.id
            baseUrl = selected.baseUrl
            model = selected.model
            maxContext = selected.maxContextUnits.toString()
            reservedOutput = selected.reservedOutputUnits.toString()
            toolCalling = selected.toolCallingSupported
            streaming = selected.streamingSupported
        }
    }
    LaunchedEffect(threadId) { threadId?.let { coordinator.reloadThread(it) } }

    Column(Modifier.testTag(if (coordinator.historyLoaded.value) "agent-ready" else "agent-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!coordinator.historyLoaded.value) Text("Loading conversation…")
        SectionHeading("Universal command", "Application-owned conversation")
        if (showProvider) {
        Text("This device’s Agent permission policy")
        effectivePolicy?.let { policy ->
            dev.agenticscheduler.agent.permission.AgentToolCapability.entries.forEach { capability ->
                Text("${capability.name}: ${policy.modeFor(capability).name}")
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
            Text(if (allowSyncedAgentWrites) {
                "Agent-origin sync writes are enabled for this SyncSpace."
            } else {
                "Agent-origin writes stay local until you enable this acknowledgement."
            })
        } ?: Text("No single active SyncSpace is selected. Agent writes remain local-only.")

        Text("Provider configuration")
        configChoices.forEach { config ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = config.id == selectedConfigId, onClick = {
                    selectedConfigId = config.id
                    configId = config.id
                    credential = ""
                    removeSavedCredential = false
                    scope.launch { state.selectProviderConfig(config.id); coordinator.reloadConfig() }
                })
                Text("${config.model} · ${config.baseUrl}")
            }
        }
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Provider base URL (explicit)") })
        OutlinedTextField(model, { model = it }, label = { Text("Model (explicit)") })
        OutlinedTextField(maxContext, { maxContext = it }, label = { Text("Maximum context units") })
        OutlinedTextField(reservedOutput, { reservedOutput = it }, label = { Text("Reserved output units") })
        Text("Structured tool calling")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = toolCalling == true, onClick = { toolCalling = true }); Text("Enabled")
            RadioButton(selected = toolCalling == false, onClick = { toolCalling = false }); Text("Disabled")
        }
        Text("Streaming")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = streaming == true, onClick = { streaming = true }); Text("Enabled")
            RadioButton(selected = streaming == false, onClick = { streaming = false }); Text("Disabled")
        }
        OutlinedTextField(
            value = credential,
            onValueChange = {
                credential = it
                if (it.isNotEmpty()) removeSavedCredential = false
            },
            label = { Text("Optional API credential (stored in Android secure storage)") },
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy && !removeSavedCredential,
        )
        val selectedCredentialReference = configId?.let { selectedId ->
            configChoices.firstOrNull { it.id == selectedId }?.credentialReference
        }
        if (selectedCredentialReference != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = removeSavedCredential,
                    onCheckedChange = { remove ->
                        removeSavedCredential = remove
                        if (remove) credential = ""
                    },
                    enabled = !busy,
                )
                Text("Remove saved credential")
            }
        }
        Text(if (removeSavedCredential && credential.isBlank()) {
            "Saving will remove the selected credential from Android secure storage."
        } else {
            "Leave the credential blank to keep the selected secure-store credential. Enter a value to replace it. Credentialed endpoints require HTTPS."
        })
        Text("A credential-free HTTP endpoint on another device or host can expose prompts and schedule data in transit.")
        configurationError?.let { Text(it) }
        Button(enabled = !busy, onClick = {
            val max = maxContext.toLongOrNull()
            val output = reservedOutput.toLongOrNull()
            if (baseUrl.isBlank() || model.isBlank() || max == null || output == null || output <= 0 || max <= output || toolCalling == null || streaming == null) {
                configurationError = "Enter URL, model, positive context/output capacities, and choose tool-calling and streaming support."
            } else if (
                (credential.isNotEmpty() || (!removeSavedCredential && selectedCredentialReference != null)) &&
                !baseUrl.trim().startsWith("https://", ignoreCase = true)
            ) {
                configurationError = "A provider with an API credential must use HTTPS."
            } else scope.launch {
                busy = true
                configurationError = null
                var newReference: SecretReference? = null
                var supersededCredential: SecretReference? = null
                var published = false
                try {
                    val previous = configId?.let { state.providerConfig(it) }
                    val previousCredential = previous?.credentialReference
                    val ref = if (credential.isNotEmpty()) {
                        val secretBytes = credential.encodeToByteArray()
                        try {
                            secureStore.importSecret(AndroidAgentCredential(secretBytes)).also { newReference = it }
                        } finally { secretBytes.fill(0) }
                    } else if (removeSavedCredential) null else previousCredential
                    val config = ProviderConfig(
                        id = configId ?: ProviderConfigId(ids.next()),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        maxContextUnits = max,
                        reservedOutputUnits = output,
                        streamingSupported = requireNotNull(streaming),
                        toolCallingSupported = requireNotNull(toolCalling),
                        credentialReference = ref,
                    )
                    state.saveProviderConfig(config)
                    published = true
                    state.selectProviderConfig(config.id)
                    supersededCredential = previousCredential?.takeIf { it != ref }
                    credential = ""
                    removeSavedCredential = false
                    configId = config.id
                    selectedConfigId = config.id
                    status = "Provider configuration saved. The runtime probes structured tool support before enabling Tools."
                    coordinator.reloadConfig()
                } catch (_: Exception) {
                    // Before publishing Room metadata, an imported key is safe to clean up.
                    // Once metadata is durable, it must stay available even if later UI work fails.
                    if (!published) newReference?.let { runCatching { secureStore.delete(it) } }
                    configurationError = "Provider configuration could not be saved. Secret contents were not stored in Agent history."
                } finally {
                    busy = false
                }
                // This is post-publication cleanup. Failure leaves an orphaned secure object,
                // never a Room reference to a deleted credential.
                if (published) {
                    supersededCredential?.let { reference -> runCatching { secureStore.delete(reference) } }
                }
            }
        }) { Text("Save provider configuration") }

        } else {
        ProviderCapabilityStatus(configChoices.firstOrNull { it.id == selectedConfigId }, providerProbes)
        Text("Writes require the local Permission Engine and confirmation.")
        Text("Agent conversation")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && pendingConfirmation == null, onClick = {
                scope.launch {
                    threadId = coordinator.newConversation()
                    coordinator.reloadConfig()
                    threadId?.let { coordinator.reloadThread(it) }
                    status = "New application-owned AgentThread created."
                }
            }) { Text("New conversation") }
            Button(enabled = !busy && pendingConfirmation == null && threadId != null, onClick = { confirmThreadDeletion = true }) {
                Text("Delete conversation")
            }
        }
        threadChoices.forEach { thread ->
            Button(enabled = !busy && pendingConfirmation == null && thread.id != threadId, onClick = { threadId = thread.id }) {
                Text(if (thread.id == threadId) "Current conversation" else "Conversation ${thread.id.value.take(8)}")
            }
        }
        OutlinedTextField(command, { command = it }, modifier = Modifier.fillMaxWidth().testTag("agent-command"), label = { Text("Command") })
        Button(enabled = !busy && pendingConfirmation == null && threadId != null && selectedConfigId != null && command.isNotBlank(), onClick = {
            val id = threadId ?: return@Button
            val request = command
            command = ""
            scope.launch {
                busy = true
                status = if (streaming == true) "Sending · streaming response…" else "Sending · thinking…"
                try {
                    when (val result = coordinator.send(id, request)) {
                        is AgentRunResult.Completed -> status = "Completed"
                        is AgentRunResult.AwaitingConfirmation -> {
                            pendingConfirmation = result.callId to result.previewJson
                            status = "Confirmation required"
                        }
                        is AgentRunResult.Failed -> status = agentFailureMessage(result.redactedCode)
                    }
                    coordinator.reloadThread(id)
                    coordinator.reloadConfig()
                } catch (_: Exception) { status = "Agent run failed. Check provider availability and retry." }
                finally { busy = false }
            }
        }) { Text("Send") }
        Text(status)
        Text("Conversation")
        messages.filter { it.role != dev.agenticscheduler.agent.history.AgentMessageRole.TOOL }.forEach { message ->
            ConversationCard(ConversationRow(message.id.value, if (message.role == dev.agenticscheduler.agent.history.AgentMessageRole.USER) "You" else "Agent", message.content))
        }
        Text("Structured Tool calls")
        toolCalls.forEach { call ->
            ToolCard(ToolRow(call.id.value, call.name, call.state.name, call.previewJson))
            Text("Input: ${call.argumentsJson}")
            call.previewJson?.let { Text("Preview: $it") }
        }
        Text("Structured Tool results")
        toolResults.forEach { result ->
            Text("${result.status.name}: ${result.resultJson}")
            if (result.mutationIds.isNotEmpty()) Text("Committed · ${result.mutationIds.size} recorded business mutation(s)")
        }
            }
    }

    if (confirmThreadDeletion) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmThreadDeletion = false },
            title = { Text("Delete this conversation?") },
            text = { Text("This removes its local messages, Tool calls and results, and summaries. Committed AgentAction and ChangeLog audit facts remain. Deletion does not erase every historical encrypted copy.") },
            confirmButton = { Button(enabled = !busy, onClick = {
                val id = threadId ?: return@Button
                scope.launch {
                    busy = true
                    try {
                        state.deleteThread(id)
                        confirmThreadDeletion = false
                        pendingConfirmation = null
                        messages.clear(); toolCalls.clear(); toolResults.clear()
                        threadId = null
                        coordinator.reloadConfig()
                        status = "Conversation deleted. Committed audit facts remain."
                    } catch (_: Exception) {
                        status = "Conversation could not be deleted."
                    } finally { busy = false }
                }
            }) { Text("Delete") } },
            dismissButton = { Button(enabled = !busy, onClick = { confirmThreadDeletion = false }) { Text("Cancel") } },
        )
    }

    pendingConfirmation?.takeUnless { confirmThreadDeletion }?.let { (callId, preview) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Confirm Agent action") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Preview of the validated change:\n$preview") } },
            confirmButton = { Button(enabled = !busy, onClick = {
                val id = threadId ?: return@Button
                scope.launch {
                    busy = true
                    pendingConfirmation = null
                    status = "Applying confirmed action…"
                    try {
                        when (val result = coordinator.confirm(id, callId, approved = true)) {
                            is AgentRunResult.Completed -> status = "Confirmed action completed"
                            is AgentRunResult.AwaitingConfirmation -> { pendingConfirmation = result.callId to result.previewJson; status = "Confirmation required" }
                            is AgentRunResult.Failed -> status = agentFailureMessage(result.redactedCode)
                        }
                        coordinator.reloadThread(id)
                    } catch (_: Exception) { status = "Confirmed action failed; inspect the structured Tool result." }
                    finally { busy = false }
                }
            }) { Text("Confirm") } },
            dismissButton = { Row {
                Button(enabled = !busy, onClick = {
                    val id = threadId ?: return@Button
                    scope.launch {
                        busy = true
                        pendingConfirmation = null
                        try {
                            when (val result = coordinator.confirm(id, callId, approved = false)) {
                                is AgentRunResult.Completed -> status = "Denied. No write was authorized."
                                is AgentRunResult.AwaitingConfirmation -> { pendingConfirmation = result.callId to result.previewJson; status = "Confirmation required" }
                                is AgentRunResult.Failed -> status = agentFailureMessage(result.redactedCode)
                            }
                            coordinator.reloadThread(id)
                        } catch (_: Exception) { status = "Denial could not be recorded." }
                        finally { busy = false }
                    }
                }) { Text("Deny") }
                Button(enabled = !busy, onClick = { confirmThreadDeletion = true }) { Text("Delete conversation") }
            } },
        )
    }
}

internal fun agentFailureMessage(code: String): String = when {
    code.contains("PROVIDER") || code.contains("NETWORK") || code.startsWith("HTTP_") || code.contains("CREDENTIAL") -> "Provider/network unavailable ($code)"
    code.contains("PERMISSION") || code.contains("DENIED") -> "Permission denied ($code)"
    code.contains("STALE") || code.contains("CONFLICT") -> "Stale or conflicting state ($code)"
    code.contains("INFEASIBLE") -> "Planner reports an infeasible request ($code)"
    else -> "Agent request failed ($code)"
}

internal class AndroidAgentCredential(private val raw: ByteArray) : PlatformSecretMaterial {
    override fun copyRawSecretBytesForSecureStore(): ByteArray = raw.copyOf()
}
