// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_deleted
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_network_lost
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_unreadable
import fr.techtical.nextsh.desktop.ui.browser.DesktopBrowserSessionHolder
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DesktopTunnelService"

private val IPV4_LITERAL = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

/**
 * Is [hostname] a loopback address by its spelling alone (no DNS resolution): a literal 127.0.0.0/8
 * address, `::1` (also in brackets or in its long form) or `localhost`, case-insensitive.
 */
internal fun isLoopbackHostname(hostname: String): Boolean {
    val name = hostname.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (name == "localhost" || name == "localhost.") return true
    if (name == "::1" || name == "0:0:0:0:0:0:0:1") return true
    val match = IPV4_LITERAL.matchEntire(name) ?: return false
    val octets = match.groupValues.drop(1).map { it.toInt() }
    return octets.all { it <= 255 } && octets[0] == 127
}

/** Statuses that hold a connection, a pending connect or a reconnect loop: what a stop has to cancel. */
private val RUNNING_STATUSES = setOf(
    TunnelStatus.ACTIVE,
    TunnelStatus.STARTING,
    TunnelStatus.RECONNECTING,
    TunnelStatus.ERROR,
)

/**
 * Statuses of a tunnel that was really running, the only ones the user is told about when it is stopped
 * because it was deleted elsewhere (plus a tunnel caught in a reconnect attempt, see
 * `DesktopTunnelService.wasReallyRunning`). A tunnel in ERROR, or still STARTING for the first time, never
 * was up: it is stopped all the same, silently. RECONNECTING counts, it was up before the link dropped.
 */
private val ANNOUNCED_STATUSES = setOf(TunnelStatus.ACTIVE, TunnelStatus.RECONNECTING)

/** Bound of [DesktopTunnelService.deletionNotice]: the banner never has to list more than a handful. */
private const val MAX_NOTICE_TUNNELS = 50

