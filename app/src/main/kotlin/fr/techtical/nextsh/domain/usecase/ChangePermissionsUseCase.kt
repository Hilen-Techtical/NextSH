// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import javax.inject.Inject

/**
 * UseCase : modifier les permissions POSIX d'un fichier ou répertoire via SFTP.
 *
 * Valide que la valeur de permissions est dans la plage 0–511 (0o000–0o777)
 * avant de déléguer à [SftpManager.chmod].
 *
 * @param sftpManager gestionnaire de connexions SFTP.
 */
class ChangePermissionsUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    /**
     * @param sessionId identifiant de la session SSH active.
     * @param path chemin absolu de l'entrée cible.
     * @param permissions valeur entière des permissions POSIX (0–511).
     */
    suspend operator fun invoke(
        sessionId: String,
        path: String,
        permissions: Int,
    ): SshResult<Unit> {
        if (permissions < 0 || permissions > 511) {
            return SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Valeur de permissions invalide (attendu 0–511)"
            )
        }
        return sftpManager.chmod(sessionId, path, permissions)
    }
}
