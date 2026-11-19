// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.vault.VaultImporter
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import javax.inject.Inject

class ImportVaultUseCase @Inject constructor(
    private val vaultImporter: VaultImporter,
) {
    suspend operator fun invoke(data: ByteArray, passphrase: CharArray): Result<BackupImportReport> {
        return vaultImporter.import(data, passphrase)
    }
}
