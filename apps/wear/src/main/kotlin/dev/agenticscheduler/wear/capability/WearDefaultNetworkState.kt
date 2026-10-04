package dev.agenticscheduler.wear.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Called under observer's lock. An old route's late loss/capabilities cannot replace the new default. */
internal class WearDefaultNetworkState<N> {
    private var current: N? = null
    private val mutableFacts = MutableStateFlow(WearNetworkFacts.OFFLINE)
    val facts = mutableFacts.asStateFlow()
    fun available(network: N) {
        if (current == network) return
        current = network; mutableFacts.value = WearNetworkFacts(true, false, false)
    }
    fun capabilities(network: N, facts: WearNetworkFacts) { if (current == network) mutableFacts.value = facts }
    fun lost(network: N) { if (current == network) clear() }
    fun clear() { current = null; mutableFacts.value = WearNetworkFacts.OFFLINE }
}
