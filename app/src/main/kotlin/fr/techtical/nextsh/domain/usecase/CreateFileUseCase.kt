// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SFTP_NAME_EMPTY_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.isValidEntryName
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : créer un fichier vide dans le répertoire courant via SFTP.
 *
 * Valide le nom du fichier avant de déléguer à [SftpManager.createFile], qui refuse de remplacer un
 * élément existant. Un nom qui contient un caractère interdit (séparateur, caractère de contrôle) est
 * refusé et dit comme tel : il n'est jamais corrigé en silence (`a/b` ne devient pas `ab`). Les espaces
 * en bordure sont seuls retirés, comme le fait le champ de saisie.
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class CreateFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param parentPath chemin absolu du répertoire parent.
     * @param fileName nom du nouveau fichier (sans séparateur de chemin).
     */
    suspend operator fun invoke(
        sessionId: String,
        parentPath: String,
        fileName: String,
    ): SshResult<Unit> {
        if (fileName.isBlank()) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EMPTY_MESSAGE)
        }

        // Refused, never corrected: creating the file under a cleaned name would not be what was typed.
        val name = fileName.trim()
        if (!isValidEntryName(name)) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_INVALID_MESSAGE)
        }

        // The name goes to the manager as it is, the parent folder apart: only the parent is resolved.
        return sftpManager.createFile(sessionId, parentPath, name)
    }
}
