// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : renommer un fichier ou répertoire distant via SFTP.
 *
 * Valide le nouveau nom avant de déléguer à [SftpManager.rename].
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class RenameFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param oldPath chemin absolu de l'entrée à renommer.
     * @param newName nouveau nom (nom de fichier uniquement, sans séparateur de chemin).
     */
    suspend operator fun invoke(
        sessionId: String,
        oldPath: String,
        newName: String,
    ): SshResult<Unit> {
        if (newName.isBlank()) {
            return SshResult.Error(SshErrorCode.UNKNOWN, "Le nom ne peut pas être vide")
        }

        val sanitizedName = sftpManager.sanitizeFilename(newName)
            ?: return SshResult.Error(SshErrorCode.UNKNOWN, "Nom de fichier invalide")

        return sftpManager.rename(sessionId, oldPath, sanitizedName)
    }
}
