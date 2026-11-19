// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.net.Uri
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.shared.util.LogId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ── Direction ─────────────────────────────────────────────────────────────────

enum class TransferDirection {
    DOWNLOAD,
    UPLOAD,
}

// ── Transfer status ───────────────────────────────────────────────────────────

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
}

// ── Transfer request: immutable parameters ───────────────────────────────────

/**
 * Paramètres immuables d'une demande de transfert.
 *
 * @param id          identifiant unique du transfert.
 * @param sessionId   identifiant de la session SSH.
 * @param remotePath  chemin absolu du fichier sur le serveur distant.
 * @param localUri    URI SAF (content://) du fichier local.
 * @param direction   sens du transfert (DOWNLOAD ou UPLOAD).
 * @param fileSize    taille du fichier en octets (0 si inconnue).
 * @param displayName nom de fichier affichable (tronqué à 20 caractères par le service).
 * @param replaceExisting vrai seulement quand l'utilisateur a confirmé le remplacement d'un fichier
 *   de même nom (envoi uniquement). Le fichier est alors remplacé sans être vidé avant la fin de
 *   l'envoi (fichier temporaire caché, puis renommage, voir [SftpManager.writeFile]). Faux par
 *   défaut : un nom déjà pris est alors refusé par le serveur et rien n'est remplacé.
 */
data class TransferRequest(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val remotePath: String,
    val localUri: Uri,
    val direction: TransferDirection,
    val fileSize: Long,
    val displayName: String,
    val replaceExisting: Boolean = false,
)

// ── Transfer state: mutable runtime state ────────────────────────────────────

/**
 * État runtime d'un transfert en cours ou terminé.
 */
