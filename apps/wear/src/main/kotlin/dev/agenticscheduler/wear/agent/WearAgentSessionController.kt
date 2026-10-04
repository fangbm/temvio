package dev.agenticscheduler.wear.agent

import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.agent.runtime.*
import dev.agenticscheduler.wear.capability.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One explicit action's immutable authorization. It is neither persisted nor transmitted. */
class WearCommandLease(val snapshot: WearRequestSnapshot?, val newCommand: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WearCommandLease>
}

enum class WearAgentUiPhase { IDLE, THINKING, TOOL_PROPOSED, TOOL_RUNNING, CONFIRMATION_REQUIRED,
    TOOL_SUCCESS, TOOL_FAILED, PERMISSION_DENIED, STALE, CONFLICT, INFEASIBLE, PROVIDER_UNAVAILABLE }

data class WearAgentSessionState(
    val threadId: AgentThreadId? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val phase: WearAgentUiPhase = WearAgentUiPhase.IDLE,
    val redactedCode: String? = null,
    val messages: List<AgentMessage> = emptyList(),
    val calls: List<AgentToolCall> = emptyList(),
    val results: List<AgentToolResult> = emptyList(),
) {
    val pending get() = calls.singleOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }
}

/** Lifecycle/explicit UI actions only. All conversation/Tool/business execution belongs to AgentRunService. */
class WearAgentSessionController(
    private val state: AgentStateRepository,
    private val runtime: AgentRunService,
    private val snapshot: suspend () -> WearRequestSnapshot?,
    private val blocker: () -> WearProviderRuntimeState,
    private val continuationBlock: suspend (AgentThreadId) -> String? = { null },
) {
    private val operation = Mutex()
    private var foregroundRequest: Job? = null
    private val mutableState = MutableStateFlow(WearAgentSessionState())
    val ui = mutableState.asStateFlow()
    fun cancelForegroundRequest() { foregroundRequest?.cancel() }

    fun editDraft(value: String) { mutableState.update { it.copy(draft = value) } }
    fun speechCandidate(value: SpeechCandidateResult) {
        if (value is SpeechCandidateResult.TextCandidate) editDraft(value.text) // Never submits/confirms.
    }
    suspend fun restoreRecentThread() {
        if (!operation.tryLock()) return
        try {
            val thread = state.threads().sortedWith(compareByDescending<AgentThread> { it.createdAtEpochMillis }.thenBy { it.id.value }).firstOrNull()
            mutableState.update { it.copy(threadId = thread?.id) }; reload()
        } finally { operation.unlock() }
    }
    suspend fun newConversationFromUserAction() {
        if (!operation.tryLock()) return
        try {
            if (mutableState.value.pending != null) return
            mutableState.value = WearAgentSessionState(threadId = runtime.createThread())
        } finally { operation.unlock() }
    }
    suspend fun submitFromUserAction() {
        if (!operation.tryLock()) return // Rapid double tap is not a queued second command.
        try {
            val before = mutableState.value
            if (before.busy || before.draft.isBlank()) return
            val thread = before.threadId ?: runtime.createThread().also { mutableState.update { s -> s.copy(threadId = it) } }
            if (state.toolCalls(thread).any { it.state == AgentToolCallState.WAITING_CONFIRMATION }) {
                mutableState.update { it.copy(phase = WearAgentUiPhase.CONFIRMATION_REQUIRED, redactedCode = "CONFIRMATION_PENDING") }; reload(); return
            }
            continuationBlock(thread)?.let { fail(it, WearAgentUiPhase.CONFLICT); return }
            val authorization = snapshot() ?: run { fail(blocker().name, WearAgentUiPhase.PROVIDER_UNAVAILABLE); return }
            mutableState.update { it.copy(busy = true, draft = "", phase = WearAgentUiPhase.THINKING, redactedCode = null) }
            foregroundRequest = currentCoroutineContext()[Job]
            val result = withContext(WearCommandLease(authorization, true)) { runtime.run(thread, before.draft) }
            finish(result)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { fail("LOCAL_AGENT_UNAVAILABLE", WearAgentUiPhase.TOOL_FAILED); reload()
        } finally { foregroundRequest = null; mutableState.update { it.copy(busy = false) }; operation.unlock() }
    }
    /** Exact displayed call and normalized preview; confirmation authority is independent of Provider readiness. */
    suspend fun confirmFromUserAction(callId: AgentToolCallId, shownPreview: String, approved: Boolean) {
        if (!operation.tryLock()) return
        try {
            val thread = mutableState.value.threadId ?: return
            val pending = state.toolCalls(thread).singleOrNull { it.state == AgentToolCallState.WAITING_CONFIRMATION }
            if (pending?.id != callId || pending.previewJson != shownPreview) { reload(); fail("STALE_PREVIEW", WearAgentUiPhase.STALE); return }
            continuationBlock(thread)?.let { fail(it, WearAgentUiPhase.CONFLICT); return }
            mutableState.update { it.copy(busy = true, phase = WearAgentUiPhase.TOOL_RUNNING, redactedCode = null) }
            foregroundRequest = currentCoroutineContext()[Job]
            val current = snapshot() // Can be null after wipe/offline: local confirmation still uses shared gates.
            val result = withContext(WearCommandLease(current, false)) { runtime.confirm(thread, callId, approved) }
            finish(result)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { fail("LOCAL_AGENT_UNAVAILABLE", WearAgentUiPhase.TOOL_FAILED); reload()
        } finally { foregroundRequest = null; mutableState.update { it.copy(busy = false) }; operation.unlock() }
    }
    suspend fun reload() {
        val thread = mutableState.value.threadId ?: return
        val messages = state.messages(thread)
        val calls = state.toolCalls(thread)
        val results = state.toolResults(thread)
        mutableState.update { current -> if (current.threadId != thread) current else current.copy(messages = messages, calls = calls, results = results,
            phase = when {
                calls.any { it.state == AgentToolCallState.WAITING_CONFIRMATION } -> WearAgentUiPhase.CONFIRMATION_REQUIRED
                current.busy && calls.lastOrNull()?.state == AgentToolCallState.RUNNING -> WearAgentUiPhase.TOOL_RUNNING
                current.busy && calls.lastOrNull()?.state == AgentToolCallState.PROPOSED -> WearAgentUiPhase.TOOL_PROPOSED
                else -> current.phase
            }) }
    }
    private suspend fun finish(result: AgentRunResult) {
        reload()
        val phase = when {
            result is AgentRunResult.AwaitingConfirmation -> WearAgentUiPhase.CONFIRMATION_REQUIRED
            result is AgentRunResult.Failed -> WearAgentUiPhase.PROVIDER_UNAVAILABLE
            else -> when (mutableState.value.results.lastOrNull()?.status) {
                AgentToolResultStatus.SUCCESS -> WearAgentUiPhase.TOOL_SUCCESS
                AgentToolResultStatus.PERMISSION_DENIED -> WearAgentUiPhase.PERMISSION_DENIED
                AgentToolResultStatus.STALE -> WearAgentUiPhase.STALE
                AgentToolResultStatus.CONFLICT -> WearAgentUiPhase.CONFLICT
                AgentToolResultStatus.INFEASIBLE -> WearAgentUiPhase.INFEASIBLE
                null -> WearAgentUiPhase.IDLE
                else -> WearAgentUiPhase.TOOL_FAILED
            }
        }
        mutableState.update { it.copy(phase = phase, redactedCode = (result as? AgentRunResult.Failed)?.redactedCode) }
    }
    private fun fail(code: String, phase: WearAgentUiPhase) { mutableState.update { it.copy(phase = phase, redactedCode = code) } }
}
