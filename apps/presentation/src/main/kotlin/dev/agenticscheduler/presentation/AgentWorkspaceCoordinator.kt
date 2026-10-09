package dev.agenticscheduler.presentation

import androidx.compose.runtime.*
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.permission.*
import dev.agenticscheduler.agent.provider.ProviderProbeResult
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.application.sync.PlatformSecretStore
import kotlinx.coroutines.CancellationException

enum class AgentOverlay { THREADS, TOOL, CONFIRMATION, DELETE }
enum class AgentProviderAvailability { NONE, CONFIGURED, CHAT_ONLY, CREDENTIAL_UNAVAILABLE, INVALID_ENDPOINT, UNAVAILABLE }

/** App-owned session presentation. The existing D9 runtime owns every turn and Tool transition. */
class AgentWorkspaceCoordinator(
    private val repository: AgentStateRepository,
    private val runtime: AgentRunService,
    private val secrets: PlatformSecretStore,
) {
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var threads by mutableStateOf<List<AgentThread>>(emptyList()); private set
    var selectedThread by mutableStateOf<AgentThreadId?>(null); private set
    var messages by mutableStateOf<List<AgentMessage>>(emptyList()); private set
    var calls by mutableStateOf<List<AgentToolCall>>(emptyList()); private set
    var results by mutableStateOf<List<AgentToolResult>>(emptyList()); private set
    var actions by mutableStateOf<List<AgentAction>>(emptyList()); private set
    var providerLabel by mutableStateOf<String?>(null); private set
    var providerAvailability by mutableStateOf(AgentProviderAvailability.NONE); private set
    var plaintextTransport by mutableStateOf(false); private set
    var policy by mutableStateOf<AgentPermissionPolicy?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var diagnostic by mutableStateOf<String?>(null); private set
    var overlay by mutableStateOf<AgentOverlay?>(null); private set
    var inspectedCall by mutableStateOf<AgentToolCallId?>(null); private set
    private val drafts = mutableStateMapOf<AgentThreadId, String>()
    private var selectedConfig: ProviderConfig? = null // Non-secret config; never exposed in a screen snapshot.
    private var observedProbe: Pair<ProviderConfig, ProviderProbeResult>? = null
    private var failedRequestConfig: ProviderConfig? = null
    val command: String get() = selectedThread?.let { drafts[it] }.orEmpty()
    val pending: AgentToolCall? get() = calls.lastOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }
    val canSwitch: Boolean get() = loaded && !busy && pending == null
    val canSend: Boolean get() = loaded && !busy && selectedThread != null && pending == null && command.isNotBlank() &&
        providerAvailability in setOf(AgentProviderAvailability.CONFIGURED, AgentProviderAvailability.CHAT_ONLY)

    fun editCommand(value: String) { if (!busy && pending == null) selectedThread?.let { drafts[it] = value } }
    fun showThreads() { if (!busy) overlay = AgentOverlay.THREADS }
    fun inspect(callId: AgentToolCallId) { if (calls.any { it.id == callId }) { inspectedCall = callId; overlay = AgentOverlay.TOOL } }
    fun reviewPending() { if (pending != null && !busy) overlay = AgentOverlay.CONFIRMATION }
    fun requestDeletion() { if (selectedThread != null && !busy) overlay = AgentOverlay.DELETE }
    /** Closing presentation never denies, confirms, sends or deletes persisted truth. */
    fun closeOverlay(): Boolean = if (overlay != null && !busy) { overlay = null; true } else false

    suspend fun refresh() {
        if (busy) return
        guarded { load(selectedThread) }
    }
    suspend fun retryAvailabilityFromUser() {
        if (busy) return
        observedProbe = null
        failedRequestConfig = null
        guarded { load(selectedThread) }
    }

    private suspend fun load(preferred: AgentThreadId?) {
        loaded = false
        // Capture complete immutable collections before publishing a new display frame.
        val newThreads = repository.threads().sortedWith(compareByDescending<AgentThread> { it.createdAtEpochMillis }.thenBy { it.id.value })
        val selected = preferred?.takeIf { id -> newThreads.any { it.id == id } } ?: newThreads.firstOrNull()?.id
        val newMessages = selected?.let { repository.messages(it).sortedBy { m -> m.ordinal } }.orEmpty()
        val newCalls = selected?.let { repository.toolCalls(it).sortedBy { c -> c.ordinal } }.orEmpty()
        val newResults = selected?.let { repository.toolResults(it).sortedBy { r -> r.ordinal } }.orEmpty()
        val newActions = selected?.let { repository.actions(it) }.orEmpty()
        val newPolicy = repository.permissionPolicy()
        val config = repository.selectedProviderConfigId()?.let { repository.providerConfig(it) }
        val credentialReference = config?.credentialReference
        val availability = when {
            config == null -> AgentProviderAvailability.NONE
            !validProviderEndpoint(config) -> AgentProviderAvailability.INVALID_ENDPOINT
            credentialReference != null && secrets.readSecret(credentialReference) == null -> AgentProviderAvailability.CREDENTIAL_UNAVAILABLE
            !config.toolCallingSupported -> AgentProviderAvailability.CHAT_ONLY
            else -> AgentProviderAvailability.CONFIGURED
        }
        threads = newThreads; selectedThread = selected; messages = newMessages; calls = newCalls; results = newResults; actions = newActions
        policy = newPolicy; selectedConfig = config; providerLabel = config?.model
        providerAvailability = availability; plaintextTransport = config?.baseUrl?.startsWith("http://", true) == true
        observedProbe?.takeIf { it.first == config }?.let { applyProbe(it.second) }
        if (config != null && failedRequestConfig == config && availability in setOf(AgentProviderAvailability.CONFIGURED,AgentProviderAvailability.CHAT_ONLY))
            providerAvailability = AgentProviderAvailability.UNAVAILABLE
        loaded = true
        if (pending != null && overlay == null) overlay = AgentOverlay.CONFIRMATION
    }

    /** Observes only the last existing explicit probe. This schedules no additional network request. */
    fun observeProbe(config: ProviderConfig?, result: ProviderProbeResult?) {
        if (config != null && result != null) observedProbe = config to result
        if (config == null || config != selectedConfig || providerAvailability in setOf(AgentProviderAvailability.NONE,
                AgentProviderAvailability.INVALID_ENDPOINT, AgentProviderAvailability.CREDENTIAL_UNAVAILABLE)) return
        applyProbe(result)
    }
    private fun applyProbe(result: ProviderProbeResult?) {
        if (providerAvailability !in setOf(AgentProviderAvailability.CONFIGURED, AgentProviderAvailability.CHAT_ONLY, AgentProviderAvailability.UNAVAILABLE)) return
        when (result) {
            ProviderProbeResult.Unsupported -> providerAvailability = AgentProviderAvailability.CHAT_ONLY
            ProviderProbeResult.Supported -> providerAvailability = AgentProviderAvailability.CONFIGURED
            is ProviderProbeResult.Unavailable -> { providerAvailability = AgentProviderAvailability.UNAVAILABLE; diagnostic = result.redactedCode }
            null -> Unit
        }
    }

    suspend fun selectThread(id: AgentThreadId) {
        if (!canSwitch || threads.none { it.id == id }) return
        guarded { load(id); overlay = null; status = null; diagnostic = null }
    }
    suspend fun newConversation() {
        if (!canSwitch) return
        guarded { load(runtime.createThread()); overlay = null; status = "New conversation"; diagnostic = null }
    }
    suspend fun send() {
        if (!canSend) return
        val id = checkNotNull(selectedThread); val submitted = command
        guarded {
            showResult(runtime.run(id, submitted))
            // Clear only the submitted session draft, after the runtime has returned.
            drafts[id] = ""; load(id)
        }
    }
    suspend fun confirm(callId: AgentToolCallId, approved: Boolean) {
        val call = pending?.takeIf { it.id == callId } ?: return
        if (busy) return
        if (approved && call.previewJson == null) { status = "No normalized preview was recorded. This action cannot be confirmed."; return }
        guarded {
            showResult(runtime.confirm(call.threadId, call.id, approved)); overlay = null; load(call.threadId)
        }
    }
    suspend fun deleteSelected() {
        if (busy || overlay != AgentOverlay.DELETE) return
        val id = selectedThread ?: return
        guarded {
            repository.deleteThread(id); drafts.remove(id); overlay = null
            load(null); status = "Conversation deleted. Committed audit facts remain."; diagnostic = null
        }
    }
    suspend fun setPermission(capability: AgentToolCapability, mode: AgentPermissionMode) {
        if (busy || mode !in legalPermissionModes(capability)) return
        guarded {
            val latest = repository.permissionPolicy()
            repository.savePermissionPolicy(latest.withMode(capability, mode)); policy = repository.permissionPolicy()
            status = "Device-local permission saved. Future Tool evaluation uses this policy."
        }
    }
    private fun showResult(result: AgentRunResult) {
        diagnostic = (result as? AgentRunResult.Failed)?.redactedCode
        diagnostic?.takeIf { it.startsWith("HTTP_") || "NETWORK" in it || "CREDENTIAL" in it || "PROVIDER" in it }?.let {
            failedRequestConfig = selectedConfig
        }
        status = when (result) {
            is AgentRunResult.Completed -> "Turn completed." // Tool status still comes exclusively from persisted results.
            is AgentRunResult.AwaitingConfirmation -> "Confirmation required. No proposed write has been committed."
            is AgentRunResult.Failed -> agentFailureCopy(result.redactedCode)
        }
    }
    private suspend fun guarded(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "Agent state could not be refreshed or the explicit action could not finish. Check persisted Tool results before retrying."; diagnostic = "AGENT_PRESENTATION_INFRASTRUCTURE_FAILURE" }
        finally { busy = false }
    }
}

