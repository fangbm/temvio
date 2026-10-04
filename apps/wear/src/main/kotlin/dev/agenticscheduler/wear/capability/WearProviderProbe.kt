package dev.agenticscheduler.wear.capability

import dev.agenticscheduler.agent.history.ProviderConfig
import dev.agenticscheduler.agent.provider.ProviderProbeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WearProbeFailure(val retryable: Boolean) {
    TIMEOUT(true), NETWORK(true), TRANSIENT_PROVIDER(true), AUTHENTICATION(false),
    INVALID_CONFIG(false), UNSUPPORTED_TOOLS(false), INVALID_RESPONSE(false), CREDENTIAL(false),
}
data class WearProbeState(val failure: WearProbeFailure? = null, val probing: Boolean = false, val nextRefreshAtMillis: Long? = null)
data class WearProbeInput(val config: ProviderConfig, val bindingGeneration: String, val network: WearNetworkFacts, val eligible: Boolean)

/** Owns readiness refresh only. It has no Agent command, Tool or business-write entry point. */
class WearProviderProbe(
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
    private val probe: suspend (ProviderConfig) -> ProviderProbeResult,
) {
    private val mutex = Mutex()
    private var input: WearProbeInput? = null
    private var generation = 0L
    private var flight: Job? = null
    private var foreground = false
    private var attempt = 0
    private val mutableState = MutableStateFlow(WearProbeState())
    val state = mutableState.asStateFlow()

    suspend fun updateInput(value: WearProbeInput?) {
        val old = mutex.withLock {
            if (input == value) return
            val bindingChanged = input?.config != value?.config || input?.bindingGeneration != value?.bindingGeneration
            generation++
            input = value
            if (bindingChanged) {
                attempt = 0
                mutableState.value = WearProbeState()
            } else mutableState.value = mutableState.value.copy(probing = false)
            flight.also { it?.cancel() }
        }
        old?.join() // Never overlap an old request with the new binding's probe.
        mutex.withLock { if (flight === old) flight = null }
    }
    suspend fun setForeground(value: Boolean) {
        mutex.withLock { foreground = value }
        if (value) refresh()
    }
    suspend fun refresh(explicitRetry: Boolean = false) = mutex.withLock {
        if (explicitRetry) {
            attempt = 0
            mutableState.value = mutableState.value.copy(failure = null, nextRefreshAtMillis = null)
        }
        val snapshot = input?.takeIf { it.eligible } ?: return@withLock
        if (flight?.isCompleted == false) return@withLock
        if (mutableState.value.failure?.retryable == false) return@withLock
        val next = mutableState.value.nextRefreshAtMillis
        val remaining = next?.let { maxOf(0L, it - monotonicMillis()) } ?: 0L
        if (remaining > 0 && !foreground) return@withLock
        val token = generation
        flight = scope.launch(start = CoroutineStart.LAZY) {
            if (remaining > 0) {
                delay(remaining)
                mutex.withLock { if (token != generation || !foreground) return@launch }
            }
            runProbe(snapshot, token)
        }.also { it.start() }
    }
    suspend fun close() {
        val old = mutex.withLock { generation++; input = null; flight.also { it?.cancel(); flight = null } }
        old?.join()
    }
    private suspend fun runProbe(snapshot: WearProbeInput, token: Long) {
        while (true) {
            mutex.withLock {
                if (token != generation) return
                mutableState.value = mutableState.value.copy(probing = true)
            }
            val result = withTimeoutOrNull(DEADLINE_MILLIS) { probe(snapshot.config) }
            val failure = classify(result)
            val wait = mutex.withLock {
                if (token != generation) return
                val backoff = if (failure?.retryable == true) BACKOFF_MILLIS[minOf(attempt++, BACKOFF_MILLIS.lastIndex)] else null
                mutableState.value = WearProbeState(failure, false, backoff?.let { monotonicMillis() + it })
                if (failure == null) attempt = 0
                if (!foreground || backoff == null) return
                backoff
            }
            delay(wait)
            mutex.withLock { if (token != generation || !foreground) return }
        }
    }
    companion object {
        const val DEADLINE_MILLIS = 10_000L
        val BACKOFF_MILLIS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        fun classify(result: ProviderProbeResult?): WearProbeFailure? = when (result) {
            null -> WearProbeFailure.TIMEOUT
            ProviderProbeResult.Supported -> null
            ProviderProbeResult.Unsupported -> WearProbeFailure.UNSUPPORTED_TOOLS
            is ProviderProbeResult.Unavailable -> when (result.redactedCode) {
                "NETWORK_FAILURE" -> WearProbeFailure.NETWORK
                "HTTP_408", "HTTP_429", "HTTP_500", "HTTP_502", "HTTP_503", "HTTP_504" -> WearProbeFailure.TRANSIENT_PROVIDER
                "HTTP_401", "HTTP_403" -> WearProbeFailure.AUTHENTICATION
                "MISSING_CREDENTIAL", "CREDENTIAL_FAILURE" -> WearProbeFailure.CREDENTIAL
                "INVALID_RESPONSE", "EMPTY_RESPONSE", "INVALID_ROLE", "MALFORMED_PROVIDER_TOOL_CALL", "UNKNOWN_PROVIDER_TOOL_NAME" -> WearProbeFailure.INVALID_RESPONSE
                else -> if (result.redactedCode.removePrefix("HTTP_").toIntOrNull() in 500..599)
                    WearProbeFailure.TRANSIENT_PROVIDER else WearProbeFailure.INVALID_CONFIG
            }
        }
    }
}
