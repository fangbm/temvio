package dev.agenticscheduler.desktop

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import dev.agenticscheduler.ui.ActionButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.application
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.agenticscheduler.application.sync.PlatformSecretMaterial
import dev.agenticscheduler.application.sync.SecretReference
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.AgentToolCall
import dev.agenticscheduler.agent.history.AgentToolCallState
import dev.agenticscheduler.agent.history.AgentToolResultStatus
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.application.sync.PlatformSecretStore
import kotlinx.coroutines.launch

@Composable
internal fun ProviderConfigurationDialog(
    existing: ProviderConfig?,
    state: AgentStateRepository,
    secureStore: PlatformSecretStore,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var baseUrl by remember(existing) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var model by remember(existing) { mutableStateOf(existing?.model.orEmpty()) }
    var contextUnits by remember(existing) { mutableStateOf(existing?.maxContextUnits?.toString().orEmpty()) }
    var outputUnits by remember(existing) { mutableStateOf(existing?.reservedOutputUnits?.toString().orEmpty()) }
    var streaming by remember(existing) { mutableStateOf(existing?.streamingSupported) }
    var toolCalling by remember(existing) { mutableStateOf(existing?.toolCallingSupported) }
    var replaceCredential by remember(existing) { mutableStateOf(false) }
    var removeCredential by remember(existing) { mutableStateOf(false) }
    var credential by remember(existing) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unsafeHttp = nonLoopbackHttp(baseUrl)
    val initialDraft = remember(existing) { listOf(baseUrl, model, contextUnits, outputUnits, streaming, toolCalling, replaceCredential, removeCredential, credential) }
    var discardRequested by remember { mutableStateOf(false) }
    val requestDismiss: () -> Unit = { if (listOf(baseUrl, model, contextUnits, outputUnits, streaming, toolCalling, replaceCredential, removeCredential, credential) != initialDraft) discardRequested = true else onDismiss() }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (existing == null) "Configure Agent provider" else "Provider configuration") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("OpenAI-compatible base URL") })
            OutlinedTextField(model, { model = it }, label = { Text("Model") })
            OutlinedTextField(contextUnits, { contextUnits = it }, label = { Text("Maximum context units") })
            OutlinedTextField(outputUnits, { outputUnits = it }, label = { Text("Reserved output units") })
            Row {
                Button(onClick = { streaming = streaming.nextExplicitChoice() }) { Text("Streaming supported: ${streaming?.let { if (it) "Yes" else "No" } ?: "Choose"}") }
                Button(onClick = { toolCalling = toolCalling.nextExplicitChoice() }) { Text("Structured tool calling: ${toolCalling?.let { if (it) "Yes" else "No" } ?: "Choose"}") }
            }
            if (existing?.credentialReference != null) Text("A credential is stored securely. Its value is never shown here.")
            Row { Checkbox(replaceCredential, { replaceCredential = it; if (it) removeCredential = false }); Text(if (existing?.credentialReference == null) "Add API credential" else "Replace saved credential") }
            if (existing?.credentialReference != null) Row { Checkbox(removeCredential, { removeCredential = it; if (it) replaceCredential = false }); Text("Remove saved credential") }
            if (replaceCredential) OutlinedTextField(credential, { credential = it }, label = { Text("API credential (optional)") }, visualTransformation = PasswordVisualTransformation())
            if (baseUrl.trim().startsWith("http://", ignoreCase = true) && (existing?.credentialReference != null && !removeCredential || replaceCredential)) Text("Credentials require HTTPS. Save an HTTPS URL before using a credential.")
            if (unsafeHttp) Text("This HTTP endpoint is not loopback. Prompt and schedule data will travel without transport encryption.")
            error?.let { Text(it) }
        } },
        confirmButton = { Button(onClick = {
            val parsedContext = contextUnits.toLongOrNull()
            val parsedOutput = outputUnits.toLongOrNull()
            if (baseUrl.isBlank() || model.isBlank() || parsedContext == null || parsedOutput == null || streaming == null || toolCalling == null ||
                parsedContext <= 0 || parsedOutput <= 0 || parsedOutput >= parsedContext ||
                (replaceCredential && credential.isBlank()) ||
                (baseUrl.trim().startsWith("http://", ignoreCase = true) &&
                    (replaceCredential || existing?.credentialReference != null && !removeCredential))
            ) {
                error = if (baseUrl.trim().startsWith("http://", ignoreCase = true) &&
                    (replaceCredential || existing?.credentialReference != null && !removeCredential)) {
                    "A provider credential requires HTTPS. No credential has been imported."
                } else "Enter every provider field explicitly. Context must be positive and output must be smaller than context."
            } else scope.launch {
                var importedReference: SecretReference? = null
                var providerSaved = false
                try {
                    val reference = when {
                        removeCredential -> null
                        replaceCredential -> {
                            val bytes = credential.encodeToByteArray()
                            try {
                                secureStore.importSecret(DesktopProviderCredentialMaterial(bytes)).also { importedReference = it }
                            }
                            finally { bytes.fill(0); credential = "" }
                        }
                        else -> existing?.credentialReference
                    }
                    val config = ProviderConfig(
                        id = existing?.id ?: ProviderConfigId(ids.next()),
                        baseUrl = baseUrl.trim(), model = model.trim(),
                        maxContextUnits = checkNotNull(parsedContext), reservedOutputUnits = checkNotNull(parsedOutput),
                        streamingSupported = checkNotNull(streaming), toolCallingSupported = checkNotNull(toolCalling),
                        credentialReference = reference,
                    )
                    state.saveProviderConfig(config)
                    providerSaved = true
                    state.selectProviderConfig(config.id)
                } catch (_: Exception) {
                    if (!providerSaved) importedReference?.let { reference -> runCatching { secureStore.delete(reference) } }
                    error = "Provider settings could not be saved. The credential value was not retained in Agent state."
                    credential = ""
                    return@launch
                }
                val previousReference = existing?.credentialReference
                if (previousReference != null && (removeCredential || replaceCredential) && previousReference != importedReference) {
                    runCatching { secureStore.delete(previousReference) }
                }
                onSaved()
            }
        }) { Text("Save provider") } },
        dismissButton = { Button(onClick = requestDismiss) { Text("Cancel") } },
    )
    if (discardRequested) dev.agenticscheduler.ui.DraftDiscardPrompt(
        onDiscard = onDismiss, onKeepEditing = { discardRequested = false })
}


