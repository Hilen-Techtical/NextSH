// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import fr.techtical.nextsh.desktop.core.ssh.StoredKnownHost
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupDecodeResult
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupKeyRules
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.backup.BackupRestoreStep
import fr.techtical.nextsh.shared.core.backup.BackupValidationResult
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.backup.LocalPrivateKey
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.memoizedWithoutPassphrase
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Arrays
import javax.crypto.AEADBadTagException

private const val TAG = "DesktopVaultImporter"

/**
 * Failure categories of an import. They carry NO displayable message and no cause: the UI translates the TYPE
 * into a resource string. The exception message is a fixed name; the message and the cause of the
 * underlying exception are dropped on purpose (the decoder and the crypto layer can copy decrypted content
 * into them).
 */
sealed class VaultImportError(name: String) : Exception(name) {
    /** The file is over [BackupLimits.MAX_FILE_BYTES]. */
    object FileTooLarge : VaultImportError("FileTooLarge")

    /** Not a backup: bad frame, bad structure, bad content. */
    object Malformed : VaultImportError("Malformed")

    /** The authentication tag does not match: wrong passphrase or altered file. */
    object WrongPassphrase : VaultImportError("WrongPassphrase")

    /** The file comes from a newer NextSH (version 3 or more). */
    class UnsupportedVersion(val version: Int) : VaultImportError("UnsupportedVersion")

    /** Any other failure while restoring (database, vault). Nothing of the underlying error is exposed. */
    object IoError : VaultImportError("IoError")
}

/**
 * Restores a `.nextsh` byte buffer (format version 1 or 2, the codec lives in `:shared`): frame, decryption,
 * decoding, validation, then restore. Returns the shared [BackupImportReport] that both UIs summarize.
 *
 * Order: EVERYTHING is checked before the first write (size, frame, passphrase, version, structure, then the
 * semantic validation of the new sections). Then:
 *
 * 1. the database half goes through [restoreStore] in ONE SQL transaction: custom themes, snippets, hosts,
 *    SSH keys, tunnels, and the known hosts (inserted only, a different local key is kept and reported);
 * 2. the secrets (passwords, private keys, key passphrases, certificates) go into the [VaultManager], only once
 *    the database part has succeeded;
 * 3. the allow-listed settings (`common` plus `desktop`) are applied by [settings]. A language change takes
 *    effect at the next start.
 *
 * Merge behaviour matches Android: the backup wins on an id collision, local items that are not in the backup
 * are kept. SSH keys are the exception: a key already on this device keeps its private key and its public
 * key (`BackupKeyRules`), and a public key line that does not match its private key is replaced by the
 * proven derived one. The LAN sync secret is never imported, even from a legacy file that still carries one. Security
 * settings are not in the format, so an import cannot change them.
 *
 * Which tunnels are restored is decided by the store: a tunnel needs a LIVE host, either in the backup or
 * already live on this device. A tunnel whose host is only a tombstone on this device (and absent from the
 * backup) is therefore skipped, and counted in [BackupImportReport.tunnelsSkipped].
 *
 * That rule INTENTIONALLY differs from the sync guard in `DesktopSyncRepository.applyRemoteBundle`, where a
 * tombstoned host counts as present. Sync replicates state between devices: a tombstone is a legitimate
 * state, the foreign key only needs the row, and the tunnel it carries will be reconciled by the merges that
 * follow (dropping it would lose data the peer still holds). A restore is a deliberate user action whose
 * result must be usable: the user deleted that host on this device and the backup does not bring it back, so
 * a tunnel attached to it would be live but unusable (no address, "?" in the list, travelling in bundles and
 * backups). A host that IS in the backup is revived, and its tunnels with it.
 *
 * Logs carry counts only: never a host, a label, a command, a fingerprint, a key name or an exception message.
 */
