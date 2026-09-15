// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Sentinel exception signaling that the provided PEM is encrypted and a
 * passphrase is required. Callers can prompt the user then retry the import
 * with the passphrase parameter populated.
 */
class PassphraseRequiredException : Exception("SSH key requires a passphrase")

/**
 * UseCase : importer une clé SSH privée.
 *
 * Formats supportés (via SSHJ) :
 * - PEM PKCS#8 (.pem, .key) : -----BEGIN PRIVATE KEY-----
 * - PEM PKCS#1 RSA (.pem)   : -----BEGIN RSA PRIVATE KEY-----
 * - OpenSSH (.key, id_rsa, id_ed25519) : -----BEGIN OPENSSH PRIVATE KEY-----
 * - PEM EC (.pem)            : -----BEGIN EC PRIVATE KEY-----
 *
 * Étapes :
 * 1. Extrait la clé publique pour la metadata DB
 * 2. Stocke la clé privée chiffrée dans le vault
 * 3. Sauvegarde la metadata dans Room
 */
class ImportSshKeyUseCase @Inject constructor(
    private val keyManager: SshKeyManager,
    private val keyRepository: SshKeyRepository,
    private val vaultManager: VaultManager,
) {
    suspend operator fun invoke(
        label: String,
        privateKeyPem: String,
        passphrase: CharArray? = null,
    ): Result<SshKey> {
        val trimmedKey = privateKeyPem.trim()
        if (!trimmedKey.contains("-----BEGIN ")) {
            passphrase?.fill('\u0000')
            return Result.failure(Exception(
                "Format de clé non reconnu. Formats supportés : PEM, OpenSSH, PKCS#8"
            ))
        }

        // SSHJ's `PasswordUtils.createOneOff` wipes the char[] it's given after the
        // first `reqPassword` call: if we passed the caller's array straight to
        // SSHJ then tried to persist it in the vault, we'd persist a blanked array
        // and connects would fail with "passphrase incorrect". Snapshot up front,
        // use the snapshot for the vault, let SSHJ consume the original.
        val passphraseSnapshot = passphrase?.copyOf()

        // Probe parse: if passphrase was null and the key is encrypted, SSHJ
        // throws: we signal [PassphraseRequiredException] so the UI can prompt.
        // If passphrase was provided but wrong, SSHJ throws the same class of
        // error, so we distinguish by whether the caller already supplied one.
        val keyProvider = try {
            keyManager.loadKeyProviderFromString(trimmedKey, passphrase)
        } catch (e: Exception) {
            Timber.w(e, "SSH key load failed for '$label' (passphrase=${if (passphrase != null) "provided" else "null"})")
            passphraseSnapshot?.fill('\u0000')
            return if (isPassphraseError(e)) {
                if (passphrase == null) {
                    Result.failure(PassphraseRequiredException())
                } else {
                    Result.failure(Exception("Passphrase incorrecte"))
                }
            } else {
                Result.failure(Exception("Impossible de lire la clé : ${e.message ?: "format inconnu"}"))
            }
        }

        val publicKeyLine = try {
            val pub = keyProvider.public
            val keyType = net.schmizz.sshj.common.KeyType.fromKey(pub)
            val encoded = java.util.Base64.getEncoder().encodeToString(
                net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(pub).compactData
            )
            "$keyType $encoded nextsh-imported"
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract public key post-load")
            return Result.failure(Exception("Impossible d'extraire la clé publique : ${e.message}"))
        }

        val keyType = when {
            publicKeyLine.startsWith("ssh-ed25519") -> SshKeyType.ED25519
            publicKeyLine.startsWith("ssh-rsa") -> SshKeyType.RSA_4096
            publicKeyLine.startsWith("ecdsa-sha2-nistp256") -> SshKeyType.ECDSA_256
            publicKeyLine.startsWith("ecdsa-sha2-nistp384") -> SshKeyType.ECDSA_384
            publicKeyLine.startsWith("ecdsa-sha2-nistp521") -> SshKeyType.ECDSA_521
            else -> SshKeyType.RSA_4096
        }

        val id = java.util.UUID.randomUUID().toString()
        val sshKey = SshKey(
            id = id,
            label = label,
            keyType = keyType,
            publicKey = publicKeyLine,
            isBiometric = false,
        )

        return try {
            vaultManager.storePrivateKey(id, trimmedKey)
            // Persist the passphrase only if one was actually used for decrypt.
            // Use the snapshot taken before SSHJ got a chance to wipe the caller's
            // array (see note above).
            if (passphraseSnapshot != null && passphraseSnapshot.isNotEmpty()) {
                vaultManager.storeKeyPassphrase(id, passphraseSnapshot.copyOf())
            }
            keyRepository.save(sshKey)
            Timber.i("SSH key imported: $label ($keyType)")
            Result.success(sshKey)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist SSH key: $label")
            Result.failure(e)
        } finally {
            passphraseSnapshot?.fill('\u0000')
        }
    }

    /**
     * Heuristic matching [DesktopSshKeyLoader.isLikelyPassphraseError]: SSHJ
     * raises different exception classes depending on the key format, so we
     * walk the cause chain and look for passphrase-related markers.
     */
    private fun isPassphraseError(e: Throwable): Boolean {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return chain.any { t ->
            val msg = t.message?.lowercase() ?: ""
            t is javax.crypto.BadPaddingException ||
                t is java.security.InvalidKeyException ||
                "password" in msg || "passphrase" in msg ||
                "decrypt" in msg || "tag mismatch" in msg
        }
    }
}
