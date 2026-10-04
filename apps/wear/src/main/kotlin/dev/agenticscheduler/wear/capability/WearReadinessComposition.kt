package dev.agenticscheduler.wear.capability

import android.content.Context
import android.os.SystemClock
import dev.agenticscheduler.agent.provider.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.*
import dev.agenticscheduler.sync.DeviceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual lifecycle composition, deliberately not installed into the Agent command/D8 runtime. */
class WearReadinessComposition(
    scope: CoroutineScope,
    val capability: WearCapabilityService,
    val settings: WearLocalSettings,
    val network: WearNetworkObserver,
    private val bindings: WearProviderBindingSource,
    private val provider: OpenAiCompatibleProvider,
    monotonicMillis: () -> Long,
) {
    private val mutex = Mutex()
    private var binding: WearProviderBinding? = null
    private var observation: WearBindingObservation? = null
    private val probe = WearProviderProbe(scope, monotonicMillis, provider::probe)
    private val mutableReadiness = MutableStateFlow(compose())
    val readiness = mutableReadiness.asStateFlow()
    private val collectors = listOf(
        scope.launch { network.facts.collect { refresh() } },
        scope.launch { settings.userEnabledAiEntry.collect { refresh() } },
        scope.launch { capability.facts.collect { refresh() } },
        scope.launch { probe.state.collect { mutex.withLock { mutableReadiness.value = compose() } } },
    )
    /** Main-thread passive inspection/observer registration, without permission or Provider command UI. */
    fun start() { capability.inspectCapability(); network.start() }
    /** Called explicitly on selection/config/credential/install/wipe change, including same-config revision changes. */
    suspend fun bindingChanged(value: WearProviderBinding?) { mutex.withLock { binding = value }; refresh() }
    suspend fun refresh(explicitRetry: Boolean = false) = mutex.withLock {
        observation = bindings.observe(binding)
        mutableReadiness.value = compose()
        probe.updateInput(observation?.config?.let { config -> WearProbeInput(config, observation!!.generation, network.facts.value, mutableReadiness.value.requestReady) })
        probe.refresh(explicitRetry)
    }
    suspend fun foregroundChanged(value: Boolean) {
        if (value) {
            withContext(Dispatchers.Main.immediate) { capability.inspectCapability() }
            refresh()
        }
        probe.setForeground(value)
    }
    suspend fun close() {
        collectors.forEach { it.cancelAndJoin() }; probe.close(); network.close()
        withContext(Dispatchers.Main.immediate) { capability.close() }
    }
    private fun compose() = WearReadiness(capability.facts.value, settings.userEnabledAiEntry.value,
        observation?.facts ?: WearProviderFacts(false, false, true, false, false, false),
        binding?.let { network.facts.value.reachable(it.route) } ?: false, probe.state.value.failure)

    companion object {
        /** Caller owns scope, HttpClient and main-thread speech lifecycle. No enrollment/provisioning/AI auto-enable. */
        fun create(context: Context, database: AgenticSchedulerDatabase, target: DeviceId?, languageTag: String,
            scope: CoroutineScope, client: HttpClient, requestMicrophoneFromUserAction: ((Boolean) -> Unit) -> Unit): WearReadinessComposition {
            val settings = WearLocalSettings(context)
            val state = RoomAgentStateRepository(database)
            val revisions = RoomProviderCredentialProvisioningRepository(database)
            val secrets = AndroidKeystoreSecureStore(context)
            val source = WearProviderBindingSource(state::providerConfig, revisions, secrets, target,
                { RoomLocalEnrollmentRepository(database).states().filterIsInstance<LocalEnrollmentState.Active>().any { it.deviceId == target } },
                settings::isCredentialFreeBindingApproved)
            // Resolve immediately before HTTPS auth; recheck current approval/ownership/wipe.
            val provider = OpenAiCompatibleProvider(client, ProviderCredentialResolver { ref ->
                val local = state.providerConfigs().singleOrNull { it.credentialReference == ref } ?: return@ProviderCredentialResolver null
                val active = target?.let { revisions.state(it, local.id.value)?.activeBinding } ?: return@ProviderCredentialResolver null
                val observation = source.observe(WearProviderBinding(active, WearEndpointRoute.PUBLIC_HTTPS))
                if (!observation.facts.providerReady) return@ProviderCredentialResolver null
                val bytes = secrets.readProviderSecret(ref) ?: return@ProviderCredentialResolver null
                try { bytes.decodeToString() } finally { bytes.fill(0) }
            })
            return WearReadinessComposition(scope, WearCapabilityService(AndroidWearSpeechPlatform(context, requestMicrophoneFromUserAction), languageTag),
                settings, WearNetworkObserver(context), source, provider, SystemClock::elapsedRealtime)
        }
        fun newProviderHttpClient() = HttpClient(Android) { followRedirects = false }
    }
}
