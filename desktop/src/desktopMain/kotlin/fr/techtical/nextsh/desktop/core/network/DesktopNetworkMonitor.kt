// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.network

import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
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
    /**
     * Source de l'ensemble des interfaces actives non loopback, une entrée par interface (nom et
     * adresses). Injectable pour les tests, comme [networkPredicate].
     */
    private val interfaceSnapshot: () -> Set<String> = ::defaultInterfaceSnapshot,
) {

    private val _isConnected = MutableStateFlow(networkPredicate())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var lastSnapshot: Set<String>? = readSnapshot()

    private val _interfaceEpoch = MutableStateFlow(0L)

    /**
     * Époque incrémentée à chaque CHANGEMENT de l'ensemble des interfaces actives non loopback
     * (interface ajoutée ou retirée, adresse nouvelle). Utile quand [isConnected] ne bouge jamais
     * (cartes virtuelles toujours « up » : VMware, Hyper-V...) : le Wi-Fi qui tombe puis revient
     * change l'ensemble sans jamais le vider. Aucune carte n'est ignorée.
     */
    val interfaceEpoch: StateFlow<Long> = _interfaceEpoch.asStateFlow()

    private var pollJob: Job? = null

    private fun readSnapshot(): Set<String>? = try {
        interfaceSnapshot()
    } catch (e: Exception) {
        Logger.w(TAG, "Erreur instantané interfaces (${errorClass(e)})")
        null
    }

    /** Une sonde : met à jour [isConnected] et [interfaceEpoch]. `internal` pour les tests. */
    internal fun pollNow() {
        val current = try {
            networkPredicate()
        } catch (e: Exception) {
            Logger.w(TAG, "Erreur prédicat réseau (${errorClass(e)})")
            // En cas d'erreur on considère "connecté" pour ne pas casser
            // l'app : le SSHClient remontera l'erreur si besoin.
            true
        }
        if (_isConnected.value != current) {
            _isConnected.value = current
            Logger.d(TAG, "Connectivité : ${if (current) "OK" else "perdue"}")
        }
        // Un instantané illisible ne change rien : on garde le précédent.
        val snapshot = readSnapshot()
        if (snapshot != null && snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            _interfaceEpoch.value = _interfaceEpoch.value + 1
            Logger.d(TAG, "Interfaces actives modifiées")
        }
    }

    fun start(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollNow()
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
         * Instantané par défaut : pour chaque interface up et non loopback, son nom et ses adresses
         * (triées), sous la forme `nom|adresse,adresse`.
         */
        fun defaultInterfaceSnapshot(): Set<String> {
            val result = mutableSetOf<String>()
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addresses = iface.inetAddresses.toList().map { it.hostAddress.orEmpty() }.sorted()
                    result += "${iface.name}|${addresses.joinToString(",")}"
                }
            }
            return result
        }

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
                Logger.w(TAG, "Erreur énumération interfaces (${errorClass(e)})")
                true
            }
        }
    }
}
