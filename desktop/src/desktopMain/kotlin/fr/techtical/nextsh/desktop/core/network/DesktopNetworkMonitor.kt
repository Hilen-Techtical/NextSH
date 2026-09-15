// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.network

import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

private const val TAG = "DesktopNetworkMonitor"
private const val DEFAULT_POLL_INTERVAL_MS = 10_000L

/**
 * Monitor réseau Desktop : équivalent (contract-wise) de `NetworkMonitor` Android
 * mais sans `ConnectivityManager` : JDK n'offre pas de callback push, on polle
 * les interfaces réseau toutes les 10 s.
 *
 * Prédicat par défaut : au moins une `NetworkInterface` `isUp && !isLoopback`.
 * Le prédicat est injectable ([networkPredicate]) pour rendre les tests
 * déterministes : le scheduler Android ne peut pas être appelé depuis un test
 * unitaire JVM, mais ici un `() -> Boolean` sert à la fois de source de vérité
 * prod (`defaultPredicate`) et de hook de test.
 *
 * Lifecycle : [start] lance la coroutine de polling dans le scope passé, [stop]
 * l'annule. L'appel [start] est idempotent : utile pour `DesktopTunnelService`
 * qui peut redémarrer le monitor au déverrouillage du vault.
 */
class DesktopNetworkMonitor(
    private val networkPredicate: () -> Boolean = ::defaultPredicate,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    private val _isConnected = MutableStateFlow(networkPredicate())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var pollJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val current = try {
                    networkPredicate()
                } catch (e: Exception) {
                    Logger.w(TAG, "Erreur prédicat réseau: ${e.message}")
                    // En cas d'erreur on considère "connecté" pour ne pas casser
                    // l'app : le SSHClient remontera l'erreur si besoin.
                    true
                }
                if (_isConnected.value != current) {
                    _isConnected.value = current
                    Logger.d(TAG, "Connectivité : ${if (current) "OK" else "perdue"}")
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Utilitaire test : force une valeur sans attendre le prochain poll. */
    internal fun setConnectedForTest(value: Boolean) {
        _isConnected.value = value
    }

    companion object {
        /**
         * Prédicat par défaut : vrai si au moins une interface réseau est up
         * et non-loopback. Valable sur JDK 17 (pas d'API récente).
         */
        fun defaultPredicate(): Boolean {
            return try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return false
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (iface.isUp && !iface.isLoopback) return true
                }
                false
            } catch (e: Exception) {
                Logger.w(TAG, "Erreur énumération interfaces: ${e.message}")
                true
            }
        }
    }
}
