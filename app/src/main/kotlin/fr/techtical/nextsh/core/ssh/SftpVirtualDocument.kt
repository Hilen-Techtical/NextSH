// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber

/**
 * Dit si les drapeaux d'un document portent `FLAG_VIRTUAL_DOCUMENT`.
 *
 * Un document virtuel (un fichier Google Docs de Drive, par exemple) n'a pas de
 * contenu brut : son fournisseur ne le donne qu'exporté, et `openInputStream`
 * lève `FileNotFoundException: File is virtual`. L'envoi ne l'exporte pas, il
 * le refuse en le disant.
 *
 * @param flags valeur de la colonne `COLUMN_FLAGS` du document, ou `null` si la
 *              colonne est absente ou illisible : le document n'est alors pas
 *              tenu pour virtuel.
 */
fun isVirtualDocumentFlags(flags: Int?): Boolean =
    flags != null && (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0

/**
 * Dit si l'URI choisie désigne un document virtuel (voir [isVirtualDocumentFlags]).
 *
 * Seule une URI de document (`DocumentsContract.isDocumentUri`) a la colonne
 * `COLUMN_FLAGS`. Une colonne absente ou nulle, une ligne absente, un curseur nul
 * ou une exception donnent `false` : un envoi qui aurait pu partir n'est jamais
 * refusé sur un doute. Une exception laisse une trace dans le journal, sa classe
 * seule. Seule la requête au `ContentResolver` vit ici, le test du drapeau est
 * dans [isVirtualDocumentFlags].
 */
fun isVirtualDocument(context: Context, uri: Uri): Boolean = try {
    if (DocumentsContract.isDocumentUri(context, uri)) {
        isVirtualDocumentFlags(
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (cursor.moveToFirst() && idx != -1 && !cursor.isNull(idx)) cursor.getInt(idx) else null
            },
        )
    } else {
        false
    }
} catch (e: Exception) {
    Timber.w("SftpVirtualDocument: cannot read the document flags (${errorClass(e)})")
    false
}
