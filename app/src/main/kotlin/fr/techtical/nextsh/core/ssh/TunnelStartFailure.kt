// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelPortProblem
import fr.techtical.nextsh.domain.model.TunnelPortRules
import fr.techtical.nextsh.domain.model.TunnelType
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import java.net.BindException

/**
 * Pourquoi le démarrage d'un tunnel a échoué, pour les cas qui ont leur propre texte : le code du
 * résultat et le texte fixe que la carte traduit (voir [tunnelMessageRes]). Jamais le message de
 * l'exception, ni un port, ni un hôte.
 */
internal data class StartFailure(val code: SshErrorCode, val message: String) {
    companion object {
        /** Aucun motif à donner : « le tunnel n'a pas pu démarrer ». */
        val GENERIC = StartFailure(SshErrorCode.UNKNOWN, TUNNEL_START_FAILED_MESSAGE)

        /** Le port local est pris par une autre application. Rejouable : l'autre application peut le libérer. */
        val PORT_IN_USE = StartFailure(SshErrorCode.PORT_IN_USE, TUNNEL_PORT_IN_USE_MESSAGE)

        /** Le serveur a refusé d'ouvrir le port distant. Rejouable : le serveur garde souvent le port d'une session coupée. */
        val REMOTE_FORWARD_REFUSED = StartFailure(SshErrorCode.UNKNOWN, TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE)

        /** La session SSH a disparu entre la connexion et le démarrage. Rejouable : la reconnexion en ouvre une autre. */
        val SESSION_NOT_FOUND = StartFailure(SshErrorCode.UNKNOWN, TUNNEL_SESSION_NOT_FOUND_MESSAGE)
    }
}

/**
 * Range l'exception qui a fait échouer le démarrage d'un tunnel de type [type]. Pure : ni SSHJ ni
 * réseau, les tests la nourrissent d'exceptions construites à la main.
 *
 * - Local et SOCKS5 : une `BindException`, de l'exception ou d'une de ses causes, est le port local
 *   pris entre le contrôle ([SshTunnelManager]) et la liaison, par une autre application.
 * - Redirection distante : le refus du serveur, voir [isRemoteForwardRefusal].
 * - Tout le reste (délai dépassé, transport perdu, erreur imprévue) : le motif générique.
 *
 * @param clientConnected la connexion SSH est encore établie (`SSHClient.isConnected`), lue au moment
 *   de la classification.
 */
internal fun classifyStartFailure(
    e: Throwable,
    type: TunnelType,
    clientConnected: () -> Boolean,
): StartFailure = when {
    TunnelPortRules.listensLocally(type) && hasBindFailure(e) -> StartFailure.PORT_IN_USE
    type == TunnelType.REMOTE_FORWARD && isRemoteForwardRefusal(e, clientConnected) -> StartFailure.REMOTE_FORWARD_REFUSED
    else -> StartFailure.GENERIC
}

