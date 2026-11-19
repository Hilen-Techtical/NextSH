// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelPortRules
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import timber.log.Timber
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Représente un tunnel actif en mémoire.
 *
 * Selon le type de tunnel, différents champs sont renseignés :
 *  - LOCAL_FORWARD  : [forwarder] + [serverSocket] + [listenerThread]
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
    /**
     * Quand le tunnel est devenu actif, sur l'horloge du gestionnaire ([SshTunnelManager]). Sert à dire
     * depuis combien de temps il tenait quand il s'est coupé ([UnexpectedStop.uptimeMs]).
     */
    val startedAtMs     : Long                            = 0L,
    // LOCAL_FORWARD
    val forwarder       : LocalPortForwarder?             = null,
    val serverSocket    : ServerSocket?                   = null,
    /** Thread qui exécute `forwarder.listen()`, à interrompre avant de fermer [serverSocket]. */
    val listenerThread  : Thread?                         = null,
    // REMOTE_FORWARD
    val remoteForwarder : RemotePortForwarder?            = null,
    val remoteForward   : RemotePortForwarder.Forward?    = null,
    // DYNAMIC_SOCKS5
    val socks5Server    : Socks5Server?                   = null,
)

/**
 * La boucle d'acceptation d'un tunnel local, de son démarrage à sa fin. Rend le message de la panne
 * qui l'a arrêtée, null si elle s'est arrêtée sans panne (le tunnel a été fermé).
 *
 * Pourquoi une boucle. `LocalPortForwarder.listen()` (SSHJ 0.38.0) ne rattrape que la
 * `SocketException` de la socket qu'on ferme : l'ouverture du canal d'UNE connexion cliente que le
 * serveur refuse (`OpenFailException` : cible qui refuse la connexion, `AllowTcpForwarding no`,
 * `PermitOpen`) en sort par une exception, et mettait fin à tout le tunnel. Un client qui
 * réessaie le tuait alors à chaque essai, et la reconnexion automatique le relevait à chaque fois.
 * Le refus d'une connexion cliente est ici consigné (la classe seulement, jamais l'hôte ni le
 * port) et l'écoute reprend, tant que la socket d'écoute est ouverte et que la connexion SSH vit.
 *
 * Un thread interrompu a été arrêté volontairement (voir `SshTunnelManager.stopLocalListener`, qui
 * l'interrompt AVANT de fermer la socket : sans cela `listen()` ne sort jamais) : la boucle rend
 * null, sans rappeler `listen()` quelle que soit l'exception qui l'a fait sortir.
 *
 * Un délai écoulé à l'ouverture du canal d'une connexion cliente (`ConnectionException` dont la cause
 * est une `TimeoutException`, levée par SSHJ au bout de `Transport.getTimeoutMs()`, 30 s par défaut)
 * peut venir d'une cible lente, pas d'un transport mort : sshd ne répond à un `direct-tcpip`
 * qu'après sa propre connexion vers la cible, et derrière un pare-feu qui jette les paquets il
 * attend le délai SYN du noyau (environ 127 s sous Linux) alors que SSHJ abandonne à 30 s. En faire
 * une panne coupait le tunnel entier, avec toutes ses autres connexions, à chaque essai du client,
 * puis la reconnexion recommençait. Mais il peut aussi venir d'une liaison muette (NAT ou pare-feu qui
 * jette tout) : `SSHClient.isConnected` reste alors vrai jusqu'à l'abandon TCP du noyau (environ
 * 15 min). Le keepalive de SSHJ ne suffit pas : il ne tue la session qu'après plusieurs
 * intervalles sans réponse, et ne dit rien d'une connexion cliente muette ; la
 * surveillance de vivacité par session est celle de `TunnelLivenessWatchdog`, qui ne passe qu'après
 * un long silence. Le délai n'est donc traité comme un refus qu'après avoir
 * prouvé que le transport répond, avec la sonde [transportAnswers] (voir [probeTransport] :
 * `keepalive@openssh.com` avec réponse demandée, 10 s d'attente) : une réponse, succès ou échec de
 * requête (OpenSSH répond ainsi aux requêtes qu'il ne connaît pas, ce qui prouve aussi la vie du
 * transport), le consigne, maintient l'écoute et reprend, tant que la socket d'écoute est ouverte et
 * que la connexion SSH vit. Sans réponse (délai de la sonde, transport fermé, interruption), le
 * délai d'ouverture reste fatal : la mort du tunnel, donc sa reconnexion, répare la liaison. Une
 * sonde restée sans réponse rend [TUNNEL_NO_ANSWER_MESSAGE] (le serveur ne répond plus) au lieu de
 * [TUNNEL_LISTENER_STOPPED_MESSAGE], et l'appelant déclare alors la session entière morte. Un
 * refus du serveur (`OpenFailException`) prouve déjà que le serveur répond : aucune sonde.
 *
 * Limite connue : si sshd confirme le canal après les 30 s de SSHJ (cible lente mais joignable), SSHJ
 * a déjà abandonné et fermé la socket cliente, et le canal reste ouvert côté serveur, orphelin,
 * jusqu'à ce que la cible le ferme ou que la session se termine. `LocalPortForwarder.startChannel`
 * est privé dans SSHJ : pas de correctif simple. Un serveur qui ne répond pas du tout aux requêtes
 * globales qu'il ne connaît pas (contraire au RFC 4254) garde le comportement d'avant : le délai
 * d'ouverture reste fatal.
 *
 * Restent fatales, comme avant : toute autre `IOException` (transport perdu, socket d'écoute
 * cassée, `SocketTimeoutException` comprise), une `ConnectionException` qui n'est ni un refus ni un
 * délai d'ouverture (transport fermé en cours d'ouverture, par exemple), et un refus ou un délai quand
 * la connexion SSH n'est plus établie.
 *
 * @param listen `LocalPortForwarder.listen`, qui bloque jusqu'à la fermeture ou à une exception.
 * @param clientConnected la connexion SSH est encore établie (`SSHClient.isConnected`).
 * @param transportAnswers sonde de vie du transport, appelée sur ce thread et uniquement après un
 *   délai d'ouverture (jamais après un refus) : vrai si le serveur a répondu. Elle bloque au plus le
 *   délai de la sonde ; une interruption pendant son attente doit laisser le thread interrompu.
 */
internal fun runLocalForwardListener(
    listen: () -> Unit,
    serverSocket: ServerSocket,
    clientConnected: () -> Boolean,
    transportAnswers: () -> Boolean,
): String? {
    while (true) {
        try {
            listen()
            return null
        } catch (e: IOException) {
            // Interrupted: the tunnel is being stopped (see SshTunnelManager.stopLocalListener), which
            // is the only way out of `listen()` that closing the socket does not give. Whatever it
            // ended with, a refusal racing the interrupt included, `listen()` is not entered again.
            // The stopper owns the state.
            if (Thread.currentThread().isInterrupted) return null
            val refused = isOpenRefusal(e)
            if (refused || isOpenTimeout(e)) {
                if (serverSocket.isClosed) return null
                if (clientConnected()) {
                    if (refused) {
                        // The server answered (it refused): the transport is alive, no probe needed.
                        Timber.w("Connexion locale refusee par le serveur (${e::class.java.simpleName})")
                        continue
                    }
                    // A timeout proves nothing about the transport: it is a slow target, or a silent
                    // link. Only an answer to the probe tells the two apart.
                    val answered = transportAnswers()
                    // The probe waits: a stop (interrupt, then close) can land while it does.
                    if (Thread.currentThread().isInterrupted || serverSocket.isClosed) return null
                    if (answered) {
                        Timber.w("Connexion locale sans reponse du serveur a l ouverture du canal (${e::class.java.simpleName})")
                        continue
                    }
                    Timber.w("Transport sans reponse a la sonde apres un delai d ouverture de canal")
                    // The link is silent: the caller also declares the whole session dead (see
                    // startListenerThread), a tunnel stopping alone would be restarted on the same session.
                    return TUNNEL_NO_ANSWER_MESSAGE
                }
            }
            // Le message de l'exception n'est ni journalisé ni montré : il vient de SSHJ, en anglais,
            // et peut citer un hôte ou un port.
            Timber.w("Tunnel local arrete (${e::class.java.simpleName})")
            return TUNNEL_LISTENER_STOPPED_MESSAGE
        }
    }
}

/** [e], ou une de ses causes, est le refus par le serveur d'ouvrir un canal ([OpenFailException]). */
private fun isOpenRefusal(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is OpenFailException) return true
        cause = cause.cause
    }
    return false
}

/**
 * [e], ou une de ses causes, est un délai écoulé à l'ouverture d'un canal : une [ConnectionException]
 * dont la cause directe est une [TimeoutException] (`Promise.retrieve` de SSHJ 0.38 chaîne ainsi le
 * délai de `Event.await`). Une `ConnectionException` d'une autre origine (transport fermé, par
 * exemple) n'en est pas un, et une [TimeoutException] sans elle non plus.
 */
private fun isOpenTimeout(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is ConnectionException && cause.cause is TimeoutException) return true
        cause = cause.cause
    }
    return false
}

/** Name of the global request of the transport probe, the one OpenSSH itself sends for its keepalives. */
private const val TRANSPORT_PROBE_REQUEST = "keepalive@openssh.com"

/** How long [probeTransport] waits for the answer of the server, in seconds. */
internal const val TRANSPORT_PROBE_TIMEOUT_SECONDS = 10L

/**
 * Sonde de vie du transport de [sshClient] : envoie `keepalive@openssh.com` avec réponse demandée et
 * attend [TRANSPORT_PROBE_TIMEOUT_SECONDS] secondes. Rend vrai si le serveur a répondu, voir
 * [transportAnswered]. Bloque le thread appelant ; une interruption l'arrête et laisse le drapeau du
 * thread positionné (SSHJ le rétablit, `Promise.tryRetrieve`).
 */
