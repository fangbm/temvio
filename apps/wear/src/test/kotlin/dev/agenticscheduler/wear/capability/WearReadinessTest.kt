package dev.agenticscheduler.wear.capability

import kotlin.test.*

class WearReadinessTest {
    private val capability = WearCapabilityFacts(true, true, OnDeviceSttAvailability.UNSUPPORTED, SpeechPermission.NOT_REQUESTED, "en-US")
    private val provider = WearProviderFacts(true, true, true, false, true, true)
    private fun ready(c: WearCapabilityFacts = capability, enabled: Boolean = true, p: WearProviderFacts = provider,
        network: Boolean = true, failure: WearProbeFailure? = null) = WearReadiness(c, enabled, p, network, failure)
    @Test fun textWithoutSpeechRemainsSupported() { assertTrue(ready().aiEntrySupported) }
    @Test fun allInputUnsupported() { assertFalse(ready(c = capability.copy(textInputSupported = false)).aiEntrySupported) }
    @Test fun unsupportedPlatformCannotEnableEntry() {
        val r = ready(c = capability.copy(platformSupported = false)); assertFalse(r.effectiveAiEntryEnabled); assertEquals(WearProviderRuntimeState.ENTRY_UNSUPPORTED, r.runtimeState)
    }
    @Test fun defaultOffIsIndependentOfProviderSuccess() {
        val r = ready(enabled = false); assertTrue(r.providerReady); assertFalse(r.requestReady); assertEquals(WearProviderRuntimeState.ENTRY_DISABLED, r.runtimeState)
    }
    @Test fun noBinding() { assertEquals(WearProviderRuntimeState.NO_BINDING, ready(p = provider.copy(bindingExists = false)).runtimeState) }
    @Test fun exactApprovalRequired() { assertEquals(WearProviderRuntimeState.BINDING_APPROVAL_REQUIRED, ready(p = provider.copy(exactBindingApproved = false)).runtimeState) }
    @Test fun realSecretRequired() { val r = ready(p = provider.copy(matchingSecretAvailable = false)); assertFalse(r.providerReady); assertEquals(WearProviderRuntimeState.CREDENTIAL_UNAVAILABLE, r.runtimeState) }
    @Test fun installBlocked() { assertEquals(WearProviderRuntimeState.INSTALL_BLOCKED, ready(p = provider.copy(installBlocked = true)).runtimeState) }
    @Test fun offlineDoesNotChangeCapabilityPreferenceBinding() {
        val r = ready(network = false); assertEquals(WearProviderRuntimeState.OFFLINE, r.runtimeState)
        assertTrue(r.aiEntrySupported && r.userEnabledAiEntry && r.providerReady); assertFalse(r.requestReady)
        assertEquals(WearProviderRuntimeState.READY, r.copy(networkReachable = true).runtimeState)
    }
    @Test fun deniedMicrophoneDoesNotDisableText() { assertTrue(ready(c = capability.copy(speechPermission = SpeechPermission.DENIED)).aiEntrySupported) }
    @Test fun readyAndProbeHealthAreDistinct() {
        val r = ready(failure = WearProbeFailure.AUTHENTICATION); assertTrue(r.providerReady && r.requestReady)
        assertEquals(WearProviderRuntimeState.PROVIDER_UNAVAILABLE, r.runtimeState); assertTrue(r.provider.bindingExists)
    }
    @Test fun credentialFreeDoesNotNeedSecret() { assertTrue(ready(p = provider.copy(credentialRequired = false, matchingSecretAvailable = false)).providerReady) }
    @Test fun multipleBlockersStayObservable() {
        val r = ready(enabled = false, p = provider.copy(installBlocked = true, matchingSecretAvailable = false), network = false)
        assertEquals(setOf(WearProviderRuntimeState.ENTRY_DISABLED, WearProviderRuntimeState.INSTALL_BLOCKED,
            WearProviderRuntimeState.CREDENTIAL_UNAVAILABLE, WearProviderRuntimeState.OFFLINE), r.blockers)
    }
    @Test fun publicRouteRequiresBothCapabilitiesButLocalDoesNot() {
        for (internet in listOf(false, true)) for (validated in listOf(false, true)) {
            val n = WearNetworkFacts(true, internet, validated)
            assertEquals(internet && validated, n.reachable(WearEndpointRoute.PUBLIC_HTTPS))
            assertTrue(n.reachable(WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL))
        }
        assertFalse(WearNetworkFacts.OFFLINE.reachable(WearEndpointRoute.EXPLICIT_CREDENTIAL_FREE_LOCAL))
    }
}
