package dev.agenticscheduler.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.wear.capability.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WearCapabilityInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun aPreferenceDefaultsOffAndSurvivesRecreationWithoutSyncOrProvisioning() {
        val preferences = context.getSharedPreferences("wear_ai_entry_v1", Context.MODE_PRIVATE)
        val old = preferences.getBoolean("userEnabledAiEntry", false)
        val configId = "01900000-0000-7000-8000-000000000099"
        try {
            assertTrue(preferences.edit().remove("userEnabledAiEntry").commit())
            val first = WearLocalSettings(context); assertFalse(first.userEnabledAiEntry.value)
            val binding = WearProviderBinding(dev.agenticscheduler.sync.WearProviderBindingMetadataV1(providerConfigId = configId,
                baseUrl = "http://192.168.1.3:8080/v1", model = "explicit-model", maxContextUnits = 2048, reservedOutputUnits = 512,
                streamingSupported = false, toolCallingSupported = true, credentialRequired = false), WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL)
            first.approveCredentialFreeBindingFromExplicitUserAction(binding)
            assertFalse(first.userEnabledAiEntry.value)
            first.setAiEntryFromExplicitUserAction(true)
            val reopened = WearLocalSettings(context); assertTrue(reopened.userEnabledAiEntry.value); assertTrue(reopened.isCredentialFreeBindingApproved(binding))
            assertFalse(reopened.isCredentialFreeBindingApproved(binding.copy(metadata = binding.metadata.copy(model = "changed-model"))))
            assertFalse(reopened.isCredentialFreeBindingApproved(binding.copy(route = WearEndpointRoute.PUBLIC_HTTPS)))
        } finally { assertTrue(preferences.edit().putBoolean("userEnabledAiEntry", old).remove("approved_$configId").commit()) }
    }
    @Test fun bRealDefaultNetworkRegistrationAndCapabilityMapping() {
        val observer = WearNetworkObserver(context)
        try {
            observer.start(); observer.start(); assertTrue(observer.isRegistered)
            assertFalse(WearNetworkObserver.mapCapabilities(NetworkCapabilities()).publicInternetRoute)
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }?.let { capabilities ->
                val mapped = WearNetworkObserver.mapCapabilities(capabilities)
                assertEquals(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), mapped.internet)
                assertEquals(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), mapped.validated)
                assertEquals(mapped.internet && mapped.validated, mapped.publicInternetRoute)
            }
            Log.i("D90302Evidence", "API=${Build.VERSION.SDK_INT}; defaultRouteFacts=${observer.facts.value}")
        } finally { observer.close(); observer.close() }
        assertFalse(observer.isRegistered); assertEquals(WearNetworkFacts.OFFLINE, observer.facts.value)
    }
    @Test fun cPassiveRealSttInspectionRequestsNeitherPermissionNorAudio() {
        var requests = 0
        instrumentation.runOnMainSync {
            val platform = AndroidWearSpeechPlatform(context) { requests++; it(false) }
            val available = platform.onDeviceServiceAvailable()
            val before = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            val service = WearCapabilityService(platform, "en-US")
            service.inspectCapability()
            assertEquals(0, requests); assertEquals(before, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            if (!available) assertEquals(OnDeviceSttAvailability.UNSUPPORTED, service.facts.value.onDeviceSttAvailability)
            Log.i("D90302Evidence", "API=${Build.VERSION.SDK_INT}; onDeviceService=$available; capability=${service.facts.value}")
            service.close()
        }
    }
    @Test fun dExplicitDeniedPermissionIsIndependentOfServiceSupport() {
        val preferences = context.getSharedPreferences("wear_capability_v1", Context.MODE_PRIVATE)
        val old = preferences.getBoolean("speech_permission_requested", false)
        try {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertTrue(preferences.edit().remove("speech_permission_requested").commit())
            instrumentation.runOnMainSync {
                var requests = 0
                val platform = AndroidWearSpeechPlatform(context) { requests++; it(false) }
                assertEquals(SpeechPermission.NOT_REQUESTED, platform.permission())
                val before = platform.onDeviceServiceAvailable()
                platform.requestMicrophoneFromUserAction { assertFalse(it) }
                assertEquals(1, requests); assertEquals(SpeechPermission.DENIED, platform.permission())
                assertEquals(before, platform.onDeviceServiceAvailable())
                val service = WearCapabilityService(platform, "en-US"); service.inspectCapability()
                assertEquals(SpeechPermission.DENIED, service.facts.value.speechPermission); assertEquals(1, requests); service.close()
            }
        } finally { assertTrue(preferences.edit().putBoolean("speech_permission_requested", old).commit()) }
    }
    @Test fun zRealGrantedPermissionIsObservedAndNeverStartsRecognitionDuringInspect() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val support = AtomicReference<OnDeviceSttAvailability>()
        val received = CountDownLatch(1)
        var handle: AutoCloseable? = null
        instrumentation.runOnMainSync {
            val platform = AndroidWearSpeechPlatform(context) { fail("passive permission request") }
            assertEquals(SpeechPermission.GRANTED, platform.permission())
            val service = WearCapabilityService(platform, "en-US"); service.inspectCapability()
            assertEquals(SpeechPermission.GRANTED, service.facts.value.speechPermission)
            Log.i("D90302Evidence", "granted; onDeviceService=${platform.onDeviceServiceAvailable()}; capability=${service.facts.value}")
            service.close()
            handle = platform.checkLanguage("en-US") { support.set(it); received.countDown() }
        }
        val completed = received.await(5, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { handle?.close() }
        Log.i("D90302Evidence", "language=en-US; actualSupportCallback=$completed; support=${support.get() ?: OnDeviceSttAvailability.LANGUAGE_UNVERIFIED}")
        // Revocation kills the app process on Android. Host resets permission between runs; no in-test revoke.
    }
    @Test fun zzExplicitNativeSpeechAttemptCanBeCancelledWithoutSubmittingCandidate() {
        val results = mutableListOf<SpeechCandidateResult>()
        instrumentation.runOnMainSync {
            val platform = AndroidWearSpeechPlatform(context) { fail("already granted; no new permission request") }
            val service = WearCapabilityService(platform, "en-US")
            service.useSpeechFromExplicitUserAction(results::add)
            service.cancel(); service.close()
            assertFalse(results.any { it is SpeechCandidateResult.TextCandidate })
            assertTrue(results.all { it == SpeechCandidateResult.Cancelled || it == SpeechCandidateResult.Failed || it == SpeechCandidateResult.ServiceUnavailable })
            Log.i("D90302Evidence", "explicit native speech attempt/cancel; service=${platform.onDeviceServiceAvailable()}; results=$results; no Agent submission")
        }
    }
}
