package dev.agenticscheduler.wear.capability

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.*
import android.view.inputmethod.InputMethodManager

/** Watch-local on-device path only. Framework methods are main-thread confined. */
class AndroidWearSpeechPlatform(
    private val context: Context,
    private val requestPermissionFromUserAction: ((Boolean) -> Unit) -> Unit,
) : WearSpeechPlatform {
    private val preferences = context.getSharedPreferences("wear_capability_v1", Context.MODE_PRIVATE)
    override val apiLevel: Int get() = Build.VERSION.SDK_INT
    override val platformSupported: Boolean get() = apiLevel >= 30 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    override val textInputSupported: Boolean get() =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.isNotEmpty() ||
            context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
    override fun permission(): SpeechPermission = when {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> SpeechPermission.GRANTED
        preferences.getBoolean("speech_permission_requested", false) -> SpeechPermission.DENIED
        else -> SpeechPermission.NOT_REQUESTED
    }
    override fun onDeviceServiceAvailable(): Boolean {
        mainThread()
        return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
    override fun requestMicrophoneFromUserAction(result: (Boolean) -> Unit) {
        mainThread()
        check(preferences.edit().putBoolean("speech_permission_requested", true).commit())
        requestPermissionFromUserAction(result)
    }
    override fun checkLanguage(language: String, result: (OnDeviceSttAvailability) -> Unit): AutoCloseable {
        mainThread()
        if (Build.VERSION.SDK_INT < 33 || permission() != SpeechPermission.GRANTED || !onDeviceServiceAvailable()) {
            result(if (onDeviceServiceAvailable()) OnDeviceSttAvailability.LANGUAGE_UNVERIFIED else OnDeviceSttAvailability.UNSUPPORTED)
            return AutoCloseable {}
        }
        val recognizer = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            catch (_: UnsupportedOperationException) { result(OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE); return AutoCloseable {} }
        var closed = false
        val close = AutoCloseable { if (!closed) { closed = true; recognizer.destroy() } }
        try {
            recognizer.checkRecognitionSupport(intent(language), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (closed) return
                    val state = when {
                        support.installedOnDeviceLanguages.any { it.equals(language, ignoreCase = true) } -> OnDeviceSttAvailability.AVAILABLE
                        (support.pendingOnDeviceLanguages + support.supportedOnDeviceLanguages).any { it.equals(language, ignoreCase = true) } -> OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE
                        else -> OnDeviceSttAvailability.UNSUPPORTED
                    }
                    result(state); close.close()
                }
                override fun onError(error: Int) {
                    if (closed) return
                    result(when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> OnDeviceSttAvailability.UNSUPPORTED
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> OnDeviceSttAvailability.TEMPORARILY_UNAVAILABLE
                        else -> OnDeviceSttAvailability.LANGUAGE_UNVERIFIED
                    }); close.close()
                }
            })
        } catch (_: SecurityException) { result(OnDeviceSttAvailability.LANGUAGE_UNVERIFIED); close.close() }
        return close
    }
    override fun recognizeOnDevice(language: String, result: (SpeechCandidateResult) -> Unit): AutoCloseable {
        mainThread()
        if (permission() != SpeechPermission.GRANTED) { result(SpeechCandidateResult.PermissionDenied); return AutoCloseable {} }
        if (Build.VERSION.SDK_INT < 31 || !onDeviceServiceAvailable()) { result(SpeechCandidateResult.ServiceUnavailable); return AutoCloseable {} }
        val recognizer = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            catch (_: UnsupportedOperationException) { result(SpeechCandidateResult.ServiceUnavailable); return AutoCloseable {} }
        var closed = false
        val close = AutoCloseable { if (!closed) { closed = true; recognizer.cancel(); recognizer.destroy() } }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {} // Never retain or forward audio.
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (!closed) result(if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    SpeechCandidateResult.PermissionDenied else SpeechCandidateResult.Failed)
                close.close()
            }
            override fun onResults(results: Bundle?) {
                if (!closed) result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let(SpeechCandidateResult::TextCandidate) ?: SpeechCandidateResult.Failed)
                close.close()
            }
        })
        try { recognizer.startListening(intent(language)) }
        catch (_: SecurityException) { result(SpeechCandidateResult.PermissionDenied); close.close() }
        return close
    }
    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}
