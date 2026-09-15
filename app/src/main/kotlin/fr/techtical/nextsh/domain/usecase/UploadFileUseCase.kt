// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import timber.log.Timber
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
 * @param sftpManager    gestionnaire des connexions SFTP.
 * @param context        contexte application pour le ContentResolver.
 */
class UploadFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
    @ApplicationContext private val context: Context,
) {
    /**
     * @param sessionId   identifiant de la session SSH active.
     * @param remotePath  chemin absolu de destination sur le serveur distant.
     * @param localUri    URI SAF (content://) du fichier source local.
     * @param onProgress  lambda appelée avec (bytesTransferred, totalBytes).
     */
    suspend operator fun invoke(
        sessionId: String,
        remotePath: String,
        localUri: Uri,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        return try {
            // Récupérer la taille via le curseur SAF (0 si inconnue)
            val fileSize = resolveFileSize(localUri)

            val inputStream = context.contentResolver.openInputStream(localUri)
                ?: return SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    "Impossible d'ouvrir le fichier source"
                )
            inputStream.use { stream ->
                sftpManager.writeFile(
                    sessionId   = sessionId,
                    remotePath  = remotePath,
                    inputStream = stream,
                    size        = fileSize,
                    onProgress  = onProgress,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Une annulation utilisateur n est pas une erreur d acces SAF : la
            // laisser tomber dans le catch generique la journaliserait comme un
            // echec et masquerait la nature de l interruption.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "UploadFileUseCase: erreur d'accès SAF pour $localUri")
            SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Impossible d'accéder au fichier source"
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
            Timber.w(e, "UploadFileUseCase: impossible de résoudre la taille pour $uri")
            0L
        }
    }
}
