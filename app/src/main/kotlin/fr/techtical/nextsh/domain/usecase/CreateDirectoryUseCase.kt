// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SFTP_FOLDER_NAME_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EMPTY_MESSAGE
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.isValidEntryName
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : créer un répertoire dans le répertoire courant via SFTP.
 *
 * Valide le nom du dossier avant de déléguer à [SftpManager.createDirectory], qui refuse de remplacer
 * un élément existant. Un nom qui n'est pas un nom nu (séparateur, caractère de contrôle, `.`, `..`)
 * est refusé et dit comme tel : il n'est jamais corrigé en silence (`a/b` ne devient pas `ab`). Les
 * espaces en bordure sont seuls retirés, comme le fait le champ de saisie.
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class CreateDirectoryUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param parentPath chemin absolu du répertoire parent.
     * @param dirName nom du nouveau répertoire, nom nu (sans séparateur de chemin).
     */
    suspend operator fun invoke(
        sessionId: String,
        parentPath: String,
        dirName: String,
    ): SshResult<Unit> {
        if (dirName.isBlank()) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EMPTY_MESSAGE)
        }

        val name = dirName.trim()
        if (!isValidEntryName(name)) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_FOLDER_NAME_INVALID_MESSAGE)
        }

        // The name goes to the manager as it is, the parent folder apart: only the parent is resolved.
        return sftpManager.createDirectory(sessionId, parentPath, name)
    }
}
