// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supprime un document créé par le sélecteur de fichiers (SAF).
 *
 * `CreateDocument` crée le fichier (vide) dès que l'utilisateur choisit l'emplacement : si
 * l'écriture échoue ensuite, ou si l'export est interrompu, ce document reste. Cette abstraction
 * existe pour pouvoir le nettoyer et pour que le nettoyage soit testable sans Android.
 *
 * Aucune méthode ne lève d'exception, et aucune ne journalise l'URI (il peut citer un nom de fichier).
 */
interface DocumentDeleter {
    /**
     * Supprime le document au mieux de ce que permet son fournisseur, sans condition. À réserver
     * aux documents dont on sait que notre propre écriture a déjà altéré le contenu.
     *
     * @return `true` si le document a bien été supprimé, `false` sinon (fournisseur sans support
     * de la suppression, document déjà absent, droits insuffisants).
     */
    fun delete(uri: Uri): Boolean

    /**
     * Ne supprime le document que si sa taille est connue ET vaut 0 : un document dont la taille
     * est inconnue ou non nulle peut être un fichier existant de l'utilisateur, on n'y touche pas.
     *
     * @return `true` si le document était vide et a été supprimé, `false` sinon.
     */
    fun deleteIfEmpty(uri: Uri): Boolean
}

/** Implémentation SAF : [DocumentsContract.deleteDocument] et colonne `COLUMN_SIZE`. */
@Singleton
class SafDocumentDeleter @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentDeleter {

    override fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        // Au mieux : un fournisseur peut refuser, ne plus exister ou ne pas gérer la suppression.
        Timber.w("Document cleanup failed: %s", e.javaClass.simpleName)
        false
    }

    override fun deleteIfEmpty(uri: Uri): Boolean {
        val size = try {
            sizeOf(uri)
        } catch (e: Exception) {
            Timber.w("Document size query failed: %s", e.javaClass.simpleName)
            null
        }
        // Taille inconnue (colonne absente ou nulle, requête impossible) : ne rien supprimer.
        return size == 0L && delete(uri)
    }

    /** Taille déclarée par le fournisseur, ou `null` si elle est inconnue. */
    private fun sizeOf(uri: Uri): Long? =
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
}
