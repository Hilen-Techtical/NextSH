// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.DesktopBuildInfo
import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupCustomTheme
import fr.techtical.nextsh.shared.core.backup.BackupDocument
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupKnownHost
import fr.techtical.nextsh.shared.core.backup.BackupProducer
import fr.techtical.nextsh.shared.core.backup.BackupSnippet
import fr.techtical.nextsh.shared.core.backup.BackupValidationResult
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.flow.first
import java.util.Arrays

private const val TAG = "DesktopVaultExporter"

/**
 * Produces a `.nextsh` backup blob (format version 2, shared with Android through the codec of `:shared`):
 *
 * ```
 * [SALT_LEN (4 bytes, big-endian)] [SALT (32 bytes)] [IV_LEN (1)] [IV (12)] [CIPHERTEXT || GCM_TAG]
 * ```
 *
 * The JSON inside is a `BackupDocument`: the v1 core (hosts, SSH keys, tunnels, passwords, private keys, key
 * passphrases) plus certificates, snippets, custom terminal themes, known hosts in portable form and the
 * allow-list of settings. The frame comes from `BackupEnvelope`; the KDF and AES-GCM stay in
 * [DesktopCryptoUtils].
 *
 * NEVER in a backup: the LAN sync secret (device-local authentication key) and the security settings
 * (clipboard clearing, capture hiding, sync, window geometry).
 */
