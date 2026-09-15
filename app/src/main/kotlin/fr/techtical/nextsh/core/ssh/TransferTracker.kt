// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.net.Uri
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
 */
data class TransferRequest(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val remotePath: String,
    val localUri: Uri,
    val direction: TransferDirection,
    val fileSize: Long,
    val displayName: String,
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
class TransferTracker @Inject constructor() {

    private val _transfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())

    /** Flux observable des transferts en cours et terminés. */
    val transfers: StateFlow<Map<String, TransferState>> = _transfers.asStateFlow()

    // ── File d'attente ────────────────────────────────────────────────────────────

    /**
     * Enregistre une nouvelle demande de transfert avec le statut [TransferStatus.QUEUED].
     * Sans effet si un transfert avec le même [TransferRequest.id] existe déjà.
     */
    fun enqueue(request: TransferRequest) {
        _transfers.update { current ->
            if (current.containsKey(request.id)) {
                Timber.w("TransferTracker: transfert ${request.id} déjà enregistré")
                return@update current
            }
            Timber.d("TransferTracker: mise en file ${request.id} (${request.displayName})")
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
            Timber.i("TransferTracker: transfert $id terminé (${state.request.displayName})")
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
            Timber.w("TransferTracker: transfert $id échoué : $error")
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
            Timber.i("TransferTracker: transfert $id annulé")
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

    // ── Interne ───────────────────────────────────────────────────────────────────

    private inline fun updateState(id: String, crossinline transform: (TransferState) -> TransferState) {
        _transfers.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to transform(existing))
        }
    }
}