class DesktopVaultImporter(
    private val vaultManager: VaultManager,
    private val restoreStore: VaultRestoreStore,
    private val settings: BackupSettingsAccess,
    private val deriver: PublicKeyDeriver = SshPublicKeyDeriver,
) {

    suspend fun import(data: ByteArray, passphrase: CharArray): Result<BackupImportReport> {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        return try {
            if (!BackupLimits.fitsFile(data.size.toLong())) {
                return Result.failure(VaultImportError.FileTooLarge)
            }
            val frame = BackupEnvelope.unpack(data) ?: return Result.failure(VaultImportError.Malformed)

            derivedKey = DesktopCryptoUtils.deriveKeyFromPassphrase(passphrase, frame.salt)

            val decrypted = try {
                DesktopCryptoUtils.decryptAesGcm(frame.sealed, derivedKey)
            } catch (e: AEADBadTagException) {
                return Result.failure(VaultImportError.WrongPassphrase)
            } catch (e: IllegalArgumentException) {
                return Result.failure(VaultImportError.Malformed)
            }
            jsonBytes = decrypted

            val decoded = when (val result = BackupCodec.decode(decrypted)) {
                is BackupDecodeResult.Decoded -> result
                is BackupDecodeResult.UnsupportedVersion ->
                    return Result.failure(VaultImportError.UnsupportedVersion(result.version))
                BackupDecodeResult.Malformed -> return Result.failure(VaultImportError.Malformed)
            }

            // Semantic validation of the new sections, still before any write. A known host the local SSH
            // library cannot read is counted as invalid.
            val validated = when (
                val result = BackupValidator.validate(decoded.document) {
                    KnownHostPortable.fromPortable(it.keyType, it.publicKey) != null
                }
            ) {
                is BackupValidationResult.Valid -> result.backup
                BackupValidationResult.Malformed -> return Result.failure(VaultImportError.Malformed)
            }
            val backup = validated.document

            val snippets = backup.snippets.map {
                Snippet(
                    id = it.id,
                    label = it.label,
                    command = it.command,
                    category = it.category,
                    hostId = it.hostId,
                    createdAt = it.createdAt,
                )
            }
            val customThemes = backup.customThemes.map {
                CustomTerminalTheme(
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
            // The validator already ran the same conversion: a null here is not expected, and is dropped and
            // counted rather than trusted.
            var unreadableKnownHosts = 0
            val knownHosts = backup.knownHosts.mapNotNull { entry ->
                val key = KnownHostPortable.fromPortable(entry.keyType, entry.publicKey)
                if (key == null) {
                    unreadableKnownHosts++
                    null
                } else {
                    StoredKnownHost(entry.hostPort, key.algorithm, key.encoded, entry.addedAt)
                }
            }

            // SSH keys: the "add only" rules decide, per key id, what goes in. Everything that reads the
            // vault or runs a signature happens HERE, before the transaction and off the UI thread.
            val keyPlan = planKeys(backup.sshKeys, backup.privateKeys, backup.keyPassphrases)

            // Database half, ONE transaction. An existing item is updated in place (never replaced: that
            // would drop its tunnels and its local-only columns), a new one is inserted, a local item absent
            // from the backup is kept. The store skips a tunnel whose host is not available and tells how many.
            val restored = restoreStore.restore(
                hosts = backup.hosts,
                sshKeys = keyPlan.rows,
                tunnels = backup.tunnels,
                snippets = snippets,
                customThemes = customThemes,
                knownHosts = knownHosts,
            )
            if (restored.skippedTunnels > 0) {
                // Count only: labels, hostnames and ids stay out of the logs.
                Logger.w(TAG, "import: ${restored.skippedTunnels} tunnel(s) skipped, host not available on this device")
            }
            if (restored.knownHostConflicts.isNotEmpty()) {
                Logger.w(TAG, "import: ${restored.knownHostConflicts.size} known host(s) kept with their local key")
            }

            // Steps after the database. Each one has its own try: a failure is logged by class name, recorded
            // in the report (partial restore) and never turns a restore that already happened into a failure.
            val failedSteps = LinkedHashSet<BackupRestoreStep>()

            // Credentials, private keys, passphrases and certificates into the vault. Defensively skip
            // any LAN sync secret a legacy backup (pre-fix) may still carry: it's a
            // device-local auth key that must not be imported onto another machine.
            // Counted as they are written: after a failure the report shows what really went in.
            var passwords = 0
            var privateKeys = 0
            var keyPassphrases = 0
            var certificates = 0
            try {
                backup.credentials.forEach { (id, password) ->
                    if (id.startsWith(LAN_SYNC_SECRET_KEY_PREFIX)) return@forEach
                    val chars = password.toCharArray()
                    try {
                        vaultManager.storePassword(id, chars)
                    } finally {
                        Arrays.fill(chars, '\u0000')
                    }
                    passwords++
                }
                // Only the secrets the key rules allow: a private key already on this device is never replaced.
                backup.privateKeys.forEach { (id, pem) ->
                    if (id !in keyPlan.writePrivateKey) return@forEach
                    vaultManager.storePrivateKey(id, pem)
                    privateKeys++
                }
                // Passphrases for encrypted private keys: without them an imported encrypted key fails at connect.
                backup.keyPassphrases.forEach { (id, pass) ->
                    if (id !in keyPlan.writePassphrase) return@forEach
                    val chars = pass.toCharArray()
                    try {
                        vaultManager.storeKeyPassphrase(id, chars)
                    } finally {
                        Arrays.fill(chars, '\u0000')
                    }
                    keyPassphrases++
                }
                // SSH certificates (validated and bounded by the shared validator).
                backup.certificates.forEach { (id, cert) ->
                    vaultManager.storeCertificate(id, cert)
                    certificates++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "import: vault step failed: ${e.javaClass.simpleName}")
                failedSteps += BackupRestoreStep.VAULT_SECRETS
            }

            // Settings last: preferences are not transactional, and only common + desktop are applied.
            val settingsResult = try {
                settings.apply(backup.settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "import: settings step failed: ${e.javaClass.simpleName}")
                failedSteps += BackupRestoreStep.SETTINGS
                BackupSettingsApplyResult(applied = 0, languageChanged = false)
            }

            Logger.d(TAG, "import done: ${snippets.size} snippet(s), ${customThemes.size} theme(s), ${restored.knownHostsAdded} known host(s) added, ${settingsResult.applied} setting(s) applied")

            Result.success(
                BackupImportReport(
                    hosts = backup.hosts.size,
                    sshKeys = backup.sshKeys.size,
                    tunnels = restored.restoredTunnels,
                    passwords = passwords,
                    privateKeys = privateKeys,
                    keyPassphrases = keyPassphrases,
                    certificates = certificates,
                    snippets = snippets.size,
                    customThemes = customThemes.size,
                    knownHostsAdded = restored.knownHostsAdded,
                    knownHostsAlreadyKnown = restored.knownHostsAlreadyKnown,
                    settingsApplied = settingsResult.applied,
                    invalidIgnored = validated.invalidTotal + unreadableKnownHosts,
                    tunnelsSkipped = restored.skippedTunnels,
                    newerDataSkipped = decoded.hasUnknownSections,
                    languageChanged = settingsResult.languageChanged,
                )
                    .copy(
                        publicKeysRecomputed = keyPlan.publicKeysRecomputed,
                        localKeysKept = keyPlan.localKeysKept,
                    )
                    .withKnownHostConflicts(restored.knownHostConflicts)
                    .withHeldKnownHosts(restored.knownHostsHeld)
                    .withFailedSteps(failedSteps),
            )
        } catch (e: VaultImportError) {
            Result.failure(e)
        } catch (e: CancellationException) {
            // A cancelled import is not a failed one: never turn it into an IoError result. The finally
            // block below still wipes the passphrase and the key material.
            throw e
        } catch (e: Exception) {
            // Class name only: the message of a database or crypto error may quote the content.
            Logger.w(TAG, "import failed: ${e.javaClass.simpleName}")
            Result.failure(VaultImportError.IoError)
        } finally {
            derivedKey?.let { DesktopCryptoUtils.secureWipe(it) }
            jsonBytes?.let { DesktopCryptoUtils.secureWipe(it) }
            Arrays.fill(passphrase, '\u0000')
        }
    }

    /** What the "add only" key rules decided for the whole file (see [planKeys]). Never holds a secret. */
    private class KeyPlan(
        val rows: List<SshKey>,
        val writePrivateKey: Set<String>,
        val writePassphrase: Set<String>,
        val publicKeysRecomputed: Int,
        val localKeysKept: Int,
    )

    /**
     * Applies [BackupKeyRules] to every SSH key id of the file (rows, private keys and passphrases). Reads the
     * local rows and, for a key the file also carries a private key for, the local private key of the vault
     * (a read failure counts as "a private key is present": the safe value). The derivation runs WITHOUT
     * passphrase (no key derivation function at import, so a file of thousands of encrypted keys cannot
     * stall it) and off the UI thread; an encrypted key proves nothing and changes nothing.
     */
    private suspend fun planKeys(
        rows: List<SshKey>,
        privateKeys: Map<String, String>,
        passphrases: Map<String, String>,
    ): KeyPlan {
        val byId = rows.associateBy { it.id }
        val ids = LinkedHashSet<String>().apply {
            addAll(byId.keys)
            addAll(privateKeys.keys)
            addAll(passphrases.keys)
        }
        val locals = restoreStore.liveSshKeys(ids)
        val decidedRows = ArrayList<SshKey>()
        val privateToWrite = HashSet<String>()
        val passphraseToWrite = HashSet<String>()
        var recomputed = 0
        var kept = 0
        var storedKeyIds: Set<String>? = null
        // One wrapper per restore, dropped with it: a private key repeated under many ids is derived once.
        val restoreDeriver = deriver.memoizedWithoutPassphrase()
        for (id in ids) {
            val incoming = byId[id]
            val local = locals[id]
            val filePrivateKey = privateKeys[id]
            val localState = if (local == null) {
                LocalPrivateKey.ABSENT
            } else {
                localPrivateKeyState(id, filePrivateKey) {
                    storedKeyIds ?: vaultManager.listStoredKeyIds().toSet().also { storedKeyIds = it }
                }
            }
            val derivation = if (filePrivateKey != null &&
                BackupKeyRules.shouldDerive(incoming, local, true, localState)
            ) {
                withContext(Dispatchers.Default) { restoreDeriver.derive(filePrivateKey, null) }
            } else {
                null
            }
            val decision = BackupKeyRules.decide(
                incoming = incoming,
                local = local,
                filePrivateKey = filePrivateKey != null,
                filePassphrase = id in passphrases,
                localPrivateKey = localState,
                derivation = derivation,
            )
            decision.key?.let { decidedRows += it }
            if (decision.writePrivateKey) privateToWrite += id
            if (decision.writePassphrase) passphraseToWrite += id
            if (decision.publicKeyRecomputed) recomputed++
            if (decision.localKeyKept) kept++
        }
        return KeyPlan(decidedRows, privateToWrite, passphraseToWrite, recomputed, kept)
    }

    /**
     * The vault state of [id]. The private key is decrypted only to compare it with the file's one
     * ([filePrivateKey] not null). Otherwise only its PRESENCE matters, read from the list of stored ids
     * ([storedIds], one listing for the whole import, nothing decrypted). Any read failure gives
     * [LocalPrivateKey.PRESENT]: the safe value, the file never replaces a key it could not compare.
     */
    private suspend fun localPrivateKeyState(
        id: String,
        filePrivateKey: String?,
        storedIds: suspend () -> Set<String>,
    ): LocalPrivateKey = try {
        if (filePrivateKey != null) {
            BackupKeyRules.localPrivateKeyState(vaultManager.getPrivateKey(id), filePrivateKey)
        } else if (id in storedIds()) {
            LocalPrivateKey.PRESENT
        } else {
            LocalPrivateKey.ABSENT
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "import: local private key unreadable: ${errorClass(e)}")
        LocalPrivateKey.PRESENT
    }

    companion object {
        /** Largest file the importer reads (50 MB): the limit of the shared format. */
        const val MAX_BACKUP_SIZE: Int = 50 * 1024 * 1024
    }
}
