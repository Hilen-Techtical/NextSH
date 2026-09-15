// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.randomUuid

private const val TAG = "HostImport"

/** Outcome of an import run. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

/**
 * Persists a pre-filtered list of [ParsedHost] into the domain: one [Host] row per
 * entry (with a freshly generated `credentialId`), plus the entry's secrets written
 * to the [VaultManager]. The UI pass owns selection: this service imports exactly
 * the entries it is handed.
 *
 * Insert pattern mirrors the existing `.nextsh` [VaultImporter]:
 *   1. build the `Host` and `hostRepository.save(host)`. `save()` auto-generates
 *      sync metadata (vector clock / timestamps), so we never set it ourselves;
 *   2. store the password / private key / passphrase under the generated ids;
 *   3. wipe the [CharArray] secrets.
 *
 * SECURITY: passwords / passphrases are wiped after they are stored (or on any
 * per-entry failure). Nothing secret is logged: error strings reference only the
 * host label / hostname.
 */
class HostImportService {

    /**
     * Imports [entries]. Each entry is independent: a failure on one is recorded in
     * [ImportResult.errors] and counted as skipped, but does not abort the run.
     *
     * @param sshKeyRepository optional: when provided, a [SshKey] metadata row is
     *   created for entries that carry a private key PEM, so the imported host's
     *   SSH key is selectable in the UI. When null, the private key is still stored
     *   in the vault under the host's credentialId but no separate key row is made.
     */
    suspend fun import(
        entries: List<ParsedHost>,
        hostRepository: HostRepository,
        vaultManager: VaultManager,
        sshKeyRepository: SshKeyRepository? = null,
    ): ImportResult {
        var imported = 0
        var skipped = 0
        val errors = ArrayList<String>()

        for (entry in entries) {
            try {
                importOne(entry, hostRepository, vaultManager, sshKeyRepository)
                imported++
            } catch (e: Exception) {
                skipped++
                // Reference only non-sensitive identifiers in the error message.
                val ref = entry.label.ifBlank { entry.hostname }
                errors += "Failed to import \"$ref\": ${e.message ?: e::class.simpleName}"
                Logger.w(TAG, "Skipping host \"$ref\", import failed (${e::class.simpleName})")
            } finally {
                // Defence in depth: ensure secrets are wiped even if importOne threw
                // before reaching its own wipe.
                entry.wipeSecrets()
            }
        }

        Logger.d(TAG, "Import complete: imported=$imported skipped=$skipped")
        return ImportResult(imported = imported, skipped = skipped, errors = errors)
    }

    private suspend fun importOne(
        entry: ParsedHost,
        hostRepository: HostRepository,
        vaultManager: VaultManager,
        sshKeyRepository: SshKeyRepository?,
    ) {
        val credentialId = randomUuid()
        // A private key gets its own keyId so it can be shared / referenced; when a
        // key row is created we use the same id for the vault and the SshKey row.
        val keyId = if (entry.privateKeyPem != null) randomUuid() else null

        val host = Host(
            id = randomUuid(),
            label = entry.label,
            hostname = entry.hostname,
            port = entry.port,
            username = entry.username,
            authType = entry.authType,
            credentialId = credentialId,
            group = entry.group,
        )

        // save() generates vectorClock/timestamps: do NOT set sync metadata here.
        hostRepository.save(host)

        when (entry.authType) {
            AuthType.SSH_KEY -> {
                val pem = entry.privateKeyPem
                if (pem != null && keyId != null) {
                    vaultManager.storePrivateKey(keyId, pem)
                    entry.keyPassphrase?.let { passphrase ->
                        if (passphrase.isNotEmpty()) {
                            vaultManager.storeKeyPassphrase(keyId, passphrase)
                        }
                    }
                    // Register key metadata so the host's key is visible in the UI.
                    sshKeyRepository?.save(
                        SshKey(
                            id = keyId,
                            label = entry.label,
                            keyType = guessKeyType(pem),
                            publicKey = "", // unknown from a private-key-only import
                        ),
                    )
                }
                // If a key host carried no PEM (referenced key whose body lives
                // outside the export), there is no secret to store: the host row
                // is imported as-is and the user attaches a key later.
            }

            else -> {
                // PASSWORD (and any non-key auth): store password if present.
                entry.password?.let { pw ->
                    if (pw.isNotEmpty()) vaultManager.storePassword(credentialId, pw)
                }
            }
        }

        // Wipe secrets immediately after they have been persisted.
        entry.wipeSecrets()
    }

    /**
     * Best-effort SSH key type detection from a PEM private key. Imports default to
     * [SshKeyType.ED25519] when the algorithm cannot be inferred: this only labels
     * the metadata row; the actual key bytes in the vault are authoritative.
     */
    private fun guessKeyType(pem: String): SshKeyType = when {
        pem.contains("BEGIN OPENSSH PRIVATE KEY") -> SshKeyType.ED25519 // most common modern OpenSSH key
        pem.contains("BEGIN EC PRIVATE KEY") -> SshKeyType.ECDSA_256
        pem.contains("BEGIN RSA PRIVATE KEY") -> SshKeyType.RSA_4096
        else -> SshKeyType.ED25519
    }
}
