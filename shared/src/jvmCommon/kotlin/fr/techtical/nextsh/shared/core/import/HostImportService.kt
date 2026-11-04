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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.CharBuffer
import java.security.MessageDigest
import java.util.Arrays

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
 * Each entry is imported atomically, in this order:
 *   1. store the password / private key / passphrase under the host's `credentialId`;
 *   2. register the [SshKey] metadata row of a key host;
 *   3. build the `Host` and `hostRepository.save(host)`. `save()` auto-generates
 *      sync metadata (vector clock / timestamps), so we never set it ourselves;
 *   4. wipe the [CharArray] secrets.
 * The host row comes last on purpose: a vault write can throw (Android vault auth
 * expired after 300 s, Desktop vault locked), and a host saved before its secret
 * would stay behind without it and be duplicated by a re-import. If a step fails,
 * what this entry already wrote is rolled back (best effort, never throwing).
 *
 * A key shared by several hosts of the export ([ParsedHost.keySourceId], Termius) is
 * stored ONCE per run: the first host that references it writes the private key, its
 * passphrase and the [SshKey] row, and every later host is linked to that same key id
 * as its `credentialId`. Hosts with different keys, or keys without an identity, each
 * get their own copy as before.
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
     *   SSH key is selectable in the UI. Its id is the host's credentialId. When
     *   null, the private key and its passphrase are still stored in the vault under
     *   the host's credentialId but no separate key row is made.
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
        // Keys already stored by this run, by source identity. An entry is added only
        // once its host and everything it wrote are committed (see importOne), so a
        // failed or rolled-back store is retried by the next host referencing the key.
        val storedKeys = HashMap<String, StoredKey>()

        for (entry in entries) {
            try {
                importOne(entry, hostRepository, vaultManager, sshKeyRepository, storedKeys)
                imported++
            } catch (e: CancellationException) {
                // The entry was already rolled back by importOne: stop the run, never
                // count a cancellation as a skipped host and carry on importing. The
                // entries that will not be reached are wiped too: nobody imports them now.
                entries.forEach { it.wipeSecrets() }
                throw e
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

    /**
     * A key stored earlier in the run: its id (the SshKey id) and a digest of the secrets it
     * was stored from (see [keyDigest]). The digest, not the PEM, is kept for the whole run.
     */
    private class StoredKey(val credentialId: String, val digest: ByteArray)

    /**
     * SHA-256 over the PEM bytes, a 0 separator, then the passphrase bytes (none when the
     * key has no passphrase). Two hosts are linked to the same stored key only when the
     * digests match: same PEM AND same passphrase. The intermediate byte arrays are wiped.
     */
    private fun keyDigest(pem: String, passphrase: CharArray?): ByteArray {
        val pemBytes = pem.toByteArray(Charsets.UTF_8)
        val passphraseBytes = passphrase?.takeIf { it.isNotEmpty() }?.let { utf8Bytes(it) } ?: ByteArray(0)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(pemBytes)
            digest.update(0.toByte())
            digest.update(passphraseBytes)
            return digest.digest()
        } finally {
            Arrays.fill(pemBytes, 0)
            Arrays.fill(passphraseBytes, 0)
        }
    }

    /** UTF-8 encoding of [chars] without a String intermediate; the encoder buffer is wiped too. */
    private fun utf8Bytes(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) Arrays.fill(buffer.array(), 0)
        return out
    }

    private suspend fun importOne(
        entry: ParsedHost,
        hostRepository: HostRepository,
        vaultManager: VaultManager,
        sshKeyRepository: SshKeyRepository?,
        storedKeys: MutableMap<String, StoredKey>,
    ) {
        val pem = if (entry.authType == AuthType.SSH_KEY) entry.privateKeyPem else null
        val keySourceId = if (pem != null) entry.keySourceId else null
        // A key already stored by this run: same source identity AND same digest (PEM and
        // passphrase). An export that reuses an id for two different keys, or for one key
        // with two passphrases, must not link a host to the wrong one. The digest is
        // computed here, before the passphrase is wiped.
        val digest = if (pem != null && keySourceId != null) keyDigest(pem, entry.keyPassphrase) else null
        val sharedKey = keySourceId?.let { storedKeys[it] }
            ?.takeIf { digest != null && MessageDigest.isEqual(it.digest, digest) }

        // The connection code reads every secret of a host under host.credentialId:
        // the password for PASSWORD, the private key and its passphrase for SSH_KEY.
        // So a key host must carry the id under which its PEM, its passphrase and its
        // SshKey row are stored (the same convention as a key picked in the host form,
        // where credentialId is the SshKey id). One id serves both purposes. Hosts that
        // share a key share that id.
        val credentialId = sharedKey?.credentialId ?: randomUuid()

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

        // What this entry has written, to undo it if a later step fails. Each flag is
        // raised BEFORE its write: a write that threw halfway may still have left
        // something behind, and every rollback delete is a no-op on a missing entry.
        // A shared key written by an earlier host is never this entry's to undo: its
        // flags stay down.
        var passwordStored = false
        var privateKeyStored = false
        var passphraseStored = false
        var keyRowSaved = false
        var hostSaved = false

        try {
            when (entry.authType) {
                AuthType.SSH_KEY -> {
                    if (pem != null && sharedKey == null) {
                        privateKeyStored = true
                        vaultManager.storePrivateKey(credentialId, pem)
                        entry.keyPassphrase?.let { passphrase ->
                            if (passphrase.isNotEmpty()) {
                                passphraseStored = true
                                vaultManager.storeKeyPassphrase(credentialId, passphrase)
                            }
                        }
                        // Register key metadata so the host's key is visible in the UI.
                        if (sshKeyRepository != null) {
                            keyRowSaved = true
                            sshKeyRepository.save(
                                SshKey(
                                    id = credentialId,
                                    label = entry.keyLabel ?: entry.label,
                                    keyType = guessKeyType(pem),
                                    publicKey = "", // unknown from a private-key-only import
                                ),
                            )
                        }
                    }
                    // If a key host carried no PEM (referenced key whose body lives
                    // outside the export), there is no secret to store: the host row
                    // is imported as-is (its credentialId points at nothing yet) and the
                    // user attaches a key later.
                }

                else -> {
                    // PASSWORD (and any non-key auth): store password if present.
                    entry.password?.let { pw ->
                        if (pw.isNotEmpty()) {
                            passwordStored = true
                            vaultManager.storePassword(credentialId, pw)
                        }
                    }
                }
            }

            // Last, once every secret is safely stored. save() generates
            // vectorClock/timestamps: do NOT set sync metadata here.
            hostSaved = true
            hostRepository.save(host)

            // Committed: later hosts of the run that reference the same key reuse it.
            if (digest != null && keySourceId != null && privateKeyStored) {
                storedKeys.putIfAbsent(keySourceId, StoredKey(credentialId, digest))
            }
        } catch (e: Throwable) {
            // Reverse order of the writes. Never throws: the original failure is what
            // the caller reports.
            withContext(NonCancellable) {
                if (hostSaved) bestEffort("host row") { hostRepository.hardDelete(host.id) }
                if (keyRowSaved) bestEffort("key row") { sshKeyRepository?.hardDelete(credentialId) }
                if (passphraseStored) bestEffort("key passphrase") { vaultManager.deleteKeyPassphrase(credentialId) }
                if (privateKeyStored) bestEffort("private key") { vaultManager.deletePrivateKey(credentialId) }
                if (passwordStored) bestEffort("password") { vaultManager.deletePassword(credentialId) }
            }
            throw e
        } finally {
            // Secrets are no longer needed once persisted, or once the entry failed.
            entry.wipeSecrets()
        }
    }

    /**
     * Runs a rollback [step]; a failure is logged (step name and exception class only) and
     * swallowed. Throwable, not Exception: an Error from one delete must neither replace
     * the original exception nor skip the remaining rollback steps.
     */
    private suspend fun bestEffort(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Logger.w(TAG, "Import rollback step \"$step\" failed (${e::class.simpleName})")
        }
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
