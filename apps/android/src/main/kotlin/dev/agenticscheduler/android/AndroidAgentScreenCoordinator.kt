package dev.agenticscheduler.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.agent.history.AgentMessage
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.history.AgentThreadId
import dev.agenticscheduler.agent.history.AgentToolCall
import dev.agenticscheduler.agent.history.AgentToolCallState
import dev.agenticscheduler.agent.history.AgentToolResult
import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.agent.runtime.AgentRunService

/** Device-session drafts and display facts only; services retain all semantic authority. */
internal class AndroidAgentScreenCoordinator(val state: AgentStateRepository, val runService: AgentRunService, val enrollments: LocalEnrollmentRepository) {
    val historyLoaded = mutableStateOf(false)
    var scope: kotlinx.coroutines.CoroutineScope? = null
    var appliedConfigId: ProviderConfigId? = null
    val configChoices = mutableStateListOf<ProviderConfig>()
    val messages = mutableStateListOf<AgentMessage>()
    val toolCalls = mutableStateListOf<AgentToolCall>()
    val toolResults = mutableStateListOf<AgentToolResult>()
    val configId = mutableStateOf<ProviderConfigId?>(null)
    val selectedConfigId = mutableStateOf<ProviderConfigId?>(null)
    val threadChoices = mutableStateOf(emptyList<dev.agenticscheduler.agent.history.AgentThread>())
    val threadId = mutableStateOf<AgentThreadId?>(null)
    val baseUrl = mutableStateOf("")
    val model = mutableStateOf("")
    val maxContext = mutableStateOf("")
    val reservedOutput = mutableStateOf("")
    val toolCalling = mutableStateOf<Boolean?>(null)
    val streaming = mutableStateOf<Boolean?>(null)
    val credential = mutableStateOf("")
    val removeSavedCredential = mutableStateOf(false)
    val command = mutableStateOf("")
    val status = mutableStateOf("Configure a provider to begin.")
    val pendingConfirmation = mutableStateOf<Pair<dev.agenticscheduler.agent.history.AgentToolCallId, String>?>(null)
    val confirmThreadDeletion = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val configurationError = mutableStateOf<String?>(null)
    val effectivePolicy = mutableStateOf<dev.agenticscheduler.agent.permission.AgentPermissionPolicy?>(null)
    val activeEnrollment = mutableStateOf<LocalEnrollmentState.Active?>(null)
    val allowSyncedAgentWrites = mutableStateOf(false)
    suspend fun reloadConfig() {
        configChoices.clear()
        configChoices.addAll(state.providerConfigs())
        selectedConfigId.value = state.selectedProviderConfigId()
        if (selectedConfigId.value != null && status.value == "Configure a provider to begin.") {
            status.value = "Provider configured; availability is checked on Send."
        }
        effectivePolicy.value = state.permissionPolicy()
        threadChoices.value = state.threads().sortedByDescending { it.createdAtEpochMillis }
        if (threadId.value == null) threadId.value = threadChoices.value.firstOrNull()?.id
        if (threadId.value == null) historyLoaded.value = true
        activeEnrollment.value = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()
        allowSyncedAgentWrites.value = activeEnrollment.value?.let { state.syncAgentOriginEnabled(it.syncSpaceId) } ?: false
    }
    suspend fun reloadThread(id: AgentThreadId) {
        historyLoaded.value = false
        messages.clear(); messages.addAll(state.messages(id).sortedWith(compareBy({ it.ordinal }, { it.id.value })))
        toolCalls.clear(); toolCalls.addAll(state.toolCalls(id).sortedWith(compareBy({ it.ordinal }, { it.id.value })))
        toolResults.clear(); toolResults.addAll(state.toolResults(id).sortedWith(compareBy({ it.ordinal }, { it.id.value })))
        pendingConfirmation.value = toolCalls.lastOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }
            ?.let { call -> call.previewJson?.let { preview -> call.id to preview } }
        historyLoaded.value = true
    }
    suspend fun send(threadId: AgentThreadId, command: String) = runService.run(threadId, command)
    suspend fun confirm(threadId: AgentThreadId, callId: dev.agenticscheduler.agent.history.AgentToolCallId, approved: Boolean) = runService.confirm(threadId, callId, approved)
    suspend fun newConversation() = runService.createThread()
}
