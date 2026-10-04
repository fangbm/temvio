package dev.agenticscheduler.wear.capability

enum class OnDeviceSttAvailability { UNSUPPORTED, LANGUAGE_UNVERIFIED, AVAILABLE, TEMPORARILY_UNAVAILABLE }
enum class SpeechPermission { NOT_REQUESTED, GRANTED, DENIED }
enum class WearProviderRuntimeState {
    ENTRY_UNSUPPORTED, ENTRY_DISABLED, NO_BINDING, BINDING_APPROVAL_REQUIRED,
    CREDENTIAL_UNAVAILABLE, INSTALL_BLOCKED, OFFLINE, READY, PROVIDER_UNAVAILABLE,
}

data class WearCapabilityFacts(
    val platformSupported: Boolean,
    val textInputSupported: Boolean,
    val onDeviceSttAvailability: OnDeviceSttAvailability,
    val speechPermission: SpeechPermission,
    val selectedLanguageTag: String,
) {
    init { require(selectedLanguageTag.isNotBlank()) }
    // Permission/network availability are not stable input capability facts.
    val aiEntrySupported: Boolean get() = platformSupported &&
        (textInputSupported || onDeviceSttAvailability == OnDeviceSttAvailability.AVAILABLE)
}

/** Explicit local endpoint selection; never inferred from credential absence or a hostname. */
enum class WearEndpointRoute { PUBLIC_HTTPS, EXPLICIT_CREDENTIAL_FREE_LOCAL }
data class WearNetworkFacts(val defaultRouteAvailable: Boolean, val internet: Boolean, val validated: Boolean) {
    val publicInternetRoute: Boolean get() = defaultRouteAvailable && internet && validated
    fun reachable(route: WearEndpointRoute): Boolean = when (route) {
        WearEndpointRoute.PUBLIC_HTTPS -> publicInternetRoute
        // Local route evidence permits a bounded endpoint attempt; it is not endpoint health.
        WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL -> defaultRouteAvailable
    }
    companion object { val OFFLINE = WearNetworkFacts(false, false, false) }
}

data class WearProviderFacts(
    val bindingExists: Boolean,
    val exactBindingApproved: Boolean,
    val adapterSupported: Boolean,
    val installBlocked: Boolean,
    val credentialRequired: Boolean,
    val matchingSecretAvailable: Boolean,
) {
    val providerReady: Boolean get() = bindingExists && exactBindingApproved && adapterSupported &&
        !installBlocked && (!credentialRequired || matchingSecretAvailable)
}

data class WearReadiness(
    val capability: WearCapabilityFacts,
    val userEnabledAiEntry: Boolean,
    val provider: WearProviderFacts,
    val networkReachable: Boolean,
    val providerProbeFailure: WearProbeFailure?,
) {
    val aiEntrySupported: Boolean get() = capability.aiEntrySupported
    val effectiveAiEntryEnabled: Boolean get() = aiEntrySupported && userEnabledAiEntry
    val providerReady: Boolean get() = provider.providerReady
    val requestReady: Boolean get() = effectiveAiEntryEnabled && providerReady && networkReachable
    /** Display-only order. No displayed reason writes back into facts or binding. */
    val blockers: Set<WearProviderRuntimeState> get() = buildSet {
        if (!aiEntrySupported) add(WearProviderRuntimeState.ENTRY_UNSUPPORTED)
        if (!userEnabledAiEntry) add(WearProviderRuntimeState.ENTRY_DISABLED)
        if (!provider.bindingExists) add(WearProviderRuntimeState.NO_BINDING)
        if (provider.bindingExists && !provider.exactBindingApproved) add(WearProviderRuntimeState.BINDING_APPROVAL_REQUIRED)
        if (provider.installBlocked) add(WearProviderRuntimeState.INSTALL_BLOCKED)
        if (provider.bindingExists && provider.credentialRequired && !provider.matchingSecretAvailable) add(WearProviderRuntimeState.CREDENTIAL_UNAVAILABLE)
        if (!networkReachable) add(WearProviderRuntimeState.OFFLINE)
        if (!provider.adapterSupported || providerProbeFailure != null) add(WearProviderRuntimeState.PROVIDER_UNAVAILABLE)
    }
    val runtimeState: WearProviderRuntimeState get() = listOf(
        WearProviderRuntimeState.ENTRY_UNSUPPORTED, WearProviderRuntimeState.ENTRY_DISABLED,
        WearProviderRuntimeState.NO_BINDING, WearProviderRuntimeState.INSTALL_BLOCKED,
        WearProviderRuntimeState.BINDING_APPROVAL_REQUIRED, WearProviderRuntimeState.CREDENTIAL_UNAVAILABLE,
        WearProviderRuntimeState.OFFLINE, WearProviderRuntimeState.PROVIDER_UNAVAILABLE,
    ).firstOrNull { it in blockers } ?: WearProviderRuntimeState.READY
}
