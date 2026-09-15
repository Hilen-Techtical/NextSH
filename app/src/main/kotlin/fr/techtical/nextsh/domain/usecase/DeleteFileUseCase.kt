// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : supprimer un fichier ou un répertoire via SFTP.
 *
 * - Pour les fichiers : délègue à [SftpManager.delete].
 * - Pour les répertoires : délègue à [SftpManager.deleteRecursive].
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class DeleteFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu de l'entrée à supprimer.
     * @param isDirectory true si l'entrée est un répertoire (suppression récursive).
     */
    suspend operator fun invoke(
        sessionId: String,
        path: String,
        isDirectory: Boolean,
    ): SshResult<Unit> = if (isDirectory) {
        sftpManager.deleteRecursive(sessionId, path)
    } else {
        sftpManager.delete(sessionId, path)
    }
}
