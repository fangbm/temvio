package dev.agenticscheduler.wear.capability

import kotlin.test.*

class WearDefaultNetworkStateTest {
    @Test fun availableCapabilitiesLossAreSeparateFacts() {
        val state = WearDefaultNetworkState<String>(); state.available("default")
        assertTrue(state.facts.value.defaultRouteAvailable); assertFalse(state.facts.value.publicInternetRoute)
        state.capabilities("default", WearNetworkFacts(true, true, true)); assertTrue(state.facts.value.publicInternetRoute)
        state.available("default"); assertTrue(state.facts.value.publicInternetRoute)
        state.capabilities("default", WearNetworkFacts(true, true, false)); assertFalse(state.facts.value.publicInternetRoute)
        state.lost("default"); assertEquals(WearNetworkFacts.OFFLINE, state.facts.value)
    }
    @Test fun staleOldNetworkCannotOverwriteOrRemoveNewRoute() {
        val state = WearDefaultNetworkState<String>(); state.available("old"); state.available("new")
        state.capabilities("new", WearNetworkFacts(true, true, true)); state.lost("old")
        state.capabilities("old", WearNetworkFacts(true, false, false)); assertTrue(state.facts.value.publicInternetRoute)
        state.clear(); state.capabilities("new", WearNetworkFacts(true, true, true)); assertEquals(WearNetworkFacts.OFFLINE, state.facts.value)
    }
}