fun validProviderEndpoint(config: ProviderConfig): Boolean = runCatching {
    val uri = java.net.URI(config.baseUrl)
    uri.host != null && uri.userInfo == null && uri.scheme?.lowercase() in setOf("http", "https") &&
        (config.credentialReference == null || uri.scheme.equals("https", true))
}.getOrDefault(false)

fun legalPermissionModes(capability: AgentToolCapability): List<AgentPermissionMode> = AgentPermissionMode.entries.filter { mode ->
    runCatching { AgentPermissionPolicy.default().withMode(capability, mode) }.isSuccess
}

fun agentFailureCopy(code: String): String = when {
    code == "PROVIDER_NOT_CONFIGURED" -> "Choose a local Provider in Settings before sending."
    code == "CONFIRMATION_PENDING" -> "Review the pending action before sending another command."
    code == "CONTEXT_TOO_LARGE" -> "This request exceeds the Provider context budget. Shorten it or start a new conversation. Stored history remains."
    "HISTORY" in code || "TRANSCRIPT" in code -> "Conversation history is invalid or unresolved. Provider continuation is blocked."
    "STALE" in code -> "The proposal is stale. Ask for a new preview; no force apply is available."
    "CONFLICT" in code -> "Unresolved conflict blocks this action. No write is implied."
    "PERMISSION" in code -> "The device-local permission policy denied this action."
    "INFEASIBLE" in code -> "Planner could not produce a feasible proposal."
    "INVALID" in code || code == "EMPTY_COMMAND" -> "The request or Tool input is invalid. Correct it and ask again."
    code == "THREAD_NOT_FOUND" -> "This conversation is no longer available. Select or create a conversation."
    else -> "Provider or network unavailable. Stored conversation and business facts remain unchanged by this failure. Retry is explicit."
}
