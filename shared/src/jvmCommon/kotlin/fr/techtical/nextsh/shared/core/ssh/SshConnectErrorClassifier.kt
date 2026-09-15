// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Classification pure (aucune I/O, aucune suspension) d'une [IOException]
 * survenue pendant l'établissement d'une connexion SSH (`connect()` + auth +
 * ouverture de session) : TABLE UNIQUE partagée par Android et Desktop, pour
 * qu'un même type d'exception JDK produise le MÊME [SshErrorCode] des deux
 * côtés.
 *
 * Historique : Desktop (`DesktopSshSessionManager`, `VaultSshClientFactory`)
 * codait TOUT échec réseau en `SshErrorCode.HOST_UNREACHABLE`, y compris les
 * timeouts, alors qu'Android (`SshSessionManager`) distinguait déjà `TIMEOUT`,
 * asymétrie corrigée en centralisant la table ici. Le mapping message
 * (compose-resources côté Desktop, `Context.getString` côté Android) reste
 * propre à chaque plateforme : voir `desktop/.../core/ssh/SshConnectErrorMapper.kt`
 * et `app/.../core/ssh/SshConnectErrorMapper.kt`, tous deux réduits à de purs
 * résolveurs de string consommant [SshConnectErrorClassification].
 *
 * Table :
 *  - [SocketTimeoutException] → [SshErrorCode.TIMEOUT]
 *  - [ConnectException]       → [SshErrorCode.HOST_UNREACHABLE] (« le serveur
 *    n'a pas répondu », pas de code plus précis dans l'enum pour un refus de
 *    connexion TCP)
 *  - [UnknownHostException]   → [SshErrorCode.UNKNOWN] : reproduit le
 *    comportement Android pré-existant (ce cas ne matchait aucune branche
 *    explicite et retombait dans le catch générique `UNKNOWN`). Aucune valeur
 *    d'enum dédiée type `HOST_NOT_FOUND` n'existe : [SshErrorCode] est un
 *    modèle domaine potentiellement synchronisé/sérialisé (voir CLAUDE.md) ;
 *    on ne l'étend pas pour ce seul cas.
 *  - toute autre [IOException] → [genericErrorCode], fourni par l'appelant :
 *    CHAQUE plateforme garde son comportement pré-existant pour ce panier
 *    fourre-tout : `HOST_UNREACHABLE` côté Desktop (seul code utilisé avant ce
 *    correctif), `UNKNOWN` côté Android (son catch-all historique). Ce n'est
 *    pas un oubli d'unification : c'est le comportement demandé pour ce cas
 *    résiduel, les deux plateformes n'ayant jamais distingué ce panier plus
 *    finement.
 *
 * Note : le catch englobe toute la tentative de connexion (`connect()` + auth
 * + ouverture de session), pas uniquement l'établissement TCP. Une
 * [SocketTimeoutException] levée ailleurs que sur `connect()` (en théorie rare
 * sur ce chemin) serait donc elle aussi rapportée comme « délai de connexion
 * dépassé », limitation acceptée, cohérente avec le comportement pré-existant
 * des deux plateformes.
 */
sealed class SshConnectErrorClassification(val errorCode: SshErrorCode) {
    /** [SocketTimeoutException] : délai de connexion TCP dépassé. */
    data class TimeoutConnect(val timeoutSeconds: Int) :
        SshConnectErrorClassification(SshErrorCode.TIMEOUT)

    /** [ConnectException] : connexion TCP refusée / serveur injoignable. */
    data object ServerSilent : SshConnectErrorClassification(SshErrorCode.HOST_UNREACHABLE)

    /** [UnknownHostException] : résolution DNS échouée. */
    data object HostNotFound : SshConnectErrorClassification(SshErrorCode.UNKNOWN)

    /** Tout autre [IOException] : panier fourre-tout, code fourni par l'appelant. */
    data class Generic(val detail: String?, val genericErrorCode: SshErrorCode) :
        SshConnectErrorClassification(genericErrorCode)
}

/**
 * Classifie [e] par type concret d'exception JDK.
 *
 * @param connectTimeoutMs Délai (ms) configuré pour `SSHClient.connectTimeout`,
 *   utilisé uniquement pour reporter le nombre de secondes dans
 *   [SshConnectErrorClassification.TimeoutConnect] (arrondi au supérieur,
 *   jamais 0).
 * @param genericErrorCode Code renvoyé pour [SshConnectErrorClassification.Generic]
 *   (toute [IOException] qui n'est ni un timeout, ni un refus de connexion, ni
 *   une résolution DNS échouée). Explicite plutôt que par défaut : chaque
 *   plateforme doit assumer son choix (Desktop → `HOST_UNREACHABLE`,
 *   Android → `UNKNOWN`) plutôt que d'hériter silencieusement d'une valeur.
 */
fun classifySshConnectError(
    e: IOException,
    connectTimeoutMs: Int,
    genericErrorCode: SshErrorCode,
): SshConnectErrorClassification = when (e) {
    is SocketTimeoutException -> SshConnectErrorClassification.TimeoutConnect(
        timeoutSeconds = ((connectTimeoutMs + 999) / 1000).coerceAtLeast(1),
    )
    is ConnectException -> SshConnectErrorClassification.ServerSilent
    is UnknownHostException -> SshConnectErrorClassification.HostNotFound
    else -> SshConnectErrorClassification.Generic(
        detail = e.message?.takeIf { it.isNotBlank() },
        genericErrorCode = genericErrorCode,
    )
}
