package dev.agenticscheduler.wear.capability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Separate from D8 sync callbacks. Never performs a public ping or submits an Agent command. */
class WearNetworkObserver(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var registered = false
    private val projection = WearDefaultNetworkState<Network>()
    val facts = projection.facts
    val isRegistered: Boolean get() = synchronized(lock) { registered }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(lock) {
            if (registered) projection.available(network)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
            if (registered) projection.capabilities(network, mapCapabilities(capabilities))
        }
        override fun onLost(network: Network) = synchronized(lock) {
            if (registered) projection.lost(network)
        }
    }
    fun start() = synchronized(lock) {
        if (registered) return@synchronized
        registered = true
        try {
            manager.registerDefaultNetworkCallback(callback)
            manager.activeNetwork?.let { network ->
                projection.available(network)
                manager.getNetworkCapabilities(network)?.let { projection.capabilities(network, mapCapabilities(it)) }
            }
        } catch (failure: SecurityException) { registered = false; throw failure }
    }
    override fun close() = synchronized(lock) {
        if (registered) { registered = false; manager.unregisterNetworkCallback(callback) }
        projection.clear()
    }
    companion object {
        fun mapCapabilities(value: NetworkCapabilities) = WearNetworkFacts(true,
            value.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }
}
