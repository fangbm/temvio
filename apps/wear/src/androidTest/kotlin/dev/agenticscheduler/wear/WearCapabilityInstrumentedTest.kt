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
import android.security.NetworkSecurityPolicy
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.application.sync.SecretReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket

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
    @Test fun eExplicitCredentialFreeLocalHttpProbeUsesNativeClientWithoutPublicValidation(): Unit = runBlocking {
        assertTrue("frozen explicit credential-free HTTP path requires platform permission", NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val captured = AtomicReference<String>()
        val headers = AtomicReference<String>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val fixture = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    headers.set(lines.joinToString("\n"))
                    val length = lines.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val chars = CharArray(length); var read = 0
                    while (read < length) { val n = reader.read(chars, read, length - read); check(n > 0); read += n }
                    val body = chars.concatToString(); captured.set(body)
                    val name = Json.parseToJsonElement(body).jsonObject["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    val response = """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"native-probe","type":"function","function":{"name":"$name","arguments":"{}"}}]}}]}""".encodeToByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".encodeToByteArray()); write(response); flush()
                    }
                }
            } catch (_: java.net.SocketException) { /* Fixture closed during failure/timeout. */ }
        }.apply { isDaemon = true; start() }
        val client = WearReadinessComposition.newProviderHttpClient()
        var resolves = 0
        try {
            val config = ProviderConfig(ProviderConfigId("01900000-0000-7000-8000-000000000098"), "http://127.0.0.1:${server.localPort}/v1", "synthetic-fixture-model", 2048, 512, false, true, null)
            val binding = WearProviderBinding(config.provisioningBinding(config.id, false), WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL)
            assertTrue(WearNetworkFacts(true, false, false).reachable(binding.route))
            val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { resolves++; error("HTTP must never resolve credential") })
            assertEquals(ProviderProbeResult.Supported, withTimeout(10_000) { provider.probe(config) })
            assertEquals(ProviderProbeResult.Unavailable("INSECURE_CREDENTIAL_TRANSPORT"), provider.probe(config.copy(credentialReference = SecretReference("native-no-read"))))
            assertEquals(0, resolves); assertFalse(headers.get().contains("Authorization", true))
            assertTrue(captured.get().contains("Call d9_capability_probe now.")); assertFalse(captured.get().contains("native-no-read"))
            Log.i("D90302Evidence", "native credential-free loopback HTTP synthetic probe=Supported; no VALIDATED requirement; zero secret reads/auth headers")
        } finally { client.close(); server.close(); fixture.join(2000) }
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
