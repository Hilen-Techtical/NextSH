// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.shared.domain.model.SshResult
import javax.inject.Inject

/**
 * UseCase : lister le contenu d'un répertoire SFTP.
 *
 * Applique les transformations suivantes sur la liste brute :
 * 1. Filtrage des fichiers cachés (commençant par `.`) si [showHidden] est false.
 * 2. Tri : dossiers en premier, puis par [sortOrder].
 *
 * Retourne [SshResult.Success] avec la liste triée/filtrée,
 * ou [SshResult.Error] en cas d'échec réseau/SFTP.
 */
class ListDirectoryUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    suspend operator fun invoke(
        sessionId: String,
        path: String,
        sortOrder: SortOrder = SortOrder.NAME_ASC,
        showHidden: Boolean = false,
    ): SshResult<List<SftpFile>> {
        return when (val result = sftpManager.listDirectory(sessionId, path)) {
            is SshResult.Success -> {
                val filtered = if (showHidden) {
                    result.data
                } else {
                    result.data.filter { !it.name.startsWith('.') }
                }

                val sorted = filtered.sortedWith(
                    compareByDescending<SftpFile> { it.isDirectory }
                        .then(sortComparator(sortOrder))
                )

                SshResult.Success(sorted)
            }
            is SshResult.Error -> result
        }
    }

    private fun sortComparator(order: SortOrder): Comparator<SftpFile> = when (order) {
        SortOrder.NAME_ASC  -> compareBy { it.name.lowercase() }
        SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
        SortOrder.SIZE_ASC  -> compareBy { it.size }
        SortOrder.SIZE_DESC -> compareByDescending { it.size }
        SortOrder.DATE_ASC  -> compareBy { it.modifiedAt }
        SortOrder.DATE_DESC -> compareByDescending { it.modifiedAt }
    }
}
