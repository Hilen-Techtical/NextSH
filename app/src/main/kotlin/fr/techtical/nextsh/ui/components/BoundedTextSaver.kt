// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.runtime.saveable.Saver
import fr.techtical.nextsh.shared.core.backup.BackupLimits

/**
 * Bornes des textes saisis qui passent par `rememberSaveable`.
 *
 * Depuis Android 7, un état sauvegardé trop gros fait échouer la transaction binder à l'arrêt de
 * l'activité (`TransactionTooLargeException`, levée en `RuntimeException`) : un plantage. Le champ
 * commande d'un snippet accepte une saisie bien au-delà de sa limite avant de la refuser, et un
 * texte peut être collé en bloc. Au-delà de la borne, le texte n'est simplement pas sauvegardé.
 */
internal object SavedTextLimits {
    /** Commande d'un snippet : la limite de la sauvegarde `.nextsh`. */
    const val COMMAND_BYTES: Int = BackupLimits.MAX_COMMAND_BYTES

    /** Libellé, catégorie, nom : 4 octets UTF-8 au plus par caractère accepté. */
    const val LABEL_BYTES: Int = 4 * BackupLimits.MAX_TEXT_LENGTH

    /** Nom de fichier ou de dossier SFTP : la règle refuse au-delà de 255 octets UTF-8. */
    const val SFTP_NAME_BYTES: Int = 4 * 255
}

/**
 * [Saver] d'un texte saisi, qui ne sauvegarde rien (la restauration retombe alors sur la valeur
 * initiale) quand le texte dépasse [maxUtf8Bytes] octets UTF-8.
 *
 * À réserver aux textes non secrets : un secret ne va jamais dans un `Bundle`.
 */
internal fun boundedTextSaver(maxUtf8Bytes: Int): Saver<String, String> = Saver(
    save = { text -> if (exceedsUtf8Bytes(text, maxUtf8Bytes)) null else text },
    restore = { it },
)

/** Vrai si [text] pèse plus de [maxBytes] octets en UTF-8, sans encoder un texte déjà trop long. */
internal fun exceedsUtf8Bytes(text: String, maxBytes: Int): Boolean {
    // Un caractère pèse au moins un octet : plus de caractères que la borne suffit à conclure.
    if (text.length > maxBytes) return true
    return text.toByteArray(Charsets.UTF_8).size > maxBytes
}
