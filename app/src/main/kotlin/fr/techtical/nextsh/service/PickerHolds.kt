// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import fr.techtical.nextsh.shared.util.LogId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Délai de garde d'un maintien : passé ce délai, il est relâché même si personne ne l'a rendu (un
 * sélecteur de fichiers abandonné dans les applications récentes, un retour perdu).
 */
internal const val PICKER_HOLD_TIMEOUT_MS = 10 * 60_000L

/**
 * Les maintiens que [SftpTransferService] tient pendant que l'utilisateur choisit un fichier dans le
 * sélecteur du système.
 *
 * Pourquoi. Le sélecteur est une autre activité : NextSH passe en arrière-plan, et sans service au
 * premier plan Android détruit les sockets de l'app environ six secondes plus tard (voir
 * [TerminalSessionService]). Un explorateur ouvert depuis la liste d'hôtes n'a aucun onglet
 * terminal pour tenir l'app, et le service de transfert ne démarre qu'après le choix : la session
 * SSH mourait donc dans le sélecteur. Un maintien garde le service, donc le processus, au premier
 * plan jusqu'au retour du sélecteur.
 *
 * Ce qu'un maintien tient. Le service au premier plan, tant qu'il en reste un (voir
 * [SftpTransferService]), et une référence sur la session SSH de l'explorateur quand elle existe
 * encore. La seconde couvre l'autre cause de perte : le système peut détruire l'activité pendant
 * le sélecteur, et le ViewModel de l'explorateur, dernier à tenir la session, la rend en
 * disparaissant. Avec la référence du maintien, la session est encore là au retour.
 *
 * Un maintien porte un identifiant, que l'écran garde dans son état sauvegardé : recréé, il rend
 * le bon maintien et jamais celui d'un autre. Chaque maintien est relâché une seule fois, par son
 * propriétaire ([release]), par le délai de garde ([timeoutMs]) ou par [releaseAll] à la fin du
 * service : le premier qui arrive gagne, les suivants ne font rien, et la référence sur la session
 * n'est jamais rendue deux fois.
 *
 * Les appels viennent du fil principal (commandes du service) et du fil du délai de garde : tout est
 * sous le verrou de l'instance.
 *
 * @param scope portée où tournent les délais de garde, celle du service.
 * @param acquireSession prend une référence sur une session SSH, vrai si elle existe encore.
 * @param releaseSession rend une référence prise par [acquireSession].
 * @param onExpired appelé hors du verrou, sur le fil du délai de garde (pas le fil principal), quand
 *   un délai de garde a relâché un maintien : le service remet la décision au fil principal, puis
 *   regarde s'il peut s'arrêter.
 */
internal class PickerHolds(
    private val scope: CoroutineScope,
    private val acquireSession: (String) -> Boolean,
    private val releaseSession: (String) -> Unit,
    private val timeoutMs: Long = PICKER_HOLD_TIMEOUT_MS,
    private val onExpired: () -> Unit = {},
) {

    /** [sessionId] est la session dont ce maintien tient une référence, nulle s'il n'en tient aucune. */
    private class Hold(val sessionId: String?, val guard: Job)

    private val holds = HashMap<String, Hold>()

    /** Nombre de maintiens en cours. */
    val count: Int
        get() = synchronized(this) { holds.size }

    fun isEmpty(): Boolean = count == 0

    /**
     * Prend le maintien [holdId]. [sessionId] est la session de l'explorateur : une référence est
     * prise dessus si elle existe, sinon le maintien tient seulement le premier plan (la session est
     * déjà perdue, l'écran le dira au retour). Sans effet pour un [holdId] déjà tenu : ni deuxième
     * référence, ni délai de garde relancé.
     */
    fun acquire(holdId: String, sessionId: String?) {
        synchronized(this) {
            if (holdId in holds) return
            val held = sessionId?.takeIf { it.isNotBlank() }?.takeIf { acquireSession(it) }
            val guard = scope.launch(start = CoroutineStart.LAZY) {
                delay(timeoutMs)
                expire(holdId)
            }
            holds[holdId] = Hold(held, guard)
            guard.start()
        }
        Timber.i("PickerHolds: hold ${LogId.of(holdId)} taken")
    }

    /**
     * Rend le maintien [holdId], et la référence qu'il tient. Faux s'il n'est plus là (déjà rendu,
     * expiré, ou jamais pris par ce service : il a pu être détruit entre-temps).
     */
    fun release(holdId: String): Boolean {
        val released = synchronized(this) { take(holdId, cancelGuard = true) }
        if (released) Timber.i("PickerHolds: hold ${LogId.of(holdId)} released")
        return released
    }

    /** Rend tous les maintiens et leurs références : le service s'arrête, quelle qu'en soit la raison. */
    fun releaseAll() {
        synchronized(this) {
            holds.keys.toList().forEach { take(it, cancelGuard = true) }
        }
    }

    private fun expire(holdId: String) {
        val expired = synchronized(this) { take(holdId, cancelGuard = false) }
        if (!expired) return
        Timber.w("PickerHolds: hold ${LogId.of(holdId)} expired, released by the guard")
        onExpired()
    }

    /** Retire [holdId] et rend sa référence. À appeler sous le verrou. Faux s'il n'était pas là. */
    private fun take(holdId: String, cancelGuard: Boolean): Boolean {
        val hold = holds.remove(holdId) ?: return false
        if (cancelGuard) hold.guard.cancel()
        hold.sessionId?.let(releaseSession)
        return true
    }
}
