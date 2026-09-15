// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import timber.log.Timber
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Représente un tunnel actif en mémoire.
 *
 * Selon le type de tunnel, différents champs sont renseignés :
 *  - LOCAL_FORWARD  : [forwarder] + [serverSocket]
 *  - REMOTE_FORWARD : [remoteForwarder] + [remoteForward]
 *  - DYNAMIC_SOCKS5 : [socks5Server]
 */
data class ActiveTunnel(
    val config          : TunnelConfig,
    /** Session SSH portant le tunnel, pour invalider tous ses tunnels à la déconnexion. */
    val sessionId       : String                          = "",
    /**
     * Identifie cette instance de tunnel, pas sa configuration.
     *
     * Une boucle d'écoute qui se termine tardivement doit pouvoir constater
     * qu'elle appartient à une instance déjà remplacée : sans cela, redémarrer
     * un tunnel juste après son arrêt ferait tuer le nouveau par le thread de
     * l'ancien.
     */
    val instanceId      : Long                            = 0L,
    // LOCAL_FORWARD
    val forwarder       : LocalPortForwarder?             = null,
    val serverSocket    : ServerSocket?                   = null,
    // REMOTE_FORWARD
    val remoteForwarder : RemotePortForwarder?            = null,
    val remoteForward   : RemotePortForwarder.Forward?    = null,
    // DYNAMIC_SOCKS5
    val socks5Server    : Socks5Server?                   = null,
)