internal fun pendingOrNull(calls: List<AgentToolCall>) = calls.lastOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }

internal fun AgentToolCallState.userLabel(): String = when (this) {
    AgentToolCallState.PROPOSED -> "proposed"
    AgentToolCallState.WAITING_CONFIRMATION -> "confirmation required"
    AgentToolCallState.RUNNING -> "running"
    AgentToolCallState.COMPLETED -> "succeeded"
    AgentToolCallState.FAILED -> "failed"
    AgentToolCallState.DENIED -> "denied"
}

internal fun AgentToolResultStatus.userLabel(): String = when (this) {
    AgentToolResultStatus.SUCCESS -> "succeeded"
    AgentToolResultStatus.INVALID_INPUT -> "invalid input"
    AgentToolResultStatus.NOT_FOUND -> "not found"
    AgentToolResultStatus.PERMISSION_DENIED -> "permission denied"
    AgentToolResultStatus.STALE -> "stale preview / conflict"
    AgentToolResultStatus.CONFLICT -> "sync conflict"
    AgentToolResultStatus.INFEASIBLE -> "infeasible PlanBranch"
    AgentToolResultStatus.UNSUPPORTED -> "unsupported Tool"
    AgentToolResultStatus.INFRASTRUCTURE_FAILURE -> "provider or infrastructure unavailable"
}

internal fun failureLabel(code: String): String = when {
    "STALE" in code -> "The preview is stale. Reload current facts and ask again. ($code)"
    "CONFLICT" in code -> "The change conflicts with unresolved Sync state. ($code)"
    "INFEASIBLE" in code -> "Planner found no feasible result. ($code)"
    "PERMISSION" in code || "CONFIRMATION" in code -> "Permission or confirmation is required. ($code)"
    "PROVIDER" in code || "NETWORK" in code || "HTTP_" in code -> "Provider/network unavailable. ($code)"
    else -> "Agent request failed. ($code)"
}

internal fun Boolean?.nextExplicitChoice(): Boolean? = when (this) { null -> true; true -> false; false -> null }

internal fun nonLoopbackHttp(value: String): Boolean = runCatching {
    val uri = java.net.URI(value.trim())
    uri.scheme.equals("http", ignoreCase = true) && uri.host?.let { host ->
        !host.equals("localhost", ignoreCase = true) && host != "127.0.0.1" && host != "::1" && !host.startsWith("127.")
    } == true
}.getOrDefault(false)

internal class DesktopProviderCredentialMaterial(private val bytes: ByteArray) : PlatformSecretMaterial {
    override fun copyRawSecretBytesForSecureStore(): ByteArray = bytes.copyOf()
}
