// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : créer un répertoire dans le répertoire courant via SFTP.
 *
 * Valide le nom du dossier avant de déléguer à [SftpManager.createDirectory].
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class CreateDirectoryUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param parentPath chemin absolu du répertoire parent.
     * @param dirName nom du nouveau répertoire (sans séparateur de chemin).
     */
    suspend operator fun invoke(
        sessionId: String,
        parentPath: String,
        dirName: String,
    ): SshResult<Unit> {
        if (dirName.isBlank()) {
            return SshResult.Error(SshErrorCode.UNKNOWN, "Le nom ne peut pas être vide")
        }

        val sanitizedName = sftpManager.sanitizeFilename(dirName)
            ?: return SshResult.Error(SshErrorCode.UNKNOWN, "Nom de dossier invalide")

        val parent = parentPath.trimEnd('/')
        val fullPath = "$parent/$sanitizedName"

        return sftpManager.createDirectory(sessionId, fullPath)
    }
}
