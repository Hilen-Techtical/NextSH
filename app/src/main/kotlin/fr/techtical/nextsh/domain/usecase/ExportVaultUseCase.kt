// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.vault.VaultExporter
import javax.inject.Inject

/**
 * UseCase : exporter le vault au format .nextsh chiffré.
 * La passphrase est effacée en mémoire après usage (dans VaultExporter).
 */
class ExportVaultUseCase @Inject constructor(
    private val vaultExporter: VaultExporter,
) {
    suspend operator fun invoke(passphrase: CharArray): Result<ByteArray> {
        return try {
            val data = vaultExporter.export(passphrase)
            Result.success(data)
        } catch (e: Exception) {
            // Sécurité : effacer la passphrase même en cas d'exception non catchée par l'exporter
            passphrase.fill('\u0000')
            Result.failure(e)
        }
    }
}
