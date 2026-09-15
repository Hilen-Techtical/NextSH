// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Arrays

/**
 * Desktop port of Android's `VaultExporter`. Produces a `.nextsh` backup blob in the
 * exact same binary layout:
 *
 * ```
 * [SALT_LEN (4 bytes, big-endian)] [SALT (32 bytes)] [IV_LEN (1)] [IV (12)] [CIPHERTEXT || GCM_TAG]
 * ```
 *
 * The JSON inside matches Android's `VaultBackup` schema: so a backup taken on
 * Desktop can be re-imported on Android and vice versa.
 */
class DesktopVaultExporter(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
) {

    /**
     * Export the entire vault. The [passphrase] array is wiped in the `finally`
     * block regardless of outcome: caller must treat it as consumed.
     */
    suspend fun export(passphrase: CharArray): ByteArray {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        try {
            val hosts = hostRepository.observeAll().first()
            val sshKeys = sshKeyRepository.observeAll().first()
            val tunnels = tunnelRepository.observeAll().first()

            // Credentials (passwords): read, snapshot to immutable String, wipe char array.
            // Exclude LAN sync secrets (lan_sync_secret_ prefix): device-local auth keys
            // that must never leave the machine, even inside an encrypted backup.
            val credentialIds = vaultManager.listStoredCredentialIds()
                .filterNot { it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
            val credentials = credentialIds.mapNotNull { id ->
                val pwd = vaultManager.getPassword(id)
                if (pwd != null) {
                    val encoded = String(pwd)
                    Arrays.fill(pwd, '\u0000')
                    id to encoded
                } else null
            }.toMap()

            // Private key PEMs.
            val keyIds = vaultManager.listStoredKeyIds()
            val privateKeys = keyIds.mapNotNull { id ->
                vaultManager.getPrivateKey(id)?.let { pem -> id to pem }
            }.toMap()

            // Per-key passphrases: MUST be exported, otherwise an encrypted key
            // imported on another peer has no way to be decrypted at connect time.
            // Read into String (immutable) + wipe the source CharArray immediately.
            val passphraseIds = vaultManager.listStoredKeyPassphraseIds()
            val keyPassphrases = passphraseIds.mapNotNull { id ->
                val chars = vaultManager.getKeyPassphrase(id) ?: return@mapNotNull null
                val encoded = String(chars)
                Arrays.fill(chars, '\u0000')
                id to encoded
            }.toMap()

            val backup = VaultBackup(
                exportedAt = System.currentTimeMillis(),
                hosts = hosts,
                sshKeys = sshKeys,
                tunnels = tunnels,
                credentials = credentials,
                privateKeys = privateKeys,
                keyPassphrases = keyPassphrases,
            )

            // jsonBytes contains the plaintext vault: wiped in the finally block.
            jsonBytes = Json.encodeToString(backup).toByteArray(Charsets.UTF_8)

            val salt = DesktopCryptoUtils.generateSalt(32)
            derivedKey = DesktopCryptoUtils.deriveKeyFromPassphrase(passphrase, salt)

            val encrypted = DesktopCryptoUtils.encryptAesGcm(jsonBytes, derivedKey)

            // Header: [SALT_LEN(4 bytes, big-endian)][SALT]
            val saltLenBytes = byteArrayOf(
                (salt.size shr 24 and 0xFF).toByte(),
                (salt.size shr 16 and 0xFF).toByte(),
                (salt.size shr 8 and 0xFF).toByte(),
                (salt.size and 0xFF).toByte(),
            )

            return saltLenBytes + salt + encrypted
        } finally {
            derivedKey?.let { DesktopCryptoUtils.secureWipe(it) }
            jsonBytes?.let { DesktopCryptoUtils.secureWipe(it) }
            Arrays.fill(passphrase, '\u0000')
        }
    }

    /** Number of entries that will be/were written: UI summary helper. */
    suspend fun entryCountSummary(): BackupCounts {
        val hosts = hostRepository.observeAll().first().size
        val sshKeys = sshKeyRepository.observeAll().first().size
        val tunnels = tunnelRepository.observeAll().first().size
        val credentials = vaultManager.listStoredCredentialIds()
            .count { !it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
        val privateKeys = vaultManager.listStoredKeyIds().size
        return BackupCounts(hosts, sshKeys, tunnels, credentials, privateKeys)
    }
}

data class BackupCounts(
    val hosts: Int,
    val sshKeys: Int,
    val tunnels: Int,
    val credentials: Int,
    val privateKeys: Int,
) {
    val total: Int get() = hosts + sshKeys + tunnels + credentials + privateKeys
}
