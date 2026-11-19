// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyLoader
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyManager
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultExporter
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.VaultImportError
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.scope.ScreenViewModel
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.backup.SshKeyRules
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyExport
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyExportRules
import fr.techtical.nextsh.shared.core.ssh.provenKey
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import kotlinx.coroutines.flow.combine
import fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Arrays

/**
 * What the Vault screen tells the user after an operation. Typed on purpose: the screen turns each case into
 * a localized resource, so no French text and no exception message (which could carry file content) lives here.
 */
sealed interface VaultNotice {
    /** True for failures, which stay on screen until the next action. */
    val isError: Boolean get() = false

    /** False when the notice must stay until the next action. Errors never auto-dismiss. */
    val autoDismiss: Boolean get() = !isError

    data object LabelRequired : VaultNotice {
        override val isError: Boolean get() = true
    }

    data class KeyGenerated(val label: String) : VaultNotice

    data object KeyGenerationFailed : VaultNotice {
        override val isError: Boolean get() = true
    }

    data class Fido2KeyEnrolled(val label: String) : VaultNotice

    data class KeyImported(val label: String) : VaultNotice

    data object KeyPassphraseRequired : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object KeyPassphraseWrong : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object KeyUnreadable : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object KeyTooLarge : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object KeyImportFailed : VaultNotice {
        override val isError: Boolean get() = true
    }

    /** The key file asks for more key derivation work than NextSH accepts (forged or absurd settings). */
    data object KeyTooCostly : VaultNotice {
        override val isError: Boolean get() = true
    }

    /** The key is encrypted with a scheme NextSH does not accept: not imported, never opened. */
    data object KeyEncryptionUnsupported : VaultNotice {
        override val isError: Boolean get() = true
    }

    /** The public key written in the file is not the one of its private key: not imported. */
    data object KeyInconsistent : VaultNotice {
        override val isError: Boolean get() = true
    }

    /** The saved public key did not match the private key: the right one was copied and the key fixed. Stays on screen. */
    data object PublicKeyCorrected : VaultNotice {
        override val autoDismiss: Boolean get() = false
    }

    /** The public key was copied without a check, the private key being unreadable. Stays on screen. */
    data object PublicKeyUnverified : VaultNotice {
        override val autoDismiss: Boolean get() = false
    }

    /** Nothing to copy: no stored public key and none can be derived. */
    data object PublicKeyUnavailable : VaultNotice {
        override val isError: Boolean get() = true
    }

    /**
     * [notRestorable] counts the snippets and themes that a restore would refuse (invisible character, too long).
     * The export is not blocked, but the warning stays on screen until the next action.
     */
    data class BackupExported(val path: String, val entries: Int, val notRestorable: Int = 0) : VaultNotice {
        override val autoDismiss: Boolean get() = notRestorable == 0
    }

    data object BackupExportFailed : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object BackupFileTooLarge : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object BackupWrongPassphrase : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object BackupMalformed : VaultNotice {
        override val isError: Boolean get() = true
    }

    data class BackupUnsupportedVersion(val version: Int) : VaultNotice {
        override val isError: Boolean get() = true
    }

    data object BackupImportFailed : VaultNotice {
        override val isError: Boolean get() = true
    }
}

/**
 * Reads [file] by chunks and gives up (null) as soon as more than [maxBytes] were read, whatever
 * `File.length()` claimed. Memory stays bounded by [maxBytes] plus one chunk.
 */
internal fun readAtMost(file: File, maxBytes: Long): ByteArray? {
    file.inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}

/** Bytes read from a private key file: 64 KiB of key plus a margin for the blanks around it. */
private const val MAX_KEY_FILE_BYTES: Long = BackupLimits.MAX_SECRET_VALUE_BYTES + 4L * 1024

