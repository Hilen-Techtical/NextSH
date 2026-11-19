// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_host_not_found
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_listener_stopped
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_no_answer
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_session_closed
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_socks_stopped
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_start_failed
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
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.DisconnectListener
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.InetAddress
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

    /**
     * Did the last [open] for [host] fail on a network-level error (no route, network unreachable),
     * as opposed to a timeout, a refusal or an authentication problem ? Read right after an `Error`
     * by the manager when the caller asked for the network reason (the reconnect loop). `false` by
     * default: an implementation that does not tell keeps its own message.
     */
    fun lastFailureWasNetworkLevel(host: Host): Boolean = false
}

/**
 * Levée par le callback `onCreated` du manager quand un Stop est arrivé avant
 * que le client ne lui soit remis : la fabrique doit renoncer à `connect()`.
 * C'est une `IOException` pour que le `catch` de [VaultSshClientFactory.open]
 * la traite comme un échec de connexion (voir [tunnelOpenError]).
 */
private class StopRequestedException : IOException("stopped")

/**
 * Translated reasons a tunnel can be lost for, shown on the card while it reconnects. Read once at
 * the top of [DesktopTunnelManager.startTunnel], before anything is bound : the callbacks that report
 * a loss (SSHJ reader thread, watchdog, accept loops) cannot call the suspend `getString`, and a
 * failing read at that moment would leave a tunnel that is down but still shown ACTIVE.
 */
