package dev.agenticscheduler.desktop

import androidx.compose.runtime.mutableStateOf
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.AgentThreadId
import dev.agenticscheduler.agent.history.AgentThread
import dev.agenticscheduler.agent.history.AgentMessage
import dev.agenticscheduler.agent.history.AgentToolCall
import dev.agenticscheduler.agent.history.AgentToolResult
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.agent.runtime.AgentRunService

/** Device-session drafts and display facts only; services retain all semantic authority. */
internal class DesktopAgentScreenCoordinator(val state: AgentStateRepository, val runService: AgentRunService) {
    val historyLoaded = mutableStateOf(false)
    var scope: kotlinx.coroutines.CoroutineScope? = null
    val providers = mutableStateOf<List<ProviderConfig>>(emptyList())
    val threads = mutableStateOf<List<AgentThread>>(emptyList())
    val selectedThread = mutableStateOf<AgentThreadId?>(null)
    val messages = mutableStateOf<List<AgentMessage>>(emptyList())
    val calls = mutableStateOf<List<AgentToolCall>>(emptyList())
    val results = mutableStateOf<List<AgentToolResult>>(emptyList())
    val selectedProviderId = mutableStateOf<ProviderConfigId?>(null)
    val command = mutableStateOf("")
    val busy = mutableStateOf(false)
    val providerDialog = mutableStateOf(false)
    val confirmThreadDeletion = mutableStateOf(false)
    val editingProvider = mutableStateOf<ProviderConfig?>(null)
    val runState = mutableStateOf<String?>(null)
    val activeEnrollment = mutableStateOf<LocalEnrollmentState.Active?>(null)
    val allowSyncedAgentWrites = mutableStateOf(false)
    val effectivePolicy = mutableStateOf<List<Pair<AgentToolCapability, String>>>(emptyList())
    suspend fun refreshThread(threadId: AgentThreadId?, createIfMissing: Boolean = true) {
        historyLoaded.value = false
        threads.value = state.threads().sortedByDescending { it.createdAtEpochMillis }
        selectedProviderId.value = state.selectedProviderConfigId()
        providers.value = state.providerConfigs().sortedBy { it.model }
        if (threadId == null) {
            selectedThread.value = threads.value.firstOrNull()?.id ?: if (createIfMissing) runService.createThread().also { selectedThread.value = it } else null
        } else selectedThread.value = threadId
        val current = selectedThread.value
        messages.value = current?.let { state.messages(it).sortedBy { item -> item.ordinal } }.orEmpty()
        calls.value = current?.let { state.toolCalls(it).sortedBy { item -> item.ordinal } }.orEmpty()
        results.value = current?.let { state.toolResults(it).sortedBy { item -> item.ordinal } }.orEmpty()
        historyLoaded.value = true
    }
    suspend fun send(threadId: AgentThreadId, command: String) = runService.run(threadId, command)
    suspend fun confirm(threadId: AgentThreadId, callId: dev.agenticscheduler.agent.history.AgentToolCallId, approved: Boolean) = runService.confirm(threadId, callId, approved)
    suspend fun newConversation() = runService.createThread()
}
