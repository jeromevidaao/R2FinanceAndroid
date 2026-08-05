package com.cleaningbutton.r2finance.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks default network availability and triggers offline-first sync on reconnect.
 */
class ConnectivityMonitor(
    context: Context,
    private val onOnline: suspend () -> Unit,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _online = MutableStateFlow(isCurrentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val wasOffline = !_online.value
            _online.value = true
            if (wasOffline) {
                scope.launch {
                    runCatching { onOnline() }
                }
            }
        }

        override fun onLost(network: Network) {
            _online.value = isCurrentlyOnline()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val wasOffline = !_online.value
            _online.value = ok
            if (ok && wasOffline) {
                scope.launch {
                    runCatching { onOnline() }
                }
            }
        }
    }

    fun start() {
        _online.value = isCurrentlyOnline()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, callback)
        }
        // Cold start online: flush any pending from last offline session.
        if (_online.value) {
            scope.launch {
                runCatching { onOnline() }
            }
        }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun isCurrentlyOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
