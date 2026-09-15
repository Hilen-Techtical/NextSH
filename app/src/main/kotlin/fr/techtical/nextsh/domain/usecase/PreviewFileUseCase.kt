// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshResult
import javax.inject.Inject

/**
 * Lit les premiers octets d'un fichier distant pour aperçu.
 *
 * Applique une limite de taille différente selon le type de fichier :
 * - Texte : [MAX_TEXT_PREVIEW_BYTES] (512 Ko)
 * - Image : [MAX_IMAGE_PREVIEW_BYTES] (2 Mo)
 *
 * Délègue la lecture à [SftpManager.readPreview] qui ferme le RemoteFile
 * dans un bloc finally et ne propage jamais d'exception vers l'appelant.
 */
class PreviewFileUseCase @Inject constructor(
    private val sftpManager: SftpManager,
) {
    suspend operator fun invoke(
        sessionId: String,
        path: String,
        isImage: Boolean,
    ): SshResult<ByteArray> {
        val maxBytes = if (isImage) MAX_IMAGE_PREVIEW_BYTES else MAX_TEXT_PREVIEW_BYTES
        return sftpManager.readPreview(sessionId, path, maxBytes)
    }

    companion object {
        const val MAX_TEXT_PREVIEW_BYTES = 512 * 1024L    // 512 Ko
        const val MAX_IMAGE_PREVIEW_BYTES = 2 * 1024 * 1024L  // 2 Mo
    }
}