internal fun probeTransport(sshClient: SSHClient): Boolean = transportAnswered {
    sshClient.connection
        .sendGlobalRequest(TRANSPORT_PROBE_REQUEST, true, ByteArray(0))
        .retrieve(TRANSPORT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}

/**
 * [probeTransport], où tout octet reçu du serveur pendant la sonde compte aussi comme une réponse :
 * sur un lien lent et chargé (transfert en cours), la réponse à la sonde attend derrière les données
 * en vol, mais ces données prouvent que la liaison vit. Sans socket instrumentée
 * ([InboundActivitySocket]), seule la réponse compte. Bloque comme [probeTransport].
 */
internal fun probeTransportOrTraffic(sshClient: SSHClient): Boolean {
    val socket = sshClient.socket as? InboundActivitySocket
    val before = socket?.lastInboundAt
    val answered = probeTransport(sshClient)
    if (answered) return true
    return socket != null && before != null && socket.lastInboundAt > before
}

/**
 * Le serveur a répondu à la requête que [sendProbe] envoie et attend (elle rend la main à la réponse
 * ou lève).
 *
 * Répondent : un succès, et l'échec de requête (`SSH_MSG_REQUEST_FAILURE`) que OpenSSH rend pour une
 * requête globale qu'il ne connaît pas, que SSHJ lève en `ConnectionException` SANS cause. Une
 * `ConnectionException` AVEC cause ne répond pas : le délai de la sonde (`TimeoutException`), ou le
 * transport tombé pendant l'attente (`TransportException` chaînée par SSHJ), ou l'interruption. Pas
 * plus une autre exception (envoi impossible, par exemple).
 */
internal fun transportAnswered(sendProbe: () -> Unit): Boolean = try {
    sendProbe()
    true
} catch (e: ConnectionException) {
    e.cause == null
} catch (e: InterruptedException) {
    // Whoever interrupted the thread (a stop) must still see the flag: the listener loop reads it.
    Thread.currentThread().interrupt()
    false
} catch (e: Exception) {
    false
}

/**
 * Un tunnel qui vient de s'arrêter de lui-même, voir [SshTunnelManager.unexpectedStops].
 *
 * @property instanceId l'instance du tunnel qui s'est coupée ([ActiveTunnel.instanceId]) : la
 *   reconnexion sait ainsi si c'est elle qui l'avait démarrée, ou l'utilisateur.
 * @property uptimeMs depuis combien de temps l'instance tenait, en millisecondes.
 */
data class UnexpectedStop(val tunnelId: String, val instanceId: Long, val uptimeMs: Long)

/** Éléments en attente d'être lus dans [SshTunnelManager.unexpectedStops], bien plus que de tunnels. */
private const val UNEXPECTED_STOPS_BUFFER = 64

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Message d'un tunnel dont la session SSH a pris fin sans qu'il l'ait voulu (transport perdu,
 * serveur parti). Texte fixe : la cause, un nom de l'énumération de SSHJ, n'a rien à dire à
 * l'utilisateur ; elle est dans le journal de la session ([SshSessionManager]).
 */
const val TUNNEL_SESSION_LOST_MESSAGE = "Connexion SSH perdue"

/** Message d'un tunnel local dont la boucle d'écoute s'est arrêtée sur une panne, voir [runLocalForwardListener]. */
const val TUNNEL_LISTENER_STOPPED_MESSAGE = "Tunnel local interrompu"

/** Message d'un tunnel SOCKS5 dont le serveur s'est arrêté sur une panne, voir [Socks5Server]. */
const val TUNNEL_SOCKS_STOPPED_MESSAGE = "Proxy SOCKS5 arrêté"

/** Motif d'un tunnel dont la session ne répondait plus à la surveillance de vivacité ([SessionEnd.Unresponsive]). */
const val TUNNEL_NO_ANSWER_MESSAGE = "Le serveur ne répond plus"

/** Motif d'une reconnexion qui attend le retour du réseau, sans consommer de tentative. */
const val TUNNEL_NETWORK_LOST_MESSAGE = "Réseau indisponible"

/** Message d'un tunnel dont le démarrage a été annulé en route, voir [SshTunnelManager.markStartCancelled]. */
const val TUNNEL_START_CANCELLED_MESSAGE = "Démarrage annulé"

/** Message d'un tunnel dont la reconnexion a été interrompue en route, voir [SshTunnelManager.markReconnectInterrupted]. */
const val TUNNEL_RECONNECT_INTERRUPTED_MESSAGE = "Reconnexion interrompue"

/**
 * Message d'un tunnel retenu hors de la reconnexion parce que son hôte demande l'utilisateur (clé de
 * sécurité, empreinte) alors que l'app n'est pas à l'écran, voir `ReconnectTunnelsUseCase`.
 */
const val TUNNEL_AUTH_REQUIRED_MESSAGE = "Authentification requise : ouvrez NextSH pour relancer ce tunnel"

/**
 * Message d'un tunnel dont le démarrage a échoué sur une exception, ou sur un appel de type
 * inattendu. Texte fixe, jamais le message de l'exception : il vient de SSHJ ou du système, en
 * anglais, et peut citer un hôte, un port ou une adresse. La classe de l'exception est au journal.
 */
const val TUNNEL_START_FAILED_MESSAGE = "Démarrage du tunnel impossible"

/** Message d'un tunnel local ou SOCKS5 dont le port local est déjà pris. Sans le numéro de port. */
const val TUNNEL_PORT_IN_USE_MESSAGE = "Port local déjà utilisé"

/**
 * Messages d'un tunnel dont un port ne peut pas servir, voir [TunnelPortProblem.tunnelMessage]. Sans
 * le numéro de port : celui qu'on a saisi est une donnée privée, et le texte traduit dit la plage.
 */
const val TUNNEL_LOCAL_PORT_INVALID_MESSAGE = "Port local invalide"
const val TUNNEL_REMOTE_PORT_INVALID_MESSAGE = "Port distant invalide"
const val TUNNEL_LOCAL_PORT_RESERVED_MESSAGE = "Port local réservé par Android"

/**
 * Message d'une redirection distante que le serveur a refusé d'ouvrir (port pris côté serveur, port
 * sous 1024 sans droits, redirections désactivées). Le protocole ne dit pas laquelle de ces raisons :
 * le texte les nomme, voir [isRemoteForwardRefusal].
 */
const val TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE = "Redirection distante refusée par le serveur"

/**
 * Résultat d'un démarrage qui s'efface sans rien écrire : l'utilisateur a arrêté le tunnel (ou tous)
 * pendant la connexion, ou l'a relancé et un démarrage plus récent a pris sa place. Jamais montré :
 * ni la carte, ni un message, ni la reconnexion ne le traitent comme un échec, voir
 * [SshTunnelManager.isStartAbandoned].
 */
const val TUNNEL_START_STOPPED_MESSAGE = "Démarrage arrêté"

/** Message d'un démarrage dont la session SSH n'existe plus. Sans l'identifiant de la session. */
const val TUNNEL_SESSION_NOT_FOUND_MESSAGE = "Session SSH introuvable"

/**
 * Message d'un tunnel dont l'hôte n'existe plus. Le démarrage partagé (`StartTunnelUseCase`, module
 * `:shared`) le pose sous ce texte exact : un test l'épingle.
 */
const val TUNNEL_HOST_NOT_FOUND_MESSAGE = "Hôte introuvable"

/** Message d'un tunnel dont la connexion à l'hôte a échoué sur une exception imprévue, voir [AndroidTunnelSessionProvider]. */
const val TUNNEL_CONNECT_UNEXPECTED_MESSAGE = "Connexion impossible : erreur inattendue"

/** Message d'un tunnel dont le démarrage a trouvé l'authentification du vault expirée, voir `AutoStartTunnelsUseCase`. */
const val TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE = "Authentification vault expirée"

/** Message d'un arrêt demandé pour un tunnel qui n'est pas connu du gestionnaire. Sans l'identifiant. */
const val TUNNEL_NOT_FOUND_MESSAGE = "Tunnel introuvable"

/** Message d'un arrêt qui a échoué sur une exception, pour la même raison que [TUNNEL_START_FAILED_MESSAGE]. */
const val TUNNEL_STOP_FAILED_MESSAGE = "Arrêt du tunnel impossible"

/**
 * True while the tunnel is up or on its way up (ACTIVE, STARTING, RECONNECTING). The one answer to
 * "is there something to start": starting such a tunnel again would restart it, and for a local
 * forward fail with PORT_IN_USE on the port its own listener still holds.
 *
 * The `when` is exhaustive on purpose: a new [TunnelStatus] does not compile until someone decides
 * whether it counts as running.
 */
fun TunnelStatus.isRunning(): Boolean = when (this) {
    TunnelStatus.ACTIVE,
    TunnelStatus.STARTING,
    TunnelStatus.RECONNECTING,
    -> true

    TunnelStatus.STOPPED,
    TunnelStatus.ERROR,
    -> false
}

/**
 * Le démarrage de [config], qui vient de rendre [error], n'a plus rien à dire : l'utilisateur a
 * arrêté le tunnel (ou tous), ou l'a relancé et un démarrage plus récent a pris sa place. Ni message,
 * ni échec compté, ni retenue, ni tentative de plus pour lui. Une seule réponse pour les trois
 * appelants (reconnexion, démarrage auto, écran) :
 * - le résultat est celui d'un démarrage effacé ([TUNNEL_START_STOPPED_MESSAGE], rendu par les
 *   `launch*` qu'un arrêt a rattrapés) ;
 * - ou le gestionnaire sait que ce démarrage est abandonné ([SshTunnelManager.isStartAbandoned]) :
 *   un échec de connexion qui arrive après l'arrêt. Seul, ce test rend faux quand le remplaçant a
 *   déjà fini, alors que le résultat d'un démarrage effacé dit toujours la vérité.
 */
fun SshTunnelManager.isStartWithdrawn(config: TunnelConfig, error: SshResult.Error): Boolean =
    error.message == TUNNEL_START_STOPPED_MESSAGE || isStartAbandoned(config)

/**
 * Les tunnels actifs de l'app.
 *
 * Référence sur la session SSH. Un tunnel est un utilisateur de sa session comme un autre (voir la
 * note de [SshSessionManager]) : il possède UNE référence sur la session qui le porte, aussi
 * longtemps qu'il est actif. Les `start*` la reçoivent de l'appelant, qui l'a prise pour lui
 * (`AndroidTunnelSessionProvider.sessionFor`) et la remet : à la fin d'un démarrage réussi elle
 * appartient au tunnel, et à chaque échec du démarrage (type erroné, port occupé, exception,
 * annulation avant même l'entrée) elle est rendue ici. Elle est rendue aussi à l'arrêt du tunnel
 * ([stopTunnel], [stopAllTunnels]) et à sa panne (boucle d'écoute finie, session finie). Une
 * session ouverte pour les seuls tunnels se ferme donc avec le dernier, et fermer l'onglet
 * terminal qui partageait la session ne touche plus aux tunnels.
 */
