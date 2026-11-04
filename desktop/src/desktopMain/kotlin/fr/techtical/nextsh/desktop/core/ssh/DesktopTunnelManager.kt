// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.DisconnectListener
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DesktopTunnelManager"

/**
 * Contrat minimal pour ouvrir + authentifier un `SSHClient` propre à un tunnel.
 *
 * Le [DesktopTunnelManager] reçoit une implémentation qui lit le vault et ouvre
 * un `SSHClient` dédié. Séparer ce contrat du manager rend :
 *  - les tests insensibles au vault et au réseau (on passe un fake qui retourne
 *    un `FakeSshClient`),
 *  - le code de prod factorisable : l'impl prod s'appuie sur [VaultManager] +
 *    [DesktopKnownHostsVerifier] pour construire le client, sans fuite de
 *    dépendances dans la classe métier.
 */
fun interface SshClientFactory {
    /**
     * Ouvre et authentifie un `SSHClient`.
     *
     * [onCreated] est appelé dès que l'instance `SSHClient` est instanciée
     * (avant `connect()`/auth) : pour que le caller puisse la stocker et
     * appeler `client.disconnect()` afin d'interrompre la connexion en cours
     * (TCP handshake / auth bloquante non-cancellable autrement).
     *
     * [onCreated] peut aussi lever une `IOException` pour demander l'abandon
     * avant `connect()` (un Stop est arrivé avant que le client existe) :
     * l'implémentation doit alors la traiter comme un échec de connexion,
     * fermer le client et renvoyer `Error`, sans ouvrir de socket. Le manager
     * rattrape l'exception si une implémentation la laisse remonter.
     *
     * Pas de paramètre default ici parce qu'un `fun interface` ne supporte
     * pas la SAM conversion avec un argument à valeur par défaut. Les tests
     * lambda passent `_` pour ignorer le tracker.
     */
    suspend fun open(host: Host, onCreated: (SSHClient) -> Unit): SshResult<SSHClient>
}

/**
 * Levée par le callback `onCreated` du manager quand un Stop est arrivé avant
 * que le client ne lui soit remis : la fabrique doit renoncer à `connect()`.
 * C'est une `IOException` pour que le `catch (IOException)` de
 * [VaultSshClientFactory.open] la traite comme un échec de connexion.
 */
private class StopRequestedException : IOException("stopped")

/**
 * État d'un tunnel actif en mémoire : garde la référence SSHJ pour pouvoir
 * l'arrêter proprement. Non-exposé à l'UI. `internal` uniquement pour que les
 * tests atteignent [DesktopTunnelManager.registerActive].
 *
 * Classe simple, pas `data class` : aucun `copy` ni comparaison structurelle,
 * et l'égalité par identité est celle qu'attend `active.remove(id, tunnel)`
 * (on retire CETTE entrée, pas une entrée aux champs égaux).
 */
internal class ActiveTunnel(
    val config: TunnelConfig,
    val client: SSHClient,
    val job: Job,
    val forwarder: LocalPortForwarder? = null,
    val serverSocket: ServerSocket? = null,
    val remoteForwarder: RemotePortForwarder? = null,
    val remoteForward: RemotePortForwarder.Forward? = null,
    val socks5Server: DesktopSocks5Server? = null,
    val livenessJob: Job? = null,
)

/**
 * Runtime Desktop des tunnels SSH : port direct du `SshTunnelManager` Android
 * (`app/core/ssh/SshTunnelManager.kt`), sans Hilt/Timber, avec un `SSHClient`
 * par tunnel pour isoler les terminaux.
 *
 * Thread-safety :
 *  - `ConcurrentHashMap<String, ActiveTunnel>` protège les références d'objets
 *    SSHJ. Les mises à jour du `StateFlow` sont déjà atomiques (`update`) et
 *    l'exclusion entre deux [startTunnel] vient de `startingIds.add` (atomique,
 *    un seul démarrage en vol par tunnel). La seule garantie du [Mutex] : un
 *    Start refusé ne revient qu'après que le gagnant a écrit STARTING (le
 *    perdant attend la fin de la section critique), donc un caller dont le
 *    Start est refusé observe déjà STARTING. `stopTunnel` ne le prend pas.
 *  - Un Stop qui tombe pendant un démarrage n'est jamais écrasé par lui :
 *    `stopTunnel` pose le drapeau d'arrêt AVANT de chercher dans `active`,
 *    le démarrage range son entrée dans `active` AVANT de relire le drapeau
 *    (voir [registerAndConfirm]). Celui qui passe en second voit l'autre.
 *  - Chaque tunnel est lancé dans un [Job] enfant du scope fourni au constructeur.
 *    Annuler ce Job via [stopTunnel] déclenche le teardown (close socket / cancel
 *    forward / SOCKS5.stop) + fermeture du `SSHClient`.
 */
