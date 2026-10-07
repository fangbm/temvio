package dev.agenticscheduler.android

import dev.agenticscheduler.agent.tool.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import dev.agenticscheduler.ui.TemvioTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.agenticscheduler.application.history.AgentOriginWriteGate
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeConfiguration
import dev.agenticscheduler.application.sync.ActiveSyncRuntimeCreation
import dev.agenticscheduler.application.sync.ActiveSyncCatchUpTrigger
import dev.agenticscheduler.application.sync.AgentOutboundCompatibilityGate
import dev.agenticscheduler.application.sync.LocalEnrollmentRepository
import dev.agenticscheduler.application.sync.LocalEnrollmentState
import dev.agenticscheduler.sync.AccountId
import dev.agenticscheduler.sync.SyncSpaceId
import dev.agenticscheduler.agent.history.AgentStateRepository
import dev.agenticscheduler.agent.permission.AgentSyncWriteGate
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private val d8StartupState = mutableStateOf(D8StartupState.Activating)
    private val d8SyncStoppedReason = mutableStateOf<String?>(null)
    private val composition by lazy { AndroidCompositionRoot(this) }
    private val database get() = composition.database
    private val events get() = composition.events
    private val tasks get() = composition.tasks
    private val transactionRunner get() = composition.transactionRunner
    private val ids get() = composition.ids
    private val secureStore get() = composition.secureStore
    private val academics get() = composition.academics
    private val profiles get() = composition.profiles
    private val agentState get() = composition.agentState
    private val localEnrollments get() = composition.localEnrollments
    private val conversationSettings get() = composition.conversationSettings
    private val agentWriteGate get() = composition.agentWriteGate
    private val mutations get() = composition.mutations
    private val d8RuntimeLazy get() = composition.d8RuntimeLazy
    private val d8Runtime get() = composition.d8Runtime
    private val reads get() = composition.reads
    private val eventEditor get() = composition.eventEditor
    private val taskEditor get() = composition.taskEditor
    private val dogfoodPlanner get() = composition.dogfoodPlanner
    private val profileSettings get() = composition.profileSettings
    private val agentHttpClientLazy get() = composition.agentHttpClientLazy
    private val agentHttpClient get() = composition.agentHttpClient
    private val agentRun get() = composition.agentRun
    private val d8Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val d8ShutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var d8SyncTrigger: ActiveSyncCatchUpTrigger? = null
    @Volatile private var d8IsForeground = false
    private var d8NetworkCallback: ConnectivityManager.NetworkCallback? = null
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
                            val trigger = d8Runtime.newCatchUpTrigger(
                                d8Scope,
                                pollingIntervalMillis = ActiveSyncCatchUpTrigger.DESKTOP_ANDROID_POLL_INTERVAL_MILLIS,
                                onUnexpectedFailure = { android.util.Log.w("D8Sync", "Catch-up failed unexpectedly; transient retry remains scheduled.") },
                                onNonRetryableFailure = { reason ->
                                    android.util.Log.e("D8Sync", "Automatic sync stopped: $reason. Check account credentials or sync integrity before retrying.")
                                    runOnUiThread { d8SyncStoppedReason.value = reason }
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
            val syncStoppedReason by d8SyncStoppedReason
            TemvioTheme(androidx.compose.foundation.isSystemInDarkTheme()) {
                // Also expose the stable Compose tags as Android resource IDs so the
                // physical-device acceptance path can use ADB/UIAutomator selectors.
                Surface(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                    when (startupState) {
                        D8StartupState.Ready -> AndroidApp(
                            reads,
                            dogfoodPlanner,
                            profileSettings,
                            eventEditor,
                            taskEditor,
                            agentState,
                            agentRun,
                            secureStore,
                            localEnrollments,
                            ids,
                            conversationSettings,
                            providerProbes = composition.providerProbes,
                            academicService = composition.academicService,
                            syncStoppedReason = syncStoppedReason,
                            onRetrySync = {
                                d8SyncStoppedReason.value = null
                                d8SyncTrigger?.retryNow()
                            },
                        )
                        D8StartupState.Activating -> D8StartupStatus("Connecting to your secure sync space…")
                        D8StartupState.Blocked -> D8StartupStatus("Sync setup is unavailable. Restore the device credential or check the configured account and server.")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        d8IsForeground = true
        d8SyncTrigger?.setForeground(true)
    }

    override fun onStop() {
        d8IsForeground = false
        d8SyncTrigger?.setForeground(false)
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
        if (d8RuntimeLazy.isInitialized()) {
            d8ShutdownScope.launch {
                try {
                    d8Runtime.deactivate()
                } finally {
                    d8ShutdownScope.cancel()
                }
            }
        }
        if (agentHttpClientLazy.isInitialized()) agentHttpClient.close()
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

    /**
     * Deployment configuration is intentionally explicit. D10 may provide UI
     * for it; D8 accepts only app-owned manifest metadata and never guesses an
     * endpoint or account.
     */
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

internal enum class D8StartupState { Activating, Ready, Blocked }
@Composable
internal fun D8StartupStatus(message: String) {
    Column { Text(message) }
}

internal class AndroidActiveEnrollmentAgentWriteGate(
    private val enrollments: LocalEnrollmentRepository,
    private val state: AgentStateRepository,
) : AgentOriginWriteGate, AgentOutboundCompatibilityGate {
    override suspend fun mayCommit(): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        return when (active.size) {
            0 -> true
            1 -> AgentSyncWriteGate(active.single().accountId, enrollments, state).mayCommit()
            else -> false
        }
    }

    override suspend fun enabled(syncSpaceId: dev.agenticscheduler.sync.SyncSpaceId): Boolean {
        val active = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>()
        val selected = active.singleOrNull { it.syncSpaceId == syncSpaceId } ?: return false
        return active.size == 1 && AgentSyncWriteGate(selected.accountId, enrollments, state).enabled(syncSpaceId)
    }
}
