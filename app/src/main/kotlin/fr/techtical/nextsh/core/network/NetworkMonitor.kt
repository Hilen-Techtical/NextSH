// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class NetworkEvent {
    object Connected : NetworkEvent()
    object Lost : NetworkEvent()
}

/**
 * The flow of [NetworkMonitor.networkEvents], apart so that a unit test can check how it behaves with a
 * slow subscriber. A slow collector must not make the others lose an event: with a buffer of 1 and no
 * overflow policy, `tryEmit` failed as soon as one subscriber was one event behind, for ALL of them
 * (reconnection, liveness watchdog, offline gate). Room for a burst, and the oldest dropped beyond it,
 * so that `tryEmit` always succeeds and the freshest events are kept.
 */
internal fun newNetworkEventFlow(): MutableSharedFlow<NetworkEvent> = MutableSharedFlow(
    replay = 0,
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)

/**
 * The active network of this manager has the INTERNET capability (not necessarily validated), read on
 * the spot. No active network, no capabilities, or a lookup that throws (the system service can fail
 * on a dying process) all count as no network. The body of [NetworkMonitor.hasUsableNetwork], apart so
 * that a unit test needs neither the registration of a callback nor the platform classes it uses.
 */
internal fun ConnectivityManager.activeNetworkHasInternet(): Boolean = try {
    val network = activeNetwork
    val capabilities = network?.let { getNetworkCapabilities(it) }
    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
} catch (e: RuntimeException) {
    Timber.w("Etat du reseau illisible (${e::class.java.simpleName})")
    false
}

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * True iff the active network uses a transport that can route to LAN-private IPs:
     * WiFi, Ethernet, or VPN (covers VPN-over-cellular back to a home LAN).
     *
     * Excludes plain cellular (TRANSPORT_CELLULAR without VPN): the Desktop's
     * private IP is unreachable over mobile data, and the attempt can trigger the
     * Windows Phone Link "file transfer" interception on some Android OEMs.
     */
    private val _isOnLanTransport = MutableStateFlow(checkCurrentLanTransport())
    val isOnLanTransport: StateFlow<Boolean> = _isOnLanTransport.asStateFlow()

    private val _networkEvents = newNetworkEventFlow()
    val networkEvents: SharedFlow<NetworkEvent> = _networkEvents.asSharedFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.d("Network available")
            _isConnected.value = true
            _isOnLanTransport.value = isLanTransport(network)
            _networkEvents.tryEmit(NetworkEvent.Connected)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isOnLanTransport.value = hasLanTransport(capabilities)
        }

        override fun onLost(network: Network) {
            Timber.d("Network lost")
            _isConnected.value = false
            _isOnLanTransport.value = false
            _networkEvents.tryEmit(NetworkEvent.Lost)
        }
    }

    private fun hasLanTransport(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun isLanTransport(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasLanTransport(capabilities)
    }

    private fun checkCurrentLanTransport(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return isLanTransport(network)
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /**
     * A network that can carry a connection exists right now: the active network has the INTERNET
     * capability. Read on the spot from [ConnectivityManager.getActiveNetwork], so it stays right when
     * several networks are up (mobile data beside Wi-Fi), which [isConnected] does not: that flag goes
     * false when ANY network is lost.
     *
     * `NET_CAPABILITY_VALIDATED` is deliberately not required: a Wi-Fi without Internet is a usable
     * route to an SSH server on the local network. Any failure of the lookup counts as no network.
     */
    fun hasUsableNetwork(): Boolean = connectivityManager.activeNetworkHasInternet()

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
