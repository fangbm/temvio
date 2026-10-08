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
internal fun AndroidProviderPanel(
    state: AgentStateRepository,
    secureStore: PlatformSecretStore,
    ids: dev.agenticscheduler.application.id.UuidV7Generator,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Credentials are transient masked editor input, never retained by the app coordinator.
    var configChoices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList<ProviderConfig>()) }
    var configId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ProviderConfigId?>(null) }
    var selectedConfigId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ProviderConfigId?>(null) }
    var baseUrl by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var model by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var maxContext by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var reservedOutput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var toolCalling by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    var streaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    var credential by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var removeSavedCredential by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var configurationError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    suspend fun reload() { configChoices=state.providerConfigs(); selectedConfigId=state.selectedProviderConfigId() }
    LaunchedEffect(state) { reload() }
    LaunchedEffect(selectedConfigId,configChoices) {
        val selected=configChoices.firstOrNull {it.id==selectedConfigId}
        if(selected!=null) {
            configId=selected.id; baseUrl=selected.baseUrl; model=selected.model
            maxContext=selected.maxContextUnits.toString(); reservedOutput=selected.reservedOutputUnits.toString()
            toolCalling=selected.toolCallingSupported; streaming=selected.streamingSupported
        }
    }
    Column(Modifier.fillMaxWidth().testTag("provider-settings"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Provider configuration")
        configChoices.forEach { config ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = config.id == selectedConfigId, onClick = {
                    selectedConfigId = config.id
                    configId = config.id
                    credential = ""
                    removeSavedCredential = false
                    scope.launch { state.selectProviderConfig(config.id); reload() }
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
                    reload()
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

        Text(status)
    }
}

internal class AndroidAgentCredential(private val raw: ByteArray) : PlatformSecretMaterial {
    override fun copyRawSecretBytesForSecureStore(): ByteArray = raw.copyOf()
}
