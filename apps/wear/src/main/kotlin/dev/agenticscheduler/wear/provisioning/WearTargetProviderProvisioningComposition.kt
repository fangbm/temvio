package dev.agenticscheduler.wear.provisioning

import android.content.Context
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.agent.history.provisioningBinding
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.database.AgenticSchedulerDatabase
import dev.agenticscheduler.database.repository.RoomAgentStateRepository
import dev.agenticscheduler.database.repository.RoomLocalEnrollmentRepository
import dev.agenticscheduler.database.repository.RoomProviderCredentialProvisioningRepository
import io.ktor.client.HttpClient
import kotlin.time.Clock

/** Manual trusted-root construction only. No UI, Agent runtime, readiness or background polling. */
class WearTargetProviderProvisioningComposition private constructor(
    val target: ProviderCredentialTarget,
    private val client: HttpClient,
) : AutoCloseable {
    /** The owner first cancels/joins outstanding intents; closing never wipes or rolls back an install. */
    override fun close() { client.close() }

    companion object {
        fun create(context: Context, database: AgenticSchedulerDatabase,
            configuration: ActiveSyncRuntimeConfiguration): WearTargetProviderProvisioningComposition {
            val enrollments = RoomLocalEnrollmentRepository(database)
            val providerConfigs = RoomAgentStateRepository(database)
            val secureStore = AndroidKeystoreSecureStore(context)
            val client = createSyncHttpClient().config { followRedirects = false; expectSuccess = false }
            val credential: suspend () -> DeviceCredential? = {
                val before = enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull()
                    ?.takeIf { it.accountId == configuration.accountId }
                val value = before?.let { secureStore.load(it.deviceCredentialReference) }
                if (before != null && enrollments.states().filterIsInstance<LocalEnrollmentState.Active>().singleOrNull() == before) value else null
            }
            val endpoint = configuration.serverBaseUrl.trim()
            val coordinator = ProviderCredentialTargetCoordinator(configuration.accountId, enrollments, secureStore,
                secureStore, secureStore, TinkPairingHpke(), RoomProviderCredentialProvisioningRepository(database),
                KtorProviderCredentialTransport(client, endpoint, credential), KtorSyncLifecycleTransport(client, endpoint, credential),
                bindingFor = { id ->
                    try { providerConfigs.providerConfig(ProviderConfigId(id))?.provisioningBinding(ProviderConfigId(id), true) }
                    catch (_: IllegalArgumentException) { throw ProviderProvisioningException(ProviderCredentialRejection.PROVIDER_BINDING_MISMATCH) }
                }, nowEpochSeconds = { Clock.System.now().epochSeconds })
            return WearTargetProviderProvisioningComposition(coordinator, client)
        }
    }
}
