// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.ui.browser.DesktopBrowserSessionHolder
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DesktopTunnelService"

private const val INITIAL_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 60_000L
// Unbounded retries intentional: users expect tunnels to self-heal after any
// outage length (coffee break, commute between Wi-Fi networks, overnight
// laptop suspend). Capping at 5 attempts meant a 30-second Wi-Fi drop could
// exhaust the budget before recovery and leave the tunnel stuck in ERROR.
// Backoff caps at MAX_BACKOFF_MS, so server load stays bounded.

/**
 * Service conceptuel (pas un Windows/Linux service OS), orchestre le cycle
 * de vie des tunnels Desktop pour la durée du process.
 *
 * Responsabilités :
 *  - auto-démarrage des tunnels marqués `autoStart=true` après déverrouillage
 *    du vault,
 *  - arrêt propre de tous les tunnels à la fermeture de l'app,
 *  - observation du `NetworkMonitor` : perte de connectivité → statuts
 *    `RECONNECTING` (le SSHClient SSHJ remontera naturellement une IOException
 *    dès que le forwarder sera sollicité), retour de connectivité → restart
 *    avec backoff exponentiel plafonné à 60s.
 *
 * Exposition : [tunnelStates], délégué au [DesktopTunnelManager]. Le VM UI
 * consomme directement ce `StateFlow`.
 */