class DesktopTunnelManager(
    private val scope: CoroutineScope,
    private val clientFactory: SshClientFactory,
) : SshTunnelManager {

    private val active = ConcurrentHashMap<String, ActiveTunnel>()
    private val mutex = Mutex()

    private val _tunnelStates = MutableStateFlow<Map<String, TunnelState>>(emptyMap())
    val tunnelStates: StateFlow<Map<String, TunnelState>> = _tunnelStates.asStateFlow()

    /**
     * RTT (ms) mesuré par le watchdog keepalive pour chaque tunnel actif.
     * Null = pas encore mesuré ou tunnel perdu. Retiré de la map à l'arrêt.
     * Exposé en lecture seule pour l'UI (TunnelListScreen / TunnelCard).
     */
    private val _latenciesByTunnel = MutableStateFlow<Map<String, Long?>>(emptyMap())
    val latenciesByTunnel: StateFlow<Map<String, Long?>> = _latenciesByTunnel.asStateFlow()

    /** Shared interface: Flow<List<TunnelState>> (platform-neutral). */
    override val tunnels: Flow<List<TunnelState>> = _tunnelStates.map { it.values.toList() }

    override fun getTunnelState(tunnelId: String): TunnelState? = _tunnelStates.value[tunnelId]

    /** @see fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager.markStarting */
    override fun markStarting(config: TunnelConfig) {
        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
        manuallyStopped.remove(config.id)
    }

    /** @see fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager.markError */
    override fun markError(config: TunnelConfig, message: String) {
        _tunnelStates.update {
            it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = message))
        }
    }

    /** IDs des tunnels arrêtés manuellement : exclus de la reconnexion automatique. */
    private val manuallyStopped = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Tracks the [SSHClient] of tunnels currently in STARTING (handshake / auth,
     * then forwarder setup). Populated by the `onCreated` callback of
     * [SshClientFactory.open] before `connect()` blocks, kept through the setup
     * that follows (see [openAndStart]), and cleared when the start ends : the
     * tunnel is up (and stored in [active]), or it failed, or it was stopped.
     *
     * `stopTunnel` calls `disconnect()` on this client to interrupt a connect
     * that is otherwise stuck on TCP SYN timeout (~30 s) or auth handshake, or a
     * remote forward request waiting for a reply that never comes. Without this,
     * "Stop" returned success but the underlying coroutine kept blocking and
     * finally surfaced a timeout error.
     */
    private val startingClients = ConcurrentHashMap<String, SSHClient>()

    /**
     * IDs of tunnels whose [startTunnel] is in flight : reserved by an atomic
     * `add` at the top of [startTunnel] (that `add` is what keeps two Starts
     * apart, not [mutex]), released in a `finally` once it returns, whatever
     * the outcome. [active] is only filled at the very end of a start, so it
     * cannot tell a second Start that a first one is still in STARTING : this
     * set does.
     */
    private val startingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Test seam, `null` in production. Run by [registerAndConfirm] right after
     * the entry is in [active], before the stop flag is read : the first of the
     * two points where a concurrent Stop can still land.
     */
    @Volatile
    internal var afterRegisterHook: (() -> Unit)? = null

    /**
     * Test seam, `null` in production. Run by [registerAndConfirm] after the
     * stop flag was read (clear) and before ACTIVE is written : the last point
     * where a concurrent Stop can land.
     */
    @Volatile
    internal var beforeConfirmHook: (() -> Unit)? = null

    /**
     * Démarre un tunnel. Utilise [hostLookup] pour retrouver l'hôte associé :
     * le caller (`DesktopTunnelService`) injecte le `HostRepository`. Ouvre un
     * `SSHClient` dédié via [SshClientFactory] puis crée le forwarder SSHJ
     * correspondant au type.
     *
     * Transitions d'état émises :
     *  - STARTING → ACTIVE   sur succès
     *  - STARTING → ERROR(m) sur échec (auth, port occupé, réseau…)
     *  - arrêt manuel pendant le démarrage : aucune écriture ici, l'état
     *    ARRÊTÉ est celui de [stopTunnel], et le démarrage renvoie `Success`
     *    (un arrêt voulu n'est pas une erreur) après avoir libéré tout ce
     *    qu'il avait ouvert.
     *
     * Un seul démarrage en vol par tunnel : l'id est réservé (voir
     * [startingIds]) jusqu'au retour de cette fonction, quelle que soit l'issue
     * (succès, erreur, arrêt manuel, annulation). Un Start émis pendant qu'un
     * démarrage précédent du même tunnel n'est pas revenu est ignoré : il
     * renvoie `Success` comme pour un tunnel déjà actif, sans ouvrir de second
     * `SSHClient` et sans toucher ni à l'état ni au drapeau d'arrêt manuel.
     *
     * « Pas revenu » veut dire : jusqu'à la fin du handshake, ou jusqu'à la
     * réponse à la question de clé d'hôte inconnue (TOFU). Cette question n'a
     * aucun délai : `DesktopKnownHostsVerifier` attend dans un `runBlocking`
     * que `disconnect()` ne réveille pas. Tant qu'elle reste sans réponse, un
     * Stop déconnecte bien le client mais le démarrage ne revient pas, et un
     * nouveau Start de ce tunnel reste ignoré : cette fenêtre n'est pas bornée.
     */
    suspend fun startTunnel(
        config: TunnelConfig,
        hostLookup: suspend (hostId: String) -> Host?,
    ): SshResult<Unit> {
        // Deux Start concurrents ne peuvent pas passer tous les deux : `add`
        // est atomique. Le verrou ne garantit qu'une chose : un Start refusé
        // ne revient qu'après que le gagnant a écrit STARTING. Déjà actif ou
        // déjà en cours de démarrage : no-op côté caller, on renvoie succès
        // sans rien toucher.
        //
        // On réserve AVANT de regarder `active`. Un démarrage qui se termine
        // pose son entrée dans `active` avant de rendre sa réservation : une
        // fois la réservation à nous, cette entrée est donc visible. Dans
        // l'ordre inverse (test de `active`, puis réservation), un démarrage
        // qui finit entre les deux nous laisserait démarrer à côté d'un tunnel
        // vivant.
        val reserved = mutex.withLock {
            if (!startingIds.add(config.id)) {
                false
            } else if (active.containsKey(config.id)) {
                startingIds.remove(config.id)
                false
            } else {
                _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
                manuallyStopped.remove(config.id)
                true
            }
        }
        if (!reserved) return SshResult.Success(Unit)

        // Aucun point de suspension entre la réservation et ce `try` : la
        // réservation est libérée sur toute sortie, annulation comprise.
        try {
            return startReserved(config, hostLookup)
        } finally {
            startingIds.remove(config.id)
        }
    }

    /** Corps de [startTunnel], exécuté une fois l'id réservé dans [startingIds]. */
    private suspend fun startReserved(
        config: TunnelConfig,
        hostLookup: suspend (hostId: String) -> Host?,
    ): SshResult<Unit> {
        val host = hostLookup(config.hostId)

        // Stop landed during the host lookup : nothing was started, so there is
        // nothing to undo and, above all, no error to write over the STOPPED that
        // [stopTunnel] already wrote (host-not-found and port-in-use both end in
        // ERROR). Same exit as the manual-stop path below.
        if (manuallyStopped.contains(config.id)) {
            return SshResult.Success(Unit)
        }

        if (host == null) {
            return failStart(config, SshResult.Error(SshErrorCode.UNKNOWN, "Hôte ${config.hostId} introuvable"))
        }

        // LOCAL_FORWARD / DYNAMIC_SOCKS5 : check port local avant même d'ouvrir la
        // connexion SSH : une erreur PORT_IN_USE ne doit pas consommer d'auth.
        if (config.type == TunnelType.LOCAL_FORWARD || config.type == TunnelType.DYNAMIC_SOCKS5) {
            if (isPortInUse(config.localPort)) {
                val msg = "Port ${config.localPort} déjà utilisé localement"
                return failStart(config, SshResult.Error(SshErrorCode.PORT_IN_USE, msg))
            }
        }

        // Second look, for the Stop that landed during the port check above :
        // [stopTunnel] found no client to disconnect, so without it the whole
        // handshake would run for nothing.
        if (manuallyStopped.contains(config.id)) {
            return SshResult.Success(Unit)
        }

        // Whatever the outcome (success, failure, stop, cancellation), nothing
        // of this start stays in [startingClients] once it is over : a stale
        // client there would be disconnected by a later, unrelated Stop. The
        // reservation guarantees this id has a single start in flight, so any
        // entry under it is this start's.
        var returned = false
        try {
            val result = openAndStart(config, host)
            returned = true
            return result
        } finally {
            val client = startingClients.remove(config.id)
            // A start that did not return (cancelled, typically the reconnection
            // job a Stop cancels) may hold a client the factory has just
            // authenticated, which nobody else will close : the Stop that follows
            // no longer finds it here. A start that returned has already closed
            // the client or handed it to the tunnel.
            if (!returned) client?.let { runCatching { it.disconnect() } }
        }
    }

    /**
     * Second half of [startReserved] : opens the SSH client, then sets the
     * forwarder up and registers the tunnel. The client is in [startingClients]
     * for the whole of it, from the factory's `onCreated` until the caller's
     * `finally`, not only until `open()` returns : the forwarder setup can block
     * too (the remote forward request is a network round trip, up to the SSH
     * timeout on a dead route), and a Stop landing there must be able to
     * `disconnect()` the client to abort it at once. That disconnect is harmless
     * on a client that is already registered or being torn down (SSHJ ignores a
     * second one).
     */
    private suspend fun openAndStart(config: TunnelConfig, host: Host): SshResult<Unit> {
        // The factory hands us the SSHClient BEFORE connect() blocks via
        // [onCreated]: we stash it so [stopTunnel] can call disconnect() on
        // it to abort an in-flight handshake. If the user clicked Stop while
        // connect/auth was running, [manuallyStopped] is now set and we bail
        // out as soon as open() returns.
        val openResult = try {
            clientFactory.open(host) { newClient ->
                startingClients[config.id] = newClient
                // [stopTunnel] sets the flag BEFORE it looks in [startingClients],
                // we store the client BEFORE we read the flag : whichever runs
                // second sees the other, so a Stop cannot slip between the two.
                // A Stop that came before the client existed found nothing to
                // disconnect, and disconnect() on an SSHClient whose connect()
                // has not begun does nothing anyway (SSHJ builds a fresh socket
                // in connect()). So we abort the open instead : the factory
                // gives up before connect() ([VaultSshClientFactory] turns this
                // into an Error), and the manual-stop branch below makes it a
                // quiet Success.
                if (manuallyStopped.contains(config.id)) throw StopRequestedException()
            }
        } catch (e: StopRequestedException) {
            // A factory that lets the abort escape instead of turning it into an
            // Error : same outcome, and the half-built client is closed here.
            startingClients[config.id]?.let { runCatching { it.disconnect() } }
            SshResult.Error(SshErrorCode.UNKNOWN, "stopped")
        }
        // Keep (or restore) the client we were handed in [startingClients] : a
        // Stop during the handshake took it out to disconnect it, and a factory
        // that never calls `onCreated` never put it there. Same ordering as in
        // `onCreated` : stored BEFORE the flag is read below, so a Stop landing
        // from here on either sees the client or is seen by the flag.
        if (openResult is SshResult.Success) startingClients[config.id] = openResult.data

        if (manuallyStopped.contains(config.id)) {
            // User hit Stop during the handshake: disconnect was already
            // called on the client, openResult might be Success (auth
            // succeeded just before disconnect) or Error (connect/auth
            // raised IOException after disconnect). Either way, don't
            // proceed to start the forwarder, and don't touch _tunnelStates :
            // the parallel stopTunnel(id) call has already set STOPPED.
            if (openResult is SshResult.Success) {
                runCatching { openResult.data.disconnect() }
            }
            // Treat as Success: the user asked to cancel and we did. Caller
            // (UI) must not surface an error toast for an explicit cancel.
            return SshResult.Success(Unit)
        }

        return when (openResult) {
            is SshResult.Error -> failStart(config, openResult)
            is SshResult.Success -> {
                val client = openResult.data
                try {
                    when (config.type) {
                        TunnelType.LOCAL_FORWARD -> startLocalForwardInternal(config, client)
                        TunnelType.REMOTE_FORWARD -> startRemoteForwardInternal(config, client)
                        TunnelType.DYNAMIC_SOCKS5 -> startSocks5Internal(config, client)
                    }
                } catch (e: Exception) {
                    // A Stop during a remote bind makes it fail on purpose (it
                    // disconnects the client) : not worth an error and a stack trace.
                    if (manuallyStopped.contains(config.id)) {
                        Logger.d(TAG, "Tunnel ${config.label} : mise en place interrompue par un arrêt")
                    } else {
                        Logger.e(TAG, "Échec démarrage tunnel ${config.label}", e)
                    }
                    try { client.disconnect() } catch (_: Exception) {}
                    // A Stop that landed while the forwarder was being set up (it
                    // may even be what made the setup fail : it disconnects the
                    // client) turns this failure into the quiet Success of the
                    // other stop paths, see [failStart].
                    failStart(config, SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue"))
                }
            }
        }
    }

    /**
     * Ends a failed start : writes ERROR and returns [error], unless a Stop
     * landed meanwhile. The user asked for this tunnel to stop, so the failure
     * is not worth an error toast and [stopTunnel] owns the state : the start
     * returns the same quiet `Success` as the other stop paths. The decision is
     * [setError]'s own (taken inside the compare-and-set), not a flag read
     * beforehand, so the state and the result can never disagree.
     */
    private fun failStart(config: TunnelConfig, error: SshResult.Error): SshResult<Unit> =
        if (setError(config, error.message)) error else SshResult.Success(Unit)

    /**
     * Arrêt propre : cancel le Job enfant, ferme le forwarder/socket/SOCKS5,
     * puis disconnect le SSHClient. Idempotent : appel sur un id inconnu renvoie
     * succès (pas d'erreur bruit sur "stopAll" puis stop explicite).
     */
    override suspend fun stopTunnel(tunnelId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        // Set the manuallyStopped flag FIRST: this serves two purposes:
        //  1. signals startTunnel(...) to bail out if it's still in flight :
        //     handshake / auth (checked when open() returns), or the forwarder
        //     setup that follows (checked once the entry is in `active`, see
        //     registerAndConfirm) ;
        //  2. tells the service-level reconnect loop "user asked to stop,
        //     don't auto-restart".
        manuallyStopped.add(tunnelId)

        // If the tunnel is currently in STARTING (connect / auth, or the remote
        // forward request, blocking on an IO thread), break it by
        // force-disconnecting the half-built client. The blocking call on the
        // SSHJ thread returns as IOException → the startTunnel coroutine
        // completes, sees manuallyStopped, and bails.
        startingClients.remove(tunnelId)?.let { startingClient ->
            runCatching { startingClient.disconnect() }
            Logger.d(TAG, "Tunnel $tunnelId : démarrage interrompu (disconnect forcé)")
        }
        _latenciesByTunnel.update { it - tunnelId }

        val tunnel = active.remove(tunnelId) ?: run {
            // Either the tunnel was never fully up (only STARTING: already
            // handled above) or it was previously stopped/errored. Reflect
            // STOPPED in the state so the UI updates immediately.
            _tunnelStates.update {
                val existing = it[tunnelId]
                if (existing != null) it + (tunnelId to existing.copy(status = TunnelStatus.STOPPED, errorMessage = null))
                else it - tunnelId
            }
            return@withContext SshResult.Success(Unit)
        }
        try {
            // Cancel the job BEFORE closing anything : it interrupts the SSHJ
            // listen loop (see startLocalForwardInternal). `accept()` stays
            // blocked with the flag set, and closing the socket then ends the
            // loop at once. Cancelling after `client.disconnect()` would leave
            // the worker spinning for as long as the disconnect takes (long on
            // a dead route). Closing the socket first also neutralises SSHJ's
            // own `LocalPortForwarder.close()`, which only interrupts while the
            // socket is still open.
            tunnel.job.cancel()
            closeTunnelResources(tunnel)
            _tunnelStates.update { it + (tunnelId to TunnelState(tunnel.config, TunnelStatus.STOPPED)) }
            // On retire au bout de quelques ms pour que l'UI observe STOPPED si besoin.
            // En pratique retirer tout de suite marche aussi ; on laisse l'entry STOPPED
            // pour permettre au service de gérer les RECONNECTING.
            Logger.d(TAG, "Tunnel ${tunnel.config.label} arrêté")
            SshResult.Success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Erreur arrêt tunnel $tunnelId", e)
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
        }
    }

    /**
     * Release the current tunnel's SSH resources WITHOUT flagging it as
     * manually stopped. Used by the service before a reconnect attempt: we
     * need to free the (possibly half-dead) SSHClient + forwarder so the
     * manager's "already running" short-circuit doesn't block the next
     * `startTunnel`, but we must NOT set the manuallyStopped flag because
     * that would signal "user stopped it, don't reconnect" and abort the
     * backoff loop. Idempotent for unknown ids.
     */
    suspend fun releaseForReconnect(tunnelId: String): Unit = withContext(Dispatchers.IO) {
        val tunnel = active.remove(tunnelId) ?: return@withContext
        try {
            // Cancel first, for the same reason as in stopTunnel.
            tunnel.job.cancel()
            closeTunnelResources(tunnel)
        } catch (e: Exception) {
            Logger.w(TAG, "releaseForReconnect($tunnelId): erreur fermeture : ${e.message}")
        }
        // Drop the latency entry: the SSHClient is gone, the next reconnect
        // will produce fresh measurements. Without this, callers that bypass
        // onTunnelLost (e.g. DesktopTunnelService.stopAllTunnels short-circuit)
        // would leak a stale Long? per tunnel into the map.
        _latenciesByTunnel.update { it - tunnelId }
    }

    /** Arrête tous les tunnels en cours. Utilisé au `onAppExit`. */
    suspend fun stopAll() {
        val ids = active.keys.toList()
        ids.forEach { stopTunnel(it) }
        _tunnelStates.update { emptyMap() }
        _latenciesByTunnel.update { emptyMap() }
        manuallyStopped.clear()
    }

    /**
     * Met à jour le statut d'un tunnel dans le StateFlow sans toucher au Job
     * ni au SSHClient. Utilisé par le service pour marquer RECONNECTING sur
     * perte réseau.
     */
    fun updateStatus(tunnelId: String, status: TunnelStatus, errorMessage: String? = null) {
        _tunnelStates.update { states ->
            val existing = states[tunnelId] ?: return@update states
            states + (tunnelId to existing.copy(status = status, errorMessage = errorMessage))
        }
    }

    /**
     * Comme [updateStatus], mais seulement si [condition] accepte le statut
     * courant. Test et écriture portent sur le même instantané (`update` est
     * une boucle compare-and-set) : un statut écrit entre-temps par un autre
     * acteur n'est jamais écrasé. Renvoie le statut vu à cet instant, `null` si
     * le tunnel est inconnu ; l'appelant le compare à ce qu'il attendait.
     */
    fun updateStatusIf(
        tunnelId: String,
        status: TunnelStatus,
        errorMessage: String? = null,
        condition: (current: TunnelStatus) -> Boolean,
    ): TunnelStatus? {
        var seen: TunnelStatus? = null
        _tunnelStates.update { states ->
            val existing = states[tunnelId]
            seen = existing?.status
            if (existing == null || !condition(existing.status)) states
            else states + (tunnelId to existing.copy(status = status, errorMessage = errorMessage))
        }
        return seen
    }

    /** IDs actifs à l'instant T : utilisé par le service pour gérer les reconnects. */
    fun activeIds(): Set<String> = active.keys.toSet()

    fun wasManuallyStopped(tunnelId: String): Boolean = tunnelId in manuallyStopped

    /**
     * Vrai tant qu'un [startTunnel] de ce tunnel n'est pas revenu (id réservé).
     * Le service s'en sert pour ne pas compter comme « reconnecté » un Start
     * que le manager ignorerait : voir `DesktopTunnelService.attemptReconnect`.
     *
     * Peut être vrai un court instant pour un tunnel déjà ACTIVE : soit la
     * réservation passagère d'un Start refusé (il réserve d'abord, voit
     * `active`, puis rend l'id), soit la fin d'un démarrage réussi (l'entrée est
     * posée dans `active` avant que l'id soit rendu). Sans conséquence : le seul
     * lecteur, la boucle de reconnexion, attend alors un palier de plus et voit
     * ACTIVE, ce qui termine la boucle.
     */
    fun isStarting(tunnelId: String): Boolean = tunnelId in startingIds

    // ── Shared interface wrappers ────────────────────────────────────────────
    // Note : l'interface partagée raisonne en termes de `sessionId` (réutilisation
    // d'un SSHClient de terminal). Côté Desktop on n'attache PAS les tunnels aux
    // sessions terminal : chaque tunnel ouvre son propre SSHClient pour
    // l'isolation (consigne Wave 5). Ces trois méthodes existent pour satisfaire
    // l'interface ; elles ne sont pas appelées par l'UI Desktop (qui utilise
    // `DesktopTunnelService.startTunnel` directement).

    override suspend fun startLocalForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
        SshResult.Error(SshErrorCode.UNKNOWN, "Utilisez DesktopTunnelService.startTunnel")

    override suspend fun startRemoteForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
        SshResult.Error(SshErrorCode.UNKNOWN, "Utilisez DesktopTunnelService.startTunnel")

    override suspend fun startDynamicForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
        SshResult.Error(SshErrorCode.UNKNOWN, "Utilisez DesktopTunnelService.startTunnel")

    // ── Internals ────────────────────────────────────────────────────────────

    private suspend fun startLocalForwardInternal(
        config: TunnelConfig,
        client: SSHClient,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket()
        try {
            bindAndRegisterLocalForward(config, client, serverSocket)
        } catch (t: Throwable) {
            // `startTunnel` only disconnects the client when this throws : the
            // bound socket would keep the local port until the app restarts.
            // Skipped once the entry is registered (it then owns the socket).
            if (active[config.id]?.serverSocket !== serverSocket) {
                runCatching { serverSocket.close() }
            }
            throw t
        }
    }

    /**
     * Body of [startLocalForwardInternal] : binds [serverSocket], starts the
     * listen loop and registers the tunnel. May throw at any point ; the caller
     * closes [serverSocket] unless the tunnel got registered.
     */
    private fun bindAndRegisterLocalForward(
        config: TunnelConfig,
        client: SSHClient,
        serverSocket: ServerSocket,
    ): SshResult<Unit> {
        val params = Parameters(
            "127.0.0.1",
            config.localPort,
            config.remoteHost,
            config.remotePort,
        )
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(params.localHost, params.localPort))

        val forwarder = client.newLocalPortForwarder(params, serverSocket)

        val job = scope.launch(Dispatchers.IO) {
            try {
                // SSHJ's `LocalPortForwarder.listen()` loops while its thread is
                // not interrupted and swallows the SocketException that
                // `accept()` throws once the server socket is closed. Closing
                // the socket alone therefore leaves this worker spinning at
                // 100% of a core and holding a Dispatchers.IO slot forever.
                // `runInterruptible` turns `job.cancel()` (every stop path
                // calls it, BEFORE closing the socket) into a Thread.interrupt(),
                // which is the only thing that ends the loop. It clears only the
                // interrupt it raised itself ; the `finally` below clears any
                // other one.
                runInterruptible { forwarder.listen() }
                // Reached only if `listen()` returned while this job was still
                // active. On stop / release / lost the job is cancelled, so
                // `runInterruptible` throws a CancellationException instead
                // (not an IOException : nothing to report, no reconnect).
                Logger.d(TAG, "Tunnel ${config.id} listen loop exited cleanly")
            } catch (e: IOException) {
                // A deliberate stop / release can surface here too (a channel
                // open torn down by the disconnect) : only an unexpected error
                // is a lost tunnel, and only that is worth a warning. Matched on
                // this loop's own client : a replaced entry (see [registerActive])
                // dies the same way, and must not take down its successor.
                if (!manuallyStopped.contains(config.id) && active[config.id]?.client === client) {
                    Logger.w(TAG, "Tunnel ${config.id} listen loop died: ${e.message}")
                    // Unexpected IO error (e.g. the SSH channel carrying this forward
                    // was torn down). Mark lost so the service reconnects: the
                    // DisconnectListener will fire too; `onTunnelLost` is idempotent.
                    onTunnelLost(config, "Tunnel local interrompu : ${e.message ?: "erreur IO"}", client)
                }
            } finally {
                // Never hand a pool worker back with the interrupt flag set : a
                // later `listen()` on it would exit at once.
                Thread.interrupted()
            }
        }

        var livenessJob: Job? = null
        val up = try {
            attachDisconnectListener(client, config)
            livenessJob = startLivenessWatchdog(client, config)
            registerAndConfirm(
                ActiveTunnel(
                    config = config,
                    client = client,
                    job = job,
                    forwarder = forwarder,
                    serverSocket = serverSocket,
                    livenessJob = livenessJob,
                ),
            )
        } catch (t: Throwable) {
            // The listen loop is already running on the socket and nothing
            // registered owns it. Cancel it FIRST (it is what interrupts the
            // loop, closing the socket alone leaves it spinning, see above),
            // the caller then closes the socket.
            job.cancel()
            livenessJob?.cancel()
            throw t
        }
        // ACTIVE was not written (a Stop got in first, or the entry was taken
        // out meanwhile) : whoever took the entry out closed it, socket
        // included, so there is nothing to announce.
        if (up) {
            Logger.d(TAG, "Tunnel LOCAL démarré : ${config.label} localhost:${config.localPort} → ${config.remoteHost}:${config.remotePort}")
        }
        return SshResult.Success(Unit)
    }

    private suspend fun startRemoteForwardInternal(
        config: TunnelConfig,
        client: SSHClient,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val remotePortForwarder = client.remotePortForwarder
        val forward = RemotePortForwarder.Forward(config.remotePort)
        remotePortForwarder.bind(
            forward,
            SocketForwardingConnectListener(InetSocketAddress("localhost", config.localPort)),
        )

        // REMOTE n'a pas de boucle bloquante : la détection de perte passe
        // uniquement par le [DisconnectListener] SSHJ du transport.
        val job = scope.launch(Dispatchers.IO) { /* tunnel actif tant que SSHClient ouvert */ }

        attachDisconnectListener(client, config)
        val livenessJob = startLivenessWatchdog(client, config)
        val up = registerAndConfirm(
            ActiveTunnel(
                config = config,
                client = client,
                job = job,
                remoteForwarder = remotePortForwarder,
                remoteForward = forward,
                livenessJob = livenessJob,
            ),
        )
        // ACTIVE was not written (a Stop got in first, or the entry was taken
        // out meanwhile) : whoever took the entry out cancelled the forward.
        if (up) {
            Logger.d(TAG, "Tunnel REMOTE démarré : ${config.label} remote:${config.remotePort} → localhost:${config.localPort}")
        }
        SshResult.Success(Unit)
    }

    /**
     * Install an SSHJ [DisconnectListener] on the transport as a secondary
     * trigger: fires when SSHJ's own reader thread detects the transport is
     * dead (e.g. server sent DISCONNECT, or the socket read timeout fires).
     * The primary failure detection is the liveness watchdog started by
     * [startLivenessWatchdog]: SSHJ's built-in keep-alive swallows failures
     * silently, so we can't rely on it alone.
     */
    private fun attachDisconnectListener(client: SSHClient, config: TunnelConfig) {
        try {
            client.transport.disconnectListener = DisconnectListener { reason: DisconnectReason, msg: String ->
                if (!manuallyStopped.contains(config.id)) {
                    onTunnelLost(config, "Session SSH fermée : ${reason.name}${if (msg.isNotBlank()) " ($msg)" else ""}", client)
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Impossible d'installer le DisconnectListener sur ${config.id} : ${e.message}")
        }
    }

    /**
     * Explicit application-level liveness watchdog.
     *
     * Rationale: SSHJ's built-in `KeepAlive` / `Heartbeater` threads CATCH AND
     * SWALLOW their own transport exceptions: a failing heartbeat send is
     * merely logged, it never triggers the `DisconnectListener`. Combined with
     * the fact that a TCP half-open socket (Wi-Fi dropped) accepts writes into
     * the kernel buffer without error, SSHJ can remain oblivious to an outage
     * for hours until the OS-level TCP keep-alive decides to tear the socket
     * down (Linux default: ~2 hours).
     *
     * This watchdog periodically sends a `keepalive@openssh.com` global
     * request with `wantReply = true` AND explicitly awaits the reply with a
     * short timeout. Missing reply → we trip [onTunnelLost] ourselves, which
     * transitions the tunnel to RECONNECTING and lets the service schedule a
     * reconnect. Deterministic detection within `keepAliveSeconds + timeout`.
     *
     * Runs on [scope] so it dies cleanly with the manager's lifecycle; the
     * job reference is stored in [ActiveTunnel.livenessJob] so `stopTunnel`
     * and `onTunnelLost` can cancel it.
     */
    private fun startLivenessWatchdog(client: SSHClient, config: TunnelConfig): Job {
        // Cadence aligned with `DesktopSshTerminalSession`'s session watchdog :
        // first probe after 3 s so the user gets feedback quickly, then 15 s
        // between probes. With the 10 s reply timeout, a real route drop is
        // detected within 15-25 s of the prior healthy probe.
        val firstProbeDelayMs = 3_000L
        val intervalMs = 15_000L
        val replyTimeoutSec = 10L
        return scope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(firstProbeDelayMs)
            } catch (_: Exception) { return@launch }
            while (coroutineContext[Job]?.isActive == true) {
                if (manuallyStopped.contains(config.id)) return@launch
                if (!active.containsKey(config.id)) return@launch

                // Measure RTT around the global request retrieve.
                // OpenSSH replies SSH_MSG_REQUEST_FAILURE to "keepalive@openssh.com"
                // (unknown request name): SSHJ translates that to ConnectionException.
                // This is a valid ACK: treat it as a successful probe and measure the
                // elapsed time. Only a timeout (elapsed >= replyTimeoutSec) means the
                // link is truly dead.
                //
                // [start] is captured AFTER sendGlobalRequest so the measurement covers
                // only the network round-trip (server reply latency), not the local
                // packet encoding / enqueue time on the SSHJ writer thread.
                val rtt: Long? = try {
                    val promise = client.connection.sendGlobalRequest(
                        "keepalive@openssh.com",
                        true,
                        ByteArray(0),
                    )
                    val start = System.nanoTime()
                    try {
                        promise.retrieve(replyTimeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                        (System.nanoTime() - start) / 1_000_000L
                    } catch (e: net.schmizz.sshj.connection.ConnectionException) {
                        val elapsedNanos = System.nanoTime() - start
                        if (elapsedNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(replyTimeoutSec)) {
                            // Got a FAILURE reply: server acknowledged the request (OpenSSH
                            // behaviour); the RTT is valid.
                            elapsedNanos / 1_000_000L
                        } else {
                            // True timeout: link is dead.
                            Logger.w(TAG, "Tunnel ${config.label} : keepalive timeout (ConnectionException après ${elapsedNanos / 1_000_000L}ms)")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Tunnel ${config.label} : keepalive probe échoué (${e.message})")
                    null
                }

                if (rtt != null) {
                    _latenciesByTunnel.update { it + (config.id to rtt) }
                } else {
                    onTunnelLost(config, "Liaison SSH perdue (keepalive sans réponse)", client)
                    return@launch
                }

                try {
                    kotlinx.coroutines.delay(intervalMs)
                } catch (_: Exception) { return@launch }
            }
        }
    }

    /**
     * Called once per tunnel when SSHJ reports a disconnect OR the listen
     * loop exits. Transitions the state to RECONNECTING and releases the SSH
     * resources so a subsequent `attemptReconnect` starts from a clean slate.
     * Idempotent: callers may invoke it multiple times without adverse effect.
     *
     * [source] is the client that reported the loss. Disconnecting a client
     * fires its `DisconnectListener` (SSHJ `TransportImpl.disconnect`), so a
     * client torn down by [registerActive] or [releaseForReconnect] reports
     * late, possibly after a new entry took the same id : such a callback is
     * ignored instead of dropping the tunnel now registered.
     */
    private fun onTunnelLost(config: TunnelConfig, reason: String, source: SSHClient) {
        val tunnel = active[config.id]
        if (tunnel == null || tunnel.client !== source) return
        if (!active.remove(config.id, tunnel)) return
        Logger.w(TAG, "Tunnel ${config.label} perdu : $reason : état RECONNECTING")
        _latenciesByTunnel.update { it - config.id }

        runCatching { tunnel.livenessJob?.cancel() }
        // Cancel first, as in stopTunnel : it interrupts the SSHJ listen loop.
        // No `forwarder.close()` : the cancellation already does its job, and
        // SSHJ's close() would interrupt the calling thread, which is the listen
        // worker itself when we come from its `catch (IOException)`.
        runCatching { tunnel.job.cancel() }
        runCatching { tunnel.serverSocket?.close() }
        runCatching { tunnel.socks5Server?.stop() }
        runCatching { tunnel.client.disconnect() }

        _tunnelStates.update {
            it + (config.id to TunnelState(config, TunnelStatus.RECONNECTING, reason))
        }
    }

    private suspend fun startSocks5Internal(
        config: TunnelConfig,
        client: SSHClient,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val server = DesktopSocks5Server(client, config.localPort)
        server.start()

        val job = scope.launch(Dispatchers.IO) { /* SOCKS5 server gère ses propres threads */ }

        attachDisconnectListener(client, config)
        val livenessJob = startLivenessWatchdog(client, config)
        val up = registerAndConfirm(
            ActiveTunnel(
                config = config,
                client = client,
                job = job,
                socks5Server = server,
                livenessJob = livenessJob,
            ),
        )
        // ACTIVE was not written (a Stop got in first, or the entry was taken
        // out meanwhile) : whoever took the entry out stopped the server.
        if (up) {
            Logger.d(TAG, "Tunnel SOCKS5 démarré : ${config.label} socks5://localhost:${config.localPort}")
        }
        SshResult.Success(Unit)
    }

    /**
     * Writes ERROR for a start that failed, unless a Stop landed meanwhile, and
     * says whether it wrote. The flag is tested inside the compare-and-set, and
     * [stopTunnel] sets it BEFORE it writes STOPPED, so an ERROR can never end up
     * over a STOPPED the user asked for (the start paths test the flag before
     * calling this, this closes the window between that test and the write).
     * Returns `false` when nothing was written : the caller then answers like the
     * other stop paths instead of reporting an error (see [failStart]).
     */
    private fun setError(config: TunnelConfig, message: String): Boolean {
        var written = false
        _tunnelStates.update { states ->
            // `update` may re-run this block : the last run is the one that lands.
            written = !manuallyStopped.contains(config.id)
            if (!written) states
            else states + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = message))
        }
        return written
    }

    /**
     * Registers [tunnel] as the live entry of its id and confirms it ACTIVE,
     * unless a Stop got in first. Shared by the three tunnel types. Returns
     * `true` only if ACTIVE was written. `false` means the tunnel is not up : a
     * Stop got in first (the entry is then gone, its resources closed, and no
     * state is written, the Stop's STOPPED stands), or the entry was taken out
     * by someone else before the ACTIVE write (see [markActive]).
     *
     * Same ordering as [startingClients] : [stopTunnel] sets the flag BEFORE it
     * looks in [active], and we fill [active] BEFORE we read the flag. Whichever
     * runs second sees the other, so a Stop cannot slip between the two :
     *  - flag already set : we take OUR entry back out (`remove(id, tunnel)`,
     *    by identity) and close it exactly as [stopTunnel] would, job cancelled
     *    FIRST. Only the one that took the entry out tears it down : if the Stop
     *    already took it, it closes it and we do not close it twice.
     *  - flag not set yet : the Stop will find the entry in [active], close it
     *    and write STOPPED ([markActive] never writes over that).
     */
    private fun registerAndConfirm(tunnel: ActiveTunnel): Boolean {
        val id = tunnel.config.id
        registerActive(tunnel)
        afterRegisterHook?.invoke()
        if (manuallyStopped.contains(id)) {
            if (active.remove(id, tunnel)) {
                tunnel.job.cancel()
                closeTunnelResources(tunnel)
                Logger.d(TAG, "Tunnel $id : arrêt reçu pendant le démarrage, ressources fermées")
            }
            return false
        }
        beforeConfirmHook?.invoke()
        return markActive(tunnel)
    }

    /**
     * Writes ACTIVE for [tunnel], only while it is still the live entry of its
     * id, and says whether it wrote. The test is inside the compare-and-set
     * (`update` re-runs it whenever another writer changed the state in
     * between), so what happened to the entry before the write decides :
     *  - [stopTunnel] and [onTunnelLost] take the entry out, then write their own
     *    status (STOPPED, RECONNECTING). Taken out before our test : it fails and
     *    their status stands. Taken out after : their status overwrites our
     *    ACTIVE. Either way ACTIVE never ends up over them.
     *  - [releaseForReconnect] takes it out and writes nothing : our test fails
     *    and the status stays what it was (RECONNECTING if a network drop turned
     *    STARTING into it), the service's reconnect loop carries on from there.
     *  - [registerActive] closes the entry it replaces : the replaced tunnel
     *    fails our test, the replacement owns the id.
     *
     * The test is the entry, not the previous status : a STOPPED written by an
     * EARLIER Stop that was still closing its tunnel when this start began
     * (the reservation does not wait for it) must not keep a live tunnel
     * from showing ACTIVE.
     */
    private fun markActive(tunnel: ActiveTunnel): Boolean {
        val id = tunnel.config.id
        var written = false
        _tunnelStates.update { states ->
            // `update` may re-run this block : the last run is the one that lands.
            written = active[id] === tunnel
            if (!written) states
            else states + (id to TunnelState(tunnel.config, TunnelStatus.ACTIVE))
        }
        return written
    }

    /**
     * Registers [tunnel] as the live entry for its id. [startTunnel] reserves
     * the id, so nothing should be there already. If an entry is anyway, it is
     * torn down exactly as [stopTunnel] does (job cancelled FIRST, then
     * [closeTunnelResources]) rather than silently overwritten : an overwritten
     * entry would orphan its listener, its ServerSocket (the local port stays
     * bound until the app restarts) and its SSH client.
     *
     * `internal` so that the tests reach it : the reservation makes a
     * replacement unreachable through [startTunnel].
     */
    internal fun registerActive(tunnel: ActiveTunnel) {
        val previous = active.put(tunnel.config.id, tunnel) ?: return
        Logger.w(
            TAG,
            "Tunnel ${tunnel.config.label} (${tunnel.config.id}) : entrée active déjà présente, " +
                "l'ancienne est fermée avant d'être remplacée",
        )
        // Cancel first, for the same reason as in stopTunnel.
        previous.job.cancel()
        closeTunnelResources(previous)
    }

    private fun closeTunnelResources(tunnel: ActiveTunnel) {
        tunnel.livenessJob?.cancel()
        try {
            when (tunnel.config.type) {
                TunnelType.LOCAL_FORWARD -> tunnel.serverSocket?.close()
                TunnelType.REMOTE_FORWARD -> {
                    val f = tunnel.remoteForwarder
                    val fw = tunnel.remoteForward
                    if (f != null && fw != null) {
                        try { f.cancel(fw) } catch (_: Exception) {}
                    }
                }
                TunnelType.DYNAMIC_SOCKS5 -> tunnel.socks5Server?.stop()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Erreur fermeture forwarder ${tunnel.config.id}: ${e.message}")
        } finally {
            try { tunnel.client.disconnect() } catch (_: Exception) {}
        }
    }

    private fun isPortInUse(port: Int): Boolean = try {
        ServerSocket(port).use { false }
    } catch (e: IOException) {
        true
    }
}

/**
 * Impl prod du [SshClientFactory] : résout l'auth à partir du [VaultManager]
 * (mot de passe / clé / certificat) et ouvre un `SSHClient` avec le
 * [hostKeyVerifier] de la partie connue des hôtes.
 *
 * Reflète [DesktopSshSessionManager] pour garder la cohérence :
 *  - mêmes erreurs SSHJ capturées,
 *  - même gestion passphrase (effacement mémoire).
 */
class VaultSshClientFactory(
    private val vaultManager: VaultManager,
    private val hostKeyVerifier: DesktopKnownHostsVerifier,
    /**
     * Délai (ms) alloué à l'établissement TCP (`SSHClient.connectTimeout`),
     * équivalent `ssh -o ConnectTimeout`. Même contrat que
     * [fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager.connectTimeoutMsProvider] :
     * câblé par [fr.techtical.nextsh.desktop.data.di.DesktopContainer] sur le
     * réglage « Timeout de connexion » (Settings, 5-30 s). `client.timeout`
     * (auth/handshake) reste une constante interne, indépendante du réglage.
     */
    private val connectTimeoutMsProvider: () -> Int = { 10_000 },
) : SshClientFactory {

    override suspend fun open(host: Host, onCreated: (SSHClient) -> Unit): SshResult<SSHClient> = withContext(Dispatchers.IO) {
        // Use KEEP_ALIVE (SSH_MSG_IGNORE without wantReply) here: NOT
        // HEARTBEAT.
        //
        // HEARTBEAT also fires `keepalive@openssh.com` global requests with
        // wantReply=true on a private SSHJ thread, which contends with our
        // own [startLivenessWatchdog] for slots in the `Connection` global-
        // request promise queue. On a tunnel where no shell channel is
        // actively pumping bytes, this race silently swallows our watchdog's
        // promise: `promise.retrieve(...)` never completes, latency is never
        // emitted, and the tunnel stays "alive but unmeasured" indefinitely.
        //
        // The watchdog itself is the proactive liveness signal here: it
        // sends its own wantReply=true probe every 15 s, measures the RTT,
        // and trips [onTunnelLost] on timeout. That's strictly stronger
        // than HEARTBEAT's "log and move on" failure handling.
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val client = SSHClient(config).apply {
            addHostKeyVerifier(hostKeyVerifier)
            connectTimeout = connectTimeoutMsProvider()
            // Borne l'authentification/handshake: constante interne, miroir d'Android.
            timeout = 30_000
        }
        try {
            // Hand the freshly-created client back to the caller BEFORE the
            // potentially-long `connect()` blocks the IO thread, so the manager
            // can stash it and call `disconnect()` on it from another coroutine
            // to abort the connection in flight. Inside the `try` on purpose :
            // the callback may throw an IOException to give up before
            // `connect()` (a Stop that landed before the client existed, where
            // `disconnect()` would be a no-op on an unconnected client). The
            // `catch (IOException)` below then closes the client and returns
            // an Error, and no socket is ever opened.
            onCreated(client)
            client.connect(host.hostname, host.port)
            // Enable SSH keep-alive so a dropped route (Wi-Fi off, VPN tunnel
            // broken, peer gone silent) is detected PROACTIVELY. Default is 0
            // (disabled). Clamp: [5, 300], too-short values spam the server.
            client.connection.keepAlive.keepAliveInterval =
                host.keepAliveSeconds.coerceIn(5, 300)
            when (host.authType) {
                AuthType.PASSWORD -> {
                    val password = vaultManager.getPassword(host.credentialId)
                        ?: return@withContext failAndClose(
                            client,
                            SshErrorCode.AUTH_FAILED,
                            "Mot de passe absent du vault pour ${host.label}",
                        )
                    try { client.authPassword(host.username, password) }
                    finally { Arrays.fill(password, '\u0000') }
                }
                AuthType.SSH_KEY -> {
                    val pem = vaultManager.getPrivateKey(host.credentialId)
                        ?: return@withContext failAndClose(
                            client,
                            SshErrorCode.AUTH_FAILED,
                            "Clé SSH absente du vault pour ${host.label}",
                        )
                    val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                    try {
                        val provider = DesktopSshKeyLoader.loadKeyProviderFromString(pem, passphrase)
                        client.authPublickey(host.username, provider)
                    } finally { passphrase?.let { Arrays.fill(it, '\u0000') } }
                }
                AuthType.CERTIFICATE -> {
                    val pem = vaultManager.getPrivateKey(host.credentialId)
                    val cert = vaultManager.getCertificate(host.credentialId)
                    if (pem == null || cert == null) {
                        return@withContext failAndClose(
                            client,
                            SshErrorCode.AUTH_FAILED,
                            "Clé ou certificat absent du vault pour ${host.label}",
                        )
                    }
                    val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                    try {
                        val provider = DesktopSshKeyLoader.loadKeyProviderWithCertificate(pem, cert, passphrase)
                        client.authPublickey(host.username, provider)
                    } finally { passphrase?.let { Arrays.fill(it, '\u0000') } }
                }
                AuthType.FIDO2, AuthType.BIOMETRIC_KEY -> {
                    return@withContext failAndClose(
                        client,
                        SshErrorCode.AUTH_FAILED,
                        "Auth FIDO2 / biométrique non disponible côté Desktop (Wave 5.x)",
                    )
                }
            }
            SshResult.Success(client)
        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            try { client.disconnect() } catch (_: Exception) {}
            SshResult.Error(SshErrorCode.AUTH_FAILED, authRefusedMessage(e.message ?: e::class.simpleName))
        } catch (e: IOException) {
            try { client.disconnect() } catch (_: Exception) {}
            connectErrorResult(e, connectTimeoutMsProvider())
        } catch (e: Exception) {
            try { client.disconnect() } catch (_: Exception) {}
            SshResult.Error(SshErrorCode.UNKNOWN, unknownSshErrorMessage(e))
        }
    }

    private fun failAndClose(client: SSHClient, code: SshErrorCode, message: String): SshResult<SSHClient> {
        try { client.disconnect() } catch (_: Exception) {}
        return SshResult.Error(code, message)
    }
}