/** Delay before the deletion watch looks again after its source failed: doubles up to the maximum. */
private const val WATCH_RETRY_INITIAL_MS = 1_000L
private const val WATCH_RETRY_MAX_MS = 30_000L

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
 *    avec backoff exponentiel plafonné à 60s,
 *  - ouverture automatique du navigateur intégré quand un tunnel
 *    `openBrowserOnConnect` passe ACTIVE, quel que soit l'écran affiché.
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
    /**
     * Cleanup run at unlock, BEFORE the auto-start: tombstones the live tunnels of deleted hosts so that
     * they are neither listed nor started. The container wires `DesktopOrphanTunnelRepair` here. No-op by
     * default.
     */
    private val repairOrphanTunnels: suspend () -> Unit = {},
) {

    /** Délégation directe : l'UI observe les états de tunnels via ce flow. */
    val tunnelStates: StateFlow<Map<String, TunnelState>> = tunnelManager.tunnelStates

    private val _deletionStops = MutableSharedFlow<TunnelDeletionStop>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Event, once per pass: tunnels that were running and were stopped because they, or their host, were
     * deleted on another device (sync, conflict resolution, repair). Nobody listening at that moment means
     * the event is lost: the tray listens for as long as it is registered, and [deletionNotice] keeps the
     * information for the screen. Carries labels: never log it.
     */
    val deletionStops: SharedFlow<TunnelDeletionStop> = _deletionStops.asSharedFlow()

    private val _deletionNotice = MutableStateFlow<List<StoppedTunnel>>(emptyList())

    /**
     * The tunnels stopped by a deletion since the user last dismissed the notice (see
     * [dismissDeletionNotice]): the banner of the tunnel list, which must still be there when the user opens
     * the screen after the stop. Most recent last, bounded.
     */
    val deletionNotice: StateFlow<List<StoppedTunnel>> = _deletionNotice.asStateFlow()

    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private var networkWatchJob: Job? = null
    private var tunnelStateWatchJob: Job? = null
    private var deletionWatchJob: Job? = null

    /** Guards [tunnelStateWatchJob]: the watch can be started from the unlock and from the first start. */
    private val stateWatchLock = Any()

    /**
     * Ids of the tunnels being stopped because they were deleted. The deletion watch and the reconnect
     * loop can both notice the same tunnel; only the one that claims the id stops it and reports it.
     */
    private val deletionStopsInFlight = ConcurrentHashMap.newKeySet<String>()

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
        wiped = false

        Logger.d(TAG, "Vault déverrouillé : démarrage monitor + auto-start tunnels")
        networkMonitor.start(appScope.coroutineScope)
        startNetworkWatch()
        ensureTunnelStateWatch()
        startDeletionWatch()

        autoStartJob = appScope.coroutineScope.launch {
            runOrphanRepair()
            try {
                val autoStart = tunnelRepository.getAutoStartTunnels()
                Logger.d(TAG, "Auto-start : ${autoStart.size} tunnel(s)")
                // The in-app browser of these tunnels is opened by the tunnel state watch (see
                // [ensureTunnelStateWatch]) when they become ACTIVE, like for a tunnel started by hand.
                // Success alone is not enough: a start the user stopped during the handshake also
                // answers Success (a deliberate stop is not an error), and so does a Start the manager
                // ignored. Neither ever writes ACTIVE, so neither opens the browser.
                autoStart.forEach { config -> startTunnel(config) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Auto-start tunnels failed (${errorClass(e)})")
            }
        }
    }

    /** Un nettoyage qui échoue ne doit pas empêcher l'auto-start : on journalise la classe de l'exception. */
    private suspend fun runOrphanRepair() {
        try {
            repairOrphanTunnels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Réparation des tunnels orphelins échouée (${errorClass(e)})")
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
        cancelWatchesAndReconnects()
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
        cancelWatchesAndReconnects()
        networkMonitor.stop()
        try {
            tunnelManager.stopAll()
        } catch (e: Exception) {
            Logger.e(TAG, "Erreur stopAll à l'exit", e)
        }
        started = false
    }

    /**
     * Called by the vault wipe, before anything is erased: stops EVERY tunnel for good and forgets all of
     * them, so that nothing reconnects against a vault and a database that are about to be empty.
     *
     * Order matters. The watches and the reconnect loops are cancelled FIRST: the state watch would
     * otherwise schedule a reconnect for each tunnel the stop below takes down, and a loop in the middle of
     * an attempt would bring one back. Then every running tunnel is stopped through the manager, which flags
     * it as manually stopped (no automatic reconnect), and the manager forgets every state. No tunnel is
     * reported as deleted elsewhere: the deletion watch is off and the notice is emptied, the user asked for
     * this. Last, the in-app browser overlay is released, and [started] goes back to false so that the next
     * [onVaultUnlocked] (the first one after the new vault) arms everything again.
     *
     * Best effort: a step that fails does not stop the following ones (the failure is logged by class only).
     */
    suspend fun onVaultWiped(autoStartJoinTimeoutMs: Long = 5_000L) {
        Logger.d(TAG, "Vault efface : arret de tous les tunnels")
        // First: from now on no start is accepted (the auto-start loop, a reconnect, a click), until the
        // next unlock. The auto-start loop is cancelled too, but NOT awaited yet: it may sit in a blocking
        // handshake that the cancellation does not interrupt, only the stop below cuts that client.
        wiped = true
        val autoStart = autoStartJob
        autoStart?.cancel()
        autoStartJob = null
        cancelWatchesAndReconnects()
        networkMonitor.stop()
        try {
            stopAllTunnels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Arret des tunnels a l'effacement du vault echoue (${errorClass(e)})")
        }
        // Bounded wait, once the stop has cut the clients: the loop must not start one more tunnel behind us.
        withTimeoutOrNull(autoStartJoinTimeoutMs) { autoStart?.join() }
        try {
            // The manual stop flags stay: a start still in its handshake sees its flag and bails.
            tunnelManager.stopAll(clearManualStops = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "stopAll a l'effacement du vault echoue (${errorClass(e)})")
        }
        deletionStopsInFlight.clear()
        _deletionNotice.value = emptyList()
        try {
            // The holder disposes the Chromium browser: that belongs to the UI thread (the Swing EDT).
            withContext(Dispatchers.Main) { browserSessionHolder?.close() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Fermeture du navigateur integre echouee (${errorClass(e)})")
        }
        started = false
    }

    /** True from [onVaultWiped] until the next [onVaultUnlocked]: no tunnel start is accepted meanwhile. */
    @Volatile
    private var wiped = false

    /** The auto-start loop of the last unlock, kept so that the vault wipe can cancel it. */
    @Volatile
    private var autoStartJob: Job? = null

    /** Cancels the three watches and every reconnect loop. */
    private fun cancelWatchesAndReconnects() {
        networkWatchJob?.cancel()
        networkWatchJob = null
        cancelTunnelStateWatch()
        deletionWatchJob?.cancel()
        deletionWatchJob = null
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
    }

    /**
     * Démarrage d'un tunnel (bouton Play, auto-start, « Tout démarrer »). Refuse un tunnel dont la ligne ou
     * l'hôte n'est plus vivant (supprimé ailleurs, interface périmée) : le manager laisserait sinon un état
     * ERROR qui compte comme un tunnel en cours. Le tunnel démarre depuis sa ligne en base, relue ici.
     *
     * Garantit que la surveillance des états tourne ([ensureTunnelStateWatch]) : lors de la première session
     * après la création du coffre il n'y a pas eu de déverrouillage, et sans elle le tunnel démarré n'aurait ni
     * ouverture automatique du navigateur ni reconnexion. Appelée AVANT le démarrage, pour que les statuts de
     * départ ne contiennent pas ce tunnel.
     */
    suspend fun startTunnel(config: TunnelConfig): SshResult<Unit> {
        // After a vault wipe nothing starts until the next unlock: the rows and the secrets are gone, and a
        // start here would re-arm the state watch and survive the wipe.
        if (wiped) return SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_deleted))
        ensureTunnelStateWatch()
        return when (val liveness = checkLiveness(config.id)) {
            is Liveness.Live -> startWithoutCheck(liveness.tunnel)
            Liveness.Deleted -> SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_deleted))
            Liveness.Unreadable -> SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_unreadable))
        }
    }

    /** Démarrage sans contrôle de liveness : l'appelant vient de le faire. */
    private suspend fun startWithoutCheck(config: TunnelConfig, networkLostReason: String? = null): SshResult<Unit> {
        if (wiped) return SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_deleted))
        return tunnelManager.startTunnel(config, networkLostReason) { hostId -> hostRepository.getById(hostId) }
    }

    /** État de la ligne d'un tunnel et de celle de son hôte, tels que la base les donne maintenant. */
    private sealed interface Liveness {
        /** Les deux lignes sont vivantes. [tunnel] est la ligne actuelle du tunnel. */
        data class Live(val tunnel: TunnelConfig) : Liveness

        /** Le tunnel ou son hôte est supprimé (tombstone ou ligne absente). */
        data object Deleted : Liveness

        /** La base n'a pas pu être lue : on ne sait rien, on ne décide rien. Transitoire. */
        data object Unreadable : Liveness
    }

    private suspend fun checkLiveness(tunnelId: String): Liveness = try {
        val row = tunnelRepository.getById(tunnelId)
        if (row == null || hostRepository.getById(row.hostId) == null) Liveness.Deleted else Liveness.Live(row)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Lecture de l'état d'un tunnel impossible (${errorClass(e)})")
        Liveness.Unreadable
    }

    /** Arrêt UI d'un tunnel (bouton Stop). */
    suspend fun stopTunnel(tunnelId: String): SshResult<Unit> {
        reconnectJobs.remove(tunnelId)?.cancel()
        return tunnelManager.stopTunnel(tunnelId)
    }

    /**
     * Arrête le tunnel s'il tourne (connecté, en démarrage, en reconnexion ou en erreur avec reconnexion
     * en cours), sans effet sinon. À appeler avant de supprimer un tunnel : la suppression ne touche que la
     * base, elle laisserait sinon le tunnel actif et invisible dans la liste.
     */
    suspend fun stopTunnelIfRunning(tunnelId: String) {
        val status = tunnelManager.getTunnelState(tunnelId)?.status ?: return
        if (status in RUNNING_STATUSES) stopTunnel(tunnelId)
    }

    /**
     * Arrête tous les tunnels en cours liés à l'hôte [hostId]. À appeler avant de supprimer l'hôte : ses
     * tunnels sont supprimés avec lui.
     *
     * Deux sources, réunies. Le `TunnelState` d'un tunnel en cours garde la config figée à son démarrage :
     * il identifie ceux qui passent réellement par cet hôte. La base donne ceux qui lui sont rattachés
     * maintenant : un tunnel déplacé vers cet hôte par une édition pendant qu'il tournait garde l'ancien
     * `hostId` dans son état, et serait raté si l'on ne lisait que l'état. La base est lue AVANT la
     * suppression ; si sa lecture échoue, on arrête quand même ceux que l'état désigne.
     */
    suspend fun stopTunnelsOfHost(hostId: String) {
        val states = tunnelManager.tunnelStates.value
        val frozenConfigIds = states
            .filterValues { state -> state.config.hostId == hostId && state.status in RUNNING_STATUSES }
            .keys
        val repositoryIds = try {
            tunnelRepository.observeByHost(hostId).first()
                .filter { it.hostId == hostId }
                .map { it.id }
                .filter { id -> states[id]?.status in RUNNING_STATUSES }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "stopTunnelsOfHost: lecture des tunnels de l'hôte impossible (${errorClass(e)})")
            emptyList()
        }
        val ids = (frozenConfigIds + repositoryIds).distinct()
        coroutineScope {
            ids.map { id -> launch { stopTunnel(id) } }.joinAll()
        }
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
            .filterValues { state -> state.status in RUNNING_STATUSES }
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

    /**
     * The translated network loss reason, null when the resource cannot be read : a failing read must
     * neither kill the network watch nor the reconnect loop, the card then shows no reason.
     */
    private suspend fun networkLostText(): String? = try {
        getString(Res.string.tunnels_error_network_lost)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Texte de perte réseau illisible (${errorClass(e)})")
        null
    }

    private suspend fun markRunningAsReconnecting() {
        // Read once, before the loop.
        val networkLostMessage = networkLostText()
        tunnelManager.tunnelStates.value.forEach { (id, state) ->
            // Test and write are one compare-and-set : a status written meanwhile by someone else
            // (a Stop, a loss reported by the manager) is never overwritten from the snapshot.
            val seen = tunnelManager.updateStatusIf(id, TunnelStatus.RECONNECTING, networkLostMessage) {
                it == TunnelStatus.ACTIVE || it == TunnelStatus.STARTING
            }
            if (seen == TunnelStatus.ACTIVE || seen == TunnelStatus.STARTING) {
                Logger.d(TAG, "Tunnel ${LogId.of(state.config.id)} marqué RECONNECTING (perte réseau)")
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
     *
     * The same watch opens the in-app browser of a tunnel that comes up (see [autoOpenBrowser]). It lives
     * here, for the whole session, and not in a screen: the browser must open whichever screen the user is
     * on when they start the tunnel.
     *
     * Idempotent, and started from two places: the unlock ([onVaultUnlocked]) and the first [startTunnel].
     * The second one covers the first session after the vault creation, which has no unlock.
     */
    private fun ensureTunnelStateWatch() {
        synchronized(stateWatchLock) {
            if (tunnelStateWatchJob?.isActive == true) return
            // Read NOW, not in the coroutine: a tunnel already ACTIVE when the watch starts must not pop the
            // browser, whereas one that comes up after this line must, even if the coroutine has not yet got
            // its first emission (the auto-start launches right after).
            val statusesAtStart = tunnelManager.tunnelStates.value.mapValues { it.value.status }
            tunnelStateWatchJob = appScope.coroutineScope.launch {
                // Two maps on purpose: [lastStatus] starts empty so that a tunnel already RECONNECTING at
                // the first emission still gets its reconnect, [lastBrowserStatus] is seeded with the
                // statuses at start so that a tunnel already ACTIVE is not mistaken for a transition.
                val lastStatus = mutableMapOf<String, TunnelStatus>()
                val lastBrowserStatus = statusesAtStart.toMutableMap()
                tunnelManager.tunnelStates.collect { states ->
                    states.forEach { (id, state) ->
                        val prev = lastStatus[id]
                        if (state.status == TunnelStatus.RECONNECTING && prev != TunnelStatus.RECONNECTING) {
                            scheduleReconnect(state.config)
                        }
                        lastStatus[id] = state.status
                        // Shares this collector with the reconnect scheduling: a failure here (the holder,
                        // the browser layer) must never stop the collector, or no tunnel would reconnect.
                        try {
                            autoOpenBrowser(state, previous = lastBrowserStatus[id])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.e(TAG, "Ouverture automatique du navigateur impossible (${errorClass(e)})")
                        }
                        lastBrowserStatus[id] = state.status
                    }
                    // Drop entries for fully-stopped tunnels so a later restart re-triggers.
                    lastStatus.keys.retainAll(states.keys)
                    lastBrowserStatus.keys.retainAll(states.keys)
                }
            }
        }
    }

    private fun cancelTunnelStateWatch() {
        synchronized(stateWatchLock) {
            tunnelStateWatchJob?.cancel()
            tunnelStateWatchJob = null
        }
    }

    /**
     * Opens the in-app browser of a LOCAL_FORWARD tunnel with `openBrowserOnConnect` when it goes from
     * another status to ACTIVE ([previous], null for a tunnel not seen yet), unless it already has a
     * session or the user closed it.
     *
     * Guards:
     *  - `hasSession(id)` avoids a double show when the overlay is already up.
     *  - `isUserDismissed(id)` avoids re-popping the browser on every RECONNECTING to ACTIVE cycle after
     *    the user closed it with `keepAliveAfterBrowserClose`. STOPPED clears that flag, so a fresh start
     *    can open it again.
     *  - A tunnel that was already ACTIVE when the watch started is never reported as a transition (the
     *    caller seeds [previous] with the statuses at start).
     */
    private fun autoOpenBrowser(state: TunnelState, previous: TunnelStatus?) {
        val holder = browserSessionHolder ?: return
        val id = state.config.id
        if (state.status == TunnelStatus.STOPPED) holder.clearUserDismissed(id)
        if (state.status == TunnelStatus.ACTIVE
            && previous != TunnelStatus.ACTIVE
            && state.config.type == TunnelType.LOCAL_FORWARD
            && state.config.openBrowserOnConnect
            && !holder.hasSession(id)
            && !holder.isUserDismissed(id)
        ) {
            holder.show(id)
        }
    }

    private fun scheduleReconnect(config: TunnelConfig) {
        if (tunnelManager.wasManuallyStopped(config.id)) return
        if (reconnectJobs.containsKey(config.id)) return
        Logger.d(TAG, "Tunnel ${LogId.of(config.id)} perdu : planification reconnexion")
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

    /**
     * Reconnect loop of a lost tunnel: unbounded, with a backoff capped at [MAX_BACKOFF_MS].
     *
     * Before each attempt the tunnel row and its host row are read again. The config this loop was started
     * with is frozen at the moment the tunnel was first started: without the re-read a tunnel (or a host)
     * deleted in the meantime would be retried every 60 s for as long as the app runs, and a deleted tunnel
     * whose host is still alive would even be silently resurrected by a successful reconnect. When either
     * row is gone the loop ends and the tunnel is stopped. When the database cannot be read nothing is
     * decided: the attempt is skipped and the loop goes on. The attempts start from the fresh row, so an
     * edit made while the tunnel was down (a port, the host) applies to the reconnect.
     */
    private suspend fun attemptReconnect(initialConfig: TunnelConfig) {
        var config = initialConfig
        var delayMs = INITIAL_BACKOFF_MS
        var attempt = 0
        var seenEpoch = networkMonitor.interfaceEpoch.value
        while (true) {
            // Keep the UI at RECONNECTING during the whole backoff cycle. Each
            // failed `startTunnel` flips the status to ERROR (via setError);
            // restoring it here avoids RECONNECTING ↔ ERROR flicker and lets
            // the tunnel row stay in "reconnecting" as long as we're trying.
            // The reason on the card stays in place ([keepErrorMessage]): first the
            // cause of the loss, then the reason of the last failed attempt (the
            // translated text `setError` wrote with the ERROR).
            //
            // Never over a live state, though : STARTING belongs to a start in
            // flight, ACTIVE to a tunnel that came up on its own. The test and
            // the write are one compare-and-set, so a status written by that
            // start between the two cannot be overwritten. ACTIVE ends the loop,
            // but only when the manager really holds the tunnel : a stale ACTIVE
            // status with no live entry (a released session) is not a reason to
            // stop retrying, the round goes on and the start below rewrites it.
            //
            // Only from RECONNECTING or ERROR (what a failed attempt leaves), and never once a Stop was
            // requested: `stopTunnel` sets the flag BEFORE it writes STOPPED, so a RECONNECTING can
            // never end up over a STOPPED the user asked for.
            val seen = tunnelManager.updateStatusIf(config.id, TunnelStatus.RECONNECTING, keepErrorMessage = true) {
                (it == TunnelStatus.RECONNECTING || it == TunnelStatus.ERROR) && !tunnelManager.wasManuallyStopped(config.id)
            }
            if (seen == TunnelStatus.ACTIVE && config.id in tunnelManager.activeIds()) {
                Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} abandonnée : déjà ACTIVE")
                return
            }
            // The wait is cut short by a change of the active network interfaces (Wi-Fi back, a VPN
            // up...): the monitor's online flag never moves on a machine with always-up virtual
            // adapters, so this is the only sign of a network that came back. A change restarts the
            // backoff from its first step and the attempt follows at once. A change seen during the
            // previous attempt counts as well (the epoch is compared with the last one seen).
            val woken = withTimeoutOrNull(delayMs) {
                networkMonitor.interfaceEpoch.first { it != seenEpoch }
            }
            if (woken != null) {
                seenEpoch = woken
                delayMs = INITIAL_BACKOFF_MS
                Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} : interfaces réseau modifiées, tentative immédiate")
            }
            // Allow an explicit stop or a successful restart (from elsewhere)
            // to break the loop.
            if (tunnelManager.wasManuallyStopped(config.id)) {
                Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} abandonnée : arrêt manuel")
                return
            }
            if (tunnelManager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE &&
                config.id in tunnelManager.activeIds()
            ) {
                Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} abandonnée : déjà ACTIVE")
                return
            }
            when (val liveness = checkLiveness(config.id)) {
                is Liveness.Live -> config = liveness.tunnel
                Liveness.Deleted -> {
                    Logger.d(TAG, "Reconnexion abandonnée : le tunnel ou son hôte a été supprimé")
                    stopDeletedFromReconnect(config)
                    return
                }
                Liveness.Unreadable -> {
                    delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
            }
            // Another start of this tunnel is still in flight (a Start the user
            // issued, or the one a network drop flipped to RECONNECTING). The
            // manager would ignore ours and answer Success, which we would take
            // for "reconnected" and leave the loop : if that start then fails,
            // nobody retries. So wait one more backoff and look again: ACTIVE
            // ends the loop above, a failure lets us start for real.
            //
            // Checked after the liveness read, right before the start: a deleted
            // tunnel is stopped even while a start of it is in flight (the stop
            // disconnects that start), and the window between this check and the
            // start stays as short as possible (the Success branch below covers it).
            //
            // The skipped round is not an attempt : it neither bumps `attempt`
            // (a log counter, the loop has no limit) nor doubles `delayMs`
            // (the backoff protects the server from failed connects, and this
            // round opened none).
            if (tunnelManager.isStarting(config.id)) {
                Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} reportée : un démarrage est déjà en cours")
                continue
            }
            // Offline according to the network monitor : no connection attempt, it would only fail
            // and replace the network reason by a host or timeout text for the whole outage. Not
            // for a host on the loopback (a local sshd, a NAT redirection on 127.0.0.1) : the
            // monitor does not speak for it, and it could always come back during an outage.
            // The network reason is written again (an earlier ERROR may have replaced it), then the
            // loop sleeps until the monitor says online (cancelled by a Stop like any other
            // suspension) instead of polling the database every second, and starts over from the
            // initial backoff: a delay grown to 60 s would hold the recovery back.
            if (!networkMonitor.isConnected.value && !isLoopbackHost(config.hostId)) {
                val networkLost = networkLostText()
                tunnelManager.updateStatusIf(config.id, TunnelStatus.RECONNECTING, networkLost) {
                    (it == TunnelStatus.RECONNECTING || it == TunnelStatus.ERROR) &&
                        !tunnelManager.wasManuallyStopped(config.id)
                }
                Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} reportée : réseau hors ligne")
                networkMonitor.isConnected.first { it }
                delayMs = INITIAL_BACKOFF_MS
                continue
            }
            attempt++
            Logger.d(TAG, "Reconnexion tunnel ${LogId.of(config.id)} tentative $attempt")
            // The loop alone asks for the network reason on a network-level failure of the open
            // (a manual start keeps its own message). A timeout is never one.
            when (startWithoutCheck(config, networkLostText())) {
                is SshResult.Success -> {
                    if (config.id in tunnelManager.activeIds()) {
                        Logger.d(TAG, "Tunnel ${LogId.of(config.id)} reconnecté (tentative $attempt)")
                        return
                    }
                    // Success without a live tunnel is not a reconnection : the
                    // manager ignored this Start (another one took the id between
                    // our `isStarting` check and the call), or a manual stop
                    // cancelled it. The stop ends the loop ; otherwise wait one
                    // more backoff and look again (ACTIVE ends it above).
                    if (tunnelManager.wasManuallyStopped(config.id)) {
                        Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} abandonnée : arrêt manuel")
                        return
                    }
                    Logger.d(TAG, "Reconnexion ${LogId.of(config.id)} : démarrage ignoré ou sans tunnel actif, on réessaie")
                    continue
                }
                is SshResult.Error -> {
                    delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /** Is the host of [hostId] on the loopback (see [isLoopbackHostname])? False when it cannot be read. */
    private suspend fun isLoopbackHost(hostId: String): Boolean = try {
        hostRepository.getById(hostId)?.hostname?.let(::isLoopbackHostname) ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    // ── Deleted tunnels ──────────────────────────────────────────────────────

    /**
     * Stops, from inside the reconnect loop, the tunnel whose row or host was deleted.
     *
     * Goes through [DesktopTunnelManager.stopTunnel] DIRECTLY, never through [stopTunnel] of this service:
     * that one cancels `reconnectJobs[id]`, which is the job running this very code, and the cancellation
     * would fire before the STOPPED state is set. The loop returns right after, which ends the job.
     */
    private suspend fun stopDeletedFromReconnect(config: TunnelConfig) {
        if (!deletionStopsInFlight.add(config.id)) return // the deletion watch is already stopping it
        try {
            // Already stopped by someone else (the watch finished just before): nothing to stop, nothing to report.
            if (tunnelManager.getTunnelState(config.id)?.status !in RUNNING_STATUSES) return
            // A tunnel with a reconnect loop was up before the link dropped: it is announced whatever the
            // status of the attempt in progress (STARTING, ERROR).
            if (tunnelManager.stopTunnel(config.id) is SshResult.Success) reportDeletionStops(listOf(config))
        } finally {
            deletionStopsInFlight.remove(config.id)
        }
    }

    /**
     * Watches the tunnel rows, the host rows and the running states, and stops every running tunnel whose
     * row, or whose host, is no longer live.
     *
     * Nothing else observes the database while tunnels run, and a sync (or a conflict resolution) can delete
     * a tunnel or a host from under them: the connection would stay up, invisible in the list.
     *
     * The rule is the one of [stopTunnelsOfHost]: the host that counts is BOTH the one the row points at now
     * and the one the running state was started with (a tunnel moved to another host by an edit while it
     * runs still goes through the old one). The lists this flow emits can lag behind the database (a tunnel
     * created and started in the same second is not in them yet), so a suspect is confirmed with a direct
     * read before anything is stopped.
     */
    private fun startDeletionWatch() {
        if (deletionWatchJob?.isActive == true) return
        deletionWatchJob = appScope.coroutineScope.launch {
            var retryDelayMs = WATCH_RETRY_INITIAL_MS
            while (true) {
                try {
                    combine(
                        tunnelRepository.observeAll(),
                        hostRepository.observeAll(),
                        tunnelManager.tunnelStates,
                    ) { tunnels, hosts, states -> Triple(tunnels, hosts, states) }
                        .collect { (tunnels, hosts, states) ->
                            retryDelayMs = WATCH_RETRY_INITIAL_MS // the source works again: next failure starts over
                            try {
                                stopDeletedTunnels(tunnels, hosts, states)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "Arrêt des tunnels supprimés impossible (${errorClass(e)})")
                            }
                        }
                    return@launch // the sources never complete; if they do, there is nothing left to watch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failing source (a row that cannot be decoded, say) must not end the watch for the
                    // rest of the session: look again after a bounded, growing delay.
                    Logger.e(TAG, "Surveillance des suppressions interrompue (${errorClass(e)}), nouvel essai")
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(WATCH_RETRY_MAX_MS)
                }
            }
        }
    }

    private suspend fun stopDeletedTunnels(
        liveTunnels: List<TunnelConfig>,
        liveHosts: List<Host>,
        states: Map<String, TunnelState>,
    ) {
        val rowHostIds = liveTunnels.associate { it.id to it.hostId }
        val liveHostIds = liveHosts.mapTo(HashSet()) { it.id }
        val suspects = states.values.filter { state ->
            if (state.status !in RUNNING_STATUSES) return@filter false
            val rowHostId = rowHostIds[state.config.id]
            rowHostId == null || rowHostId !in liveHostIds || state.config.hostId !in liveHostIds
        }
        if (suspects.isEmpty()) return

        val confirmed = suspects.filter { state -> isDeletedNow(state.config) }
        if (confirmed.isEmpty()) return

        val stopped = coroutineScope {
            confirmed.map { state ->
                async {
                    val id = state.config.id
                    if (!deletionStopsInFlight.add(id)) return@async null // the reconnect loop is on it
                    try {
                        // Read now, before the stop (it removes the reconnect job), not from the snapshot.
                        val announce = wasReallyRunning(id)
                        // The service's stop: it also cancels the reconnect loop of a RECONNECTING tunnel.
                        // A tunnel that was not really running is stopped silently.
                        if (stopTunnel(id) is SshResult.Success && announce) state.config else null
                    } finally {
                        deletionStopsInFlight.remove(id)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        reportDeletionStops(stopped)
    }

    /**
     * True when the tunnel was really up, so that its stop is worth telling the user: ACTIVE, RECONNECTING,
     * or caught in a reconnect attempt (a loop is running: the status of the attempt flips to STARTING then
     * ERROR, but the tunnel was up before the link dropped). A tunnel in ERROR after a failed start, or
     * still STARTING for the first time, never was.
     */
    private fun wasReallyRunning(tunnelId: String): Boolean =
        tunnelManager.getTunnelState(tunnelId)?.status in ANNOUNCED_STATUSES || reconnectJobs.containsKey(tunnelId)

    /**
     * True when the row of [frozen] or one of its hosts (the one of the row, the one it was started with)
     * is not live according to a direct read. A read failure is transient: it decides nothing, the next
     * emission of the watch looks again.
     */
    private suspend fun isDeletedNow(frozen: TunnelConfig): Boolean = try {
        val row = tunnelRepository.getById(frozen.id)
        row == null || setOf(row.hostId, frozen.hostId).any { hostId -> hostRepository.getById(hostId) == null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Lecture de confirmation impossible (${errorClass(e)})")
        false
    }

    /**
     * Tells the app that [stopped] tunnels, which were running, have just been stopped because they, or
     * their host, were deleted on another device: one event for the whole pass ([deletionStops], for the
     * tray) and a notice that stays until dismissed ([deletionNotice], for the banner of the tunnel list).
     */
    private fun reportDeletionStops(stopped: List<TunnelConfig>) {
        if (stopped.isEmpty()) return
        // Count only: labels, hostnames and ids stay out of the logs.
        Logger.w(TAG, "${stopped.size} tunnel(s) arrêté(s) : supprimé(s) sur un autre appareil")
        val tunnels = stopped.map { StoppedTunnel(id = it.id, label = it.label) }
        _deletionNotice.update { current ->
            val ids = tunnels.mapTo(HashSet()) { it.id }
            (current.filterNot { it.id in ids } + tunnels).takeLast(MAX_NOTICE_TUNNELS)
        }
        _deletionStops.tryEmit(TunnelDeletionStop(tunnels))
    }

    /**
     * Forgets the notices of [seenTunnelIds], the tunnels the banner was showing when the user closed it.
     * A tunnel stopped between the moment the banner was drawn and the click is not in that list: its
     * notice stays, the user has not seen it.
     */
    fun dismissDeletionNotice(seenTunnelIds: Collection<String>) {
        val seen = seenTunnelIds.toSet()
        _deletionNotice.update { current -> current.filterNot { it.id in seen } }
    }
}
