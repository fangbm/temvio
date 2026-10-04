package dev.agenticscheduler.wear.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All calls/callbacks run on the platform main thread; no network or Agent dependency. */
interface WearSpeechPlatform {
    val apiLevel: Int
    val platformSupported: Boolean
    val textInputSupported: Boolean
    fun permission(): SpeechPermission
    fun onDeviceServiceAvailable(): Boolean
    fun checkLanguage(language: String, result: (OnDeviceSttAvailability) -> Unit): AutoCloseable
    fun requestMicrophoneFromUserAction(result: (Boolean) -> Unit)
    fun recognizeOnDevice(language: String, result: (SpeechCandidateResult) -> Unit): AutoCloseable
}
sealed interface SpeechCandidateResult {
    data class TextCandidate(val text: String) : SpeechCandidateResult
    data object Cancelled : SpeechCandidateResult
    data object PermissionDenied : SpeechCandidateResult
    data object ServiceUnavailable : SpeechCandidateResult
    data object Busy : SpeechCandidateResult
    data object Failed : SpeechCandidateResult
}

class WearCapabilityService(private val platform: WearSpeechPlatform, languageTag: String) : AutoCloseable {
    private var language = languageTag.also { require(it.isNotBlank()) }
    private var inspection = 0L
    private var speechGeneration = 0L
    private var supportQuery: AutoCloseable? = null
    private var speech: AutoCloseable? = null
    private var speechPending = false
    private var speechConsumer: ((SpeechCandidateResult) -> Unit)? = null
    private var closed = false
    private val languageAvailability = mutableMapOf<String, OnDeviceSttAvailability>()
    private val mutableFacts = MutableStateFlow(baseFacts())
    val facts = mutableFacts.asStateFlow()

    /** Passive: no permission request, audio, model download or cloud fallback. */
    fun inspectCapability() {
        check(!closed)
        val token = ++inspection
        supportQuery?.close(); supportQuery = null
        mutableFacts.value = baseFacts()
        if (platform.apiLevel < 31 || !platform.onDeviceServiceAvailable() ||
            platform.apiLevel < 33 || platform.permission() != SpeechPermission.GRANTED) return
        supportQuery = platform.checkLanguage(language) { availability ->
            if (token == inspection && !closed) {
                languageAvailability[language] = availability
                mutableFacts.value = baseFacts()
            }
        }
    }
    fun selectLanguageFromUserAction(tag: String) {
        require(tag.isNotBlank())
        cancel()
        language = tag
        inspectCapability()
    }
    /** The caller must be handling a user speech action. Result is a candidate, never a command. */
    fun useSpeechFromExplicitUserAction(result: (SpeechCandidateResult) -> Unit) {
        check(!closed)
        if (speechPending) { result(SpeechCandidateResult.Busy); return }
        inspectCapability()
        if (mutableFacts.value.onDeviceSttAvailability == OnDeviceSttAvailability.UNSUPPORTED) {
            result(SpeechCandidateResult.ServiceUnavailable); return
        }
        speechPending = true
        speechConsumer = result
        val token = ++speechGeneration
        if (platform.permission() != SpeechPermission.GRANTED) {
            platform.requestMicrophoneFromUserAction { granted ->
                if (token != speechGeneration || closed) return@requestMicrophoneFromUserAction
                inspectCapability()
                if (!granted || platform.permission() != SpeechPermission.GRANTED) finish(token, SpeechCandidateResult.PermissionDenied)
                else start(token)
            }
        } else start(token)
    }
    private fun start(token: Long) {
        if (platform.permission() != SpeechPermission.GRANTED) { finish(token, SpeechCandidateResult.PermissionDenied); return }
        if (mutableFacts.value.onDeviceSttAvailability == OnDeviceSttAvailability.UNSUPPORTED) {
            finish(token, SpeechCandidateResult.ServiceUnavailable); return
        }
        val handle = platform.recognizeOnDevice(language) { value ->
            if (token != speechGeneration || closed) return@recognizeOnDevice
            if (platform.permission() != SpeechPermission.GRANTED) finish(token, SpeechCandidateResult.PermissionDenied)
            else finish(token, value)
        }
        // A synchronous failure may complete before the platform returns its handle.
        if (token == speechGeneration && speechPending) speech = handle else handle.close()
    }
    private fun finish(token: Long, result: SpeechCandidateResult) {
        if (token != speechGeneration || !speechPending) return
        speechGeneration++
        speechPending = false
        speech?.close(); speech = null
        mutableFacts.value = baseFacts()
        val consumer = speechConsumer
        speechConsumer = null
        consumer?.invoke(result)
    }
    fun cancel() {
        val consumer = speechConsumer
        speechGeneration++
        speechPending = false
        speechConsumer = null
        speech?.close(); speech = null
        consumer?.invoke(SpeechCandidateResult.Cancelled)
    }
    override fun close() { cancel(); inspection++; supportQuery?.close(); supportQuery = null; closed = true }
    private fun baseFacts() = WearCapabilityFacts(platform.platformSupported, platform.textInputSupported,
        if (platform.apiLevel < 31 || !platform.onDeviceServiceAvailable()) OnDeviceSttAvailability.UNSUPPORTED
        else languageAvailability[language] ?: OnDeviceSttAvailability.LANGUAGE_UNVERIFIED, platform.permission(), language)
}
