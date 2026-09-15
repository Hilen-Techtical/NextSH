// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private val _networkEvents = MutableSharedFlow<NetworkEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
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

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
