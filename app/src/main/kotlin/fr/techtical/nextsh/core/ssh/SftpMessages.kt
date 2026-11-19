// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.annotation.StringRes
import fr.techtical.nextsh.R

// The texts [SftpManager] and the SFTP use cases put in an `SshResult.Error`. They have no `Context`,
// so the result carries a plain string, and these French literals are the keys of [sftpMessageRes],
// which the browser calls at display time to say them in the language of the user.
//
// None of them carries a path, a file size, a session identifier or the message of an exception: the
// message of an exception comes from SSHJ or the system, in English, and can quote a path or a host.
// The class of the exception is in the log, and the screen says what could not be done.
//
// Every value is different from the others, the table of [sftpMessageRes] would read the second of two
// equal values as unreachable.

/** The SSH session of the browser is gone: closed, or never opened. Without the identifier of the session. */
internal const val SFTP_SESSION_NOT_FOUND_MESSAGE = "Session SSH introuvable"

/** The SFTP channel did not open on a session that exists. */
internal const val SFTP_OPEN_FAILED_MESSAGE = "Ouverture de SFTP impossible"

/** An operation on a session whose SFTP client is not open. Without the identifier of the session. */
internal const val SFTP_NO_SESSION_MESSAGE = "Client SFTP non initialisé"

/** A remote path that [SftpManager.sanitizePath] refuses (empty, null byte, above the root). Without the path. */
internal const val SFTP_INVALID_PATH_MESSAGE = "Chemin invalide"

/** The server refused the operation for lack of rights. */
internal const val SFTP_PERMISSION_DENIED_MESSAGE = "Permission refusée"

/** The file or the folder is not on the server. */
internal const val SFTP_NOT_FOUND_MESSAGE = "Fichier ou dossier introuvable"

/** A failure the manager does not tell apart from another. */
internal const val SFTP_UNKNOWN_MESSAGE = "Erreur SFTP"

/**
 * The connection dropped during an operation. The browser reads it, before translating it, to offer
 * the reconnection: this constant is what tells a drop from any other failure.
 */
internal const val SFTP_CONNECTION_LOST_MESSAGE = "Connexion SFTP perdue"

/** The name of a new folder is not a bare name (see [isValidEntryName]). */
internal const val SFTP_FOLDER_NAME_INVALID_MESSAGE = "Nom de dossier invalide"

/** A folder with this name is already there. */
internal const val SFTP_FOLDER_EXISTS_MESSAGE = "Un dossier portant ce nom existe déjà"

/** The server did not create the folder, for a reason it does not say. */
internal const val SFTP_MKDIR_FAILED_MESSAGE = "Impossible de créer le dossier"

/** The server did not create the empty file, for a reason it does not say. */
internal const val SFTP_CREATE_FILE_FAILED_MESSAGE = "Impossible de créer le fichier"

/** The server did not delete the file or the folder, for a reason it does not say. */
internal const val SFTP_DELETE_FAILED_MESSAGE = "Impossible de supprimer l'élément"

/** The name of a new file is not a bare name (see [isValidEntryName]). */
internal const val SFTP_NAME_INVALID_MESSAGE = "Nom de fichier invalide"

/** The new name of a file or a folder being renamed is not a bare name (see [isValidEntryName]). */
internal const val SFTP_ENTRY_NAME_INVALID_MESSAGE = "Nom invalide"

/** A file or a folder with this name is already there. */
internal const val SFTP_NAME_EXISTS_MESSAGE = "Un élément portant ce nom existe déjà"

/** The server did not rename the file or the folder, for a reason it does not say. */
internal const val SFTP_RENAME_FAILED_MESSAGE = "Impossible de renommer"

/** The server did not change the permissions, for a reason it does not say. */
internal const val SFTP_CHMOD_FAILED_MESSAGE = "Impossible de modifier les permissions"

/** The permissions are out of the range of a POSIX mode. The screen builds them from nine boxes, so it never sends this. */
internal const val SFTP_PERMISSIONS_INVALID_MESSAGE = "Valeur de permissions invalide"