/** The notice for a key that SSHJ could not read: no passphrase asked and one needed, a wrong one, or a bad file. */
internal fun loadFailureNotice(e: Throwable, passphraseSupplied: Boolean): VaultNotice =
    if (DesktopSshKeyLoader.isLikelyPassphraseError(e)) {
        if (!passphraseSupplied) VaultNotice.KeyPassphraseRequired else VaultNotice.KeyPassphraseWrong
    } else {
        VaultNotice.KeyUnreadable
    }

data class VaultOperationState(
    val isBusy: Boolean = false,
    val notice: VaultNotice? = null,
    /**
     * Summary of the last successful backup import. The screen shows it in a dialog that stays until the
     * user closes it (see [VaultScreenViewModel.dismissImportReport]); it is not a transient message.
     */
    val importReport: BackupImportReport? = null,
)

/**
 * ViewModel de l'écran Vault. Les observateurs de la base (`keys`, `hostsUsingKey`) vivent dans le scope de
 * l'écran ; la génération, l'import, l'export et la suppression restent dans le scope de l'app : ils vont
 * au bout même si l'écran est quitté pendant l'opération.
 */
class VaultScreenViewModel(
    appScope: AppScope = DesktopContainer.appScope,
    private val keyRepository: SshKeyRepository = DesktopContainer.sshKeyRepository,
    private val hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val vaultManager: VaultManager = DesktopContainer.vaultManager,
    private val generate: GenerateSshKeyUseCase = DesktopContainer.generateSshKeyUseCase,
    private val keyManager: DesktopSshKeyManager = DesktopSshKeyManager(),
    private val exporter: DesktopVaultExporter = DesktopContainer.vaultExporter,
    private val importer: DesktopVaultImporter = DesktopContainer.vaultImporter,
    private val deriver: PublicKeyDeriver = SshPublicKeyDeriver,
) : ScreenViewModel(appScope) {
    val keys: StateFlow<List<SshKey>> = keyRepository
        .observeAll()
        .stateIn(screenScope, SharingStarted.Eagerly, emptyList())

    /**
     * Map de keyId → nb d'hôtes utilisant cette clé (auth `SSH_KEY` ou
     * `CERTIFICATE` avec `host.credentialId == key.id`). Recalculé à chaque
     * changement de la liste des hôtes : Eagerly pour que la UI reçoive la
     * valeur au premier rendu.
     */
    val hostsUsingKey: StateFlow<Map<String, Int>> = hostRepository
        .observeAll()
        .combine(keys) { hosts, allKeys ->
            allKeys.associate { key ->
                key.id to hosts.count { h ->
                    (h.authType == AuthType.SSH_KEY || h.authType == AuthType.CERTIFICATE) &&
                        h.credentialId == key.id
                }
            }
        }
        .stateIn(screenScope, SharingStarted.Eagerly, emptyMap())

    private val _status = MutableStateFlow(VaultOperationState())
    val status: StateFlow<VaultOperationState> = _status.asStateFlow()

    /** Clears a transient notice. A pending import summary is left alone: only [dismissImportReport] removes it. */
    fun clearStatus() {
        val current = _status.value
        if (current.notice != null) _status.value = current.copy(notice = null)
    }

    fun dismissImportReport() {
        val current = _status.value
        if (current.importReport != null) _status.value = current.copy(importReport = null)
    }

    /**
     * Notifie l'UI qu'une clé FIDO2 sk-ssh-ed25519 vient d'être enrôlée.
     * La clé est déjà sauvegardée dans le repository par [Fido2EnrollViewModel] :
     * on se contente d'afficher le message de confirmation.
     */
    fun notifyFido2KeyEnrolled(key: SshKey) {
        _status.value = VaultOperationState(notice = VaultNotice.Fido2KeyEnrolled(key.label))
    }

    fun generateKey(label: String, keyType: SshKeyType) {
        if (label.isBlank()) {
            _status.value = VaultOperationState(notice = VaultNotice.LabelRequired)
            return
        }
        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { generate(label.trim(), keyType) }
            val generated = result.getOrNull()
            _status.value = if (generated != null) {
                VaultOperationState(notice = VaultNotice.KeyGenerated(generated.label))
            } else {
                VaultOperationState(notice = VaultNotice.KeyGenerationFailed)
            }
        }
    }

    fun importKey(file: File, label: String, passphrase: CharArray?) {
        if (label.isBlank()) {
            _status.value = VaultOperationState(notice = VaultNotice.LabelRequired)
            passphrase?.let { java.util.Arrays.fill(it, '\u0000') }
            return
        }
        val effectivePassphrase = passphrase?.takeIf { it.isNotEmpty() }  // empty = no passphrase at all
        // SSHJ's `PasswordUtils.createOneOff` wipes the char[] it's given after the first
        // `reqPassword` call (defensive security). If we pass [effectivePassphrase]
        // straight to SSHJ and THEN try to persist it in the vault, we persist a wiped
        // array: at connect time the stored "passphrase" is all zeros and decrypt fails.
        // Snapshot it up front so we have an untouched copy to store.
        val passphraseSnapshot = effectivePassphrase?.copyOf()

        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            try {
                // Bounded read, counted as it is read (File.length() is not trusted): a private key
                // is at most 64 KiB, the limit of one secret in a backup, plus a margin for blanks.
                val raw = withContext(Dispatchers.IO) { readAtMost(file, MAX_KEY_FILE_BYTES) }
                val pem = raw?.toString(Charsets.UTF_8)
                if (pem == null || pem.trim().toByteArray(Charsets.UTF_8).size > BackupLimits.MAX_SECRET_VALUE_BYTES) {
                    _status.value = VaultOperationState(notice = VaultNotice.KeyTooLarge)
                    return@launch
                }

                // Use the shared loader so we can distinguish passphrase errors from format
                // errors: the legacy [keyManager.extractPublicKey] returns null on either.
                // SSHJ reads a key lazily, at its first `private` / `public` access: the read is
                // forced here, inside the try, or an encrypted key would never be detected.
                // The shared deriver runs FIRST: it bounds the cost of the key derivation function (a forged
                // file with 2^31 bcrypt rounds would spin a core for ever inside SSHJ) and reads the key
                // from its private half. SSHJ only comes after, for the keys the deriver accepts or does
                // not read.
                val derivation = withContext(Dispatchers.Default) { deriver.derive(pem, passphraseSnapshot) }
                val refusal = when (derivation) {
                    is SshPublicKeyDerivation.TooCostly -> VaultNotice.KeyTooCostly
                    is SshPublicKeyDerivation.TooLarge -> VaultNotice.KeyTooLarge
                    is SshPublicKeyDerivation.NeedsPassphrase -> VaultNotice.KeyPassphraseRequired
                    is SshPublicKeyDerivation.WrongPassphrase -> VaultNotice.KeyPassphraseWrong
                    // SSHJ presents the public copy of the file, not the key of its private half: such a
                    // file could never connect.
                    is SshPublicKeyDerivation.Inconsistent -> VaultNotice.KeyInconsistent
                    // An encrypted key whose scheme the deriver does not accept: never handed to SSHJ, which
                    // would run the key derivation function with the typed passphrase and no bound.
                    is SshPublicKeyDerivation.UnsupportedEncryption -> VaultNotice.KeyEncryptionUnsupported
                    else -> null
                }
                if (refusal != null) {
                    _status.value = VaultOperationState(notice = refusal)
                    return@launch
                }
                val publicKeyOpenSsh = try {
                    withContext(Dispatchers.IO) {
                        val keyProvider = DesktopSshKeyLoader.loadKeyProviderFromString(pem, effectivePassphrase)
                        keyProvider.private
                        val pub = keyProvider.public
                        val keyType = net.schmizz.sshj.common.KeyType.fromKey(pub)
                        val encoded = java.util.Base64.getEncoder().encodeToString(
                            net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(pub).compactData
                        )
                        val fallback = "$keyType $encoded nextsh-imported"
                        // The public key stored is the one computed from the PRIVATE key and proven by a
                        // signature. Not derivable (unsupported format): the line read by SSHJ, as before.
                        derivation.provenKey()?.line("nextsh-imported") ?: fallback
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The loader's message may quote the file: only the category reaches the screen.
                    _status.value = VaultOperationState(notice = loadFailureNotice(e, effectivePassphrase != null))
                    return@launch
                } catch (e: OutOfMemoryError) {
                    // A forged key can announce gigabytes in a length field: unreadable, not a crash.
                    _status.value = VaultOperationState(notice = VaultNotice.KeyUnreadable)
                    return@launch
                }
                val id = randomUuid()
                vaultManager.storePrivateKey(id, pem)
                if (passphraseSnapshot != null) {
                    // storeKeyPassphrase copies the array internally.
                    vaultManager.storeKeyPassphrase(id, passphraseSnapshot.copyOf())
                }
                keyRepository.save(
                    SshKey(
                        id = id,
                        label = label.trim(),
                        keyType = inferType(publicKeyOpenSsh),
                        publicKey = publicKeyOpenSsh,
                        isBiometric = false,
                        keystoreAlias = null,
                    ),
                )
                _status.value = VaultOperationState(notice = VaultNotice.KeyImported(label.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = VaultOperationState(notice = VaultNotice.KeyImportFailed)
            } finally {
                passphrase?.let { java.util.Arrays.fill(it, '\u0000') }
                passphraseSnapshot?.let { java.util.Arrays.fill(it, '\u0000') }
            }
        }
    }

    fun deleteKey(key: SshKey) {
        appScope.coroutineScope.launch {
            // A key owns its private key, its passphrase and its certificate (stored
            // under the key id). Not the password stored under the same id: a host
            // switched from key to password keeps the key id as its credentialId, and
            // that password belongs to the host.
            vaultManager.deletePrivateKey(key.id)
            vaultManager.deleteKeyPassphrase(key.id)
            vaultManager.deleteCertificate(key.id)
            keyRepository.delete(key.id)
        }
    }

    /**
     * The public key line to copy or save for [key], or null when there is none (then a notice says why).
     *
     * The stored line is never trusted: for a software key it is checked against the private key of the vault
     * ([SshPublicKeyExportRules]), with the stored passphrase when the key is encrypted. A wrong stored line
     * is replaced by the derived one, the key is corrected (the sync spreads it) and a notice says so. A key
     * whose private key cannot be read is copied as stored, with a notice. A FIDO2 key is copied as stored.
     * Never logs the key, and never keeps the passphrase.
     */
    suspend fun publicKeyLine(key: SshKey): String? {
        val derivation = if (SshKeyRules.isHardwareKey(key)) null else deriveStoredKey(key)
        return when (val export = SshPublicKeyExportRules.resolve(key, derivation)) {
            is SshPublicKeyExport.Verified -> export.line
            is SshPublicKeyExport.Hardware -> export.line
            is SshPublicKeyExport.Corrected -> {
                try {
                    keyRepository.update(export.correctedKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The line to copy is right anyway: the correction of the stored key will be retried at the next copy.
                }
                if (export.hadStoredLine) _status.value = VaultOperationState(notice = VaultNotice.PublicKeyCorrected)
                export.line
            }
            is SshPublicKeyExport.Unverified -> {
                _status.value = VaultOperationState(notice = VaultNotice.PublicKeyUnverified)
                export.line
            }
            SshPublicKeyExport.Unavailable -> {
                _status.value = VaultOperationState(notice = VaultNotice.PublicKeyUnavailable)
                null
            }
        }
    }

    /** The derivation of the private key stored for [key], or null when the vault has none or cannot be read. */
    private suspend fun deriveStoredKey(key: SshKey): SshPublicKeyDerivation? {
        var passphrase: CharArray? = null
        return try {
            val pem = vaultManager.getPrivateKey(key.id) ?: return null
            passphrase = vaultManager.getKeyPassphrase(key.id)
            withContext(Dispatchers.Default) { deriver.derive(pem, passphrase) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            passphrase?.let { Arrays.fill(it, '\u0000') }
        }
    }

    /**
     * Export the vault to [destination]. [passphrase] is copied and wiped by the
     * exporter: the caller's array is not reused after this call.
     */
    fun exportBackup(destination: File, passphrase: CharArray) {
        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            var bytes: ByteArray? = null
            try {
                val export = withContext(Dispatchers.IO) { exporter.exportWithSummary(passphrase) }
                val produced = export.bytes
                bytes = produced
                val counts = export.counts
                withContext(Dispatchers.IO) {
                    destination.parentFile?.takeIf { !it.exists() }?.mkdirs()
                    destination.writeBytes(produced)
                }
                _status.value = VaultOperationState(
                    notice = VaultNotice.BackupExported(destination.absolutePath, counts.total, counts.notRestorable),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = VaultOperationState(notice = VaultNotice.BackupExportFailed)
            } finally {
                bytes?.fill(0)
            }
        }
    }

    /**
     * Import a `.nextsh` backup from [source]. Success publishes the typed [BackupImportReport] in [status];
     * a failure publishes a [VaultNotice] (never the importer's or the decoder's text). [passphrase] is copied
     * and wiped by the importer, and wiped here on every path.
     */
    fun importBackup(source: File, passphrase: CharArray) {
        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            try {
                if (!BackupLimits.fitsFile(source.length())) {
                    _status.value = VaultOperationState(notice = VaultNotice.BackupFileTooLarge)
                    return@launch
                }
                // The size above can be 0 (special file) or out of date (file still growing): the read itself
                // is bounded too.
                val bytes = withContext(Dispatchers.IO) { readAtMost(source, BackupLimits.MAX_FILE_BYTES) }
                if (bytes == null) {
                    _status.value = VaultOperationState(notice = VaultNotice.BackupFileTooLarge)
                    return@launch
                }
                val result = importer.import(bytes, passphrase)
                result.fold(
                    onSuccess = { report ->
                        _status.value = VaultOperationState(importReport = report)
                    },
                    onFailure = { err ->
                        _status.value = VaultOperationState(notice = noticeFor(err))
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = VaultOperationState(notice = VaultNotice.BackupImportFailed)
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }

    private fun noticeFor(err: Throwable): VaultNotice = when (err) {
        is VaultImportError.FileTooLarge -> VaultNotice.BackupFileTooLarge
        is VaultImportError.WrongPassphrase -> VaultNotice.BackupWrongPassphrase
        is VaultImportError.Malformed -> VaultNotice.BackupMalformed
        is VaultImportError.UnsupportedVersion -> VaultNotice.BackupUnsupportedVersion(err.version)
        else -> VaultNotice.BackupImportFailed
    }

    private fun inferType(publicKeyOpenSsh: String): SshKeyType {
        val prefix = publicKeyOpenSsh.substringBefore(' ')
        return when (prefix) {
            "ssh-ed25519" -> SshKeyType.ED25519
            "ssh-rsa" -> SshKeyType.RSA_4096
            "ecdsa-sha2-nistp256" -> SshKeyType.ECDSA_256
            "ecdsa-sha2-nistp384" -> SshKeyType.ECDSA_384
            "ecdsa-sha2-nistp521" -> SshKeyType.ECDSA_521
            "sk-ssh-ed25519@openssh.com" -> SshKeyType.SK_ED25519
            "sk-ecdsa-sha2-nistp256@openssh.com" -> SshKeyType.SK_ECDSA_256
            else -> SshKeyType.ED25519
        }
    }
}
