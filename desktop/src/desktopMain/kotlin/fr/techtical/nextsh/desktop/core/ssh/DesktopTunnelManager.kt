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
     * Pas de paramètre default ici parce qu'un `fun interface` ne supporte
     * pas la SAM conversion avec un argument à valeur par défaut. Les tests
     * lambda passent `_` pour ignorer le tracker.
     */
    suspend fun open(host: Host, onCreated: (SSHClient) -> Unit): SshResult<SSHClient>
}

/**
 * État d'un tunnel actif en mémoire : garde la référence SSHJ pour pouvoir
 * l'arrêter proprement. Non-exposé à l'UI.
 */
private data class ActiveTunnel(
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
 *    SSHJ : le [Mutex] sert uniquement à sérialiser les mises à jour du
 *    `StateFlow` pour éviter les collisions start/stop concurrentes sur le
 *    même tunnel.
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
     * Tracks the [SSHClient] of tunnels currently in STARTING (handshake / auth).
     * Populated by the `onCreated` callback of [SshClientFactory.open] before
     * `connect()` blocks ; cleared once the tunnel is fully up (and stored in
     * [active]) or fails.
     *
     * `stopTunnel` calls `disconnect()` on this client to interrupt a connect
     * that is otherwise stuck on TCP SYN timeout (~30 s) or auth handshake.
     * Without this, "Stop" returned success but the underlying coroutine kept
     * blocking and finally surfaced a timeout error.
     */
    private val startingClients = ConcurrentHashMap<String, SSHClient>()

    /**
     * Démarre un tunnel. Utilise [hostLookup] pour retrouver l'hôte associé :
     * le caller (`DesktopTunnelService`) injecte le `HostRepository`. Ouvre un
     * `SSHClient` dédié via [SshClientFactory] puis crée le forwarder SSHJ
     * correspondant au type.
     *
     * Transitions d'état émises :
     *  - STARTING → ACTIVE   sur succès
     *  - STARTING → ERROR(m) sur échec (auth, port occupé, réseau…)
     */
    suspend fun startTunnel(
        config: TunnelConfig,
        hostLookup: suspend (hostId: String) -> Host?,
    ): SshResult<Unit> {
        if (active.containsKey(config.id)) {
            // Déjà démarré (ou en STARTING) : no-op côté caller. On renvoie succès.
            return SshResult.Success(Unit)
        }

        mutex.withLock {
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
            manuallyStopped.remove(config.id)
        }

        val host = hostLookup(config.hostId)
        if (host == null) {
            setError(config, "Hôte ${config.hostId} introuvable")
            return SshResult.Error(SshErrorCode.UNKNOWN, "Hôte ${config.hostId} introuvable")
        }

        // LOCAL_FORWARD / DYNAMIC_SOCKS5 : check port local avant même d'ouvrir la
        // connexion SSH : une erreur PORT_IN_USE ne doit pas consommer d'auth.
        if (config.type == TunnelType.LOCAL_FORWARD || config.type == TunnelType.DYNAMIC_SOCKS5) {
            if (isPortInUse(config.localPort)) {
                val msg = "Port ${config.localPort} déjà utilisé localement"
                setError(config, msg)
                return SshResult.Error(SshErrorCode.PORT_IN_USE, msg)
            }
        }

        // The factory hands us the SSHClient BEFORE connect() blocks via
        // [onCreated]: we stash it so [stopTunnel] can call disconnect() on
        // it to abort an in-flight handshake. If the user clicked Stop while
        // connect/auth was running, [manuallyStopped] is now set and we bail
        // out as soon as open() returns.
        val openResult = clientFactory.open(host) { newClient ->
            startingClients[config.id] = newClient
        }
        // Always drop the starting reference once open() returns: either we
        // succeed (the client moves into `active`), we fail (it's already
        // disconnected), or stopTunnel pre-emptively disconnected it (and
        // open() returned an error).
        startingClients.remove(config.id)

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
            is SshResult.Error -> {
                setError(config, openResult.message)
                openResult
            }
            is SshResult.Success -> {
                val client = openResult.data
                try {
                    when (config.type) {
                        TunnelType.LOCAL_FORWARD -> startLocalForwardInternal(config, client)
                        TunnelType.REMOTE_FORWARD -> startRemoteForwardInternal(config, client)
                        TunnelType.DYNAMIC_SOCKS5 -> startSocks5Internal(config, client)
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Échec démarrage tunnel ${config.label}", e)
                    try { client.disconnect() } catch (_: Exception) {}
                    val msg = e.message ?: "Erreur inconnue"
                    setError(config, msg)
                    SshResult.Error(SshErrorCode.UNKNOWN, msg)
                }
            }
        }
    }

    /**
     * Arrêt propre : cancel le Job enfant, ferme le forwarder/socket/SOCKS5,
     * puis disconnect le SSHClient. Idempotent : appel sur un id inconnu renvoie
     * succès (pas d'erreur bruit sur "stopAll" puis stop explicite).
     */
    override suspend fun stopTunnel(tunnelId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        // Set the manuallyStopped flag FIRST: this serves two purposes:
        //  1. signals startTunnel(...) to bail out if it's still in flight
        //     (handshake / auth) by the time open() returns ;
        //  2. tells the service-level reconnect loop "user asked to stop,
        //     don't auto-restart".
        manuallyStopped.add(tunnelId)

        // If the tunnel is currently in STARTING (connect / auth blocking on
        // an IO thread), break it by force-disconnecting the half-built client.
        // The blocking call on the SSHJ thread returns as IOException → the
        // startTunnel coroutine completes, sees manuallyStopped, and bails.
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
            closeTunnelResources(tunnel)
            tunnel.job.cancel()
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
            closeTunnelResources(tunnel)
            tunnel.job.cancel()
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

    /** IDs actifs à l'instant T : utilisé par le service pour gérer les reconnects. */
    fun activeIds(): Set<String> = active.keys.toSet()

    fun wasManuallyStopped(tunnelId: String): Boolean = tunnelId in manuallyStopped

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
        val params = Parameters(
            "127.0.0.1",
            config.localPort,
            config.remoteHost,
            config.remotePort,
        )
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(params.localHost, params.localPort))

        val forwarder = client.newLocalPortForwarder(params, serverSocket)

        val job = scope.launch(Dispatchers.IO) {
            try {
                forwarder.listen()
                // Clean return: SSHJ's `LocalPortForwarder.listen()` exits
                // normally only when the server socket is closed from OUTSIDE
                // (i.e. `stopTunnel`). No reconnect needed.
                Logger.d(TAG, "Tunnel ${config.id} listen loop exited cleanly")
            } catch (e: IOException) {
                Logger.w(TAG, "Tunnel ${config.id} listen loop died: ${e.message}")
                // Unexpected IO error (e.g. the SSH channel carrying this forward
                // was torn down). Mark lost so the service reconnects: the
                // DisconnectListener will fire too; `onTunnelLost` is idempotent.
                if (!manuallyStopped.contains(config.id) && active.containsKey(config.id)) {
                    onTunnelLost(config, "Tunnel local interrompu : ${e.message ?: "erreur IO"}")
                }
            }
        }

        attachDisconnectListener(client, config)
        val livenessJob = startLivenessWatchdog(client, config)
        active[config.id] = ActiveTunnel(
            config = config,
            client = client,
            job = job,
            forwarder = forwarder,
            serverSocket = serverSocket,
            livenessJob = livenessJob,
        )
        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }
        Logger.d(TAG, "Tunnel LOCAL démarré : ${config.label} localhost:${config.localPort} → ${config.remoteHost}:${config.remotePort}")
        SshResult.Success(Unit)
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
        active[config.id] = ActiveTunnel(
            config = config,
            client = client,
            job = job,
            remoteForwarder = remotePortForwarder,
            remoteForward = forward,
            livenessJob = livenessJob,
        )
        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }
        Logger.d(TAG, "Tunnel REMOTE démarré : ${config.label} remote:${config.remotePort} → localhost:${config.localPort}")
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
                    onTunnelLost(config, "Session SSH fermée : ${reason.name}${if (msg.isNotBlank()) " ($msg)" else ""}")
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
                    onTunnelLost(config, "Liaison SSH perdue (keepalive sans réponse)")
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
     */
    private fun onTunnelLost(config: TunnelConfig, reason: String) {
        val tunnel = active.remove(config.id) ?: return
        Logger.w(TAG, "Tunnel ${config.label} perdu : $reason : état RECONNECTING")
        _latenciesByTunnel.update { it - config.id }

        runCatching { tunnel.livenessJob?.cancel() }
        runCatching { tunnel.forwarder?.close() }
        runCatching { tunnel.serverSocket?.close() }
        runCatching { tunnel.socks5Server?.stop() }
        runCatching { tunnel.client.disconnect() }
        runCatching { tunnel.job.cancel() }

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
        active[config.id] = ActiveTunnel(
            config = config,
            client = client,
            job = job,
            socks5Server = server,
            livenessJob = livenessJob,
        )
        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }
        Logger.d(TAG, "Tunnel SOCKS5 démarré : ${config.label} socks5://localhost:${config.localPort}")
        SshResult.Success(Unit)
    }

    private fun setError(config: TunnelConfig, message: String) {
        _tunnelStates.update {
            it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = message))
        }
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
        // Hand the freshly-created client back to the caller BEFORE the
        // potentially-long `connect()` blocks the IO thread, so the manager
        // can stash it and call `disconnect()` on it from another coroutine
        // to abort the connection in flight.
        onCreated(client)
        try {
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

