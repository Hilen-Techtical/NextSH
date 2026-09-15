// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.serialization.json.Json
import java.util.Arrays
import javax.crypto.AEADBadTagException

/** Error categories surfaced to the UI with user-readable messages. */
sealed class VaultImportError(message: String) : Exception(message) {
    object FileTooLarge : VaultImportError("Fichier trop volumineux (maximum 50 Mo).")
    object Malformed : VaultImportError("Fichier corrompu ou format invalide.")
    object WrongPassphrase : VaultImportError("Passphrase incorrecte.")
    class UnsupportedVersion(val version: Int) : VaultImportError("Version de backup non supportée : $version.")
    class IoError(cause: Throwable) : VaultImportError("Erreur d'import : ${cause.message ?: "erreur inconnue"}")
}

/**
 * Result from a successful import: the counts mirror the equivalent on export
 * so the UI can display a single "restored N items" message.
 */
data class ImportSummary(
    val hosts: Int,
    val sshKeys: Int,
    val tunnels: Int,
    val credentials: Int,
    val privateKeys: Int,
    val keyPassphrases: Int = 0,
) {
    val total: Int get() = hosts + sshKeys + tunnels + credentials + privateKeys + keyPassphrases
}

// Lenient decoder: accept unknown top-level keys so a future version written by
// the other platform doesn't break parsing here.
private val jsonDecoder = Json { ignoreUnknownKeys = true }

/**
 * Desktop port of Android's `VaultImporter`. Accepts a `.nextsh` byte buffer,
 * validates + decrypts + restores.
 *
 * Merge behaviour matches Android: every entry is upserted via `repository.save`
 * (id collision = last-write-wins in favour of the backup). Credentials and
 * private keys are written back into the [VaultManager].
 */
class DesktopVaultImporter(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
) {

    suspend fun import(data: ByteArray, passphrase: CharArray): Result<ImportSummary> {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        return try {
            if (data.size > MAX_BACKUP_SIZE) {
                return Result.failure(VaultImportError.FileTooLarge)
            }
            if (data.size < 4) {
                return Result.failure(VaultImportError.Malformed)
            }

            val saltLen = ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)

            if (saltLen !in 1..MAX_SALT_LEN || 4 + saltLen >= data.size) {
                return Result.failure(VaultImportError.Malformed)
            }

            val salt = data.sliceArray(4 until (4 + saltLen))
            val encrypted = data.sliceArray((4 + saltLen) until data.size)

            derivedKey = DesktopCryptoUtils.deriveKeyFromPassphrase(passphrase, salt)

            val decrypted = try {
                DesktopCryptoUtils.decryptAesGcm(encrypted, derivedKey)
            } catch (e: AEADBadTagException) {
                return Result.failure(VaultImportError.WrongPassphrase)
            } catch (e: IllegalArgumentException) {
                return Result.failure(VaultImportError.Malformed)
            }
            jsonBytes = decrypted

            val json = decrypted.toString(Charsets.UTF_8)

            val backup = try {
                jsonDecoder.decodeFromString<VaultBackup>(json)
            } catch (e: Exception) {
                return Result.failure(VaultImportError.Malformed)
            }

            if (backup.version != 1) {
                return Result.failure(VaultImportError.UnsupportedVersion(backup.version))
            }

            // Upsert hosts, SSH keys, tunnels. Conflicts → backup wins (matches Android's fusion).
            backup.hosts.forEach { hostRepository.save(it) }
            backup.sshKeys.forEach { sshKeyRepository.save(it) }
            backup.tunnels.forEach { tunnelRepository.save(it) }

            // Restore credentials and private keys into the vault. Defensively skip
            // any LAN sync secret a legacy backup (pre-fix) may still carry: it's a
            // device-local auth key that must not be imported onto another machine.
            backup.credentials.forEach { (id, password) ->
                if (id.startsWith(LAN_SYNC_SECRET_KEY_PREFIX)) return@forEach
                val chars = password.toCharArray()
                try {
                    vaultManager.storePassword(id, chars)
                } finally {
                    Arrays.fill(chars, '\u0000')
                }
            }
            backup.privateKeys.forEach { (id, pem) ->
                vaultManager.storePrivateKey(id, pem)
            }

            // Restore passphrases for encrypted private keys. Without this an imported
            // encrypted key looks fine on paper but the next connect fails with
            // "Decryption of the key failed": matches the Wave 2 passphrase fix.
            backup.keyPassphrases.forEach { (id, pass) ->
                val chars = pass.toCharArray()
                try {
                    vaultManager.storeKeyPassphrase(id, chars)
                } finally {
                    Arrays.fill(chars, '\u0000')
                }
            }

            Result.success(
                ImportSummary(
                    hosts = backup.hosts.size,
                    sshKeys = backup.sshKeys.size,
                    tunnels = backup.tunnels.size,
                    credentials = backup.credentials.count { !it.key.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) },
                    privateKeys = backup.privateKeys.size,
                    keyPassphrases = backup.keyPassphrases.size,
                ),
            )
        } catch (e: VaultImportError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(VaultImportError.IoError(e))
        } finally {
            derivedKey?.let { DesktopCryptoUtils.secureWipe(it) }
            jsonBytes?.let { DesktopCryptoUtils.secureWipe(it) }
            Arrays.fill(passphrase, '\u0000')
        }
    }

    companion object {
        const val MAX_BACKUP_SIZE: Int = 50 * 1024 * 1024  // 50 MB: matches Android
        private const val MAX_SALT_LEN: Int = 1024          // sanity guard against malicious header
    }
}