@Singleton
class SshTunnelManager internal constructor(
    private val sessionManager: SshSessionManager,
    /** Horloge monotone en millisecondes, remplaçable dans les tests : mesure la durée de vie des tunnels. */
    private val clock: () -> Long,
    /**
     * The wipe of the vault: while the app is closed no tunnel starts, whoever asks (the user, the
     * automatic reconnection, the automatic start), see [markStarting], [reserveReconnectStart] and
     * [reserveAutoStart]. The closing stops the tunnels, this keeps them stopped.
     */
    private val wipeGate: VaultWipeGate = VaultWipeGate(),
) : fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager {

    @Inject
    constructor(sessionManager: SshSessionManager, wipeGate: VaultWipeGate) :
        this(sessionManager, { System.nanoTime() / NANOS_PER_MILLI }, wipeGate)

    /** Without the wipe gate of the app (tests). Not an injection point. */
    constructor(sessionManager: SshSessionManager) : this(sessionManager, VaultWipeGate())

    private val _activeTunnels = MutableStateFlow<Map<String, ActiveTunnel>>(emptyMap())
    val activeTunnels: StateFlow<Map<String, ActiveTunnel>> = _activeTunnels

    private val _tunnelStates = MutableStateFlow<Map<String, TunnelState>>(emptyMap())
    val tunnelStates: StateFlow<Map<String, TunnelState>> = _tunnelStates.asStateFlow()

    private val _unexpectedStops = MutableSharedFlow<UnexpectedStop>(
        extraBufferCapacity = UNEXPECTED_STOPS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Each tunnel that has just stopped by itself, after it was put in RECONNECTING (ERROR if nobody listens, see
     * [markUnexpectedStop]): the session it ran on was lost ([SessionEnd.Lost]), its listener ended,
     * its SOCKS server failed. Never for a stop by hand, for all, or for a tunnel deleted elsewhere
     * (see [stopTunnel], [stopAllTunnels]), nor for a start that failed (it had no running tunnel to
     * lose), nor for a session the app closed itself ([SessionEnd.Closed]: its tunnels go to ERROR
     * as before, and the next network event picks them up). It is what lets the automatic
     * reconnection start right away (after a short wait of its own), without waiting for the next
     * network event, with how long the tunnel had held ([UnexpectedStop.uptimeMs]) so that a tunnel
     * that drops as soon as it is restored is not restored for ever.
     *
     * No replay: a stop nobody listens to is not kept. The reconnection lives in the foreground
     * service, which runs as long as a tunnel state exists (an ERROR one included), so someone
     * listens whenever a tunnel is up. A burst larger than the buffer drops the oldest ones; a
     * tunnel dropped that way is left in RECONNECTING with no loop, and the next network event picks it up.
     */
    val unexpectedStops: SharedFlow<UnexpectedStop> = _unexpectedStops.asSharedFlow()

    /** Shared interface: Flow<List<TunnelState>> (platform-neutral). */
    override val tunnels: Flow<List<TunnelState>> = _tunnelStates.map { it.values.toList() }

    override fun getTunnelState(tunnelId: String): TunnelState? = _tunnelStates.value[tunnelId]

    /**
     * Le démarrage de [config] commence : le tunnel passe en DÉMARRAGE, ses exclusions (arrêt manuel,
     * retenue après un échec non retentable) sont levées, et ce démarrage devient LE démarrage
     * retenu du tunnel (voir [starts]). Un démarrage plus ancien encore en route s'efface alors sans
     * rien écrire : le plus récent seul aboutit.
     *
     * Exception : une ligne réservée par la reconnexion ou le démarrage auto ([reserveReconnectStart],
     * [reserveAutoStart]) qu'un arrêt a rattrapée depuis ne fait rien. Ni DÉMARRAGE ni levée des
     * exclusions : ce serait ressusciter le tunnel que l'utilisateur vient de fermer. Le démarrage
     * va jusqu'à sa connexion, puis s'efface à l'entrée des `launch*`.
     *
     * Le contrat, que le démarrage partagé (`StartTunnelUseCase`) tient :
     * - `markStarting(config)` est appelé AVANT le `start*` de la même ligne. Un `start*` jamais
     *   annoncé n'est le démarrage retenu de personne : il s'efface sans rien ouvrir ni écrire.
     * - Le démarrage retenu se reconnaît à l'identité (`===`) de la ligne, jamais à sa valeur : deux
     *   lignes égales par valeur sont deux démarrages. Chaque appelant passe donc une instance neuve,
     *   lue en base pour ce démarrage (`LiveTunnelLookup`), et la même au `markStarting` et au
     *   `start*`. Rejouer l'instance d'un démarrage fini fait un nouveau démarrage, pas le même.
     *
     * @see fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager.markStarting
     */
    override fun markStarting(config: TunnelConfig) {
        synchronized(reconnectLock) {
            // The wipe of the vault has the app closed: nothing is announced, so the `start*` that follows
            // is the start of nobody and opens nothing (see above). No state, no exclusion lifted.
            if (wipeGate.isClosed()) return
            val entry = starts[config.id]
            // Only the line the reconnection reserved carries its reason; any other start has none.
            var reason: String? = null
            if (entry != null && entry.config === config && entry.phase == StartPhase.RESERVED) {
                if (entry.stopped) return
                entry.phase = StartPhase.IN_FLIGHT
                reason = entry.reason
            } else {
                starts[config.id] = PendingStart(config, StartPhase.IN_FLIGHT)
            }
            _tunnelStates.update {
                it + (config.id to TunnelState(config, TunnelStatus.STARTING, errorMessage = reason))
            }
            manuallyStoppedIds.remove(config.id)
            reconnectHeldIds.remove(config.id)
        }
    }

    /**
     * Le démarrage de [config] a échoué avant d'atteindre les `launch*` (connexion refusée, hôte
     * introuvable) : le tunnel passe en ERREUR avec [message].
     *
     * Rien n'est écrit si ce démarrage n'est plus celui du tunnel : arrêté depuis (la carte a été
     * retirée, elle ne doit pas revenir), ou remplacé par un démarrage plus récent encore en route
     * (c'est lui qui parle), ou si un démarrage plus récent a abouti et que le tunnel tourne (un échec
     * ancien ne masque pas un tunnel actif). Sans démarrage retenu pour ce tunnel (rien ne l'a annoncé
     * ni réservé : l'hôte introuvable d'un démarrage à la main, avant son `markStarting`), l'erreur
     * est écrite comme toujours. Les démarrages que la reconnexion et le démarrage auto réservent
     * ont un démarrage retenu dès avant leur lecture de l'hôte : leur erreur obéit aux règles
     * ci-dessus, un arrêt qui les a rattrapés l'efface.
     *
     * @see fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager.markError
     */
    override fun markError(config: TunnelConfig, message: String) {
        synchronized(reconnectLock) { writeStartError(config, message) }
    }

    /**
     * Écrit l'ERREUR d'un démarrage, sous [reconnectLock], selon les règles de [markError]. Vrai si la
     * carte a été écrite. Termine le démarrage de [config] s'il est le démarrage retenu : il n'ira
     * pas plus loin.
     */
    private fun writeStartError(config: TunnelConfig, message: String): Boolean {
        val entry = starts[config.id]
        if (entry != null) {
            if (entry.config === config) {
                val stopped = entry.stopped
                entry.phase = StartPhase.FINISHED
                if (stopped) return false
            } else if (entry.phase == StartPhase.IN_FLIGHT) {
                return false
            } else if (_activeTunnels.value.containsKey(config.id)) {
                return false
            }
        }
        _tunnelStates.update {
            it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = message))
        }
        return true
    }

    /**
     * Refuse de démarrer [config] si un de ses ports ne peut pas servir ([TunnelPortRules.problemOf]) :
     * un tunnel venu de la synchro ou d'un import peut avoir un port hors plage, ou un port local sous
     * 1024 (Android l'interdit), et `ServerSocket` lève alors une exception qui n'est pas une
     * `IOException`. Rend null si les ports vont. Sinon le tunnel passe en ERREUR avec le motif (texte
     * fixe, sans le port), il est retenu hors de la reconnexion automatique (un port invalide ne se
     * répare pas tout seul ; un démarrage à la main lève la retenue) et le résultat d'erreur à
     * rendre à l'appelant est retourné.
     *
     * À appeler AVANT d'ouvrir la connexion : pas de demande de clé ni d'empreinte pour un tunnel qui
     * ne peut pas démarrer. Un seul appel, sous le verrou : l'erreur, la retenue et la fin du démarrage
     * ne peuvent pas être séparées par un arrêt ou un redémarrage. Avec un démarrage qu'un arrêt a
     * rattrapé, rien n'est écrit (voir [markError]) ; l'appelant le voit par [isStartAbandoned].
     */
    fun refuseInvalidPorts(config: TunnelConfig): SshResult.Error? {
        val problem = TunnelPortRules.problemOf(config) ?: return null
        val message = problem.tunnelMessage()
        synchronized(reconnectLock) {
            if (writeStartError(config, message)) reconnectHeldIds.add(config.id)
        }
        // Texte fixe : jamais le port.
        Timber.w("Tunnel refusé : port invalide ($problem)")
        return SshResult.Error(SshErrorCode.UNKNOWN, message)
    }

    // ── Shared interface wrappers (sessionId-based) ───────────────────────────────

    /**
     * Les trois démarrages reçoivent de l'appelant une référence sur [sessionId] et la prennent à
     * leur charge : voir la note de la classe.
     */
    override suspend fun startLocalForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
        startOwning(config, sessionId) { client -> launchLocalForward(client, config, sessionId) }

    override suspend fun startRemoteForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
        startOwning(config, sessionId) { client -> launchRemoteForward(client, config, sessionId) }

    override suspend fun startDynamicForward(config: TunnelConfig, sessionId: String): SshResult<Unit> =
        startOwning(config, sessionId) { client -> launchDynamicSocks5(client, config, sessionId) }

    /**
     * Fait tourner [start] sur le client de [sessionId], avec la référence que l'appelant vient de
     * remettre : si le démarrage réussit, le tunnel l'a gardée (voir [activateOrUndo]) ; sinon elle est
     * rendue ici, quelle que soit la cause : session introuvable, erreur du démarrage, exception, ou
     * annulation avant que le corps ait commencé (`withContext` lève alors sans rien exécuter).
     *
     * Une annulation qui arrive après la fin du corps ne défait pas un tunnel déjà actif : il garde
     * sa référence, et c'est son arrêt qui la rendra.
     *
     * Une session coupée entre la connexion et ici (plus de client) est un échec du démarrage de
     * [config] comme un autre : le tunnel passe en ERREUR avec [TUNNEL_SESSION_NOT_FOUND_MESSAGE], si
     * ce démarrage est toujours le retenu et n'a pas été arrêté (voir [failStart]). Sinon la carte
     * restait en DÉMARRAGE pour toujours, hors de la reconnexion.
     */
    private suspend fun startOwning(
        config: TunnelConfig,
        sessionId: String,
        start: (SSHClient) -> SshResult<Unit>,
    ): SshResult<Unit> {
        var entered = false
        try {
            return withContext(Dispatchers.IO) {
                entered = true
                val client = sessionManager.getClient(sessionId)
                val result = if (client == null) {
                    failStart(config, StartFailure.SESSION_NOT_FOUND)
                } else {
                    try {
                        start(client)
                    } catch (e: Throwable) {
                        sessionManager.release(sessionId)
                        throw e
                    }
                }
                if (result !is SshResult.Success) sessionManager.release(sessionId)
                result
            }
        } finally {
            if (!entered) sessionManager.release(sessionId)
        }
    }

    /**
     * Dernier mot d'un `launch*` : active [active] si son démarrage est toujours celui du tunnel et
     * n'a pas été arrêté, et rend vrai. Sinon rend faux après avoir exécuté [undo], qui défait ce que
     * le démarrage a construit (écoute fermée, redirection distante annulée, serveur SOCKS arrêté) :
     * un arrêt ou un démarrage plus récent l'a emporté pendant la connexion.
     *
     * Activer, c'est enregistrer le tunnel avec la référence qu'il porte ([activeTunnels]), le dater,
     * le passer en ACTIF et clore le démarrage. Tout cela sous [reconnectLock], le même verrou que
     * l'arrêt (voir [stopTunnel], [stopAllTunnels]) : un arrêt tombe avant, et ce démarrage s'efface,
     * ou après, et il trouve le tunnel actif et l'arrête. Sans verrou, un arrêt entre la vérification
     * et l'enregistrement laisserait un tunnel actif que personne ne sait fermer, la carte retirée.
     *
     * Un tunnel redémarré sur un tunnel encore actif le remplace dans la liste : la référence de
     * l'ancien, qui n'a plus de porteur, est rendue, hors du verrou (la fermeture d'une session fait
     * des E/S).
     *
     * `internal` pour que les tests rejouent un arrêt tombé juste avant l'activation d'un démarrage
     * (les `launch*` réels exigent un `SSHClient` et une socket).
     */
    @androidx.annotation.VisibleForTesting
    internal fun activateOrUndo(active: ActiveTunnel, undo: () -> Unit): Boolean {
        val config = active.config
        var replaced: ActiveTunnel? = null
        val activated = synchronized(reconnectLock) {
            if (!isCurrentStartLocked(config)) return@synchronized false
            starts.getValue(config.id).phase = StartPhase.FINISHED
            // Daté ici, à l'instant où il devient actif : la durée de vie se compte depuis que le
            // tunnel tient, pas depuis que son démarrage a commencé.
            val stamped = active.copy(startedAtMs = clock())
            lastInstanceIds[config.id] = stamped.instanceId
            replaced = _activeTunnels.getAndUpdate { it + (config.id to stamped) }[config.id]
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }
            true
        }
        if (!activated) {
            undo()
            return false
        }
        replaced?.let { sessionManager.release(it.sessionId) }
        return true
    }

    /**
     * Le démarrage de [config] est-il toujours celui du tunnel, et n'a-t-il pas été arrêté ? Faux pour
     * un démarrage arrêté, remplacé par un plus récent, ou jamais annoncé par [markStarting].
     */
    private fun isCurrentStart(config: TunnelConfig): Boolean =
        synchronized(reconnectLock) { isCurrentStartLocked(config) }

    private fun isCurrentStartLocked(config: TunnelConfig): Boolean {
        val entry = starts[config.id] ?: return false
        return entry.config === config && !entry.stopped && entry.phase != StartPhase.FINISHED
    }

    /** IDs des tunnels arrêtés manuellement, exclus de la reconnexion automatique. */
    private val manuallyStoppedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * IDs des tunnels en ERREUR après un échec non retentable (refus d'authentification, annulation
     * de la demande de clé, délai écoulé, clé illisible, clé d'hôte qui ne correspond plus, voir
     * [isNonRetryable]) : exclus de la reconnexion automatique jusqu'à ce que l'utilisateur les
     * redémarre. Une nouvelle tentative ne peut pas les réparer, pour un hôte FIDO2 chacune
     * rouvrirait la demande de clé sur l'écran en cours, et une clé d'hôte qui change est un
     * événement de sécurité qu'on ne rejoue pas dans le dos de l'utilisateur.
     */
    private val reconnectHeldIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Verrou des décisions qui lisent et écrivent ensemble l'état d'un tunnel, ses exclusions et son
     * démarrage en cours : l'entrée en démarrage ([markStarting]), l'activation d'un démarrage
     * ([activateOrUndo]), l'arrêt ([stopTunnel], [stopAllTunnels]) et l'abandon de la reconnexion
     * ([holdReconnect]). Sans lui, un abandon décidé juste avant un redémarrage manuel pouvait
     * s'écrire juste après (le tunnel relancé se retrouvait en ERREUR, exclu de la reconnexion), et
     * un arrêt tombé entre la vérification et l'enregistrement d'un démarrage le laissait actif.
     *
     * Jamais tenu pendant une E/S ni une suspension : fermer une socket ou une session, annuler une
     * redirection distante, rendre une référence se font hors du verrou.
     */
    private val reconnectLock = Any()

    /** Où en est le démarrage retenu d'un tunnel, voir [PendingStart]. */
    private enum class StartPhase {
        /** Réservé par la reconnexion ou le démarrage auto, que [markStarting] n'a pas encore annoncé. */
        RESERVED,

        /** Annoncé par [markStarting] : la connexion, puis le `launch*`, sont en route. */
        IN_FLIGHT,

        /** Terminé : actif, en erreur, ou annulé. Gardé pour que les plus anciens sachent qu'ils le sont. */
        FINISHED,
    }

    /**
     * Le dernier démarrage d'un tunnel : la ligne exacte qu'il a reçue (comparée par identité, comme
     * [markStartCancelled]), où il en est, et si un arrêt l'a rattrapé en route ([stopped]).
     *
     * Un démarrage dure de quelques secondes à plus d'une minute derrière une invite de clé, pendant
     * lesquelles l'utilisateur peut arrêter le tunnel (la carte est en DÉMARRAGE), l'arrêter tous, ou
     * le relancer. Seul le démarrage retenu peut écrire l'état du tunnel ou s'y enregistrer : celui
     * qu'un arrêt a rattrapé, ou qu'un plus récent a remplacé, s'efface sans rien écrire.
     */
    private class PendingStart(
        val config: TunnelConfig,
        var phase: StartPhase,
        var stopped: Boolean = false,
        /**
         * Why the reconnection that reserved this start is under way ([reserveReconnectStart]), shown on
         * the card during the attempt. Null for every other start.
         */
        val reason: String? = null,
    )

    /**
     * Le dernier démarrage de chaque tunnel, par id, sous [reconnectLock]. Un par tunnel configuré,
     * comme [lastInstanceIds] : sans purge. Même après sa fin il reste, pour qu'un démarrage plus
     * ancien qui finit tard ne s'écrive pas par-dessus.
     */
    private val starts = HashMap<String, PendingStart>()

    /**
     * Réserve auprès du gestionnaire le démarrage de [config] par la reconnexion automatique, si le
     * tunnel l'attend encore ([isAwaitingReconnect]). La vérification et la réservation sont une
     * seule décision, sous le verrou : un arrêt à la main qui tombe ensuite, avant ou pendant la
     * lecture de l'hôte que fait le démarrage partagé, est vu par [markStarting], qui ne lève alors
     * pas les exclusions et ne ressuscite pas le tunnel. Faux (rien à démarrer) si le tunnel a été
     * arrêté, retenu, relancé ou supprimé entre-temps.
     *
     * À appeler avec la ligne exacte passée ensuite au démarrage partagé.
     *
     * [reason] : le motif de la reconnexion (texte fixe, voir [tunnelMessageRes]). Le [markStarting] de
     * CETTE ligne réservée le reporte sur le DÉMARRAGE de la tentative, pour que la carte ne perde pas
     * le motif ; tout autre démarrage reste sans message.
     */
    fun reserveReconnectStart(config: TunnelConfig, reason: String? = null): Boolean = synchronized(reconnectLock) {
        // A reconnection loop that started before the wipe of the vault finds the app closed: nothing to start.
        if (wipeGate.isClosed()) return false
        if (!isAwaitingReconnect(config.id)) return false
        starts[config.id] = PendingStart(config, StartPhase.RESERVED, reason = reason)
        true
    }

    /**
     * Écrit [status] avec [reason] seulement si [tunnelId] attend encore une reconnexion ([isAwaitingReconnect] :
     * en ERREUR ou en RECONNEXION, ni arrêté à la main, ni retenu), en une seule décision sous
     * [reconnectLock]. Rend faux, sans rien écrire, si le tunnel a été arrêté, redémarré à la main
     * (DÉMARRAGE, ACTIF), retenu ou supprimé entre-temps : la reconnexion s'arrête alors. Remplace le
     * couple « vérifier puis écrire » de la reconnexion, que ces cas traversaient.
     */
    fun markIfAwaitingReconnect(tunnelId: String, status: TunnelStatus, reason: String?): Boolean =
        synchronized(reconnectLock) {
            if (!isAwaitingReconnect(tunnelId)) return false
            updateTunnelStatus(tunnelId, status, reason)
            true
        }

    /**
     * Comme [reserveReconnectStart], pour le démarrage automatique et la reprise : faux si le
     * tunnel tourne déjà ou est en route (ACTIF, DÉMARRAGE, RECONNEXION, voir [isRunning]), car le
     * redémarrer ferait échouer un tunnel local sur son propre port.
     */
    fun reserveAutoStart(config: TunnelConfig): Boolean = synchronized(reconnectLock) {
        if (wipeGate.isClosed()) return false
        if (_tunnelStates.value[config.id]?.status?.isRunning() == true) return false
        starts[config.id] = PendingStart(config, StartPhase.RESERVED)
        true
    }

    /**
     * Le démarrage de [config] ne mène plus nulle part : un arrêt l'a rattrapé, ou un démarrage plus
     * récent est en route. À consulter sur un résultat d'ERREUR : l'appelant ne montre alors rien et
     * ne compte pas d'échec, le tunnel n'est plus à lui. Faux pour un échec ordinaire de ce démarrage,
     * et pour un démarrage plus ancien dont le remplaçant a déjà fini (rien ne dit alors lequel est
     * le plus récent, l'erreur reste dite).
     */
    fun isStartAbandoned(config: TunnelConfig): Boolean = synchronized(reconnectLock) {
        val entry = starts[config.id] ?: return false
        if (entry.config === config) entry.stopped else entry.phase == StartPhase.IN_FLIGHT
    }

    /**
     * Invalide le démarrage en route de [tunnelId], s'il y en a un (réservé ou annoncé) : il s'effacera
     * sans rien écrire. Vrai s'il y en avait un. Sous [reconnectLock], à appeler par un arrêt.
     */
    private fun stopPendingStartLocked(tunnelId: String): Boolean {
        val entry = starts[tunnelId] ?: return false
        if (entry.phase == StartPhase.FINISHED) return false
        entry.stopped = true
        return true
    }

    /** Un démarrage que rien n'a arrêté est en route pour [tunnelId]. Sous [reconnectLock]. */
    private fun isStartInFlightLocked(tunnelId: String): Boolean {
        val entry = starts[tunnelId] ?: return false
        return entry.phase != StartPhase.FINISHED && !entry.stopped
    }

    private fun isReconnectBlocked(tunnelId: String): Boolean =
        tunnelId in manuallyStoppedIds || tunnelId in reconnectHeldIds

    /** Distingue deux démarrages successifs d'un même tunnel, voir [ActiveTunnel.instanceId]. */
    private val instanceCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Par tunnel, l'instance enregistrée en dernier, conservée après sa coupure : voir
     * [lastInstanceId]. Un entier par tunnel configuré, comme [manuallyStoppedIds] : sans purge.
     */
    private val lastInstanceIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        sessionManager.addSessionEndListener(::onSessionEnded)
    }

    // ── Détection de panne ────────────────────────────────────────────────────────

    /**
     * Bascule un tunnel en [TunnelStatus.ERROR] après une panne, en le retirant
     * des tunnels actifs (ses ressources sont mortes). Si la panne est annoncée et que quelqu'un
     * écoute [unexpectedStops] (la reconnexion, dans le service), la carte passe directement en
     * [TunnelStatus.RECONNECTING], avec le motif : pas de flash rouge avant la reconnexion.
     *
     * Sans effet si le tunnel a été arrêté manuellement ou n'est plus actif.
     * L'arrêt voulu d'un LOCAL_FORWARD (interruption du thread d'écoute, puis
     * fermeture de la ServerSocket) fait aussi sortir la boucle d'écoute, qui
     * s'efface alors sans appeler cette fonction : celui qui arrête possède
     * les transitions d'état, et il ne faut pas confondre cette sortie avec
     * une panne.
     *
     * C'est cette transition qui rend le tunnel visible pour
     * [getTunnelsForReconnect] et donc éligible à la reconnexion automatique (en ERREUR comme en
     * RECONNEXION : une annonce perdue, buffer plein ou service détruit, laisse une RECONNEXION
     * orpheline que le prochain événement réseau reprend).
     *
     * [announce] : la panne est dite sur [unexpectedStops], ce qui lance la reconnexion tout de suite.
     * Faux pour une session que l'app a elle-même fermée ([SessionEnd.Closed]) : c'est sa décision, pas
     * une coupure à réparer. Le tunnel tombe en erreur, comme avant, et le prochain événement réseau
     * le relève.
     */
    private fun markUnexpectedStop(
        tunnelId: String,
        instanceId: Long?,
        message: String,
        transportGone: Boolean = false,
        announce: Boolean = true,
    ) {
        if (tunnelId in manuallyStoppedIds) return
        val active = _activeTunnels.value[tunnelId] ?: return
        // Le thread d'une instance remplacee ne doit pas tuer la nouvelle.
        if (instanceId != null && active.instanceId != instanceId) return

        // Fermer les ressources AVANT de lacher la reference : une coupure du
        // transport SSH ne ferme pas la socket locale, qui resterait liee au
        // port sans que rien ne la reference. La reconnexion echouerait alors
        // indefiniment sur "port deja utilise".
        closeTunnelResources(active, transportGone)

        // Un seul des chemins d'arrêt qui se croisent (cette panne, un arrêt manuel, l'arrêt de
        // tous, la fin de la session) retire l'entrée, voir [detach] : les autres n'ont rien à faire
        // de plus, ni l'état ni la référence.
        val detached = detach(tunnelId, active) ?: return
        // Annoncée ET entendue : la carte passe DIRECTEMENT en RECONNEXION (ambre), sans montrer
        // d'ERREUR rouge le temps que la reconnexion, qui écrit après plusieurs lectures en base,
        // prenne la main. Sans auditeur (ou sans annonce) personne ne la reprendra tout de suite :
        // ERREUR, que le prochain événement réseau relèvera. Lu avant l'écriture de l'état, comme le
        // dit [announce] plus bas : un auditeur qui arrive entre les deux retrouve de toute façon
        // une RECONNEXION orpheline, que [getTunnelsForReconnect] reprend.
        val handedOver = announce && _unexpectedStops.subscriptionCount.value > 0
        _tunnelStates.update { states ->
            val existing = states[tunnelId] ?: return@update states
            states + (tunnelId to existing.copy(
                status       = if (handedOver) TunnelStatus.RECONNECTING else TunnelStatus.ERROR,
                errorMessage = message,
            ))
        }
        // Le tunnel n'est plus : sa référence sur la session s'en va avec lui, après son retrait
        // de la liste (une session qui se ferme ne doit plus le trouver). Sans effet si la session
        // est justement ce qui vient de finir. Le tunnel redémarré par la reconnexion en prendra
        // une nouvelle.
        sessionManager.release(detached.sessionId)
        // Texte fixe : ni libelle, ni id, ni message d'erreur (il peut citer un hote ou un port).
        Timber.w("Tunnel interrompu")
        // Dit en dernier : la reconnexion qui l ecoute trouve le tunnel en RECONNEXION, hors de la liste
        // des tunnels actifs et sans reference sur la session.
        if (announce) {
            _unexpectedStops.tryEmit(
                UnexpectedStop(
                    tunnelId = tunnelId,
                    instanceId = detached.instanceId,
                    uptimeMs = (clock() - detached.startedAtMs).coerceAtLeast(0L),
                ),
            )
        }
    }

    /**
     * Retire [tunnelId] des tunnels actifs, à condition que l'entrée soit encore [expected], et
     * rend l'entrée retirée ; null s'il n'y en avait plus, ou si c'en était une autre (un tunnel
     * redémarré entre-temps). Atomique : de deux appels qui se croisent sur le même tunnel un seul
     * reçoit l'entrée, l'autre reçoit null.
     *
     * C'est ce retour qui décide qui rend la référence du tunnel sur sa session. Lire l'entrée puis
     * la retirer en deux temps laissait deux arrêts concurrents la rendre chacun : le second rendait
     * alors celle d'un autre porteur, et pouvait fermer la session d'un terminal.
     */
    private fun detach(tunnelId: String, expected: ActiveTunnel): ActiveTunnel? {
        var detached: ActiveTunnel? = null
        _activeTunnels.update { current ->
            val entry = current[tunnelId]
            if (entry === expected) {
                detached = entry
                current - tunnelId
            } else {
                detached = null
                current
            }
        }
        return detached
    }

    /**
     * Libère les ressources d'un tunnel selon son type, sans toucher aux états.
     *
     * [transportGone] : le transport SSH de la session est mort ou fermé. Le `cancel` d'un
     * REMOTE_FORWARD est un échange avec le serveur, qui n'a plus personne à qui parler et peut
     * attendre le délai du transport avant d'échouer : il est sauté, le serveur a perdu le
     * transfert avec la connexion.
     */
    private fun closeTunnelResources(active: ActiveTunnel, transportGone: Boolean = false) {
        try {
            when (active.config.type) {
                TunnelType.LOCAL_FORWARD  -> stopLocalListener(active.listenerThread, active.serverSocket)
                TunnelType.REMOTE_FORWARD -> {
                    val forwarder = active.remoteForwarder
                    val forward   = active.remoteForward
                    if (!transportGone && forwarder != null && forward != null) forwarder.cancel(forward)
                }
                TunnelType.DYNAMIC_SOCKS5 -> active.socks5Server?.stop()
            }
        } catch (e: Exception) {
            Timber.w("Erreur fermeture tunnel (${e::class.java.simpleName})")
        }
    }

    /**
     * Arrête la boucle d'écoute d'un LOCAL_FORWARD.
     *
     * Fermer la ServerSocket ne suffit pas : `LocalPortForwarder.listen()` de
     * SSHJ boucle sur `while (!thread.isInterrupted())` et avale la
     * SocketException d'`accept()` quand la socket est fermée. Sans
     * interruption, le thread retourne aussitôt dans un `accept()` qui échoue
     * à nouveau, sans fin, à 100 % d'un coeur.
     *
     * L'interruption passe AVANT la fermeture : l'`accept()` réveillé par la
     * fermeture trouve alors l'interruption et la boucle se termine. On évite
     * `forwarder.close()`, qui fait la même chose mais lève une NPE tant que
     * `listen()` n'a pas posé son thread.
     *
     * Prend le thread et la socket plutôt qu'un [ActiveTunnel] : un démarrage
     * qui échoue en cours de route les possède avant que le tunnel existe.
     */
    private fun stopLocalListener(listenerThread: Thread?, serverSocket: ServerSocket?) {
        listenerThread?.interrupt()
        serverSocket?.close()
    }

    /**
     * Fin d'une session SSH : tous les tunnels qui la portaient s'arrêtent en panne.
     *
     * Indispensable : une coupure réseau ne ferme pas les sockets locales, donc
     * ni la boucle d'écoute d'un LOCAL_FORWARD ni celle d'un SOCKS5 ne
     * remontent d'erreur tant qu'aucun client ne s'y connecte. Sans cette
     * notification, un tunnel resterait affiché ACTIF alors que le transport est
     * mort, et la reconnexion ne se déclencherait jamais. C'est également le
     * seul signal de panne disponible pour un REMOTE_FORWARD, qui n'ouvre
     * aucune socket locale.
     *
     * Le listener du transport SSHJ est posé par [SshSessionManager], une seule fois par session :
     * ce gestionnaire s'abonne à sa fin au lieu de l'écraser.
     */
    private fun onSessionEnded(sessionId: String, end: SessionEnd) {
        // La raison détaillée est au journal de la session ; le tunnel porte un texte fixe, que la carte
        // traduit : « ne répond plus » quand la surveillance de vivacité a déclaré la session morte,
        // « connexion perdue » pour tout autre fin.
        val message = if (end is SessionEnd.Unresponsive) TUNNEL_NO_ANSWER_MESSAGE else TUNNEL_SESSION_LOST_MESSAGE
        _activeTunnels.value
            .filterValues { it.sessionId == sessionId }
            .map { (id, tunnel) -> id to tunnel.instanceId }
            // Le transport de la session est mort ou fermé, quelle que soit la fin : rien à dire au serveur.
            // Seul le transport perdu ou muet est une coupure à réparer tout de suite : une session
            // fermée par l'app (dernière référence rendue, fermeture forcée) est une décision de l'app.
            .forEach { (id, instanceId) ->
                markUnexpectedStop(id, instanceId, message, transportGone = true, announce = end !is SessionEnd.Closed)
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
     *
     * Bloquant, appelé depuis [startOwning] sur le dispatcher IO. Sur un succès le tunnel porte la
     * référence sur [sessionId] (voir [activateOrUndo]) ; sur une erreur c'est l'appelant qui la rend.
     *
     * Le démarrage n'écrit l'état que s'il est toujours celui du tunnel (voir [PendingStart]) : arrêté
     * pendant la connexion, ou remplacé, il rend [TUNNEL_START_STOPPED_MESSAGE] avant d'ouvrir quoi
     * que ce soit, ou défait ce qu'il a construit si l'arrêt tombe en route.
     */
    private fun launchLocalForward(
        sshClient: SSHClient,
        config: TunnelConfig,
        sessionId: String,
    ): SshResult<Unit> {
        if (config.type != TunnelType.LOCAL_FORWARD) return rejectWrongType(config)

        if (!isCurrentStart(config)) return startStopped(config)

        // Dernier recours : les appelants refusent déjà un port invalide avant la connexion.
        refuseInvalidPorts(config)?.let { return it }

        if (isPortInUse(config.localPort)) return failStart(config, StartFailure.PORT_IN_USE)

        // Hors du try : un echec apres leur creation doit pouvoir les liberer,
        // sinon la socket reste liee au port et le thread reste en ecoute.
        var createdSocket: ServerSocket? = null
        var createdThread: Thread? = null

        return try {
            val params = Parameters(
                "127.0.0.1",
                config.localPort,
                config.remoteHost,
                config.remotePort
            )
            val serverSocket = ServerSocket()
            createdSocket = serverSocket
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(params.localHost, params.localPort))

            val forwarder = sshClient.newLocalPortForwarder(params, serverSocket)
            val instanceId = instanceCounter.incrementAndGet()

            val listenerThread = startListenerThread(config, instanceId, forwarder, serverSocket, sshClient, sessionId)
            createdThread = listenerThread

            val activated = activateOrUndo(
                ActiveTunnel(
                    config         = config,
                    sessionId      = sessionId,
                    instanceId     = instanceId,
                    forwarder      = forwarder,
                    serverSocket   = serverSocket,
                    listenerThread = listenerThread,
                ),
                // Le thread AVANT la socket, comme à tout arrêt (voir stopLocalListener).
                undo = { stopLocalListener(listenerThread, serverSocket) },
            )
            if (!activated) return startStopped(config)

            // Texte fixe : le libelle, les ports et l'hote distant sont des donnees privees.
            Timber.i("Tunnel LOCAL démarré")
            SshResult.Success(Unit)

        } catch (e: Exception) {
            Timber.e("Échec démarrage tunnel LOCAL (${e::class.java.simpleName})")
            // Le thread et la socket créés avant l'échec : sans cela la socket reste liée au port et
            // le thread reste en écoute. Le thread interrompu s'efface sans toucher à l'état (ci-dessous).
            try {
                stopLocalListener(createdThread, createdSocket)
            } catch (closeError: Exception) {
                Timber.w("Erreur fermeture tunnel (${closeError::class.java.simpleName})")
            }
            failStart(config, classifyStartFailure(e, config.type) { sshClient.isConnected })
        }
    }

    /**
     * Lance le thread daemon qui exécute la boucle d'écoute d'un LOCAL_FORWARD
     * ([runLocalForwardListener]) et rend le tunnel en panne si elle s'arrête sur une panne.
     */
    private fun startListenerThread(
        config: TunnelConfig,
        instanceId: Long,
        forwarder: LocalPortForwarder,
        serverSocket: ServerSocket,
        sshClient: SSHClient,
        sessionId: String,
    ): Thread = Thread {
        val failure = runLocalForwardListener(
            listen = forwarder::listen,
            serverSocket = serverSocket,
            clientConnected = { sshClient.isConnected },
            transportAnswers = { probeTransportOrTraffic(sshClient) },
        )
        // Seul stopLocalListener interrompt ce thread : un thread interrompu a été arrêté
        // volontairement, et celui qui l'a arrêté possède les transitions d'état (ne pas les refaire,
        // ni écraser son message). Une vraie panne d'écoute ne positionne pas le drapeau.
        if (Thread.currentThread().isInterrupted) return@Thread
        // Une liaison muette (sonde sans réponse) est celle de TOUTE la session : la déclarer morte
        // d'abord, pour que tous ses tunnels (celui-ci compris) tombent avec le bon motif et que la
        // reconnexion ne retrouve pas la même session morte dans le registre. Sans effet si la session
        // a déjà fini ; le tunnel est alors rendu en panne ci-dessous.
        // Seulement si la surveillance de cette session est voulue : une cadence absente ou <= 0 (le
        // champ Keepalive de l'hôte à 0) ne doit pas couper terminal, SFTP et autres tunnels ; seul ce
        // tunnel s'arrête alors, comme avant la surveillance.
        val watched = (sessionManager.livenessCadenceSeconds(sessionId) ?: 0) > 0
        if (failure == TUNNEL_NO_ANSWER_MESSAGE && watched) {
            try {
                sessionManager.declareUnresponsive(sessionId, sshClient)
            } catch (e: Exception) {
                Timber.w("Declaration de session sans reponse impossible (${e::class.java.simpleName})")
            }
        }
        // listen() rend la main aussi bien sur un arrêt que sur une panne : markUnexpectedStop filtre
        // un arrêt manuel et un tunnel qui n'est plus actif.
        markUnexpectedStop(config.id, instanceId, failure ?: TUNNEL_LISTENER_STOPPED_MESSAGE)
    }.apply {
        name = "tunnel-listener"
        isDaemon = true
        start()
    }

    // ── Remote forward ────────────────────────────────────────────────────────────

    /**
     * Démarre un tunnel remote forward.
     *
     * Équivalent CLI : ssh -R remotePort:localhost:localPort user@vps -N
     *
     * Le serveur SSH écoute sur [config.remotePort] et redirige vers
     * localhost:[config.localPort] côté client.
     *
     * Même contrat que [launchLocalForward] pour la référence sur [sessionId].
     */
    private fun launchRemoteForward(
        sshClient: SSHClient,
        config: TunnelConfig,
        sessionId: String,
    ): SshResult<Unit> {
        if (config.type != TunnelType.REMOTE_FORWARD) return rejectWrongType(config)

        if (!isCurrentStart(config)) return startStopped(config)

        // Dernier recours, voir launchLocalForward.
        refuseInvalidPorts(config)?.let { return it }

        return try {
            val remotePortForwarder = sshClient.remotePortForwarder
            val forward = RemotePortForwarder.Forward(config.remotePort)

            remotePortForwarder.bind(
                forward,
                SocketForwardingConnectListener(
                    InetSocketAddress("localhost", config.localPort)
                )
            )

            val activated = activateOrUndo(
                ActiveTunnel(
                    config          = config,
                    sessionId       = sessionId,
                    instanceId      = instanceCounter.incrementAndGet(),
                    remoteForwarder = remotePortForwarder,
                    remoteForward   = forward,
                ),
                // Le serveur écoute déjà : sans l'annulation il garderait le port jusqu'à la fin de la session.
                undo = { remotePortForwarder.cancel(forward) },
            )
            if (!activated) return startStopped(config)

            Timber.i("Tunnel REMOTE démarré")
            SshResult.Success(Unit)

        } catch (e: Exception) {
            Timber.e("Échec démarrage tunnel REMOTE (${e::class.java.simpleName})")
            failStart(config, classifyStartFailure(e, config.type) { sshClient.isConnected })
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
     *
     * Même contrat que [launchLocalForward] pour la référence sur [sessionId].
     */
    private fun launchDynamicSocks5(
        sshClient: SSHClient,
        config: TunnelConfig,
        sessionId: String,
    ): SshResult<Unit> {
        if (config.type != TunnelType.DYNAMIC_SOCKS5) return rejectWrongType(config)

        if (!isCurrentStart(config)) return startStopped(config)

        // Dernier recours, voir launchLocalForward.
        refuseInvalidPorts(config)?.let { return it }

        if (isPortInUse(config.localPort)) return failStart(config, StartFailure.PORT_IN_USE)

        // Hors du try, comme launchLocalForward : un échec après sa création doit pouvoir l'arrêter.
        var createdServer: Socks5Server? = null

        return try {
            val instanceId = instanceCounter.incrementAndGet()
            val server = Socks5Server(sshClient, config.localPort) { failure ->
                markUnexpectedStop(config.id, instanceId, failure)
            }
            createdServer = server
            server.start()

            val activated = activateOrUndo(
                ActiveTunnel(
                    config       = config,
                    sessionId    = sessionId,
                    instanceId   = instanceId,
                    socks5Server = server,
                ),
                undo = { server.stop() },
            )
            if (!activated) return startStopped(config)

            Timber.i("Tunnel SOCKS5 démarré")
            SshResult.Success(Unit)

        } catch (e: Exception) {
            Timber.e("Échec démarrage tunnel SOCKS5 (${e::class.java.simpleName})")
            try {
                createdServer?.stop()
            } catch (closeError: Exception) {
                Timber.w("Erreur fermeture tunnel (${closeError::class.java.simpleName})")
            }
            failStart(config, classifyStartFailure(e, config.type) { sshClient.isConnected })
        }
    }

    // ── Échecs de démarrage ───────────────────────────────────────────────────────
    // Chaque motif est un texte fixe que la carte du tunnel traduit à l'affichage (voir
    // [tunnelMessageRes]) : jamais le message d'une exception, ni un numéro de port, ni un identifiant.
    // Les exceptions sont rangées par [classifyStartFailure], une fonction pure.

    /** Le démarrage a été appelé pour un tunnel d'un autre type : un défaut de l'appelant, pas de l'utilisateur. */
    private fun rejectWrongType(config: TunnelConfig): SshResult<Unit> {
        Timber.w("Tunnel démarré avec un type inattendu")
        return failStart(config)
    }

    /**
     * Le démarrage de [config] a échoué pour [failure] : la classe de l'exception est au journal, le
     * motif est un texte fixe. Le tunnel passe en ERREUR, mais seulement si ce démarrage est toujours
     * celui du tunnel et n'a pas été arrêté (voir [writeStartError]) ; sinon rien n'est écrit, et le
     * résultat est celui d'un démarrage effacé ([startStopped]) : la carte n'a pas à revenir, et
     * l'appelant n'a pas d'échec à dire.
     */
    private fun failStart(config: TunnelConfig, failure: StartFailure = StartFailure.GENERIC): SshResult<Unit> {
        val written = synchronized(reconnectLock) {
            // Un démarrage jamais annoncé (ni retenu) n'a rien à écrire non plus : voir isCurrentStart.
            val entry = starts[config.id]
            if (entry == null || entry.config !== config) {
                false
            } else {
                val stopped = entry.stopped
                entry.phase = StartPhase.FINISHED
                if (stopped) {
                    false
                } else {
                    _tunnelStates.update {
                        it + (config.id to TunnelState(config, TunnelStatus.ERROR, errorMessage = failure.message))
                    }
                    true
                }
            }
        }
        return if (written) SshResult.Error(failure.code, failure.message) else startStopped(config)
    }

    /**
     * Le démarrage de [config] s'efface sans rien écrire : voir [TUNNEL_START_STOPPED_MESSAGE]. Il est
     * terminé, `stopped` gardé : une entrée arrêtée laissée `IN_FLIGHT` serait lue comme « un
     * démarrage plus récent est en route » par le démarrage d'une autre instance qui échoue avant son
     * `markStarting` (ports invalides, hôte introuvable) : rien ne serait écrit ni dit pour lui.
     */
    private fun startStopped(config: TunnelConfig): SshResult<Unit> {
        synchronized(reconnectLock) { finishStartLocked(config) }
        return SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_START_STOPPED_MESSAGE)
    }

    // ── Stop ──────────────────────────────────────────────────────────────────────

    /** Ce que l'arrêt d'un tunnel a décidé sous [reconnectLock] : démonter un tunnel actif, ou déjà fini. */
    private sealed interface StopDecision {
        class Teardown(val active: ActiveTunnel) : StopDecision
        class Done(val result: SshResult<Unit>) : StopDecision
    }

    /**
     * Arrêt propre d'un tunnel, selon son type.
     *
     * Arrêter un tunnel en DÉMARRAGE l'emporte sur son démarrage : la connexion continue (quelques
     * secondes, plus d'une minute derrière une invite de clé) mais le démarrage est invalidé, il ne
     * s'enregistrera pas et n'écrira plus aucun état (voir [PendingStart], [activateOrUndo]).
     */
    override suspend fun stopTunnel(tunnelId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        // Une seule décision, sous le verrou de l'activation d'un démarrage : le tunnel est actif, et
        // on le démonte, ou il ne l'est pas et un démarrage en route est invalidé dans le même
        // temps. Lire « pas actif » puis marquer le démarrage en deux temps laisserait un démarrage
        // s'activer entre les deux : un tunnel actif sans carte.
        val decision = synchronized(reconnectLock) {
            val current = _activeTunnels.value[tunnelId]
            val hadStart = stopPendingStartLocked(tunnelId)
            when {
                current != null -> {
                    manuallyStoppedIds.add(tunnelId)
                    StopDecision.Teardown(current)
                }
                // Tunnel en panne : ses ressources sont deja fermees, mais son etat ERREUR subsiste pour
                // l'afficher et permettre la reconnexion. Le retirer est le seul moyen pour
                // l'utilisateur de solder la carte, et c'est aussi ce qui laisse le service au premier
                // plan s'arreter quand plus aucun tunnel ne reste. Même chose pour un tunnel en
                // DÉMARRAGE : le démarrage invalidé plus haut s'efface de lui-même.
                _tunnelStates.value.containsKey(tunnelId) || hadStart -> {
                    manuallyStoppedIds.add(tunnelId)
                    _tunnelStates.update { it - tunnelId }
                    StopDecision.Done(SshResult.Success(Unit))
                }
                else -> StopDecision.Done(SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_NOT_FOUND_MESSAGE))
            }
        }
        val active = when (decision) {
            is StopDecision.Done -> {
                if (decision.result is SshResult.Success) Timber.i("Tunnel retiré de la liste")
                return@withContext decision.result
            }
            is StopDecision.Teardown -> decision.active
        }

        return@withContext try {
            when (active.config.type) {
                TunnelType.LOCAL_FORWARD -> {
                    stopLocalListener(active.listenerThread, active.serverSocket)
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

            // Un arrêt concurrent (le même tunnel arrêté deux fois, une panne, l'arrêt de tous) a pu
            // retirer l'entrée avant nous : seul celui qui la retire rend la référence, voir [detach].
            val detached = detach(tunnelId, active)
            // L'état part avec l'arrêt, sauf si un autre démarrage du même tunnel est déjà actif, ou
            // en route (son DÉMARRAGE n'est pas à nous).
            synchronized(reconnectLock) {
                if (_activeTunnels.value[tunnelId] == null && !isStartInFlightLocked(tunnelId)) {
                    _tunnelStates.update { it - tunnelId }
                }
            }
            // Sa référence sur la session s'en va avec lui : le dernier tunnel d'une session ouverte
            // pour eux la ferme. Rendue une fois le tunnel hors de la liste, la fermeture de la
            // session ne le retrouve plus.
            if (detached != null) sessionManager.release(detached.sessionId)
            Timber.i("Tunnel arrêté proprement")
            SshResult.Success(Unit)

        } catch (e: Exception) {
            // Un teardown qui echoue (transport deja mort cote REMOTE_FORWARD,
            // typiquement) laisserait sinon l'id marque comme arrete a la main
            // pour toujours, ce qui desactive definitivement la detection de
            // panne sur ce tunnel.
            manuallyStoppedIds.remove(tunnelId)
            Timber.e("Erreur arrêt tunnel (${e::class.java.simpleName})")
            SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_STOP_FAILED_MESSAGE)
        }
    }

    /**
     * Arrête tous les tunnels : ceux qui sont actifs, et les démarrages en route (une carte en
     * DÉMARRAGE n'a pas de tunnel actif, mais elle s'arrête comme les autres).
     */
    fun stopAllTunnels() {
        // Marquer AVANT de fermer : la fermeture des sockets reveille les boucles
        // d'ecoute, qui appellent markUnexpectedStop. Sans ce marquage prealable
        // un arret volontaire serait pris pour une panne et declencherait une
        // reconnexion automatique.
        //
        // Le relevé des tunnels actifs et l'invalidation des démarrages en route sont une seule
        // décision, sous le verrou de l'activation (voir [activateOrUndo]) : un démarrage qui
        // s'active avant le relevé est arrêté avec les autres, un qui s'activerait après s'efface.
        val stopping = synchronized(reconnectLock) {
            val snapshot = _activeTunnels.value.values.toList()
            manuallyStoppedIds.addAll(snapshot.map { it.config.id })
            for ((id, entry) in starts) {
                if (entry.phase == StartPhase.FINISHED) continue
                entry.stopped = true
                manuallyStoppedIds.add(id)
            }
            snapshot
        }
        stopping.forEach { closeTunnelResources(it) }
        // Les entrées sont retirées une à une, chacune seulement si c'est encore celle qu'on a
        // fermée : vider la liste d'un coup effaçait aussi un tunnel enregistré entre l'instantané
        // et le vidage, sans rendre sa référence, et rendait deux fois celle d'un tunnel qu'un
        // autre arrêt venait de retirer (voir [detach]).
        val detached = stopping.mapNotNull { detach(it.config.id, it) }
        // Les états partent tous, sauf ceux d'un tunnel actif qui n'était pas dans l'instantané et
        // ceux d'un démarrage lancé depuis (ceux des démarrages invalidés plus haut partent aussi).
        synchronized(reconnectLock) {
            val stillActive = _activeTunnels.value.keys
            _tunnelStates.update { states ->
                states.filterKeys { it in stillActive || isStartInFlightLocked(it) }
            }
        }
        // Chaque tunnel retiré rend sa référence sur sa session, une fois la liste vidée.
        detached.forEach { sessionManager.release(it.sessionId) }
        // manuallyStoppedIds n'est PAS vide ici : chaque demarrage retire son
        // propre id (markStarting), donc un redemarrage reste possible sans
        // rouvrir la fenetre de course ci-dessus.
    }

    /**
     * Efface l'erreur de [tunnelId] à la demande de l'utilisateur (la croix de la carte, ou
     * l'enregistrement du tunnel depuis son formulaire) : la carte repasse à Arrêté, comme après
     * [stopTunnel] sur un tunnel en ERREUR. Son état est retiré, ce qui laisse le service au premier
     * plan et sa notification s'arrêter quand plus rien ne tourne, et l'arrêt est marqué comme manuel :
     * la reconnexion automatique ne relance plus ce tunnel tant que l'utilisateur ne le redémarre pas
     * ([markStarting] lève l'exclusion).
     *
     * N'agit que si le tunnel est encore en ERREUR au moment de la décision et n'a pas de tunnel actif.
     * Un tunnel relancé depuis l'affichage de la carte (DÉMARRAGE), en RECONNEXION, actif ou inconnu
     * n'est pas touché, et rien n'est marqué : l'appelant n'a pas à dire que le tunnel s'est arrêté. Le
     * contrôle et l'effacement sont une seule décision, sous [reconnectLock] : un démarrage ne peut pas
     * tomber entre les deux, et [stopTunnel], qui arrêterait le tunnel relancé, n'est pas appelé.
     *
     * Un tunnel en ERREUR l'emporte toujours sur un démarrage retenu qui n'est pas terminé (une ligne
     * réservée par la reconnexion ou le démarrage auto, [reserveReconnectStart], [reserveAutoStart]) :
     * ce démarrage est invalidé comme par un arrêt ([stopPendingStartLocked]) et s'effacera sans rien
     * écrire à son [markStarting] ou à son `launch*`, au lieu de bloquer l'effacement. Une réservation
     * oubliée (la reconnexion retenue pour l'utilisateur, voir [holdReconnect]) ne rendrait sinon la
     * croix muette pour toujours.
     *
     * Entre deux tentatives de la reconnexion la carte est en RECONNEXION (ambre, avec le motif), pas en
     * ERREUR : la croix n'existe pas alors, et l'enregistrement du tunnel depuis son formulaire dans ce
     * laps de temps ne touche à rien (rien n'est en ERREUR). La reconnexion relit la ligne en base avant
     * chaque tentative : la modification s'applique à la suivante. Seule la fin de la boucle
     * (tentatives épuisées) laisse la carte en ERREUR, avec sa croix.
     *
     * Ne touche pas aux ressources : un tunnel en ERREUR a déjà fermé les siennes, voir
     * [markUnexpectedStop]. Aucune E/S, aucune suspension.
     *
     * @return vrai si l'erreur a été effacée, faux si le tunnel n'était plus en ERREUR (ou plus seul).
     */
    fun dismissError(tunnelId: String): Boolean {
        val dismissed = synchronized(reconnectLock) {
            val inError = _tunnelStates.value[tunnelId]?.status == TunnelStatus.ERROR
            if (!inError || _activeTunnels.value.containsKey(tunnelId)) {
                false
            } else {
                stopPendingStartLocked(tunnelId)
                manuallyStoppedIds.add(tunnelId)
                _tunnelStates.update { it - tunnelId }
                true
            }
        }
        // Texte fixe : ni libellé, ni id, ni message d'erreur.
        if (dismissed) Timber.i("Erreur de tunnel effacée")
        return dismissed
    }

    // ── Reconnect helpers ─────────────────────────────────────────────────────────

    /**
     * Retourne les tunnels en erreur, ou en RECONNEXION, qui n'ont pas été arrêtés manuellement ni
     * abandonnés après un échec non retentable (voir [holdReconnect]), éligibles à une tentative de
     * reconnexion automatique.
     *
     * Une RECONNEXION n'a pas forcément de boucle derrière elle : une coupure annoncée à personne
     * (annonce perdue, service détruit) la laisse orpheline. Le gestionnaire ne sait pas si une
     * boucle tourne ; c'est la garde de la reconnexion (un tunnel à la fois) qui écarte celui qui en a
     * une.
     */
    fun getTunnelsForReconnect(): List<TunnelConfig> {
        return _tunnelStates.value
            .filter { (id, state) ->
                (state.status == TunnelStatus.ERROR || state.status == TunnelStatus.RECONNECTING) &&
                    !isReconnectBlocked(id)
            }
            .map { it.value.config }
    }

    /**
     * Le tunnel [tunnelId] s'il vient de se couper et attend une reconnexion ([isAwaitingReconnect]) :
     * en ERREUR, ou déjà en RECONNEXION (la coupure l'y a mis directement, voir [markUnexpectedStop]).
     * Null si arrêté à la main, retenu, relancé, plus connu.
     */
    fun getTunnelForCut(tunnelId: String): TunnelConfig? {
        val state = _tunnelStates.value[tunnelId] ?: return null
        return state.config.takeIf { isAwaitingReconnect(tunnelId) }
    }

    /**
     * Le tunnel [tunnelId] s il est en ERREUR et éligible à la reconnexion automatique (voir
     * [getTunnelsForReconnect]), null sinon : arrêté à la main, retenu, relancé, plus connu.
     */
    fun getTunnelForReconnect(tunnelId: String): TunnelConfig? {
        val state = _tunnelStates.value[tunnelId] ?: return null
        return state.config.takeIf { state.status == TunnelStatus.ERROR && !isReconnectBlocked(tunnelId) }
    }

    /**
     * L'instance de [tunnelId] enregistrée en dernier ([ActiveTunnel.instanceId]), même si elle est
     * déjà coupée ; null si le tunnel n'a pas démarré depuis le lancement de l'app. La
     * reconnexion la note quand elle vient de démarrer un tunnel, pour reconnaître plus tard une
     * coupure de CE démarrage et non d'un démarrage de l'utilisateur. Le tunnel actif ne suffisait
     * pas : un tunnel coupé avant que le démarrage rende la main n'est déjà plus dans la liste.
     */
    fun lastInstanceId(tunnelId: String): Long? = lastInstanceIds[tunnelId]

    /**
     * Abandonne la reconnexion automatique de [tunnelId] : il reste en ERREUR avec
     * [errorMessage], et aucun événement réseau ne le relancera tant que l'utilisateur ne l'a pas
     * redémarré (`markStarting` lève l'exclusion).
     *
     * Sans effet si le tunnel n'attend plus de reconnexion (arrêté ou redémarré entre-temps) :
     * on ne marque jamais en erreur un tunnel que l'utilisateur vient de relancer.
     *
     * Renvoie vrai si le tunnel a bien été retenu, faux s'il n'attendait plus de reconnexion : celui
     * qui veut prévenir l'utilisateur ne le fait que dans le premier cas.
     *
     * Ne termine aucune réservation : il ne sait pas à qui est celle du tunnel (la reconnexion, ou le
     * démarrage auto qui a réservé après elle), et en terminer une qui n'est pas la sienne rendrait
     * un démarrage invisible à un arrêt ou à l'effacement de la carte. C'est l'appelant qui rend la
     * sienne, par identité de ligne ([releaseReservation]).
     */
    fun holdReconnect(tunnelId: String, errorMessage: String): Boolean {
        // One decision under the lock of markStarting: the check, the exclusion and the new
        // state cannot straddle a start that the user triggers in between.
        synchronized(reconnectLock) {
            if (!isAwaitingReconnect(tunnelId)) return false
            reconnectHeldIds.add(tunnelId)
            updateTunnelStatus(tunnelId, TunnelStatus.ERROR, errorMessage)
            return true
        }
    }

    /** Vrai si [tunnelId] est retenu hors de la reconnexion par [holdReconnect] (ou un démarrage annulé) et ne l'a pas été relancé depuis. */
    fun isReconnectHeld(tunnelId: String): Boolean = tunnelId in reconnectHeldIds

    /**
     * Le démarrage de [config] a été annulé en route : la coroutine qui le menait a pris fin (écran
     * quitté, ViewModel détruit) avant que le tunnel soit actif ou en erreur. Le use case partagé
     * ne pose pas d'ERREUR sur une annulation : sans cet appel la carte resterait en DÉMARRAGE pour
     * toujours, sans rien en cours pour la faire évoluer.
     *
     * Le tunnel passe en ERREUR avec [message]. Avec [hold] il est exclu de la reconnexion
     * automatique jusqu'à un redémarrage : un démarrage voulu par l'utilisateur puis abandonné ne
     * doit pas rouvrir, au prochain événement réseau, une demande de clé sur un autre écran.
     *
     * Le démarrage annulé ne touche que le sien : l'état doit être DÉMARRAGE et le DÉMARRAGE doit être
     * celui de [config] (voir [ownsStartingLocked]). Un DÉMARRAGE posé entre-temps par un autre
     * démarrage (l'utilisateur a arrêté puis relancé le tunnel) n'est pas modifié ; pas plus qu'un
     * tunnel actif, arrêté à la main ou déjà en erreur. Un tunnel arrêté pendant son démarrage n'a
     * plus d'état : l'arrêt l'a retiré, et l'annulation qui le suit (celle de l'écran qui a vu
     * l'arrêt) n'écrit rien. Ne suspend pas : sûr à appeler depuis un `catch` d'annulation.
     *
     * Le démarrage est terminé dans tous les cas : il n'ira pas plus loin.
     */
    fun markStartCancelled(config: TunnelConfig, message: String, hold: Boolean) {
        synchronized(reconnectLock) {
            val owns = ownsStartingLocked(config.id, config)
            finishStartLocked(config)
            val state = _tunnelStates.value[config.id] ?: return
            if (state.status != TunnelStatus.STARTING || !owns) return
            if (hold) reconnectHeldIds.add(config.id)
            updateTunnelStatus(config.id, TunnelStatus.ERROR, message)
        }
    }

    /**
     * Le DÉMARRAGE que montre la carte de [tunnelId] est-il celui du démarrage de [config] ? Sous
     * [reconnectLock].
     *
     * Le démarrage retenu fait foi, quand il y en a un : [config] doit être lui. L'instance que porte
     * l'état ne dit rien de sûr : deux lignes égales par valeur (le démarrage auto et le raccourci
     * lisent chacun la ligne en base) donnent deux DÉMARRAGE égaux, et le `StateFlow` garde alors
     * l'instance de l'ancien. Comparer l'état à [config] fermait la carte à tout le monde dans ce
     * cas, le retenu y compris, et la laissait en DÉMARRAGE pour toujours. Sans démarrage retenu
     * (jamais annoncé), on retombe sur l'instance de l'état.
     */
    private fun ownsStartingLocked(tunnelId: String, config: TunnelConfig): Boolean {
        val retained = starts[tunnelId]
        if (retained != null) return retained.config === config
        return _tunnelStates.value[tunnelId]?.config === config
    }

    /**
     * Une reconnexion automatique de [tunnelId] a été interrompue en route (le scope qui observe le
     * réseau prend fin, le service s'arrête). Le tunnel repasse en ERREUR avec [message], et reste
     * candidat : une reconnexion coupée reprend au prochain événement réseau.
     *
     * Ne modifie que l'état de la reconnexion : RECONNEXION (posé par elle), ou DÉMARRAGE posé par
     * sa tentative en cours, qui porte la ligne [attempt] (null entre deux tentatives). Un
     * DÉMARRAGE d'une autre origine, typiquement un tunnel que l'utilisateur vient de relancer, a
     * une autre instance et est laissé tel quel. Ne suspend pas.
     *
     * Le démarrage de [attempt] est terminé. Entre deux tentatives ([attempt] null) aucune réservation
     * n'est terminée : celle du tunnel peut être celle du démarrage auto, voir [holdReconnect]. La
     * reconnexion rend la sienne par [releaseReservation].
     */
    fun markReconnectInterrupted(tunnelId: String, attempt: TunnelConfig?, message: String) {
        synchronized(reconnectLock) {
            // Same rule as markStartCancelled: the retained start owns the DÉMARRAGE.
            val ownsStarting = attempt != null && ownsStartingLocked(tunnelId, attempt)
            if (attempt != null) finishStartLocked(attempt)
            val state = _tunnelStates.value[tunnelId] ?: return
            val ours = state.status == TunnelStatus.RECONNECTING ||
                (state.status == TunnelStatus.STARTING && ownsStarting)
            if (!ours) return
            updateTunnelStatus(tunnelId, TunnelStatus.ERROR, message)
        }
    }

    /** Termine le démarrage de [config] s'il est toujours le démarrage retenu de son tunnel. Sous [reconnectLock]. */
    private fun finishStartLocked(config: TunnelConfig) {
        val entry = starts[config.id] ?: return
        if (entry.config === config) entry.phase = StartPhase.FINISHED
    }

    /**
     * Rend la réservation de [config] ([reserveReconnectStart], [reserveAutoStart]) à celui qui sort
     * sans l'avoir annoncée ([markStarting]) : une exception, une annulation, une retenue. Sans cela
     * elle resterait un démarrage en route pour toujours, tant qu'aucun autre démarrage ne la remplace.
     * Idempotente et sans effet sur un démarrage annoncé ou terminé, sur une autre ligne que [config]
     * (comparée par identité, comme le démarrage retenu : celui qui a réservé après a remplacé la
     * réservation de l'autre, et ne doit pas la perdre), et sur une réservation qu'un arrêt a
     * rattrapée (son [markStarting] doit la lire comme telle, et s'effacer). Ne suspend pas : sûre
     * dans un `finally`.
     */
    fun releaseReservation(config: TunnelConfig) {
        synchronized(reconnectLock) {
            val entry = starts[config.id] ?: return
            if (entry.config === config && entry.phase == StartPhase.RESERVED && !entry.stopped) {
                entry.phase = StartPhase.FINISHED
            }
        }
    }

    /**
     * Applique [holdReconnect] si [result] est un échec non retentable (voir [isNonRetryable] :
     * authentification, clé illisible, clé d'hôte qui ne correspond plus). À appeler avec le
     * résultat de chaque démarrage de tunnel (reconnexion, démarrage automatique, démarrage
     * manuel), pour que l'annulation d'une demande de clé ou une clé d'hôte changée ne soit pas
     * suivie d'une nouvelle tentative au prochain événement réseau. Renvoie vrai si le résultat
     * est un échec non retentable.
     */
    fun holdReconnectIfNonRetryable(tunnelId: String, result: SshResult<*>): Boolean {
        if (result !is SshResult.Error || !result.code.isNonRetryable()) return false
        holdReconnect(tunnelId, result.message)
        return true
    }

    /**
     * Vrai tant que [tunnelId] attend une reconnexion automatique : son etat est ERREUR ou
     * RECONNEXION et personne ne l'a arrete a la main depuis.
     *
     * La boucle de reconnexion l'interroge avant chaque tentative. Un arret manuel retire
     * l'etat (l'id passe en plus dans [manuallyStoppedIds]) ; un redemarrage manuel le remet en
     * DEMARRAGE puis ACTIF. Dans les deux cas la reconnexion doit s'effacer : `markStarting`
     * retire l'id des arrets manuels, donc une tentative lancee apres un arret ressusciterait le
     * tunnel que l'utilisateur vient de fermer.
     */
    fun isAwaitingReconnect(tunnelId: String): Boolean {
        val state = _tunnelStates.value[tunnelId] ?: return false
        return !isReconnectBlocked(tunnelId) &&
            (state.status == TunnelStatus.ERROR || state.status == TunnelStatus.RECONNECTING)
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

    /**
     * Enregistre un tunnel actif sans ressources, comme apres un demarrage reussi. [serverSocket]
     * donne au tunnel (local) une socket dont le test controle la fermeture.
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectActiveForTest(config: TunnelConfig, sessionId: String, serverSocket: ServerSocket? = null): Long {
        val instanceId = instanceCounter.incrementAndGet()
        synchronized(reconnectLock) {
            // Comme une activation : le démarrage de cette ligne, s'il y en a un, est terminé.
            finishStartLocked(config)
            lastInstanceIds[config.id] = instanceId
            _activeTunnels.update {
                it + (config.id to ActiveTunnel(
                    config = config,
                    sessionId = sessionId,
                    instanceId = instanceId,
                    startedAtMs = clock(),
                    serverSocket = serverSocket,
                ))
            }
            _tunnelStates.update { it + (config.id to TunnelState(config, TunnelStatus.ACTIVE)) }
        }
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

    /** Le port local est-il déjà pris ? Voir [isLocalPortTaken]. */
    private fun isPortInUse(port: Int): Boolean = isLocalPortTaken(port)
}