class DesktopVaultExporter(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val snippetRepository: SnippetRepository,
    private val customThemeRepository: CustomTerminalThemeRepository,
    private val knownHostsStore: DesktopKnownHostsStore,
    private val settings: BackupSettingsAccess,
    private val appVersion: String = DesktopBuildInfo.VERSION,
) {

    /**
     * Export the entire vault. The [passphrase] array is wiped in the `finally`
     * block regardless of outcome: caller must treat it as consumed.
     */
    suspend fun export(passphrase: CharArray): ByteArray = exportWithSummary(passphrase).bytes

    /**
     * Like [export], and also returns what was written, counted on the very document that was encrypted
     * (one read of the secrets, no second pass): [BackupCounts.notRestorable] is what an import would refuse.
     */
    suspend fun exportWithSummary(passphrase: CharArray): BackupExport {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        try {
            val hosts = hostRepository.observeAll().first()
            val sshKeys = sshKeyRepository.observeAll().first()
            val tunnels = tunnelRepository.observeAll().first()

            val credentials = readCredentials()
            val privateKeys = readPrivateKeys()
            val keyPassphrases = readKeyPassphrases()
            val certificates = readCertificates()

            val snippets = backupSnippets()
            val customThemes = backupThemes()
            val knownHosts = portableKnownHosts()

            val document = BackupDocument(
                exportedAt = System.currentTimeMillis(),
                hosts = hosts,
                sshKeys = sshKeys,
                tunnels = tunnels,
                credentials = credentials,
                privateKeys = privateKeys,
                keyPassphrases = keyPassphrases,
                certificates = certificates,
                snippets = snippets,
                customThemes = customThemes,
                knownHosts = knownHosts,
                settings = settings.snapshot(),
                producer = BackupProducer(platform = "DESKTOP", appVersion = appVersion),
            )

            // jsonBytes contains the plaintext vault: wiped in the finally block. The codec always writes
            // "version":2, which makes the 1.0.x readers refuse the file cleanly instead of importing a part.
            jsonBytes = BackupCodec.encodeToBytes(document)

            val salt = DesktopCryptoUtils.generateSalt(BackupEnvelope.DEFAULT_SALT_BYTES)
            derivedKey = DesktopCryptoUtils.deriveKeyFromPassphrase(passphrase, salt)

            val sealed = DesktopCryptoUtils.encryptAesGcm(jsonBytes, derivedKey)

            return BackupExport(BackupEnvelope.pack(salt, sealed), countsOf(document))
        } finally {
            derivedKey?.let { DesktopCryptoUtils.secureWipe(it) }
            jsonBytes?.let { DesktopCryptoUtils.secureWipe(it) }
            Arrays.fill(passphrase, '\u0000')
        }
    }

    /**
     * Counts for the UI, WITHOUT reading any secret (ids and non secret content only). [BackupCounts.notRestorable]
     * therefore covers hosts, SSH keys, tunnels, snippets and themes but not the secrets: the count of an export
     * that was just made, secrets included, comes with it from [exportWithSummary].
     */
    suspend fun entryCountSummary(): BackupCounts {
        val document = BackupDocument(
            exportedAt = 0L,
            hosts = hostRepository.observeAll().first(),
            sshKeys = sshKeyRepository.observeAll().first(),
            tunnels = tunnelRepository.observeAll().first(),
            credentials = emptyMap(),
            privateKeys = emptyMap(),
            snippets = backupSnippets(),
            customThemes = backupThemes(),
        )
        return countsOf(document).copy(
            credentials = vaultManager.listStoredCredentialIds().count { !it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) },
            privateKeys = vaultManager.listStoredKeyIds().size,
            keyPassphrases = vaultManager.listStoredKeyPassphraseIds().size,
            certificates = vaultManager.listStoredCertificateIds().size,
            knownHosts = portableKnownHosts().size,
        )
    }

    /**
     * Counts of [document] as written. [BackupCounts.notRestorable] is what `BackupValidator.validate` (the
     * validation of the import) would drop from it, each entry counted once. They are still written, the
     * export is not blocked.
     */
    private fun countsOf(document: BackupDocument): BackupCounts {
        val refused = (BackupValidator.validate(document) as? BackupValidationResult.Valid)?.backup?.invalidTotal ?: 0
        return BackupCounts(
            hosts = document.hosts.size,
            sshKeys = document.sshKeys.size,
            tunnels = document.tunnels.size,
            credentials = document.credentials.size,
            privateKeys = document.privateKeys.size,
            keyPassphrases = document.keyPassphrases.size,
            certificates = document.certificates.size,
            snippets = document.snippets.size,
            customThemes = document.customThemes.size,
            knownHosts = document.knownHosts.size,
            notRestorable = refused,
        )
    }

    // Passwords: read, snapshot to immutable String, wipe the char array. The LAN sync secrets (prefix
    // lan_sync_secret_) are device-local auth keys that must never leave the machine, even inside an
    // encrypted backup.
    private suspend fun readCredentials(): Map<String, String> = vaultManager.listStoredCredentialIds()
        .filterNot { it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
        .mapNotNull { id ->
            val pwd = vaultManager.getPassword(id)
            if (pwd != null) {
                val encoded = String(pwd)
                Arrays.fill(pwd, '\u0000')
                id to encoded
            } else null
        }.toMap()

    private suspend fun readPrivateKeys(): Map<String, String> = vaultManager.listStoredKeyIds().mapNotNull { id ->
        vaultManager.getPrivateKey(id)?.let { pem -> id to pem }
    }.toMap()

    // Per-key passphrases: MUST be exported, otherwise an encrypted key imported on another peer has no way to
    // be decrypted at connect time. Read into String (immutable) + wipe the source CharArray immediately.
    private suspend fun readKeyPassphrases(): Map<String, String> = vaultManager.listStoredKeyPassphraseIds()
        .mapNotNull { id ->
            val chars = vaultManager.getKeyPassphrase(id) ?: return@mapNotNull null
            val encoded = String(chars)
            Arrays.fill(chars, '\u0000')
            id to encoded
        }.toMap()

    // SSH certificates: same treatment as the private keys. Without them a restored CERTIFICATE host would
    // lose its certificate.
    private suspend fun readCertificates(): Map<String, String> = vaultManager.listStoredCertificateIds()
        .mapNotNull { id -> vaultManager.getCertificate(id)?.let { cert -> id to cert } }.toMap()

    private suspend fun backupSnippets(): List<BackupSnippet> = snippetRepository.observeAll().first().map {
        BackupSnippet(
            id = it.id,
            label = it.label,
            command = it.command,
            category = it.category,
            hostId = it.hostId,
            createdAt = it.createdAt,
        )
    }

    private suspend fun backupThemes(): List<BackupCustomTheme> = customThemeRepository.observeAll().first().map {
        BackupCustomTheme(
            id = it.id,
            name = it.name,
            background = it.background,
            foreground = it.foreground,
            cursor = it.cursor,
            selectionBg = it.selectionBg,
            ansi = it.ansi,
            createdAt = it.createdAt,
        )
    }

    /**
     * Known hosts in the portable form (SSH key type plus base64 of the SSH key blob). An entry that cannot be
     * rebuilt is left out and counted in the log (a count only: no host, no key).
     */
    private fun portableKnownHosts(): List<BackupKnownHost> {
        val entries = knownHostsStore.exportEntries()
        val portable = entries.mapNotNull { entry ->
            val key = KnownHostPortable.toPortable(entry.algorithm, entry.publicKeyEncoded) ?: return@mapNotNull null
            BackupKnownHost(
                hostPort = entry.hostPort,
                keyType = key.keyType,
                publicKey = key.publicKey,
                addedAt = entry.addedAt.coerceAtLeast(0),
            )
        }
        val skipped = entries.size - portable.size
        if (skipped > 0) Logger.w(TAG, "export: $skipped known host(s) left out, key not convertible")
        return portable
    }
}

/** An encrypted backup and what it holds, counted on the document that was encrypted. */
class BackupExport(val bytes: ByteArray, val counts: BackupCounts)

/**
 * Entries of each section, for the UI summary before an export. [total] is the sum of all of them.
 */
data class BackupCounts(
    val hosts: Int,
    val sshKeys: Int,
    val tunnels: Int,
    val credentials: Int,
    val privateKeys: Int,
    val keyPassphrases: Int = 0,
    val certificates: Int = 0,
    val snippets: Int = 0,
    val customThemes: Int = 0,
    val knownHosts: Int = 0,
    /** Entries that exist but that an import would refuse (invalid characters, size, cap). Not in [total]. */
    val notRestorable: Int = 0,
) {
    val total: Int
        get() = hosts + sshKeys + tunnels + credentials + privateKeys +
            keyPassphrases + certificates + snippets + customThemes + knownHosts
}