private class TunnelLossTexts(
    val listenerStopped: String,
    val socksStopped: String,
    val sessionClosed: String,
    val noAnswer: String,
)

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
     * Test seam, the real `os.name` in production. Decides whether a local port below 1024 is
     * reserved to administrators ([isReservedLocalPort]) : the tests must not depend on the system
     * they run on.
     */
    @Volatile
    internal var osName: String = System.getProperty("os.name", "")

    /**
     * Test seam, opens then closes a server socket in production. The check of the local port that
     * runs before the SSH connection opens ([localPortFailure]) : the tests answer for the system.
     */
    @Volatile
    internal var portProbe: (Int) -> Unit = { ServerSocket(it).close() }

    /**
     * Test seam, binds the loopback address in production. The listening socket of a SOCKS5 tunnel
     * ([DesktopSocks5Server]) : the tests hand out a socket whose `accept()` fails on demand.
     */
    @Volatile
    internal var socks5ServerSocket: (Int) -> ServerSocket = { ServerSocket(it, 50, InetAddress.getByName("127.0.0.1")) }

    /**
     * Test seam, a short TCP connection to the loopback address in production. Tells a port below
     * 1024 that a service really holds from one the system forbids ([isReservedLocalPort]) : the
     * tests must not depend on what runs on the machine.
     */
    @Volatile
    internal var portListens: (Int) -> Boolean = ::isLocalPortListening

    /**
     * Démarre un tunnel. Utilise [hostLookup] pour retrouver l'hôte associé :
     * le caller (`DesktopTunnelService`) injecte le `HostRepository`. Ouvre un
     * `SSHClient` dédié via [SshClientFactory] puis crée le forwarder SSHJ
     * correspondant au type.
     *
     * Transitions d'état émises :
     *  - STARTING → ACTIVE   sur succès
     *  - STARTING → ERROR(m) sur échec (auth, port occupé, réseau…) : `m` est un texte traduit qui
     *    dit la catégorie de l'échec (voir [TunnelSetupFailure] pour la mise en place du forwarder),
     *    jamais le message de l'exception SSHJ ou JDK
     *  - STARTING → ERROR(m) sur exception inattendue (recherche de l'hôte,
     *    test du port, fabrique de client…) : `m` est le texte traduit « le
     *    tunnel n'a pas pu démarrer », jamais le message de l'exception, et
     *    l'appelant reçoit un `Error` au lieu d'une exception (règle projet : les
     *    erreurs SSH sont des `SshResult`)
     *  - STARTING → ERROR(m) sur `Error` JVM (`NoClassDefFoundError`,
     *    `UnsatisfiedLinkError` d'un runtime jlink incomplet, `OutOfMemoryError`…) :
     *    même texte `m`, mais l'`Error` est ensuite relancée telle quelle, on ne
     *    l'avale pas ; seule la carte cesse d'afficher « Démarrage… »
     *  - RECONNECTING → STARTING : le motif de la reconnexion est gardé pendant la tentative
     *    (la réservation ne repart d'un état neuf que depuis un autre statut)
     *  - STARTING → STOPPED  sur annulation de la coroutine appelante, qui est
     *    ensuite relancée : sans cette écriture le tunnel resterait affiché
     *    « Démarrage… » jusqu'à ce que l'utilisateur clique dessus
     *  - arrêt manuel pendant le démarrage : aucune écriture ici, l'état
     *    ARRÊTÉ est celui de [stopTunnel], et le démarrage renvoie `Success`
     *    (un arrêt voulu n'est pas une erreur) après avoir libéré tout ce
     *    qu'il avait ouvert.
     *
     * Les écritures sur exception, sur `Error` et sur annulation partent de
     * STARTING et de lui seul (compare-and-set, voir [leaveStarting]) : un état
     * déjà ACTIVE, STOPPED ou RECONNECTING, écrit entre-temps par un autre
     * acteur, n'est jamais écrasé. Une exception inattendue qui trouve l'état
     * ainsi changé, ou un arrêt manuel posé, renvoie `Success` comme les autres
     * sorties d'arrêt : l'état en place dit déjà ce qu'il faut.
     *
     * [networkLostReason], donné par la boucle de reconnexion seulement : si l'ouverture du client
     * échoue sur une erreur de niveau réseau (voir [SshClientFactory.lastFailureWasNetworkLevel]),
     * l'ERROR écrit porte ce texte au lieu du texte de l'échec. Un démarrage manuel ne le donne pas
     * et garde son message. Un délai d'attente n'est jamais une perte de réseau.
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
        networkLostReason: String? = null,
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
        //
        // Localized, and without the host id: the message ends up on the tunnel card and in the error dialog,
        // where a UUID tells the user nothing. Resolved BEFORE the reservation and any state change: a resource
        // failure here throws with nothing touched, instead of leaving the tunnel stuck in STARTING (and its
        // id reserved, since the `try` that releases it only begins after the reservation).
        //
        // [startFailedMessage] is the one the `catch` below writes for an unexpected exception : it has to
        // exist before the failure it reports, and reading it there could fail the same way as the exception
        // it stands for. It is also the fallback of the forwarder setup (see [openAndStart]).
        val hostNotFoundMessage = getString(Res.string.tunnels_error_host_not_found)
        val startFailedMessage = getString(Res.string.tunnels_error_start_failed)
        // The reasons a tunnel can be lost for, read here for the same reason : the callbacks that report
        // a loss cannot read a resource, and a failing read then would leave a dead tunnel shown ACTIVE.
        val lossTexts = TunnelLossTexts(
            listenerStopped = getString(Res.string.tunnels_error_listener_stopped),
            socksStopped = getString(Res.string.tunnels_error_socks_stopped),
            sessionClosed = getString(Res.string.tunnels_error_session_closed),
            noAnswer = getString(Res.string.tunnels_error_no_answer),
        )

        val reserved = mutex.withLock {
            if (!startingIds.add(config.id)) {
                false
            } else if (active.containsKey(config.id)) {
                startingIds.remove(config.id)
                false
            } else {
                _tunnelStates.update { states ->
                    // Only a STARTING that follows RECONNECTING (an attempt of the reconnect loop)
                    // carries the reason of the loss : the card must not blink during the attempt.
                    // A start from STOPPED, ERROR or nothing starts without text.
                    val carried = states[config.id]
                        ?.takeIf { it.status == TunnelStatus.RECONNECTING }
                        ?.errorMessage
                    states + (config.id to TunnelState(config, TunnelStatus.STARTING, errorMessage = carried))
                }
                manuallyStopped.remove(config.id)
                true
            }
        }
        if (!reserved) return SshResult.Success(Unit)

        // Aucun point de suspension entre la réservation et ce `try` : la
        // réservation est libérée sur toute sortie, annulation comprise.
        try {
            return startReserved(config, hostLookup, hostNotFoundMessage, startFailedMessage, lossTexts, networkLostReason)
        } catch (e: CancellationException) {
            // Whatever cancelled us (the reconnect job a Stop cancels, a scope shutting down), the
            // STARTING written at the reservation would otherwise outlive the start for good : nothing
            // else writes a status for it. From STARTING only : a Stop may have written STOPPED already
            // or be about to (the service cancels the reconnect job BEFORE it calls [stopTunnel], so the
            // write can be a duplicate, which is harmless), a network drop RECONNECTING, and a tunnel
            // that came up ACTIVE must stay so.
            leaveStarting(config, TunnelStatus.STOPPED, errorMessage = null, unlessManuallyStopped = false)
            throw e
        } catch (e: Exception) {
            // Something nobody planned for (the host lookup, the port probe, the client factory, a
            // resource read...) : the start is over, and the caller gets an `Error` like for any other
            // failure instead of an exception. Class names only in the log, never the message (it can
            // quote a path or a host name). Same rule as [setError] for a Stop that landed meanwhile, and
            // same answer as [failStart] when nothing was written : the state in place already says what
            // there is to say (STOPPED by the Stop, ACTIVE or RECONNECTING by someone else).
            Logger.e(TAG, "Démarrage tunnel ${LogId.of(config.id)} : exception inattendue (${errorClass(e)})")
            return if (leaveStarting(config, TunnelStatus.ERROR, startFailedMessage, unlessManuallyStopped = true)) {
                SshResult.Error(SshErrorCode.UNKNOWN, startFailedMessage)
            } else {
                SshResult.Success(Unit)
            }
        } catch (t: Throwable) {
            // An Error (NoClassDefFoundError or UnsatisfiedLinkError from a jlink runtime that lacks a
            // module, an OutOfMemoryError...) is not ours to swallow : it goes up as it came. But the
            // card must not stay on "Démarrage…" either, so the state leaves STARTING first, under the
            // same rules as for an Exception. Best effort : a failure of the log or of the write
            // (an OOM tends to come in twos) must not replace the Throwable that is being rethrown.
            runCatching {
                Logger.e(TAG, "Démarrage tunnel ${LogId.of(config.id)} : erreur fatale (${errorClass(t)})")
                leaveStarting(config, TunnelStatus.ERROR, startFailedMessage, unlessManuallyStopped = true)
            }
            throw t
        } finally {
            startingIds.remove(config.id)
        }
    }

    /**
     * Leaves STARTING for [status] (STOPPED on a cancelled start, ERROR on an unexpected exception) and
     * says whether it wrote. The state of [config] must be STARTING at the instant of the write : test and
     * write are one compare-and-set (`update` re-runs the block whenever another writer changed the state
     * in between), so a status written meanwhile is never overwritten. With [unlessManuallyStopped], a Stop
     * that landed counts as well, and is tested the same way as in [setError] : [stopTunnel] sets the flag
     * BEFORE it writes STOPPED, so an ERROR can never end up over a STOPPED the user asked for.
     *
     * Not [setError] : that one does not look at the current status, which suits the start paths that
     * know what they are replacing, not an exception that can come from anywhere in the start.
     */
    private fun leaveStarting(
        config: TunnelConfig,
        status: TunnelStatus,
        errorMessage: String?,
        unlessManuallyStopped: Boolean,
    ): Boolean {
        var written = false
        _tunnelStates.update { states ->
            // `update` may re-run this block : the last run is the one that lands.
            written = states[config.id]?.status == TunnelStatus.STARTING &&
                !(unlessManuallyStopped && manuallyStopped.contains(config.id))
            if (!written) states
            else states + (config.id to TunnelState(config, status, errorMessage = errorMessage))
        }
        return written
    }

    /** Corps de [startTunnel], exécuté une fois l'id réservé dans [startingIds]. */
    private suspend fun startReserved(
        config: TunnelConfig,
        hostLookup: suspend (hostId: String) -> Host?,
        hostNotFoundMessage: String,
        startFailedMessage: String,
        lossTexts: TunnelLossTexts,
        networkLostReason: String?,
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
            return failStart(config, SshResult.Error(SshErrorCode.UNKNOWN, hostNotFoundMessage))
        }

        // LOCAL_FORWARD / DYNAMIC_SOCKS5 : check port local avant même d'ouvrir la
        // connexion SSH : une erreur PORT_IN_USE ne doit pas consommer d'auth.
        if (config.type == TunnelType.LOCAL_FORWARD || config.type == TunnelType.DYNAMIC_SOCKS5) {
            val portProblem = localPortFailure(config.localPort, osName, portProbe, portListens)
            if (portProblem != null) {
                // Port taken, or reserved to administrators by the system (Linux and macOS, below
                // 1024). The text is read only on this branch. A Stop landing during the read is
                // handled by [failStart] (its compare-and-set), a resource failure by the `catch`
                // of [startTunnel].
                return failStart(config, portProblem.toStartError(config, startFailedMessage))
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
            val result = openAndStart(config, host, startFailedMessage, lossTexts, networkLostReason)
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
     *
     * A forwarder setup failure is sorted by [classifyTunnelSetupFailure] and
     * the user reads the translated text of its category, never the message of
     * the exception (SSHJ and the JDK word it in English and may quote a host
     * or a request). [startFailedMessage] is the text of the category with
     * nothing more precise to say ([TunnelSetupFailure.GENERIC]).
     */
    private suspend fun openAndStart(
        config: TunnelConfig,
        host: Host,
        startFailedMessage: String,
        lossTexts: TunnelLossTexts,
        networkLostReason: String?,
    ): SshResult<Unit> {
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
            is SshResult.Error -> {
                // The factory logs at debug level only (a Stop makes it fail on purpose, and what it
                // sees is the exception of the closed socket) : this is where a failure that nobody
                // asked for is told, with the code of the error and never its text.
                if (!manuallyStopped.contains(config.id)) {
                    Logger.w(TAG, "Tunnel ${LogId.of(config.id)} : ouverture du client refusée (${openResult.code})")
                }
                // Asked for by the reconnect loop only: a network-level failure (not a timeout) of
                // the open says the network is down, not "connection impossible".
                val reported = if (networkLostReason != null && clientFactory.lastFailureWasNetworkLevel(host)) {
                    SshResult.Error(openResult.code, networkLostReason)
                } else {
                    openResult
                }
                failStart(config, reported)
            }
            is SshResult.Success -> {
                val client = openResult.data
                try {
                    when (config.type) {
                        TunnelType.LOCAL_FORWARD -> startLocalForwardInternal(config, client, lossTexts)
                        TunnelType.REMOTE_FORWARD -> startRemoteForwardInternal(config, client, lossTexts)
                        TunnelType.DYNAMIC_SOCKS5 -> startSocks5Internal(config, client, lossTexts)
                    }
                } catch (e: CancellationException) {
                    // The setup functions are `suspend` : a cancellation surfaces here and must not fall
                    // into the `catch (Exception)` below, which would write an ERROR. The client is closed
                    // by the `finally` of [startReserved], which disconnects every start that did not
                    // return ; [startTunnel] writes the state.
                    throw e
                } catch (e: Exception) {
                    // Sorted BEFORE the disconnect : the refusal of a remote forward is only
                    // recognised on a client that is still connected.
                    val failure = classifyTunnelSetupFailure(
                        e,
                        config.type,
                        localPortReserved = { isReservedLocalPort(config.localPort, osName, portListens) },
                    ) {
                        try { client.isConnected } catch (_: Exception) { false }
                    }
                    // A Stop during a remote bind makes it fail on purpose (it
                    // disconnects the client) : not worth an error and a stack trace.
                    if (manuallyStopped.contains(config.id)) {
                        Logger.d(TAG, "Tunnel ${LogId.of(config.id)} : mise en place interrompue par un arrêt")
                    } else {
                        // The category (an enum: its name) and the class chain of the exception, never its message.
                        Logger.e(TAG, "Échec démarrage tunnel ${LogId.of(config.id)} ($failure)", e)
                    }
                    try { client.disconnect() } catch (_: Exception) {}
                    // A Stop that landed while the forwarder was being set up (it
                    // may even be what made the setup fail : it disconnects the
                    // client) turns this failure into the quiet Success of the
                    // other stop paths, see [failStart].
                    //
                    // The text is a string resource, read here without any `runCatching` or
                    // `catch (Exception)` around it : a cancellation must go up to [startTunnel],
                    // which writes STOPPED. The client is already closed above.
                    failStart(config, failure.toStartError(config, startFailedMessage))
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
            Logger.d(TAG, "Tunnel ${LogId.of(tunnelId)} : démarrage interrompu (disconnect forcé)")
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
            try {
                tunnel.job.cancel()
            } finally {
                // The entry is already out of `active` : if nothing closed it here, nobody would
                // ever, and the next Stop would answer Success over a client that is still connected.
                closeTunnelResources(tunnel)
            }
            _tunnelStates.update { it + (tunnelId to TunnelState(tunnel.config, TunnelStatus.STOPPED)) }
            // On retire au bout de quelques ms pour que l'UI observe STOPPED si besoin.
            // En pratique retirer tout de suite marche aussi ; on laisse l'entry STOPPED
            // pour permettre au service de gérer les RECONNECTING.
            Logger.d(TAG, "Tunnel ${LogId.of(tunnelId)} arrêté")
            SshResult.Success(Unit)
        } catch (e: Exception) {
            // The class chain of the exception, never its message (a path, a host...).
            Logger.e(TAG, "Erreur arrêt tunnel ${LogId.of(tunnelId)}", e)
            // Whatever failed, the entry is out of `active` and its resources were closed above
            // ([closeTunnelResources] never throws) : the tunnel IS stopped, which is what the user
            // asked for. So the answer is Success and the card says STOPPED. An Error would invite a
            // retry that makes no sense (a click on a STOPPED card STARTS the tunnel), and the service
            // does not read an Error as a stop (see `DesktopTunnelService.stopDeletedFromReconnect`).
            _tunnelStates.update { it + (tunnelId to TunnelState(tunnel.config, TunnelStatus.STOPPED)) }
            SshResult.Success(Unit)
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
            Logger.w(TAG, "releaseForReconnect(${LogId.of(tunnelId)}): erreur fermeture (${errorClass(e)})")
        }
        // Drop the latency entry: the SSHClient is gone, the next reconnect
        // will produce fresh measurements. Without this, callers that bypass
        // onTunnelLost (e.g. DesktopTunnelService.stopAllTunnels short-circuit)
        // would leak a stale Long? per tunnel into the map.
        _latenciesByTunnel.update { it - tunnelId }
    }

    /**
     * Arrête tous les tunnels en cours. Utilisé au `onAppExit`.
     *
     * [clearManualStops] false (vault wipe): the manual stop flags stay. A start still in flight (STARTING,
     * handshake) checks its flag when it comes back and bails; clearing the flags here would let it go on
     * and turn ACTIVE after the wipe. A later real start of the same id removes its flag itself.
     */
    suspend fun stopAll(clearManualStops: Boolean = true) {
        val ids = active.keys.toList()
        ids.forEach { stopTunnel(it) }
        _tunnelStates.update { emptyMap() }
        _latenciesByTunnel.update { emptyMap() }
        if (clearManualStops) manuallyStopped.clear()
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
     *
     * Avec [keepErrorMessage], l'état écrit garde le texte de l'état courant et
     * [errorMessage] est ignoré : la boucle de reconnexion y repasse à chaque tour
     * sans effacer le motif affiché sur la carte.
     */
    fun updateStatusIf(
        tunnelId: String,
        status: TunnelStatus,
        errorMessage: String? = null,
        keepErrorMessage: Boolean = false,
        condition: (current: TunnelStatus) -> Boolean,
    ): TunnelStatus? {
        var seen: TunnelStatus? = null
        _tunnelStates.update { states ->
            val existing = states[tunnelId]
            seen = existing?.status
            if (existing == null || !condition(existing.status)) states
            else states + (
                tunnelId to existing.copy(
                    status = status,
                    errorMessage = if (keepErrorMessage) existing.errorMessage else errorMessage,
                )
            )
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
        lossTexts: TunnelLossTexts,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        // The text of a dead listen loop comes from [lossTexts], read before anything is bound : at
        // the moment the loop dies, a failing read would leave an ACTIVE tunnel that no longer listens.
        val serverSocket = ServerSocket()
        try {
            bindAndRegisterLocalForward(config, client, serverSocket, lossTexts)
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
        lossTexts: TunnelLossTexts,
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
            val listenJob = coroutineContext.job
            // Touched only by the listen thread, which calls onClientFailed one failure at a time.
            var failures = 0
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
                // other one. The accept loop runs INSIDE it : it reads that
                // interrupt, and the state of the job as a second signal.
                // A client connection whose channel the server refuses, or does not
                // answer in time while the SSH connection lives, does not end the loop
                // (see `runLocalForwardListener`) : the log carries the class of the
                // exception, which tells the two apart, never its message (host, port
                // and server text).
                val failure = runInterruptible {
                    runLocalForwardListener(
                        listen = forwarder::listen,
                        serverSocket = serverSocket,
                        clientConnected = { try { client.isConnected } catch (_: Exception) { false } },
                        stopRequested = { !listenJob.isActive },
                        onClientFailed = { failed ->
                            failures++
                            if (failures == 1) {
                                Logger.w(
                                    TAG,
                                    "Tunnel ${LogId.of(config.id)} : ouverture du canal d'une connexion cliente " +
                                        "en échec, écoute maintenue (${errorClass(failed)})",
                                )
                            } else {
                                // A client that retries can fail by the hundred : only the first
                                // one is a warning, the next ones are debug lines with the count.
                                Logger.d(
                                    TAG,
                                    "Tunnel ${LogId.of(config.id)} : ouverture du canal d'une connexion cliente " +
                                        "en échec ($failures depuis le démarrage, ${errorClass(failed)})",
                                )
                            }
                        },
                    )
                }
                if (failure == null) {
                    // Reached only if the loop ended without a failure while this job
                    // was still active (`listen()` returned, or the socket was closed).
                    // On stop / release / lost the job is cancelled, so
                    // `runInterruptible` throws a CancellationException instead
                    // (nothing to report, no reconnect).
                    Logger.d(TAG, "Tunnel ${LogId.of(config.id)} listen loop exited cleanly")
                } else if (!manuallyStopped.contains(config.id) && active[config.id]?.client === client) {
                    // A deliberate stop / release can surface here too (a channel
                    // open torn down by the disconnect) : only an unexpected error
                    // is a lost tunnel, and only that is worth a warning (logged by
                    // `onTunnelLost`, from `logReason`). Matched on this loop's own
                    // client : a replaced entry (see [registerActive]) dies the same
                    // way, and must not take down its successor.
                    // Unexpected IO error (e.g. the SSH channel carrying this forward
                    // was torn down). Mark lost so the service reconnects: the
                    // DisconnectListener will fire too; `onTunnelLost` is idempotent.
                    // The state carries a fixed translated text, never the message of
                    // the exception (SSHJ English text that can name a host or a port).
                    onTunnelLost(
                        config,
                        reason = lossTexts.listenerStopped,
                        logReason = "listen loop died (${errorClass(failure)})",
                        source = client,
                    )
                }
            } finally {
                // Never hand a pool worker back with the interrupt flag set : a
                // later `listen()` on it would exit at once.
                Thread.interrupted()
            }
        }

        var livenessJob: Job? = null
        val up = try {
            attachDisconnectListener(client, config, lossTexts)
            livenessJob = startLivenessWatchdog(client, config, lossTexts)
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
            Logger.d(TAG, "Tunnel LOCAL démarré : ${LogId.of(config.id)}")
        }
        return SshResult.Success(Unit)
    }

    private suspend fun startRemoteForwardInternal(
        config: TunnelConfig,
        client: SSHClient,
        lossTexts: TunnelLossTexts,
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

        attachDisconnectListener(client, config, lossTexts)
        val livenessJob = startLivenessWatchdog(client, config, lossTexts)
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
            Logger.d(TAG, "Tunnel REMOTE démarré : ${LogId.of(config.id)}")
        }
        SshResult.Success(Unit)
    }

    /**
     * Install an SSHJ [DisconnectListener] on the transport as a secondary
     * trigger: fires when SSHJ's own reader thread detects the transport is
     * dead (e.g. server sent DISCONNECT, or the socket read timeout fires).
     * The primary failure detection is the liveness watchdog started by
     * [startLivenessWatchdog]: SSHJ's built-in keep-alive is not used on Desktop,
     * and a half-open link only shows up when a probe goes unanswered.
     */
    private fun attachDisconnectListener(client: SSHClient, config: TunnelConfig, lossTexts: TunnelLossTexts) {
        try {
            // `msg` is nullable : SSHJ passes `SSHException.getMessage()`, which can be null.
            client.transport.disconnectListener = DisconnectListener { reason: DisconnectReason, _: String? ->
                if (!manuallyStopped.contains(config.id)) {
                    // The text sent by the server (or the English message of an SSHJ or JDK exception)
                    // reaches neither the tunnel state nor the log : the state carries a fixed
                    // translated text, the log the protocol reason code only.
                    onTunnelLost(
                        config,
                        reason = lossTexts.sessionClosed,
                        logReason = "SSH session closed (${reason.name})",
                        source = client,
                    )
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Impossible d'installer le DisconnectListener sur ${LogId.of(config.id)} (${errorClass(e)})")
        }
    }

    /**
     * Explicit application-level liveness watchdog.
     *
     * Rationale: SSHJ's built-in keep-alive is not used on Desktop (its
     * interval is never set). A TCP half-open socket (Wi-Fi dropped) accepts
     * writes into the kernel buffer without error, so SSHJ can remain oblivious
     * to an outage for hours until the OS-level TCP keep-alive decides to tear
     * the socket down (Linux default: ~2 hours).
     *
     * This watchdog periodically sends a `keepalive@openssh.com` global
     * request with `wantReply = true` AND explicitly awaits the reply with a
     * short timeout. Missing reply → we trip [onTunnelLost] ourselves, which
     * transitions the tunnel to RECONNECTING and lets the service schedule a
     * reconnect. Fixed 15 s cadence, reply expected within 10 s.
     *
     * Runs on [scope] so it dies cleanly with the manager's lifecycle; the
     * job reference is stored in [ActiveTunnel.livenessJob] so `stopTunnel`
     * and `onTunnelLost` can cancel it.
     */
    private fun startLivenessWatchdog(client: SSHClient, config: TunnelConfig, lossTexts: TunnelLossTexts): Job {
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
                            Logger.w(TAG, "Tunnel ${LogId.of(config.id)} : keepalive timeout (ConnectionException après ${elapsedNanos / 1_000_000L}ms)")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Tunnel ${LogId.of(config.id)} : keepalive probe échoué (${errorClass(e)})")
                    null
                }

                if (rtt != null) {
                    _latenciesByTunnel.update { it + (config.id to rtt) }
                } else {
                    onTunnelLost(
                        config,
                        reason = lossTexts.noAnswer,
                        logReason = "keepalive without reply",
                        source = client,
                    )
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
     * [reason] is shown to the user through the tunnel state: a fixed translated text, never text
     * from the server, SSHJ or the JDK. [logReason] is its log counterpart: fixed text, a protocol
     * code or an exception class chain, never a message.
     *
     * [source] is the client that reported the loss. Disconnecting a client
     * fires its `DisconnectListener` (SSHJ `TransportImpl.disconnect`), so a
     * client torn down by [registerActive] or [releaseForReconnect] reports
     * late, possibly after a new entry took the same id : such a callback is
     * ignored instead of dropping the tunnel now registered.
     */
    private fun onTunnelLost(config: TunnelConfig, reason: String, logReason: String, source: SSHClient) {
        val tunnel = active[config.id]
        if (tunnel == null || tunnel.client !== source) return
        if (!active.remove(config.id, tunnel)) return
        Logger.w(TAG, "Tunnel ${LogId.of(config.id)} perdu : $logReason : état RECONNECTING")
        _latenciesByTunnel.update { it - config.id }

        runCatching { tunnel.livenessJob?.cancel() }
        // Cancel first, as in stopTunnel : it interrupts the SSHJ listen loop.
        // No `forwarder.close()` : the cancellation already does its job, and
        // SSHJ's close() would interrupt the calling thread, which is the listen
        // worker itself when we come from its `catch (IOException)`.
        runCatching { tunnel.job.cancel() }
        runCatching { tunnel.serverSocket?.close() }
        runCatching { tunnel.socks5Server?.stop() }

        // RECONNECTING is written AFTER the listeners are closed and BEFORE the disconnect, which can
        // block on the SSHJ write lock for as long as a dead route takes: a Stop (or a Stop then a
        // Start) landing during it must not be overwritten by a stale RECONNECTING written afterwards.
        // Not written at all once a Stop was requested (same rule as [setError]): the Stop owns the
        // state. Test and write are one compare-and-set.
        _tunnelStates.update { states ->
            if (manuallyStopped.contains(config.id)) states
            else states + (config.id to TunnelState(config, TunnelStatus.RECONNECTING, reason))
        }
        runCatching { tunnel.client.disconnect() }
    }

    private suspend fun startSocks5Internal(
        config: TunnelConfig,
        client: SSHClient,
        lossTexts: TunnelLossTexts,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val server = DesktopSocks5Server(client, config.localPort, socks5ServerSocket)
        // Binds the port and accepts nothing yet.
        server.start()

        // The server runs its own threads : this job carries nothing. The end of its accept loop is
        // reported by `startAccepting` below.
        val job = scope.launch(Dispatchers.IO) { }

        var livenessJob: Job? = null
        val up = try {
            attachDisconnectListener(client, config, lossTexts)
            livenessJob = startLivenessWatchdog(client, config, lossTexts)
            registerAndConfirm(
                ActiveTunnel(
                    config = config,
                    client = client,
                    job = job,
                    socks5Server = server,
                    livenessJob = livenessJob,
                ),
            )
        } catch (t: Throwable) {
            // `startTunnel` only disconnects the client when this throws : the bound port would stay
            // held until the app restarts. Skipped once the entry is registered (it then owns the server).
            if (active[config.id]?.socks5Server !== server) {
                runCatching { server.stop() }
            }
            job.cancel()
            livenessJob?.cancel()
            throw t
        }
        // ACTIVE was not written (a Stop got in first, or the entry was taken
        // out meanwhile) : whoever took the entry out stopped the server, and no
        // accept loop was ever started.
        if (up) {
            // The loop starts AFTER the registration, so that a failure can never come before
            // [onTunnelLost] is able to find the entry. A client that connects between the bind and
            // this call waits a few milliseconds in the queue of the socket.
            server.startAccepting { failure ->
                // No unexpected end is survived (see [DesktopSocks5Server]) : the tunnel is lost, the
                // service reconnects it and the reconnection binds the port again. A stop or a release
                // takes the entry out BEFORE it closes the server, so it never gets here.
                onTunnelLost(
                    config,
                    reason = lossTexts.socksStopped,
                    logReason = "SOCKS5 accept loop died (${errorClass(failure)})",
                    source = client,
                )
            }
            Logger.d(TAG, "Tunnel SOCKS5 démarré : ${LogId.of(config.id)}")
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
                Logger.d(TAG, "Tunnel ${LogId.of(id)} : arrêt reçu pendant le démarrage, ressources fermées")
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
            "Tunnel ${LogId.of(tunnel.config.id)} : entrée active déjà présente, " +
                "l'ancienne est fermée avant d'être remplacée",
        )
        // Cancel first, for the same reason as in stopTunnel.
        previous.job.cancel()
        closeTunnelResources(previous)
    }

    /**
     * Closes what a tunnel holds : the watchdog, the forwarder (socket, remote forward, SOCKS5 server),
     * then the SSH client, which is closed whatever happens before. Never throws an `Exception` : a
     * caller that removed the entry from [active] relies on this to know the tunnel is down.
     */
    private fun closeTunnelResources(tunnel: ActiveTunnel) {
        try {
            tunnel.livenessJob?.cancel()
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
            Logger.w(TAG, "Erreur fermeture forwarder ${LogId.of(tunnel.config.id)} (${errorClass(e)})")
        } finally {
            try { tunnel.client.disconnect() } catch (_: Exception) {}
        }
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

    /** Ids of the hosts whose last [open] failed on a network-level error (see [isNetworkLevelFailure]). */
    private val networkFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun lastFailureWasNetworkLevel(host: Host): Boolean = host.id in networkFailures

    override suspend fun open(host: Host, onCreated: (SSHClient) -> Unit): SshResult<SSHClient> = withContext(Dispatchers.IO) {
        networkFailures.remove(host.id)
        // No SSHJ keep-alive here: its interval is never set, so its thread never
        // starts. Liveness is ensured by [startLivenessWatchdog], which sends its
        // own `keepalive@openssh.com` probe (wantReply=true) every 15 s, measures
        // the RTT and trips [onTunnelLost] on timeout.
        //
        // WARNING: SSHJ 0.38 `DefaultConfig()` picks HEARTBEAT. If an interval is
        // ever set before `connect`, choose KEEP_ALIVE explicitly: HEARTBEAT would
        // send an SSH_MSG_IGNORE during the key exchange, which strict KEX refuses.
        val config = DefaultConfig()
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
            // `catch` below then closes the client and returns an Error, and
            // no socket is ever opened.
            onCreated(client)
            client.connect(host.hostname, host.port)
            // What each type needs from the vault is read first, then [tunnelCredentialProblem] says
            // whether it is enough : one place decides, the branches below only authenticate.
            val password = if (host.authType == AuthType.PASSWORD) vaultManager.getPassword(host.credentialId) else null
            val pem = if (host.authType == AuthType.SSH_KEY || host.authType == AuthType.CERTIFICATE) {
                vaultManager.getPrivateKey(host.credentialId)
            } else {
                null
            }
            val cert = if (host.authType == AuthType.CERTIFICATE) vaultManager.getCertificate(host.credentialId) else null
            val problem = tunnelCredentialProblem(
                authType = host.authType,
                hasPassword = password != null,
                hasPrivateKey = pem != null,
                hasCertificate = cert != null,
            )
            if (problem != null) {
                return@withContext failAndClose(client, problem.toAuthError(host.label))
            }
            when (host.authType) {
                AuthType.PASSWORD -> {
                    val secret = checkNotNull(password) { "tunnelCredentialProblem lets a missing password through" }
                    try { client.authPassword(host.username, secret) }
                    finally { Arrays.fill(secret, '\u0000') }
                }
                AuthType.SSH_KEY -> {
                    val key = checkNotNull(pem) { "tunnelCredentialProblem lets a missing key through" }
                    val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                    try {
                        // Loading is its own step : a key that cannot be read is an identity problem
                        // ([TunnelKeyUnreadableException]), not an error of the authentication below.
                        val provider = loadTunnelKeyProvider(key, certificate = null, passphrase = passphrase)
                        client.authPublickey(host.username, provider)
                    } finally { passphrase?.let { Arrays.fill(it, '\u0000') } }
                }
                AuthType.CERTIFICATE -> {
                    val key = checkNotNull(pem) { "tunnelCredentialProblem lets a missing key through" }
                    val certificate = checkNotNull(cert) { "tunnelCredentialProblem lets a missing certificate through" }
                    val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                    try {
                        // A truncated certificate raises a BufferException (an IOException) here.
                        val provider = loadTunnelKeyProvider(key, certificate = certificate, passphrase = passphrase)
                        client.authPublickey(host.username, provider)
                    } finally { passphrase?.let { Arrays.fill(it, '\u0000') } }
                }
                // Refused above by [tunnelCredentialProblem]. Closed all the same : a client that
                // authenticated nothing must never be handed back as a success (the `catch` below
                // turns this into an Error).
                AuthType.FIDO2, AuthType.BIOMETRIC_KEY ->
                    error("tunnelCredentialProblem lets an authentication without tunnel support through")
            }
            SshResult.Success(client)
        } catch (e: CancellationException) {
            // `getString` and the other suspend calls of this block can be cancelled : the client is
            // closed, nobody else holds it, and the cancellation goes up to [DesktopTunnelManager.startTunnel],
            // which writes STOPPED. Without this branch the `catch (Exception)` below would turn it into
            // an Error.
            try { client.disconnect() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            // One clause, and [tunnelOpenError] decides : the order of several `catch` could not be
            // trusted to keep a UserAuthException (an IOException) away from the connection text.
            //
            // Debug level : a Stop makes the open fail on purpose (the socket is closed under it), and
            // what the factory sees then is the exception of that socket. [DesktopTunnelManager] tells
            // a failure nobody asked for, with the code of the error, at warning level.
            // A Stop that landed before `connect()` (StopRequestedException) is not worth a line.
            if (e !is StopRequestedException) {
                Logger.d(TAG, "Ouverture du client de tunnel : échec (${errorClass(e)})")
            }
            if (e.isNetworkLevelFailure()) networkFailures.add(host.id)
            try { client.disconnect() } catch (_: Exception) {}
            tunnelOpenError(e, connectTimeoutMsProvider())
        }
    }

    private fun failAndClose(client: SSHClient, error: SshResult.Error): SshResult<SSHClient> {
        try { client.disconnect() } catch (_: Exception) {}
        return error
    }
}