class DesktopTunnelService(
    private val tunnelManager: DesktopTunnelManager,
    private val networkMonitor: DesktopNetworkMonitor,
    private val tunnelRepository: TunnelRepository,
    private val hostRepository: HostRepository,
    private val appScope: AppScope,
    private val browserSessionHolder: DesktopBrowserSessionHolder? = null,
) {

    /** Délégation directe : l'UI observe les états de tunnels via ce flow. */
    val tunnelStates: StateFlow<Map<String, TunnelState>> = tunnelManager.tunnelStates

    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private var networkWatchJob: Job? = null
    private var tunnelStateWatchJob: Job? = null

    @Volatile
    private var started = false

    /**
     * Appelé après déverrouillage PIN réussi. Lance le monitor réseau, puis
     * démarre tous les tunnels marqués `autoStart=true`. Idempotent :
     * appels multiples → no-op.
     */
    fun onVaultUnlocked() {
        if (started) return
        started = true

        Logger.d(TAG, "Vault déverrouillé : démarrage monitor + auto-start tunnels")
        networkMonitor.start(appScope.coroutineScope)
        startNetworkWatch()
        startTunnelStateWatch()

        appScope.coroutineScope.launch {
            try {
                val autoStart = tunnelRepository.getAutoStartTunnels()
                Logger.d(TAG, "Auto-start : ${autoStart.size} tunnel(s)")
                autoStart.forEach { config ->
                    val result = startTunnel(config)
                    // Open the in-app browser automatically for LOCAL_FORWARD tunnels that
                    // have openBrowserOnConnect=true and started successfully.
                    // Guards:
                    // - ACTIVE: Success is not enough. A start the user stopped during
                    //   the handshake also answers Success (a deliberate stop is not an
                    //   error), and so does a Start the manager ignored.
                    // - hasSession: do not double-show an already visible overlay.
                    // - isUserDismissed: do not reopen a tunnel the user closed earlier
                    //   (otherwise keepAliveAfterBrowserClose + idle reconnections
                    //   would re-pop the overlay every cycle).
                    if (result is SshResult.Success
                        && tunnelManager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE
                        && config.type == TunnelType.LOCAL_FORWARD
                        && config.openBrowserOnConnect
                        && browserSessionHolder?.hasSession(config.id) == false
                        && browserSessionHolder.isUserDismissed(config.id).not()
                    ) {
                        browserSessionHolder.show(config.id)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Erreur auto-start tunnels", e)
            }
        }
    }

    /**
     * Appelé à la fermeture de l'app (`DesktopContainer.shutdown`). Annule les
     * jobs de reconnect, stoppe le monitor, puis fire-and-forget une coroutine
     * `stopAll` qui ferme chaque `SSHClient` : on s'appuie sur les socket
     * timeouts JVM pour garantir qu'aucun thread ne survit à la JVM qui va
     * exiter juste après.
     *
     * Variante suspend disponible via [onAppExitSuspend] pour les callers qui
     * veulent attendre la fin du teardown (tests, scenarios scripted).
     */
    fun onAppExit() {
        Logger.d(TAG, "Shutdown : arrêt de tous les tunnels")
        networkWatchJob?.cancel()
        networkWatchJob = null
        tunnelStateWatchJob?.cancel()
        tunnelStateWatchJob = null
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        networkMonitor.stop()
        appScope.coroutineScope.launch(Dispatchers.IO) {
            try {
                tunnelManager.stopAll()
            } catch (e: Exception) {
                Logger.e(TAG, "Erreur stopAll à l'exit", e)
            }
        }
        started = false
    }

    /**
     * Variante suspend de [onAppExit] : attend le teardown. Utile en test.
     */
    suspend fun onAppExitSuspend() {
        networkWatchJob?.cancel()
        networkWatchJob = null
        tunnelStateWatchJob?.cancel()
        tunnelStateWatchJob = null
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        networkMonitor.stop()
        try {
            tunnelManager.stopAll()
        } catch (e: Exception) {
            Logger.e(TAG, "Erreur stopAll à l'exit", e)
        }
        started = false
    }

    /** Démarrage UI d'un tunnel (bouton Play). Délègue au manager + hook host lookup. */
    suspend fun startTunnel(config: TunnelConfig): SshResult<Unit> {
        return tunnelManager.startTunnel(config) { hostId -> hostRepository.getById(hostId) }
    }

    /** Arrêt UI d'un tunnel (bouton Stop). */
    suspend fun stopTunnel(tunnelId: String): SshResult<Unit> {
        reconnectJobs.remove(tunnelId)?.cancel()
        return tunnelManager.stopTunnel(tunnelId)
    }

    /**
     * Démarre tous les profils de tunnels actuellement connus, à l'exception
     * de ceux déjà en cours (ACTIVE/STARTING/RECONNECTING).
     */
    suspend fun startAllTunnels(): List<Pair<String, SshResult<Unit>>> {
        val configs = try {
            tunnelRepository.observeAll().first()
        } catch (e: Exception) {
            Logger.e(TAG, "Impossible de lire la liste des tunnels", e)
            return emptyList()
        }
        // Parallel start: a tunnel hanging on a slow handshake / TCP timeout
        // must NOT block the others. Each `startTunnel` runs in its own
        // child coroutine ; the `coroutineScope` waits for all of them.
        val toStart = configs.filter { config ->
            val status = tunnelManager.getTunnelState(config.id)?.status
            status != TunnelStatus.ACTIVE
                && status != TunnelStatus.STARTING
                && status != TunnelStatus.RECONNECTING
        }
        return coroutineScope {
            toStart
                .map { config -> async { config.id to startTunnel(config) } }
                .awaitAll()
        }
    }

    /** Arrête tous les tunnels actifs. */
    suspend fun stopAllTunnels() {
        // Parallel stop: same reasoning as startAllTunnels. A tunnel that
        // hangs on shutdown (e.g. slow socket close on a dead route) must
        // not block the others.
        //
        // Source of ids = `tunnelStates` (not just `activeIds()`) so we also
        // pick up tunnels currently in STARTING/RECONNECTING/ERROR, those
        // aren't yet in `active` map but their startTunnel coroutine may be
        // blocked on connect/auth and needs the cancel signal.
        val ids = tunnelManager.tunnelStates.value
            .filterValues { state ->
                state.status == TunnelStatus.ACTIVE
                    || state.status == TunnelStatus.STARTING
                    || state.status == TunnelStatus.RECONNECTING
                    || state.status == TunnelStatus.ERROR
            }
            .keys
            .toList()
        coroutineScope {
            ids.map { id -> launch { stopTunnel(id) } }.joinAll()
        }
    }

    // ── Network watch ────────────────────────────────────────────────────────

    private fun startNetworkWatch() {
        if (networkWatchJob?.isActive == true) return
        networkWatchJob = appScope.coroutineScope.launch {
            var wasConnected = networkMonitor.isConnected.value
            networkMonitor.isConnected.collect { connected ->
                if (connected != wasConnected) {
                    Logger.d(TAG, "Changement connectivité : wasConnected=$wasConnected → connected=$connected")
                    if (!connected) {
                        markRunningAsReconnecting()
                    } else {
                        restartReconnectingTunnels()
                    }
                    wasConnected = connected
                }
            }
        }
    }

    private fun markRunningAsReconnecting() {
        tunnelManager.tunnelStates.value.forEach { (id, state) ->
            if (state.status == TunnelStatus.ACTIVE || state.status == TunnelStatus.STARTING) {
                tunnelManager.updateStatus(id, TunnelStatus.RECONNECTING)
                Logger.d(TAG, "Tunnel ${state.config.label} marqué RECONNECTING (perte réseau)")
            }
        }
    }

    /**
     * Watches [DesktopTunnelManager.tunnelStates] for transitions to RECONNECTING
     * and schedules a reconnect.
     *
     * Why this matters more than [startNetworkWatch]: the [DesktopNetworkMonitor]
     * heuristic (any UP non-loopback interface counts as "connected") is blind
     * to per-route failures, on a laptop with Wi-Fi + Ethernet + VPN, toggling
     * Wi-Fi off never flips the monitor flag, so the old code never triggered a
     * reconnect. This watch is driven by the SSHJ `DisconnectListener` installed
     * on each tunnel's `SSHClient`, which catches the actual session death
     * regardless of OS network state.
     *
     * Deduplicated via [reconnectJobs]: multiple state emissions for the same
     * id while a reconnect is pending are no-ops.
     */
    private fun startTunnelStateWatch() {
        if (tunnelStateWatchJob?.isActive == true) return
        tunnelStateWatchJob = appScope.coroutineScope.launch {
            val lastStatus = mutableMapOf<String, TunnelStatus>()
            tunnelManager.tunnelStates.collect { states ->
                states.forEach { (id, state) ->
                    val prev = lastStatus[id]
                    if (state.status == TunnelStatus.RECONNECTING && prev != TunnelStatus.RECONNECTING) {
                        scheduleReconnect(state.config)
                    }
                    lastStatus[id] = state.status
                }
                // Drop entries for fully-stopped tunnels so a later restart re-triggers.
                lastStatus.keys.retainAll(states.keys)
            }
        }
    }

    private fun scheduleReconnect(config: TunnelConfig) {
        if (tunnelManager.wasManuallyStopped(config.id)) return
        if (reconnectJobs.containsKey(config.id)) return
        Logger.d(TAG, "Tunnel ${config.label} perdu : planification reconnexion")
        val job = appScope.coroutineScope.launch {
            try {
                // Clear any residual active-entry. `onTunnelLost` already does this
                // on the SSHJ-disconnect path, but the network-monitor path
                // (`markRunningAsReconnecting`) flips state without touching the
                // `active` map: without this call, `attemptReconnect → startTunnel`
                // short-circuits as "already running" and the factory is never
                // re-invoked, so no reconnect happens.
                //
                // MUST use `releaseForReconnect` (not `stopTunnel`): the latter
                // flags the tunnel as manually stopped, which would abort the
                // backoff loop below.
                tunnelManager.releaseForReconnect(config.id)
                attemptReconnect(config)
            } finally {
                reconnectJobs.remove(config.id)
            }
        }
        reconnectJobs[config.id] = job
    }

    /**
     * Network is back : reconnects the tunnels left RECONNECTING that have no
     * reconnect job yet. The state watch normally holds one for each of them,
     * so this is a safety net, and `internal` only so that the tests reach it.
     */
    internal fun restartReconnectingTunnels() {
        val toRestart = tunnelManager.tunnelStates.value
            .filter { (_, s) -> s.status == TunnelStatus.RECONNECTING && !tunnelManager.wasManuallyStopped(s.config.id) }
            .map { it.value.config }

        toRestart.forEach { config ->
            if (reconnectJobs.containsKey(config.id)) return@forEach
            val job = appScope.coroutineScope.launch {
                try {
                    // Free the old client if it is still in memory, then reconnect
                    // with backoff. MUST be `releaseForReconnect`, not `stopTunnel`,
                    // for the reason given in [scheduleReconnect] : `stopTunnel` flags
                    // the tunnel as manually stopped, `attemptReconnect` then sees
                    // the flag and gives up at once, leaving the tunnel RECONNECTING
                    // for good.
                    tunnelManager.releaseForReconnect(config.id)
                    attemptReconnect(config)
                } finally {
                    reconnectJobs.remove(config.id)
                }
            }
            reconnectJobs[config.id] = job
        }
    }

    private suspend fun attemptReconnect(config: TunnelConfig) {
        var delayMs = INITIAL_BACKOFF_MS
        var attempt = 0
        while (true) {
            // Keep the UI at RECONNECTING during the whole backoff cycle. Each
            // failed `startTunnel` flips the status to ERROR (via setError);
            // restoring it here avoids RECONNECTING ↔ ERROR flicker and lets
            // the tunnel row stay in "reconnecting" as long as we're trying.
            //
            // Never over a live state, though : STARTING belongs to a start in
            // flight, ACTIVE to a tunnel that came up on its own. The test and
            // the write are one compare-and-set, so a status written by that
            // start between the two cannot be overwritten. ACTIVE ends the loop,
            // but only when the manager really holds the tunnel : a stale ACTIVE
            // status with no live entry (a released session) is not a reason to
            // stop retrying, the round goes on and the start below rewrites it.
            val seen = tunnelManager.updateStatusIf(config.id, TunnelStatus.RECONNECTING) {
                it != TunnelStatus.STARTING && it != TunnelStatus.ACTIVE
            }
            if (seen == TunnelStatus.ACTIVE && config.id in tunnelManager.activeIds()) {
                Logger.d(TAG, "Reconnexion ${config.label} abandonnée : déjà ACTIVE")
                return
            }
            delay(delayMs)
            // Allow an explicit stop or a successful restart (from elsewhere)
            // to break the loop.
            if (tunnelManager.wasManuallyStopped(config.id)) {
                Logger.d(TAG, "Reconnexion ${config.label} abandonnée : arrêt manuel")
                return
            }
            if (tunnelManager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE &&
                config.id in tunnelManager.activeIds()
            ) {
                Logger.d(TAG, "Reconnexion ${config.label} abandonnée : déjà ACTIVE")
                return
            }
            // Another start of this tunnel is still in flight (a Start the user
            // issued, or the one a network drop flipped to RECONNECTING). The
            // manager would ignore ours and answer Success, which we would take
            // for "reconnected" and leave the loop : if that start then fails,
            // nobody retries. So wait one more backoff and look again: ACTIVE
            // ends the loop above, a failure lets us start for real.
            //
            // The skipped round is not an attempt : it neither bumps `attempt`
            // (a log counter, the loop has no limit) nor doubles `delayMs`
            // (the backoff protects the server from failed connects, and this
            // round opened none).
            if (tunnelManager.isStarting(config.id)) {
                Logger.d(TAG, "Reconnexion ${config.label} reportée : un démarrage est déjà en cours")
                continue
            }
            attempt++
            Logger.d(TAG, "Reconnexion tunnel ${config.label} tentative $attempt")
            when (startTunnel(config)) {
                is SshResult.Success -> {
                    if (config.id in tunnelManager.activeIds()) {
                        Logger.d(TAG, "Tunnel ${config.label} reconnecté (tentative $attempt)")
                        return
                    }
                    // Success without a live tunnel is not a reconnection : the
                    // manager ignored this Start (another one took the id between
                    // our `isStarting` check and the call), or a manual stop
                    // cancelled it. The stop ends the loop ; otherwise wait one
                    // more backoff and look again (ACTIVE ends it above).
                    if (tunnelManager.wasManuallyStopped(config.id)) {
                        Logger.d(TAG, "Reconnexion ${config.label} abandonnée : arrêt manuel")
                        return
                    }
                    Logger.d(TAG, "Reconnexion ${config.label} : démarrage ignoré ou sans tunnel actif, on réessaie")
                    continue
                }
                is SshResult.Error -> {
                    delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }
}
