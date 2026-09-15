// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.shared.domain.model.SshResult
import timber.log.Timber
import javax.inject.Inject

/**
 * Télécharge un fichier distant vers un URI local via SAF.
 *
 * Ouvre un OutputStream depuis le ContentResolver SAF, puis délègue la
 * lecture à [SftpManager.readFile] avec callback de progression.
 *
 * Pas de permission stockage nécessaire : SAF gère l'accès fichier.
 *
 * @param sftpManager    gestionnaire des connexions SFTP.
 * @param context        contexte application pour le ContentResolver.
 */
class DownloadFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
    @ApplicationContext private val context: Context,
) {
    /**
     * @param sessionId   identifiant de la session SSH active.
     * @param remotePath  chemin absolu du fichier distant à télécharger.
     * @param localUri    URI SAF (content://) de destination.
     * @param onProgress  lambda appelée avec (bytesTransferred, totalBytes).
     */
    suspend operator fun invoke(
        sessionId: String,
        remotePath: String,
        localUri: Uri,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        return try {
            val outputStream = context.contentResolver.openOutputStream(localUri)
                ?: return SshResult.Error(
                    fr.techtical.nextsh.domain.model.SshErrorCode.UNKNOWN,
                    "Impossible d'ouvrir le fichier de destination"
                )
            outputStream.use { stream ->
                sftpManager.readFile(
                    sessionId    = sessionId,
                    remotePath   = remotePath,
                    outputStream = stream,
                    onProgress   = onProgress,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Une annulation utilisateur n est pas une erreur d acces SAF : la
            // laisser tomber dans le catch generique la journaliserait comme un
            // echec et masquerait la nature de l interruption.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "DownloadFileUseCase: erreur d'accès SAF pour $localUri")
            SshResult.Error(
                fr.techtical.nextsh.domain.model.SshErrorCode.UNKNOWN,
                "Impossible d'accéder au fichier de destination"
            )
        }
    }
}