/**
 * [e] est-elle le refus, par le serveur, de la requête `tcpip-forward` d'une redirection distante ?
 *
 * Le protocole ne donne pas la raison du refus (SSH_MSG_REQUEST_FAILURE, sans texte). Ce que SSHJ
 * 0.38.0 fait de la requête (`RemotePortForwarder.bind`, qui attend la réponse pendant le délai du
 * transport), lu sur son bytecode :
 *
 * - **Refus** : `ConnectionImpl.gotGlobalReqResponse(null)` remet à la requête une
 *   `ConnectionException(String)` : la classe `ConnectionException` elle-même, sans cause, avec
 *   `DisconnectReason.UNKNOWN` (le constructeur à message n'en pose pas d'autre). La connexion vit.
 * - **Délai dépassé** : `Promise.retrieve` fait chaîner une `java.util.concurrent.TimeoutException`,
 *   que le chaîneur de `ConnectionException` enveloppe : cause non nulle. Ce n'est pas un refus : le
 *   serveur n'a pas répondu dans le délai, et rien ici ne dit pourquoi (liaison muette ou serveur
 *   lent). Le motif reste générique. À ne pas confondre avec le délai d'ouverture du canal
 *   `direct-tcpip` d'un tunnel local, qui n'arrête pas le tunnel quand la sonde du transport répond
 *   (voir [runLocalForwardListener]).
 * - **Transport perdu pendant la requête** : `ConnectionImpl.notifyError` livre l'erreur du transport
 *   aux requêtes en attente. Le chaîneur enveloppe ce qui n'est pas une `ConnectionException`
 *   (cause non nulle) et garde telle quelle une `ConnectionException` de protocole, qui porte alors
 *   son propre motif (`PROTOCOL_ERROR`, pas `UNKNOWN`). Écrire sur un transport déjà mort lève une
 *   `TransportException`, pas une `ConnectionException`. La connexion n'est plus établie.
 *
 * Le refus est donc reconnu par sa forme : classe exacte `ConnectionException` (`OpenFailException`
 * en hérite, jamais levée ici), sans cause, motif `UNKNOWN`, et une connexion encore établie (un
 * refus suivi d'une coupure ne se distingue pas d'une coupure : texte générique, ce qui est sûr).
 * Le texte du message n'est PAS lu : il vient de SSHJ, en anglais, et peut citer un nom de requête.
 * Reconnaître ce texte ("Global request [...] failed") n'ajouterait rien à cette forme et casserait
 * à la première reformulation de la bibliothèque.
 */
internal fun isRemoteForwardRefusal(e: Throwable, clientConnected: () -> Boolean): Boolean =
    e.javaClass == ConnectionException::class.java &&
        e.cause == null &&
        (e as ConnectionException).disconnectReason == DisconnectReason.UNKNOWN &&
        clientConnected()

/**
 * Le port local est-il déjà pris ? Seule une `BindException` (de l'exception ou d'une de ses causes)
 * le dit, comme pour [classifyStartFailure]. Rien d'autre ne sort d'ici : une autre `IOException`
 * (réseau, système) ou une exception d'exécution (un refus de la politique de sécurité, un port hors
 * plage qui aurait échappé au contrôle de [TunnelPortRules]) ne dit pas que le port est pris, et le
 * dire montrerait un faux « port déjà utilisé ». Elle rend faux : le démarrage qui suit lie la
 * socket pour de bon, échoue de son côté, et le motif est celui de [classifyStartFailure].
 *
 * @param probe ouvre le port puis le referme ; remplaçable par les tests.
 */
internal fun isLocalPortTaken(
    port: Int,
    probe: (Int) -> Unit = { java.net.ServerSocket(it).close() },
): Boolean = try {
    probe(port)
    false
} catch (e: java.io.IOException) {
    hasBindFailure(e)
} catch (e: RuntimeException) {
    false
}

/** [e], ou une de ses causes, est une `BindException`. Profondeur bornée : une chaîne de causes peut boucler. */
private fun hasBindFailure(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is BindException) return true
        cause = cause.cause
    }
    return false
}

/**
 * Le texte fixe de ce que cloche dans les ports d'un tunnel, traduit à l'affichage par
 * [tunnelMessageRes]. Exhaustif : un nouveau motif ne compile pas tant qu'on n'a pas dit ce qu'il affiche.
 */
internal fun TunnelPortProblem.tunnelMessage(): String = when (this) {
    TunnelPortProblem.LOCAL_OUT_OF_RANGE -> TUNNEL_LOCAL_PORT_INVALID_MESSAGE
    TunnelPortProblem.LOCAL_RESERVED -> TUNNEL_LOCAL_PORT_RESERVED_MESSAGE
    TunnelPortProblem.REMOTE_OUT_OF_RANGE -> TUNNEL_REMOTE_PORT_INVALID_MESSAGE
}
