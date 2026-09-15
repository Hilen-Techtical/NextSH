// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exporte l'intégralité du vault au format .nextsh (JSON chiffré AES-256-GCM).
 *
 * Structure JSON avant chiffrement :
 * {
 *   "version": 1,
 *   "exportedAt": timestamp,
 *   "hosts": [...],
 *   "sshKeys": [...],
 *   "tunnels": [...],
 *   "credentials": { "credentialId": "encryptedPassword" },
 *   "privateKeys":  { "keyId": "privateKeyPem" }
 * }
 *
 * Format binaire du fichier :
 * [SALT_SIZE(4 bytes)][SALT][ENCRYPTED_JSON]
 * où ENCRYPTED_JSON = [IV_SIZE(1)][IV][CIPHERTEXT] (format CryptoUtils)
 */
@Singleton
class VaultExporter @Inject constructor(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val cryptoUtils: CryptoUtils,
) {

    /**
     * Exporte tout le vault en format .nextsh (JSON chiffré AES-256-GCM).
     * La passphrase est effacée de la mémoire en fin d'appel.
     *
     * @return ByteArray du fichier .nextsh prêt à être écrit sur disque
     */
    suspend fun export(passphrase: CharArray): ByteArray {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        try {
            Timber.i("VaultExporter: starting export")

            val hosts   = hostRepository.observeAll().first()
            val sshKeys = sshKeyRepository.observeAll().first()
            val tunnels = tunnelRepository.observeAll().first()

            // Récupérer les credentials (mots de passe). Exclure les secrets de sync
            // LAN (préfixe lan_sync_secret_) : ce sont des clés d'authentification
            // locales à l'appareil qui ne doivent jamais quitter la machine, même
            // dans un backup chiffré.
            val credentialIds = vaultManager.listStoredCredentialIds()
                .filterNot { it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
            val credentials = credentialIds.mapNotNull { id ->
                val pwd = vaultManager.getPassword(id)
                if (pwd != null) {
                    val encoded = String(pwd)
                    pwd.fill('\u0000')
                    id to encoded
                } else null
            }.toMap()

            // Récupérer les clés privées PEM
            val keyIds = vaultManager.listStoredKeyIds()
            val privateKeys = keyIds.mapNotNull { id ->
                vaultManager.getPrivateKey(id)?.let { pem -> id to pem }
            }.toMap()

            // Passphrases pour clés privées chiffrées : MUST be exported, sinon une
            // clé importée avec passphrase sur un autre peer est inutilisable.
            val passphraseIds = vaultManager.listStoredKeyPassphraseIds()
            val keyPassphrases = passphraseIds.mapNotNull { id ->
                val chars = vaultManager.getKeyPassphrase(id) ?: return@mapNotNull null
                val encoded = String(chars)
                chars.fill('\u0000')
                id to encoded
            }.toMap()

            val backup = VaultBackup(
                exportedAt  = System.currentTimeMillis(),
                hosts       = hosts,
                sshKeys     = sshKeys,
                tunnels     = tunnels,
                credentials = credentials,
                privateKeys = privateKeys,
                keyPassphrases = keyPassphrases,
            )

            // SECURITY: jsonBytes contient le vault en clair, wipé dans le finally block
            jsonBytes = Json.encodeToString(backup).toByteArray(Charsets.UTF_8)

            // Dériver la clé AES depuis la passphrase
            val salt = cryptoUtils.generateSalt(32)
            derivedKey = cryptoUtils.deriveKeyFromPassphrase(passphrase, salt)

            val encrypted = cryptoUtils.encryptAesGcm(jsonBytes, derivedKey)

            // Format final : [SALT_LEN(4)][SALT(32)][ENCRYPTED]
            val saltLenBytes = byteArrayOf(
                (salt.size shr 24 and 0xFF).toByte(),
                (salt.size shr 16 and 0xFF).toByte(),
                (salt.size shr  8 and 0xFF).toByte(),
                (salt.size        and 0xFF).toByte(),
            )

            Timber.i("VaultExporter: export complete (${hosts.size} hosts, ${sshKeys.size} keys, ${tunnels.size} tunnels)")
            return saltLenBytes + salt + encrypted

        } finally {
            derivedKey?.let { cryptoUtils.secureWipe(it) }
            jsonBytes?.let { cryptoUtils.secureWipe(it) }
            passphrase.fill('\u0000')
        }
    }
}
