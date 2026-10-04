package dev.agenticscheduler.wear.capability

import android.content.Context
import dev.agenticscheduler.agent.history.*
import dev.agenticscheduler.application.sync.*
import dev.agenticscheduler.sync.*
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local composition only: frozen metadata plus explicit route selection, never a new wire DTO. */
data class WearProviderBinding(val metadata: WearProviderBindingMetadataV1, val route: WearEndpointRoute) {
    init { require(route != WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL || !metadata.credentialRequired) }
}

/** Device-local settings are separate from Room, workspace consent and provisioning. */
class WearLocalSettings(context: Context) {
    private val preferences = context.getSharedPreferences("wear_ai_entry_v1", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("userEnabledAiEntry", false))
    val userEnabledAiEntry = mutableEnabled.asStateFlow()
    val selectedSpeechLanguageTag: String get() = preferences.getString("selectedSpeechLanguageTag", null) ?: "und"
    fun selectSpeechLanguageFromExplicitUserAction(tag: String) {
        require(tag.isNotBlank() && tag != "und")
        check(preferences.edit().putString("selectedSpeechLanguageTag", tag).commit())
    }
    fun setAiEntryFromExplicitUserAction(enabled: Boolean) {
        check(preferences.edit().putBoolean("userEnabledAiEntry", enabled).commit())
        mutableEnabled.value = enabled
    }
    /** Credential-free configurations have no credential install/SAS; approval still pins exact local metadata. */
    fun approveCredentialFreeBindingFromExplicitUserAction(binding: WearProviderBinding) {
        require(!binding.metadata.credentialRequired)
        check(preferences.edit().putString("approved_${binding.metadata.providerConfigId}", approvalValue(binding)).commit())
    }
    fun isCredentialFreeBindingApproved(binding: WearProviderBinding): Boolean = !binding.metadata.credentialRequired &&
        preferences.getString("approved_${binding.metadata.providerConfigId}", null) == approvalValue(binding)
    private fun approvalValue(binding: WearProviderBinding) = binding.route.name + ":" +
        ProviderCredentialWireCodec.encodeBinding(binding.metadata).decodeToString()
}

data class WearBindingObservation(val config: ProviderConfig?, val facts: WearProviderFacts, val generation: String)

/** Reads existing local config/revision/journal and real secure store; never trusts a non-null SecretRef alone. */
class WearProviderBindingSource(
    private val config: suspend (ProviderConfigId) -> ProviderConfig?,
    private val revisions: ProviderCredentialProvisioningRepository,
    private val secrets: PlatformProviderCredentialStore,
    private val target: DeviceId?,
    private val targetActive: suspend () -> Boolean,
    private val credentialFreeApproved: (WearProviderBinding) -> Boolean,
) {
    suspend fun observe(binding: WearProviderBinding?): WearBindingObservation {
        if (binding == null) return WearBindingObservation(null, WearProviderFacts(false, false, true, false, false, false), "NO_BINDING")
        val metadata = binding.metadata
        val id = ProviderConfigId(metadata.providerConfigId)
        val local = config(id)
        if (local == null) return WearBindingObservation(null, WearProviderFacts(false, false, true, false, metadata.credentialRequired, false), "NO_CONFIG")
        val state = target?.let { revisions.state(it, id.value) }
        val journals = target?.let { revisions.journals(it).filter { journal -> journal.providerConfigId == id.value } }.orEmpty()
        val exact = try { local.provisioningBinding(id, metadata.credentialRequired) == metadata }
            catch (_: IllegalArgumentException) { false }
        val adapter = metadata.adapterProfile == "OPENAI_COMPATIBLE_CHAT_TOOLS" && validEndpoint(binding)
        val ownJournal = journals.singleOrNull { it.installIdentity == state?.activeInstallIdentity }
        val journalValid = ownJournal != null && ownJournal.targetDeviceId == target && ownJournal.preparedReference == state?.activeReference &&
            ownJournal.revision == state?.highestAcceptedRevision && ownJournal.phase == ProviderInstallPhase.METADATA_COMMITTED
        val unfinished = journals.any { it.phase != ProviderInstallPhase.COMPLETED && it != ownJournal }
        val credentialOwnershipValid = state != null && state.targetDeviceId == target && state.providerConfigId == id.value &&
            state.phase == ProviderReservationPhase.ACTIVE && state.highestAcceptedRevision > state.rejectionFloor &&
            state.activeBinding == metadata && state.activeReference == local.credentialReference?.value && journalValid
        val blocked = unfinished || state?.phase == ProviderReservationPhase.DISABLED ||
            if (metadata.credentialRequired) !targetActive() || state == null ||
                state.phase == ProviderReservationPhase.USER_APPROVED ||
                state.phase == ProviderReservationPhase.ACTIVE && !credentialOwnershipValid
            else local.credentialReference != null
        val approved = exact && if (metadata.credentialRequired)
            state?.phase == ProviderReservationPhase.ACTIVE && state.activeBinding == metadata
            else credentialFreeApproved(binding)
        var readable = false
        if (metadata.credentialRequired && approved && !blocked && adapter && credentialOwnershipValid) {
            val bytes = try { secrets.readProviderSecret(requireNotNull(local.credentialReference)) }
                catch (_: SecureStoreUnavailableException) { null }
            readable = bytes != null && bytes.size in 1..4096 && bytes.all { it.toInt() in 0x21..0x7e }
            bytes?.fill(0)
        }
        // Close asynchronous secret-read races against binding edit, install and wipe.
        val unchanged = config(id) == local && (target == null || revisions.state(target, id.value) == state &&
            revisions.journals(target).filter { it.providerConfigId == id.value } == journals) &&
            (!metadata.credentialRequired || targetActive())
        val facts = WearProviderFacts(true, approved, adapter, blocked || !unchanged, metadata.credentialRequired, readable && unchanged)
        // Temporary store/route/permission availability is not a credential/config change.
        // It must not reset an authentication/unsupported terminal probe stop.
        return WearBindingObservation(local, facts,
            "${binding.route}:${metadata.credentialRequired}:${state?.generation?.decimal}:${state?.highestAcceptedRevision}:${state?.activeInstallIdentity}:$approved")
    }
    private fun validEndpoint(binding: WearProviderBinding): Boolean = try {
        val uri = URI(binding.metadata.baseUrl)
        uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true) &&
                binding.route == WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL && !binding.metadata.credentialRequired)
    } catch (_: IllegalArgumentException) { false }
      catch (_: java.net.URISyntaxException) { false }
}