/** A name that is empty before sanitizing. The dialogs do not send it, the use cases check it all the same. */
internal const val SFTP_NAME_EMPTY_MESSAGE = "Le nom ne peut pas être vide"

/**
 * The file is bigger than the preview reads. Without its size: the manager measures the file it
 * opens (the target of a symbolic link, a file that grows), which is not always the size the
 * listing showed, so the sentence says no number rather than a wrong one.
 */
internal const val SFTP_PREVIEW_TOO_LARGE_MESSAGE = "Fichier trop volumineux pour l'aperçu"

/** The server did not give the file to the preview, or the read failed. */
internal const val SFTP_READ_FAILED_MESSAGE = "Impossible de lire le fichier"

/** The download of a file failed, for a reason the server does not say. */
internal const val SFTP_DOWNLOAD_FAILED_MESSAGE = "Impossible de télécharger le fichier"

/** The server has no room left for the file. */
internal const val SFTP_NO_SPACE_MESSAGE = "Espace disque insuffisant"

/** The upload of a file failed, for a reason the server does not say. */
internal const val SFTP_UPLOAD_FAILED_MESSAGE = "Impossible d'envoyer le fichier"

/**
 * The server refused to open the destination of an upload because something already has this name,
 * and the user did not confirm a replacement. Nothing was written. Not [SFTP_NAME_EXISTS_MESSAGE]:
 * that one says a name is taken when creating, this one says what the upload did not do.
 */
internal const val SFTP_UPLOAD_TARGET_EXISTS_MESSAGE = "Un élément portant ce nom existe déjà sur le serveur, rien n'a été remplacé"

/**
 * What the user confirmed to replace is no longer a file that can be replaced (it became a folder, a
 * pipe, a socket or a device, or a link that leads nowhere): checked before the first byte is sent, or
 * after the failure of the last step of the replacement. Nothing was replaced.
 */
internal const val SFTP_UPLOAD_TARGET_CHANGED_MESSAGE = "L'élément à remplacer a changé sur le serveur, rien n'a été remplacé"

/**
 * The replacement of a file failed for a reason the server does not say (a full disk, a quota and a
 * refused rename all look alike to a SFTP v3 server), and the file that was there is untouched: the
 * new content goes to a temporary file, which only replaces the old one once it is complete.
 */
internal const val SFTP_REPLACE_FAILED_KEPT_MESSAGE = "L'envoi a échoué, le fichier existant est intact"

/** The local file a download writes to could not be opened. */
internal const val SFTP_DESTINATION_UNAVAILABLE_MESSAGE = "Impossible d'écrire le fichier de destination"

/** The local file an upload reads could not be opened. */
internal const val SFTP_SOURCE_UNAVAILABLE_MESSAGE = "Impossible de lire le fichier à envoyer"

/**
 * The source of an upload ended before the size its provider announced: the file that arrived is
 * shorter than the one that was chosen, and an upload that went through would pass for a complete one.
 * The upload is cancelled. Says nothing of what the server holds, which depends on the way the file
 * was written (a replacement through a temporary file leaves the old file as it was, one written in
 * place does not).
 */
internal const val SFTP_SOURCE_TRUNCATED_MESSAGE = "Le fichier source s'est arrêté avant la fin : envoi annulé"

/**
 * The source of an upload written in place ended before its announced size, after the first block:
 * the file on the server was already emptied and is only partly rewritten, so it is incomplete.
 * Only this way of writing says so; the others leave nothing incomplete under the real name.
 */
internal const val SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE =
    "Le fichier source s'est arrêté avant la fin : le fichier sur le serveur est incomplet"

/**
 * The file to upload is a virtual document (a Google Docs file of Drive, for example): it has no raw
 * content, its provider only gives it exported. Nothing is exported, the user is told to do it.
 */
internal const val SFTP_SOURCE_VIRTUAL_MESSAGE = "Ce document n'existe qu'en ligne et ne peut pas être envoyé tel quel"

