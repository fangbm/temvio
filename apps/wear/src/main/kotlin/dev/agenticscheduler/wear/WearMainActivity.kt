package dev.agenticscheduler.wear

import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.agenticscheduler.wear.agent.*
import dev.agenticscheduler.wear.presentation.*
import dev.agenticscheduler.wear.provisioning.WearTargetProviderProvisioningComposition
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.agent.history.provisioningBinding
import dev.agenticscheduler.wear.capability.WearLocalSettings
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.application.history.ConflictAwareSourceFactReadService
import dev.agenticscheduler.application.id.productionUuidV7Generator
import dev.agenticscheduler.application.history.MutationWallClock
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCreation
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.AndroidKeystoreSecureStore
import dev.agenticscheduler.application.sync.TinkPairingHpke
import dev.agenticscheduler.database.openAndroidDatabase
import dev.agenticscheduler.database.repository.RoomAcademicRepository
import dev.agenticscheduler.database.repository.RoomEventRepository
import dev.agenticscheduler.database.repository.RoomPlanningProfileRepository
import dev.agenticscheduler.database.repository.RoomTaskRepository
import dev.agenticscheduler.database.repository.RoomD8RuntimeComposition
import dev.agenticscheduler.sync.AccountId
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    private val d8StartupState = mutableStateOf(D8StartupState.Activating)
    private val d8SyncFailure = mutableStateOf<String?>(null)
    private val database by lazy { openAndroidDatabase(this) }
    private val d8RuntimeLazy = lazy {
        RoomD8RuntimeComposition(
            database,
            AndroidKeystoreSecureStore(this),
            TinkPairingHpke(),
            productionUuidV7Generator(),
            MutationWallClock { Clock.System.now().toEpochMilliseconds() },
            agentOutboundGate = WearV2BusinessWriteGate(RoomLocalEnrollmentRepository(database), RoomAgentStateRepository(database)),
        )
    }
    private val d8Runtime by d8RuntimeLazy
    private val d8Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val d8ShutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val agentRuntime = mutableStateOf<WearAgentRuntimeComposition?>(null)
    private val agentInitialization = mutableStateOf(false)
    private val agentFailure = mutableStateOf<String?>(null)
    @Volatile private var activeD8Configuration: ActiveSyncRuntimeConfiguration? = null
    private var targetProvisioning: WearTargetProviderProvisioningComposition? = null
    private val credentialSetup = mutableStateOf<WearCredentialSetupPresentation?>(null)
    private val credentialSetupVisible = mutableStateOf(false)
    private val credentialSetupOpening = mutableStateOf(false)
    private var credentialSetupGeneration = 0
    private val providerRefresh = mutableStateOf(0)
    private var credentialOpening: kotlinx.coroutines.Job? = null
    private fun closeCredentialSetup() {
        credentialSetupGeneration++
        credentialOpening?.cancel(); credentialSetup.value?.close()
        credentialSetup.value = null; credentialSetupVisible.value = false; credentialSetupOpening.value = false
    }
    private fun openCredentialSetup(id: ProviderConfigId) {
        closeCredentialSetup(); credentialSetupVisible.value = true
        val configuration = activeD8Configuration ?: return
        val generation = credentialSetupGeneration
        credentialSetupOpening.value = true
        credentialOpening = agentScope.launch {
            try {
                val state = RoomAgentStateRepository(database)
                if (state.selectedProviderConfigId() != id) return@launch
                val config = state.providerConfig(id) ?: return@launch
                if (generation != credentialSetupGeneration || !credentialSetupVisible.value) return@launch
                val root = targetProvisioning ?: WearTargetProviderProvisioningComposition.create(this@WearMainActivity, database, configuration)
                    .also { targetProvisioning = it }
                credentialSetup.value = WearCredentialSetupPresentation(root.target, config.provisioningBinding(id, true), agentScope) {
                    // Re-read committed association; readiness remains its existing independent authority.
                    if (state.providerConfig(id) != null) {
                        agentRuntime.value?.refreshSelectedBinding()
                        agentRuntime.value?.readiness?.refresh(true)
                        providerRefresh.value++
                    }
                }.also { it.open() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { credentialSetup.value = null }
            finally { if (generation == credentialSetupGeneration) credentialSetupOpening.value = false }
        }
    }
    private var microphoneResult: ((Boolean) -> Unit)? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = microphoneResult; microphoneResult = null; callback?.invoke(granted)
    }
    private fun openAgent() {
        if (agentRuntime.value != null || agentInitialization.value) return
        agentInitialization.value = true
        agentScope.launch {
            try {
                val settings = WearLocalSettings(this@WearMainActivity)
                val runtime = WearAgentRuntimeComposition.createActive(this@WearMainActivity, database, agentScope, d8Runtime,
                    { callback -> microphoneResult = callback; microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) }, settings.selectedSpeechLanguageTag)
                runtime.readiness.start()
                runtime.refreshSelectedBinding()
                runtime.foregroundChanged(d8IsForeground)
                agentRuntime.value = runtime
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { agentFailure.value = "LOCAL_AGENT_UNAVAILABLE"
            } finally { agentInitialization.value = false }
        }
    }
    @Volatile private var d8SyncTrigger: ActiveSyncCatchUpTrigger? = null
    @Volatile private var d8IsForeground = false
    private var d8NetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val calendarQueryService: ConflictAwareSourceFactReadService by lazy {
        ConflictAwareSourceFactReadService(
            RoomEventRepository(database),
            RoomTaskRepository(database),
            RoomPlanningProfileRepository(database),
            RoomAcademicRepository(database),
            d8Runtime.sourceFacts,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configuration = try {
            d8RuntimeConfigurationOrNull()
        } catch (_: Throwable) {
            d8StartupState.value = D8StartupState.Blocked
            null
        }
        if (d8StartupState.value != D8StartupState.Blocked) {
            d8StartupState.value = D8StartupState.Activating
            d8Scope.launch {
                try {
                    val creation = configuration?.let { d8Runtime.activate(it) }
                        ?: d8Runtime.activateWithoutConfiguration()
                    when (creation) {
                        is ActiveSyncRuntimeCreation.Active -> {
                            activeD8Configuration = configuration
                            val trigger = d8Runtime.newCatchUpTrigger(
                                d8Scope,
                                pollingIntervalMillis = ActiveSyncCatchUpTrigger.WEAR_POLL_INTERVAL_MILLIS,
                                onUnexpectedFailure = { android.util.Log.w("D8Sync", "Catch-up failed unexpectedly; transient retry remains scheduled.") },
                                onNonRetryableFailure = { reason ->
                                    runOnUiThread { d8SyncFailure.value = reason }
                                    android.util.Log.e("D8Sync", "Automatic sync stopped: $reason")
                                },
                            )
                            d8SyncTrigger = trigger
                            trigger.start()
                            trigger.setForeground(d8IsForeground)
                            registerNetworkRetry(trigger)
                            d8StartupState.value = D8StartupState.Ready
                        }
                        is ActiveSyncRuntimeCreation.ActiveEnrollmentOffline -> d8StartupState.value = D8StartupState.Ready
                        ActiveSyncRuntimeCreation.NoEnrollment -> d8StartupState.value = D8StartupState.Ready
                        is ActiveSyncRuntimeCreation.MultipleActiveEnrollments -> d8StartupState.value = D8StartupState.Blocked
                        ActiveSyncRuntimeCreation.EnrollmentNotActive,
                        is ActiveSyncRuntimeCreation.ActiveEnrollmentAccountMismatch,
                        is ActiveSyncRuntimeCreation.MissingDeviceCredential,
                        -> d8StartupState.value = D8StartupState.Blocked
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    d8StartupState.value = D8StartupState.Blocked
                }
            }
        }
        setContent {
            val startupState by d8StartupState
            val localEnabled by remember { WearLocalSettings(this@WearMainActivity) }.userEnabledAiEntry.collectAsState()
            WearProductTheme {
                when (startupState) {
                    D8StartupState.Ready -> WearApp(calendarQueryService, agentRuntime.value, localEnabled,
                        agentInitialization.value, agentFailure.value != null,
                        onInitializeAgent = { agentFailure.value = null; openAgent() },
                        syncFailure = d8SyncFailure.value,
                        onRetrySync = { d8SyncFailure.value = null; d8SyncTrigger?.retryNow() },
                        credentialSetupVisible = credentialSetupVisible.value, credentialSetup = credentialSetup.value,
                        credentialSetupOpening = credentialSetupOpening.value,
                        onOpenCredential = ::openCredentialSetup, onCloseCredential = ::closeCredentialSetup, providerRefresh = providerRefresh.value)
                    D8StartupState.Activating -> WearStartupScreen(false)
                    D8StartupState.Blocked -> WearStartupScreen(true)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        d8IsForeground = true
        d8SyncTrigger?.setForeground(true)
        agentRuntime.value?.let { runtime -> agentScope.launch { runtime.refreshSelectedBinding(); runtime.foregroundChanged(true) } }
    }

    override fun onStop() {
        d8IsForeground = false
        d8SyncTrigger?.setForeground(false)
        agentRuntime.value?.let { runtime ->
            runtime.stopForegroundFromLifecycle()
            agentScope.launch { runtime.foregroundChanged(false) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        d8NetworkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        d8NetworkCallback = null
        d8SyncTrigger?.close()
        d8SyncTrigger = null
        d8Scope.cancel()
        agentScope.cancel()
        if (d8RuntimeLazy.isInitialized()) {
            d8ShutdownScope.launch {
                try {
                    agentScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                    credentialSetup.value?.close()
                    targetProvisioning?.close()
                    agentRuntime.value?.close()
                    d8Runtime.deactivate()
                } finally {
                    d8ShutdownScope.cancel()
                }
            }
        }
        super.onDestroy()
    }

    private fun registerNetworkRetry(trigger: ActiveSyncCatchUpTrigger) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trigger.requestCatchUp()
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        d8NetworkCallback = callback
    }

    private fun d8RuntimeConfigurationOrNull(): ActiveSyncRuntimeConfiguration? {
        val metadata = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA).metaData
        val baseUrl = metadata?.getString(D8_SYNC_BASE_URL)?.trim().orEmpty()
        val accountId = metadata?.getString(D8_SYNC_ACCOUNT_ID)?.trim().orEmpty()
        if (baseUrl.isEmpty() && accountId.isEmpty()) return null
        require(baseUrl.isNotEmpty() && accountId.isNotEmpty()) { "D8 sync manifest configuration requires both base URL and account ID." }
        return ActiveSyncRuntimeConfiguration(AccountId(accountId), baseUrl)
    }

    private companion object {
        const val D8_SYNC_BASE_URL = "dev.agenticscheduler.sync.BASE_URL"
        const val D8_SYNC_ACCOUNT_ID = "dev.agenticscheduler.sync.ACCOUNT_ID"
    }
}

private enum class D8StartupState { Activating, Ready, Blocked }
