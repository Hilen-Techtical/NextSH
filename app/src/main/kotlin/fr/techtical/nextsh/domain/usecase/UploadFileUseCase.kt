// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.ssh.SFTP_SOURCE_UNAVAILABLE_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_SOURCE_VIRTUAL_MESSAGE
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.isVirtualDocument
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber
import java.io.FileNotFoundException
import javax.inject.Inject

/**
 * Envoie un fichier local vers le serveur distant via SAF.
 *
 * Ouvre un InputStream depuis le ContentResolver SAF, récupère la taille
 * du fichier via les métadonnées du curseur, puis délègue l'écriture à
 * [SftpManager.writeFile] avec callback de progression.
 *
 * Pas de permission stockage nécessaire : SAF gère l'accès fichier.
 *
 * Un document virtuel (Google Docs de Drive) ne s'ouvre pas : le résultat porte
 * [SFTP_SOURCE_VIRTUAL_MESSAGE] et non [SFTP_SOURCE_UNAVAILABLE_MESSAGE], pour que
 * l'écran dise d'exporter le document. Rien n'est exporté ici.
 *
 * @param sftpManager    gestionnaire des connexions SFTP.
 * @param context        contexte application pour le ContentResolver.
 */
class UploadFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
    @ApplicationContext private val context: Context,
) {
    /**
     * La source est ouverte avant le serveur : une source illisible ne crée rien côté serveur.
     *
     * @param sessionId   identifiant de la session SSH active.
     * @param remotePath  chemin absolu de destination sur le serveur distant.
     * @param localUri    URI SAF (content://) du fichier source local.
     * @param replaceExisting vrai seulement après la confirmation de l'utilisateur, quand un fichier
     *   porte déjà ce nom : il est alors remplacé, sans être vidé avant la fin de l'envoi (le contenu
     *   passe par un fichier temporaire caché du même dossier, qui prend sa place d'un seul coup, voir
     *   [SftpManager.writeFile]). Faux, un nom déjà pris est refusé par le serveur
     *   ([fr.techtical.nextsh.core.ssh.SFTP_UPLOAD_TARGET_EXISTS_MESSAGE]) et rien n'est remplacé.
     *   Sans valeur par défaut : aucun appelant ne remplace un fichier par omission.
     * @param onProgress  lambda appelée avec (bytesTransferred, totalBytes).
     */
    suspend operator fun invoke(
        sessionId: String,
        remotePath: String,
        localUri: Uri,
        replaceExisting: Boolean,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        return try {
            // Récupérer la taille via le curseur SAF (0 si inconnue)
            val fileSize = resolveFileSize(localUri)

            val inputStream = context.contentResolver.openInputStream(localUri)
                ?: return SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    SFTP_SOURCE_UNAVAILABLE_MESSAGE
                )
            inputStream.use { stream ->
                sftpManager.writeFile(
                    sessionId       = sessionId,
                    remotePath      = remotePath,
                    inputStream     = stream,
                    size            = fileSize,
                    replaceExisting = replaceExisting,
                    onProgress      = onProgress,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Une annulation utilisateur n est pas une erreur d acces SAF : la
            // laisser tomber dans le catch generique la journaliserait comme un
            // echec et masquerait la nature de l interruption.
            throw e
        } catch (e: Exception) {
            // Un document virtuel (Google Docs de Drive) n'a pas de contenu brut : son fournisseur
            // refuse de l'ouvrir en `FileNotFoundException`, comme un fichier disparu. Les drapeaux
            // du document les distinguent, pour dire à l'utilisateur d'exporter le document plutôt
            // que de lui annoncer une lecture impossible. L'écran refuse déjà ce choix avant tout
            // transfert : ceci est le filet, au cas où il n'aurait pas vu le drapeau.
            val virtual = e is FileNotFoundException && isVirtualDocument(context, localUri)
            Timber.e("UploadFileUseCase: SAF access error (virtual document: $virtual, ${errorClass(e)})")
            SshResult.Error(
                SshErrorCode.UNKNOWN,
                if (virtual) SFTP_SOURCE_VIRTUAL_MESSAGE else SFTP_SOURCE_UNAVAILABLE_MESSAGE
            )
        }
    }

    /**
     * Résout la taille du fichier via le curseur SAF.
     * Retourne 0 si la taille n'est pas disponible.
     */
    private fun resolveFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    }
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            Timber.w("UploadFileUseCase: cannot resolve the file size (${errorClass(e)})")
            0L
        }
    }
}