/**
 * The string resource that says [message] in the language of the user, or null when [message] is not
 * one of the fixed texts of the SFTP browser.
 *
 * [SftpManager] and the use cases have no `Context`, so the error they return is a plain string: the
 * constants above, French literals that would be shown raw to an English-speaking user. The browser
 * maps them here, at display time, and shows any other message as it is (the transfer tracker carries
 * texts that are already resolved with the application `Context`). None of the sentences takes an
 * argument.
 */
@StringRes
fun sftpMessageRes(message: String): Int? = when (message) {
    SFTP_SESSION_NOT_FOUND_MESSAGE -> R.string.sftp_error_session_not_found
    SFTP_OPEN_FAILED_MESSAGE -> R.string.sftp_error_open_failed
    SFTP_NO_SESSION_MESSAGE -> R.string.sftp_transfer_no_session
    SFTP_INVALID_PATH_MESSAGE -> R.string.sftp_error_invalid_path
    SFTP_PERMISSION_DENIED_MESSAGE -> R.string.sftp_error_permission
    SFTP_NOT_FOUND_MESSAGE -> R.string.sftp_error_not_found
    SFTP_UNKNOWN_MESSAGE -> R.string.sftp_error_unknown
    SFTP_CONNECTION_LOST_MESSAGE -> R.string.sftp_error_connection
    SFTP_FOLDER_NAME_INVALID_MESSAGE -> R.string.sftp_error_folder_name_invalid
    SFTP_FOLDER_EXISTS_MESSAGE -> R.string.sftp_error_folder_exists
    SFTP_MKDIR_FAILED_MESSAGE -> R.string.sftp_error_mkdir_failed
    SFTP_CREATE_FILE_FAILED_MESSAGE -> R.string.sftp_error_create_file_failed
    SFTP_DELETE_FAILED_MESSAGE -> R.string.sftp_error_delete_failed
    SFTP_NAME_INVALID_MESSAGE -> R.string.sftp_error_name_invalid
    SFTP_ENTRY_NAME_INVALID_MESSAGE -> R.string.sftp_error_entry_name_invalid
    SFTP_NAME_EXISTS_MESSAGE -> R.string.sftp_error_name_exists
    SFTP_RENAME_FAILED_MESSAGE -> R.string.sftp_error_rename_failed
    SFTP_CHMOD_FAILED_MESSAGE -> R.string.sftp_error_chmod_failed
    SFTP_PERMISSIONS_INVALID_MESSAGE -> R.string.sftp_error_permissions_invalid
    SFTP_NAME_EMPTY_MESSAGE -> R.string.sftp_error_name_empty
    SFTP_PREVIEW_TOO_LARGE_MESSAGE -> R.string.sftp_preview_too_large
    SFTP_READ_FAILED_MESSAGE -> R.string.sftp_error_read_failed
    SFTP_DOWNLOAD_FAILED_MESSAGE -> R.string.sftp_error_download_failed
    SFTP_NO_SPACE_MESSAGE -> R.string.sftp_error_no_space
    SFTP_UPLOAD_FAILED_MESSAGE -> R.string.sftp_error_upload_failed
    SFTP_UPLOAD_TARGET_EXISTS_MESSAGE -> R.string.sftp_transfer_target_exists
    SFTP_UPLOAD_TARGET_CHANGED_MESSAGE -> R.string.sftp_transfer_target_changed
    SFTP_REPLACE_FAILED_KEPT_MESSAGE -> R.string.sftp_transfer_replace_failed_kept
    SFTP_DESTINATION_UNAVAILABLE_MESSAGE -> R.string.sftp_error_destination_unavailable
    SFTP_SOURCE_UNAVAILABLE_MESSAGE -> R.string.sftp_error_source_unavailable
    SFTP_SOURCE_TRUNCATED_MESSAGE -> R.string.sftp_error_source_truncated
    SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE -> R.string.sftp_error_source_truncated_in_place
    SFTP_SOURCE_VIRTUAL_MESSAGE -> R.string.sftp_error_source_virtual
    else -> null
}
