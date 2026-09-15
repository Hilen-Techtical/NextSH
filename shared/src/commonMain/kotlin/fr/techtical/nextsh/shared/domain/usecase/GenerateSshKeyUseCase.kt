// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.ssh.SshKeyManager
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.randomUuid

/**
 * UseCase : générer une paire de clés SSH in-app.
 * - Génère la paire via SshKeyManager (returns Pair<privateKeyPem, publicKeyOpenSsh>)
 * - Stocke la clé privée dans le vault
 * - Sauvegarde la metadata dans le repository
 */
class GenerateSshKeyUseCase(
    private val keyManager: SshKeyManager,
    private val keyRepository: SshKeyRepository,
    private val vaultManager: VaultManager,
) {
    suspend operator fun invoke(
        label: String,
        keyType: SshKeyType = SshKeyType.ED25519,
    ): Result<SshKey> {
        return try {
            val (privateKeyPem, publicKeyOpenSsh) = keyManager.generateKeyPair(keyType, label)

            val id = randomUuid()
            val sshKey = SshKey(
                id = id,
                label = label,
                keyType = keyType,
                publicKey = publicKeyOpenSsh,
                isBiometric = false,
                keystoreAlias = null,
            )

            // Store private key in vault
            vaultManager.storePrivateKey(sshKey.id, privateKeyPem)

            // Save metadata in repository
            keyRepository.save(sshKey)

            Result.success(sshKey)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