data class TransferState(
    val request: TransferRequest,
    val status: TransferStatus = TransferStatus.QUEUED,
    val bytesTransferred: Long = 0L,
    val errorMessage: String? = null,
) {
    /** Progression de 0.0 à 1.0, ou null si la taille est inconnue. */
    val progress: Float?
        get() = if (request.fileSize > 0L) {
            (bytesTransferred.toFloat() / request.fileSize.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    /** Pourcentage entier de 0 à 100, ou null si la taille est inconnue. */
    val progressPercent: Int?
        get() = progress?.let { (it * 100).toInt() }
}

// ── TransferTracker ───────────────────────────────────────────────────────────

/**
 * Singleton partagé entre [SftpTransferService] et [SftpBrowserViewModel]
 * pour le suivi de la progression des transferts SFTP.
 *
 * Thread-safe : les mises à jour passent toutes par [updateState] qui utilise
 * [MutableStateFlow.update] (compare-and-swap atomique).
 */
@Singleton
class TransferTracker @Inject constructor(
    /** The wipe of the vault: while the app is closed no transfer is accepted, see [enqueue]. */
    private val wipeGate: VaultWipeGate,
) {
    /** Without the wipe gate of the app (tests): a gate that is never closed. Not an injection point. */
    constructor() : this(VaultWipeGate())


    private val _transfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())

    /** Flux observable des transferts en cours et terminés. */
    val transfers: StateFlow<Map<String, TransferState>> = _transfers.asStateFlow()

    // ── File d'attente ────────────────────────────────────────────────────────────

    /**
     * Enregistre une nouvelle demande de transfert avec le statut [TransferStatus.QUEUED].
     * Sans effet si un transfert avec le même [TransferRequest.id] existe déjà.
     */
    fun enqueue(request: TransferRequest) {
        // The wipe of the vault has the app closed: a transfer asked for now (a screen that is still up)
        // would run on a session that is gone, and would write its file name into the list just emptied.
        if (wipeGate.isClosed()) {
            Timber.w("TransferTracker: transfer ${LogId.of(request.id)} refused, the app is being wiped")
            return
        }
        _transfers.update { current ->
            if (current.containsKey(request.id)) {
                Timber.w("TransferTracker: transfer ${LogId.of(request.id)} already registered")
                return@update current
            }
            Timber.d("TransferTracker: queued ${LogId.of(request.id)} (${request.direction}, ${request.fileSize} bytes)")
            current + (request.id to TransferState(request = request))
        }
    }

    // ── Mises à jour de progression ───────────────────────────────────────────────

    /**
     * Met à jour la progression d'un transfert.
     * Passe automatiquement le statut à [TransferStatus.IN_PROGRESS].
     */
    fun updateProgress(id: String, bytes: Long, total: Long) {
        updateState(id) { state ->
            // A late callback of the copy loop must not bring back to life a transfer that was cancelled
            // (the cancellation of everything, by the wipe of the vault, comes first and the loop ends after).
            if (state.status == TransferStatus.CANCELLED) return@updateState state
            state.copy(
                status           = TransferStatus.IN_PROGRESS,
                bytesTransferred = bytes,
            )
        }
    }

    /**
     * Marque un transfert comme terminé avec succès.
     *
     * Sans effet sur un transfert déjà annulé : la boucle de copie s'arrête à
     * la demande d'annulation, et la marquer terminée annoncerait un fichier
     * complet alors qu'il est tronqué.
     */
    fun complete(id: String) {
        updateState(id) { state ->
            if (state.status == TransferStatus.CANCELLED) return@updateState state
            Timber.i("TransferTracker: transfer ${LogId.of(id)} completed (${state.request.direction})")
            state.copy(
                status           = TransferStatus.COMPLETED,
                bytesTransferred = state.request.fileSize,
            )
        }
    }

    /**
     * Marque un transfert comme échoué avec un message d'erreur.
     */
    fun fail(id: String, error: String) {
        updateState(id) { state ->
            // [error] is the text shown to the user and can quote a path: never logged.
            Timber.w("TransferTracker: transfer ${LogId.of(id)} failed (${state.request.direction})")
            state.copy(
                status       = TransferStatus.FAILED,
                errorMessage = error,
            )
        }
    }

    /**
     * Marque un transfert comme annulé.
     *
     * Sans effet sur un transfert déjà terminé ou échoué : la notification
     * n'étant rafraîchie qu'une fois par demi-seconde, son bouton Annuler reste
     * cliquable un court instant après la fin. Un fichier correctement
     * transféré serait alors affiché comme annulé.
     */
    fun cancel(id: String) {
        updateState(id) { state ->
            if (state.status == TransferStatus.COMPLETED || state.status == TransferStatus.FAILED) {
                return@updateState state
            }
            Timber.i("TransferTracker: transfer ${LogId.of(id)} cancelled")
            state.copy(status = TransferStatus.CANCELLED)
        }
    }

    /**
     * Supprime un transfert de la map (nettoyage après affichage du résultat).
     */
    fun remove(id: String) {
        _transfers.update { current ->
            current - id
        }
    }

    /**
     * L'utilisateur a été prévenu de l'issue de [id] : le tracker l'oublie.
     *
     * Le tracker vit autant que le processus et garde les transferts terminés. Sans acquittement,
     * chaque nouvel explorateur retrouvait les échecs déjà annoncés (de toutes les sessions) et les
     * annonçait de nouveau. L'explorateur qui annonce un échec l'acquitte donc, une seule fois pour
     * tous les explorateurs de la session.
     *
     * Sans effet sur un transfert en attente ou en cours : il n'est pas fini, et le retirer
     * ferait perdre sa file au service.
     */
    fun acknowledge(id: String) {
        _transfers.update { current ->
            val state = current[id] ?: return@update current
            if (state.status == TransferStatus.QUEUED || state.status == TransferStatus.IN_PROGRESS) {
                return@update current
            }
            current - id
        }
    }

    // ── Accès à la file d'attente ─────────────────────────────────────────────────

    /**
     * Retourne la liste des transferts en attente (statut [TransferStatus.QUEUED]).
     */
    fun getQueued(): List<TransferRequest> =
        _transfers.value.values
            .filter { it.status == TransferStatus.QUEUED }
            .map { it.request }

    /**
     * Retire atomiquement le prochain transfert en attente et le passe en
     * [TransferStatus.IN_PROGRESS].
     *
     * L'atomicité est indispensable : chaque demande de transfert redémarre le
     * service, donc plusieurs vidages de file pouvaient se chevaucher et
     * transférer deux fois la même requête. Le compare-and-swap de
     * [MutableStateFlow.update] garantit qu'un seul appelant obtient une
     * requête donnée.
     *
     * @return la requête réservée, ou null si la file est vide.
     */
    fun dequeue(): TransferRequest? {
        var claimed: TransferRequest? = null
        _transfers.update { current ->
            val next = current.values.firstOrNull { it.status == TransferStatus.QUEUED }
            if (next == null) {
                claimed = null
                return@update current
            }
            claimed = next.request
            current + (next.request.id to next.copy(status = TransferStatus.IN_PROGRESS))
        }
        return claimed
    }

    /** True si l'utilisateur ou le système a demandé l'annulation de ce transfert. */
    fun isCancelled(id: String): Boolean =
        _transfers.value[id]?.status == TransferStatus.CANCELLED

    /**
     * Marque en échec tous les transferts encore en attente ou en cours.
     *
     * Utilisé quand le système reprend la main sur le service au premier plan
     * (quota de durée atteint) : il reste quelques secondes avant l'arrêt
     * forcé, la file ne sera pas traitée et l'utilisateur doit le savoir.
     */
    fun failAllUnfinished(error: String) {
        _transfers.update { current ->
            current.mapValues { (_, state) ->
                if (state.status == TransferStatus.QUEUED || state.status == TransferStatus.IN_PROGRESS) {
                    state.copy(status = TransferStatus.FAILED, errorMessage = error)
                } else {
                    state
                }
            }
        }
    }

    /**
     * Marks every transfer that is not over (waiting or running) as cancelled, in one update. The first
     * half of the cancellation of everything ("Wipe the vault"): the service then cancels the jobs of
     * the copies, [cancel] alone does not stop a copy.
     */
    fun cancelAll() {
        _transfers.update { current ->
            current.mapValues { (_, state) ->
                if (state.status == TransferStatus.QUEUED || state.status == TransferStatus.IN_PROGRESS) {
                    state.copy(status = TransferStatus.CANCELLED)
                } else {
                    state
                }
            }
        }
    }

    private val _copying = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The ids of the transfers whose copy is really running (the job of the service has not ended), which
     * is not what [transfers] says: a cancelled transfer is CANCELLED at once, its copy ends a moment
     * later. The wipe of the vault waits for this to be empty before it closes the sessions.
     */
    val copying: StateFlow<Set<String>> = _copying.asStateFlow()

    /** The copy of [id] starts: the service calls it before it launches the job. */
    fun copyStarted(id: String) {
        _copying.update { it + id }
    }

    /** The copy of [id] is over (the job ended, whatever the way). Idempotent. */
    fun copyEnded(id: String) {
        _copying.update { it - id }
    }

    /**
     * Forgets every transfer: the list holds file names and remote paths, which are private data. A late
     * write for a forgotten transfer ([updateProgress], [complete], [fail], [cancel]) finds nothing and
     * changes nothing.
     */
    fun clear() {
        _transfers.value = emptyMap()
    }

    // ── Interne ───────────────────────────────────────────────────────────────────

    private inline fun updateState(id: String, crossinline transform: (TransferState) -> TransferState) {
        _transfers.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to transform(existing))
        }
    }
}