@Singleton
class SshTunnelManager @Inject constructor(
    private val sessionManager: SshSessionManager,
) : fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager {

    private val _activeTunnels = MutableStateFlow<Map<String, ActiveTunnel>>(emptyMap())
    val activeTunnels: StateFlow<Map<String, ActiveTunnel>> = _activeTunnels

    private val _tunnelStates = MutableStateFlow<Map<String, TunnelState>>(emptyMap())
    val tunnelStates: StateFlow<Map<String, TunnelState>> = _tunnelStates.asStateFlow()

    /** Shared interface: Flow<List<TunnelState>> (platform-neutral). */
    override val tunnels: Flow<List<TunnelState>> = _tunnelStates.map { it.values.toList() }

    override fun getTunnelState(tunnelId: String): TunnelState? = _tunnelStates.value[tunnelId]

    /** @see fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager.markStarting */
    override fun markStarting(config: TunnelConfig) {
        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
        manuallyStoppedIds.remove(config.id)
    }

    /** @see fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager.markError */
    override fun markError(config: TunnelConfig, message: String) {
        _tunnelStates.update {
            it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = message))
        }
    }

    // ── Shared interface wrappers (sessionId-based) ───────────────────────────────

    override suspend fun startLocalForward(config: TunnelConfig, sessionId: String): SshResult<Unit> {
        val client = sessionManager.getClient(sessionId)
            ?: return SshResult.Error(SshErrorCode.UNKNOWN, "Session $sessionId introuvable")
        return startLocalForward(client, config, sessionId)
    }

    override suspend fun startRemoteForward(config: TunnelConfig, sessionId: String): SshResult<Unit> {
        val client = sessionManager.getClient(sessionId)
            ?: return SshResult.Error(SshErrorCode.UNKNOWN, "Session $sessionId introuvable")
        return startRemoteForward(client, config, sessionId)
    }

    override suspend fun startDynamicForward(config: TunnelConfig, sessionId: String): SshResult<Unit> {
        val client = sessionManager.getClient(sessionId)
            ?: return SshResult.Error(SshErrorCode.UNKNOWN, "Session $sessionId introuvable")
        return startDynamicSocks5(client, config, sessionId)
    }

    /** IDs des tunnels arrêtés manuellement, exclus de la reconnexion automatique. */
    private val manuallyStoppedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Sessions SSH sur lesquelles un listener de déconnexion est déjà posé. */
    private val watchedSessionIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Distingue deux démarrages successifs d'un même tunnel, voir [ActiveTunnel.instanceId]. */
    private val instanceCounter = java.util.concurrent.atomic.AtomicLong(0L)

    // ── Détection de panne ────────────────────────────────────────────────────────

    /**
     * Bascule un tunnel en [TunnelStatus.ERROR] après une panne, en le retirant
     * des tunnels actifs (ses ressources sont mortes).
     *
     * Sans effet si le tunnel a été arrêté manuellement ou n'est plus actif :
     * la fermeture volontaire d'une ServerSocket fait aussi sortir la boucle
     * d'écoute, et il ne faut pas la confondre avec une panne.
     *
     * C'est cette transition qui rend le tunnel visible pour
     * [getTunnelsForReconnect] et donc éligible à la reconnexion automatique.
     */
    private fun markUnexpectedStop(tunnelId: String, instanceId: Long?, message: String) {
        if (tunnelId in manuallyStoppedIds) return
        val active = _activeTunnels.value[tunnelId] ?: return
        // Le thread d'une instance remplacee ne doit pas tuer la nouvelle.
        if (instanceId != null && active.instanceId != instanceId) return

        // Fermer les ressources AVANT de lacher la reference : une coupure du
        // transport SSH ne ferme pas la socket locale, qui resterait liee au
        // port sans que rien ne la reference. La reconnexion echouerait alors
        // indefiniment sur "port deja utilise".
        closeTunnelResources(active)

        _activeTunnels.update { it - tunnelId }
        _tunnelStates.update { states ->
            val existing = states[tunnelId] ?: return@update states
            states + (tunnelId to existing.copy(
                status       = TunnelStatus.ERROR,
                errorMessage = message,
            ))
        }
        Timber.w("Tunnel ${active.config.label} interrompu : $message")
    }

    /** Libère les ressources d'un tunnel selon son type, sans toucher aux états. */
    private fun closeTunnelResources(active: ActiveTunnel) {
        try {
            when (active.config.type) {
                TunnelType.LOCAL_FORWARD  -> active.serverSocket?.close()
                TunnelType.REMOTE_FORWARD -> {
                    val forwarder = active.remoteForwarder
                    val forward   = active.remoteForward
                    if (forwarder != null && forward != null) forwarder.cancel(forward)
                }
                TunnelType.DYNAMIC_SOCKS5 -> active.socks5Server?.stop()
            }
        } catch (e: Exception) {
            Timber.w("Erreur fermeture tunnel ${active.config.id} : ${e.message}")
        }
    }

    /**
     * Pose un listener de déconnexion sur le transport SSH de [sessionId], une
     * seule fois par session.
     *
     * Indispensable : une coupure réseau ne ferme pas les sockets locales, donc
     * ni la boucle d'écoute d'un LOCAL_FORWARD ni celle d'un SOCKS5 ne
     * remontent d'erreur tant qu'aucun client ne s'y connecte. Sans ce
     * listener, un tunnel resterait affiché ACTIF alors que le transport est
     * mort, et la reconnexion ne se déclencherait jamais. C'est également le
     * seul signal de panne disponible pour un REMOTE_FORWARD, qui n'ouvre
     * aucune socket locale.
     */
    private fun registerDisconnectListener(client: SSHClient, sessionId: String) {
        if (sessionId.isEmpty() || !watchedSessionIds.add(sessionId)) return
        try {
            client.transport.disconnectListener = net.schmizz.sshj.transport.DisconnectListener { reason, _ ->
                watchedSessionIds.remove(sessionId)
                val message = "Connexion SSH perdue (${reason.name})"
                _activeTunnels.value
                    .filterValues { it.sessionId == sessionId }
                    .map { (id, tunnel) -> id to tunnel.instanceId }
                    .forEach { (id, instanceId) -> markUnexpectedStop(id, instanceId, message) }
            }
        } catch (e: Exception) {
            watchedSessionIds.remove(sessionId)
            Timber.w("Impossible d'observer la deconnexion de la session $sessionId : ${e.message}")
        }
    }

    // ── Local forward ─────────────────────────────────────────────────────────────

    /**
     * Démarre un tunnel local forward.
     *
     * Cas d'usage de référence : n8n VPS
     *   localPort   = 5678
     *   remoteHost  = "127.0.0.1"
     *   remotePort  = 5678
     *
     * Équivalent CLI : ssh -L 5678:127.0.0.1:5678 user@vps -N
     */
    suspend fun startLocalForward(
        sshClient: SSHClient,
        config: TunnelConfig,
        sessionId: String = "",
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        if (config.type != TunnelType.LOCAL_FORWARD) {
            val msg = "startLocalForward appelé avec type ${config.type}"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            return@withContext SshResult.Error(SshErrorCode.UNKNOWN, msg)
        }

        if (isPortInUse(config.localPort)) {
            val msg = "Port ${config.localPort} déjà utilisé localement"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            return@withContext SshResult.Error(SshErrorCode.PORT_IN_USE, msg)
        }

        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
        manuallyStoppedIds.remove(config.id)

        return@withContext try {
            val params = Parameters(
                "127.0.0.1",
                config.localPort,
                config.remoteHost,
                config.remotePort
            )
            val serverSocket = ServerSocket()
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(params.localHost, params.localPort))

            val forwarder = sshClient.newLocalPortForwarder(params, serverSocket)
            val instanceId = instanceCounter.incrementAndGet()

            Thread {
                var failure: String? = null
                try {
                    forwarder.listen()
                } catch (e: IOException) {
                    Timber.w("Tunnel ${config.id} stopped: ${e.message}")
                    failure = e.message ?: "Erreur d'écoute locale"
                }
                // listen() rend la main aussi bien sur arrêt manuel (ServerSocket
                // fermée) que sur panne : markUnexpectedStop filtre le premier cas.
                markUnexpectedStop(config.id, instanceId, failure ?: "Tunnel local interrompu")
            }.apply {
                name = "tunnel-${config.label}"
                isDaemon = true
                start()
            }

            _activeTunnels.update { it + (config.id to
                ActiveTunnel(
                    config       = config,
                    sessionId    = sessionId,
                    instanceId   = instanceId,
                    forwarder    = forwarder,
                    serverSocket = serverSocket,
                )) }

            registerDisconnectListener(sshClient, sessionId)
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }

            Timber.i(
                "Tunnel LOCAL démarré : ${config.label} " +
                "localhost:${config.localPort} → ${config.remoteHost}:${config.remotePort}"
            )
            SshResult.Success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "Échec démarrage tunnel LOCAL ${config.label}")
            val msg = e.message ?: "Erreur inconnue"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        }
    }

    // ── Remote forward ────────────────────────────────────────────────────────────

    /**
     * Démarre un tunnel remote forward.
     *
     * Équivalent CLI : ssh -R remotePort:localhost:localPort user@vps -N
     *
     * Le serveur SSH écoute sur [config.remotePort] et redirige vers
     * localhost:[config.localPort] côté client.
     */
    suspend fun startRemoteForward(
        sshClient: SSHClient,
        config: TunnelConfig,
        sessionId: String = "",
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        if (config.type != TunnelType.REMOTE_FORWARD) {
            val msg = "startRemoteForward appelé avec type ${config.type}"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            return@withContext SshResult.Error(SshErrorCode.UNKNOWN, msg)
        }

        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
        manuallyStoppedIds.remove(config.id)

        return@withContext try {
            val remotePortForwarder = sshClient.remotePortForwarder
            val forward = RemotePortForwarder.Forward(config.remotePort)

            remotePortForwarder.bind(
                forward,
                SocketForwardingConnectListener(
                    InetSocketAddress("localhost", config.localPort)
                )
            )

            _activeTunnels.update { it + (config.id to
                ActiveTunnel(
                    config          = config,
                    sessionId       = sessionId,
                    instanceId      = instanceCounter.incrementAndGet(),
                    remoteForwarder = remotePortForwarder,
                    remoteForward   = forward,
                )) }

            registerDisconnectListener(sshClient, sessionId)
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }

            Timber.i(
                "Tunnel REMOTE démarré : ${config.label} " +
                "remote:${config.remotePort} → localhost:${config.localPort}"
            )
            SshResult.Success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "Échec démarrage tunnel REMOTE ${config.label}")
            val msg = e.message ?: "Erreur inconnue"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        }
    }

    // ── Dynamic SOCKS5 ────────────────────────────────────────────────────────────

    /**
     * Démarre un proxy SOCKS5 dynamique.
     *
     * Équivalent CLI : ssh -D localPort user@vps -N
     *
     * Toutes les connexions vers localhost:[config.localPort] sont routées
     * via le serveur SSH comme proxy SOCKS5.
     */
    suspend fun startDynamicSocks5(
        sshClient: SSHClient,
        config: TunnelConfig,
        sessionId: String = "",
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        if (config.type != TunnelType.DYNAMIC_SOCKS5) {
            val msg = "startDynamicSocks5 appelé avec type ${config.type}"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            return@withContext SshResult.Error(SshErrorCode.UNKNOWN, msg)
        }

        if (isPortInUse(config.localPort)) {
            val msg = "Port ${config.localPort} déjà utilisé localement"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            return@withContext SshResult.Error(SshErrorCode.PORT_IN_USE, msg)
        }

        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.STARTING)) }
        manuallyStoppedIds.remove(config.id)

        return@withContext try {
            val instanceId = instanceCounter.incrementAndGet()
            val server = Socks5Server(sshClient, config.localPort) { failure ->
                markUnexpectedStop(config.id, instanceId, failure)
            }
            server.start()

            _activeTunnels.update { it + (config.id to
                ActiveTunnel(
                    config       = config,
                    sessionId    = sessionId,
                    instanceId   = instanceId,
                    socks5Server = server,
                )) }

            registerDisconnectListener(sshClient, sessionId)
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }

            Timber.i(
                "Tunnel SOCKS5 démarré : ${config.label} " +
                "socks5://localhost:${config.localPort}"
            )
            SshResult.Success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "Échec démarrage tunnel SOCKS5 ${config.label}")
            val msg = e.message ?: "Erreur inconnue"
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = msg)) }
            SshResult.Error(SshErrorCode.UNKNOWN, msg)
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────────

    /**
     * Arrêt propre d'un tunnel, selon son type.
     */
    override suspend fun stopTunnel(tunnelId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        val active = _activeTunnels.value[tunnelId]

        if (active == null) {
            // Tunnel en panne : ses ressources sont deja fermees, mais son etat
            // ERREUR subsiste pour l'afficher et permettre la reconnexion. Le
            // retirer est le seul moyen pour l'utilisateur de solder la carte,
            // et c'est aussi ce qui laisse le service au premier plan s'arreter
            // quand plus aucun tunnel ne reste.
            if (_tunnelStates.value.containsKey(tunnelId)) {
                manuallyStoppedIds.add(tunnelId)
                _tunnelStates.update { it - tunnelId }
                Timber.i("Tunnel $tunnelId en erreur retire de la liste")
                return@withContext SshResult.Success(Unit)
            }
            return@withContext SshResult.Error(SshErrorCode.UNKNOWN, "Tunnel $tunnelId introuvable")
        }

        manuallyStoppedIds.add(tunnelId)

        return@withContext try {
            when (active.config.type) {
                TunnelType.LOCAL_FORWARD -> {
                    active.serverSocket?.close()
                }
                TunnelType.REMOTE_FORWARD -> {
                    val forwarder = active.remoteForwarder
                    val forward   = active.remoteForward
                    if (forwarder != null && forward != null) {
                        forwarder.cancel(forward)
                    }
                }
                TunnelType.DYNAMIC_SOCKS5 -> {
                    active.socks5Server?.stop()
                }
            }

            _activeTunnels.update { it - tunnelId }
            _tunnelStates.update { it - tunnelId }
            Timber.i("Tunnel ${active.config.label} arrêté proprement")
            SshResult.Success(Unit)

        } catch (e: Exception) {
            // Un teardown qui echoue (transport deja mort cote REMOTE_FORWARD,
            // typiquement) laisserait sinon l'id marque comme arrete a la main
            // pour toujours, ce qui desactive definitivement la detection de
            // panne sur ce tunnel.
            manuallyStoppedIds.remove(tunnelId)
            Timber.e(e, "Erreur arrêt tunnel $tunnelId")
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
        }
    }

    fun stopAllTunnels() {
        // Marquer AVANT de fermer : la fermeture des sockets reveille les boucles
        // d'ecoute, qui appellent markUnexpectedStop. Sans ce marquage prealable
        // un arret volontaire serait pris pour une panne et declencherait une
        // reconnexion automatique.
        manuallyStoppedIds.addAll(_activeTunnels.value.keys)
        _activeTunnels.value.values.forEach { closeTunnelResources(it) }
        _activeTunnels.update { emptyMap() }
        _tunnelStates.update { emptyMap() }
        watchedSessionIds.clear()
        // manuallyStoppedIds n'est PAS vide ici : chaque demarrage retire son
        // propre id (markStarting et les trois start*), donc un redemarrage
        // reste possible sans rouvrir la fenetre de course ci-dessus.
    }

    // ── Reconnect helpers ─────────────────────────────────────────────────────────

    /**
     * Retourne les tunnels en erreur qui n'ont pas été arrêtés manuellement,
     * éligibles à une tentative de reconnexion automatique.
     */
    fun getTunnelsForReconnect(): List<TunnelConfig> {
        return _tunnelStates.value
            .filter { (id, state) ->
                state.status == TunnelStatus.ERROR && id !in manuallyStoppedIds
            }
            .map { it.value.config }
    }

    /**
     * Met à jour le statut d'un tunnel dans le StateFlow sans toucher aux tunnels actifs.
     * Utilisé par la logique de reconnexion pour indiquer RECONNECTING ou ERROR.
     */
    fun updateTunnelStatus(tunnelId: String, status: TunnelStatus, errorMessage: String? = null) {
        _tunnelStates.update { states ->
            val existing = states[tunnelId] ?: return@update states
            states + (tunnelId to existing.copy(status = status, errorMessage = errorMessage))
        }
    }

    // ── Points d'accès de test ────────────────────────────────────────────────────
    // Les tunnels reels naissent d'un SSHClient connecte et d'une ServerSocket
    // liee : impossible a monter en test unitaire JVM. Ces deux fonctions
    // permettent de verifier la machine a etats (panne, arret manuel,
    // eligibilite a la reconnexion) sans reseau ni SSHJ.

    /** Enregistre un tunnel actif sans ressources, comme apres un demarrage reussi. */
    @androidx.annotation.VisibleForTesting
    internal fun injectActiveForTest(config: TunnelConfig, sessionId: String): Long {
        val instanceId = instanceCounter.incrementAndGet()
        _activeTunnels.update {
            it + (config.id to ActiveTunnel(config = config, sessionId = sessionId, instanceId = instanceId))
        }
        _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }
        return instanceId
    }

    /**
     * Rejoue ce que fait une boucle d'ecoute qui se termine.
     *
     * @param instanceId null pour ignorer le controle d'instance, sinon
     *                   l'identifiant rendu par [injectActiveForTest].
     */
    @androidx.annotation.VisibleForTesting
    internal fun simulateUnexpectedStopForTest(tunnelId: String, message: String, instanceId: Long? = null) =
        markUnexpectedStop(tunnelId, instanceId, message)

    // ── Private ───────────────────────────────────────────────────────────────────

    private fun isPortInUse(port: Int): Boolean {
        return try {
            ServerSocket(port).use { false }
        } catch (e: IOException) {
            true
        }
    }
}
