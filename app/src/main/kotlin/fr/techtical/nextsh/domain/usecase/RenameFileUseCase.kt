// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SFTP_ENTRY_NAME_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EMPTY_MESSAGE
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.isValidEntryName
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : renommer un fichier ou répertoire distant via SFTP.
 *
 * Valide le nouveau nom avant de déléguer à [SftpManager.rename], qui refuse de remplacer un élément
 * existant. Un nom qui n'est pas un nom nu (séparateur, caractère de contrôle, `.`, `..`) est refusé
 * et dit comme tel : il n'est jamais corrigé en silence (`a/b` ne devient pas `ab`). Les espaces en
 * bordure sont seuls retirés, comme le fait le champ de saisie.
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class RenameFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param oldPath chemin absolu de l'entrée à renommer.
     * @param newName nouveau nom, nom nu (nom de fichier uniquement, sans séparateur de chemin).
     */
    suspend operator fun invoke(
        sessionId: String,
        oldPath: String,
        newName: String,
    ): SshResult<Unit> {
        if (newName.isBlank()) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EMPTY_MESSAGE)
        }

        val name = newName.trim()
        if (!isValidEntryName(name)) {
            return SshResult.Error(SshErrorCode.UNKNOWN, SFTP_ENTRY_NAME_INVALID_MESSAGE)
        }

        return sftpManager.rename(sessionId, oldPath, name)
    }
}
