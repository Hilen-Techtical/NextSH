// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.crypto.BiometricKeyAlias
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.crypto.StrongBiometricStatus
import fr.techtical.nextsh.core.di.DefaultDispatcher
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.DocumentDeleter
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultBackupReadException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.data.repository.KeyUsage
import fr.techtical.nextsh.data.repository.KeyUsageReader
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ExportVaultUseCase
import fr.techtical.nextsh.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportVaultUseCase
import fr.techtical.nextsh.domain.usecase.InconsistentKeyException
import fr.techtical.nextsh.domain.usecase.KeyTooCostlyException
import fr.techtical.nextsh.domain.usecase.KeyTooLargeException
import fr.techtical.nextsh.domain.usecase.UnreadableKeyException
import fr.techtical.nextsh.domain.usecase.UnsupportedKeyEncryptionException
import fr.techtical.nextsh.domain.usecase.WrongKeyPassphraseException
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.backup.BackupReportRow
import fr.techtical.nextsh.shared.core.backup.BackupRestoreStep
import fr.techtical.nextsh.shared.core.backup.BackupReportWarning
import fr.techtical.nextsh.shared.core.backup.SshKeyRules
import fr.techtical.nextsh.shared.core.backup.SshPublicKeyLine
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyExport
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyExportRules
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException as SharedVaultAuthExpiredException

/**
 * The sentence that says why a biometric key cannot be created while the phone is in [status], or
 * null when it can. No `else`: a new status will not compile without a sentence.
 */
@StringRes
internal fun biometricKeyBlockedMessageRes(status: StrongBiometricStatus): Int? = when (status) {
    StrongBiometricStatus.AVAILABLE -> null
    StrongBiometricStatus.NO_HARDWARE -> R.string.vault_biometric_no_sensor
    StrongBiometricStatus.NOT_ENROLLED -> R.string.vault_biometric_not_enrolled
    StrongBiometricStatus.UNAVAILABLE -> R.string.vault_biometric_unavailable
}

/**
 * True when [e], or one of its causes, says that the authentication window of the vault is over.
 * Two classes carry that meaning: the app's (thrown by the Android vault) and the shared one,
 * which is what a shared use case such as the key generation reports, the app's staying as its
 * cause (see VaultManagerAdapter).
 */
private fun isVaultAuthExpired(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any {
        it is VaultAuthExpiredException || it is SharedVaultAuthExpiredException
    }

private const val MAX_CAUSE_DEPTH = 8

/** Comment of the public key line of a biometric key read from the Keystore when the stored line has none. */
private const val BIOMETRIC_DEFAULT_COMMENT = "nextsh-biometric"

/**
 * The sentence for a key that could not be generated or deleted. An expired vault window gets its
 * own words (retrying cannot work before the lock screen opens the vault again); anything else gets [fallback]. The
 * message of the exception is never read: it can carry a path or a label.
 */
@StringRes
internal fun keyActionFailureRes(e: Throwable, @StringRes fallback: Int): Int =
    if (isVaultAuthExpired(e)) R.string.vault_error_auth_expired else fallback

/**
 * The sentence for a key that could not be imported: the typed reasons of the use case first (a
 * wrong passphrase, a file that is not a readable key), then [keyActionFailureRes]. The message
 * of the exception is never read: it can carry what the SSH library says about the file.
 */
@StringRes
internal fun keyImportFailureRes(e: Throwable): Int = when (e) {
    is WrongKeyPassphraseException -> R.string.vault_key_import_wrong_passphrase
    is UnreadableKeyException -> R.string.vault_key_import_unreadable
    is KeyTooLargeException -> R.string.vault_key_import_too_large
    is KeyTooCostlyException -> R.string.vault_key_import_too_costly
    is InconsistentKeyException -> R.string.vault_key_import_inconsistent
    is UnsupportedKeyEncryptionException -> R.string.vault_key_import_unsupported_encryption
    else -> keyActionFailureRes(e, R.string.vault_key_import_failed)
}

/** The label of a line of the import summary. No `else`: a new row will not compile without a label. */
@StringRes
internal fun importRowLabelRes(row: BackupReportRow): Int = when (row) {
    BackupReportRow.HOSTS -> R.string.vault_import_row_hosts
    BackupReportRow.SSH_KEYS -> R.string.vault_import_row_ssh_keys
    BackupReportRow.TUNNELS -> R.string.vault_import_row_tunnels
    BackupReportRow.PASSWORDS -> R.string.vault_import_row_passwords
    BackupReportRow.PRIVATE_KEYS -> R.string.vault_import_row_private_keys
    BackupReportRow.KEY_PASSPHRASES -> R.string.vault_import_row_key_passphrases
    BackupReportRow.CERTIFICATES -> R.string.vault_import_row_certificates
    BackupReportRow.SNIPPETS -> R.string.vault_import_row_snippets
    BackupReportRow.CUSTOM_THEMES -> R.string.vault_import_row_custom_themes
    BackupReportRow.KNOWN_HOSTS_ADDED -> R.string.vault_import_row_known_hosts_added
    BackupReportRow.KNOWN_HOSTS_ALREADY_KNOWN -> R.string.vault_import_row_known_hosts_known
    BackupReportRow.SETTINGS_APPLIED -> R.string.vault_import_row_settings
}

/** The words for a restore step that failed. No `else`: a new step will not compile without a label. */
@StringRes
internal fun importStepLabelRes(step: BackupRestoreStep): Int = when (step) {
    BackupRestoreStep.VAULT_SECRETS -> R.string.vault_import_step_secrets
    BackupRestoreStep.KNOWN_HOSTS -> R.string.vault_import_step_known_hosts
    BackupRestoreStep.SETTINGS -> R.string.vault_import_step_settings
}

/** The `host:port` names of a warning, followed by "and N others" when more were counted. */
private fun namedHostPorts(resources: Resources, names: List<String>, notNamed: Int): String {
    val joined = names.joinToString(", ")
    return if (notNamed > 0) {
        joined + " " + resources.getQuantityString(R.plurals.vault_import_warning_more, notNamed, notNamed)
    } else {
        joined
    }
}

/** One "label: count" line of the import summary, in the language of [resources]. */
internal fun importRowText(resources: Resources, row: BackupReportRow, count: Int): String =
    resources.getString(R.string.vault_import_row_format, resources.getString(importRowLabelRes(row)), count)

/**
 * The sentence of one warning of the import summary. Only counts and the `host:port` names the
 * report carries (at most [BackupImportReport.MAX_NAMED_CONFLICTS]) are used.
 */
internal fun importWarningText(
    resources: Resources,
    report: BackupImportReport,
    warning: BackupReportWarning,
): String = when (warning) {
    BackupReportWarning.TUNNELS_SKIPPED -> resources.getQuantityString(
        R.plurals.vault_import_skipped_tunnels,
        report.tunnelsSkipped,
        report.tunnelsSkipped,
    )
    BackupReportWarning.KNOWN_HOST_CONFLICTS -> resources.getQuantityString(
        R.plurals.vault_import_warning_known_host_conflicts,
        report.knownHostConflicts,
        report.knownHostConflicts,
        namedHostPorts(resources, report.knownHostConflictNames, report.knownHostConflictsNotNamed),
    )
    BackupReportWarning.KNOWN_HOST_HELD -> resources.getQuantityString(
        R.plurals.vault_import_warning_known_host_held,
        report.knownHostsHeld,
        report.knownHostsHeld,
        namedHostPorts(resources, report.knownHostHeldNames, report.knownHostsHeldNotNamed),
    )
    BackupReportWarning.PUBLIC_KEYS_RECOMPUTED -> resources.getQuantityString(
        R.plurals.vault_import_warning_public_keys_recomputed,
        report.publicKeysRecomputed,
        report.publicKeysRecomputed,
    )
    BackupReportWarning.LOCAL_KEYS_KEPT -> resources.getQuantityString(
        R.plurals.vault_import_warning_local_keys_kept,
        report.localKeysKept,
        report.localKeysKept,
    )
    BackupReportWarning.PARTIAL_RESTORE -> resources.getString(
        R.string.vault_import_warning_partial,
        // Fixed order (enum order), whatever order the steps were recorded in.
        BackupRestoreStep.entries.filter { it in report.failedSteps }
            .joinToString(", ") { resources.getString(importStepLabelRes(it)) },
    )
    BackupReportWarning.INVALID_IGNORED -> resources.getQuantityString(
        R.plurals.vault_import_warning_invalid,
        report.invalidIgnored,
        report.invalidIgnored,
    )
    BackupReportWarning.NEWER_DATA_NOT_RESTORED -> resources.getString(R.string.vault_import_warning_newer_data)
    BackupReportWarning.LANGUAGE_AFTER_RESTART -> resources.getString(R.string.vault_import_warning_language)
}

/** The backup file is longer than [BackupLimits.MAX_FILE_BYTES]. Carries nothing. */
private class BackupFileTooLargeException : IOException()

/**
 * Reads [input] whole, but gives up with [BackupFileTooLargeException] as soon as more than
 * [BackupLimits.MAX_FILE_BYTES] bytes have come in: the size a document provider announces is not
 * trusted, and a huge file is never held in memory.
 */
private fun readAtMost(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_CHUNK_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (!BackupLimits.fitsFile(total)) throw BackupFileTooLargeException()
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val READ_CHUNK_BYTES = 64 * 1024

/**
 * A sentence of the screen kept as a string resource and its arguments, for the actions that have
 * no Context: the screen reads it with [resolve] in the language of the phone at that moment.
 */
data class MessageRes(
    @StringRes val res: Int,
    val args: List<String> = emptyList(),
) {
    fun resolve(context: Context): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args.toTypedArray())
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val vaultManager: VaultManager,
    private val sshKeyManager: SshKeyManager,
    private val keystoreManager: KeystoreManager,
    private val biometricHelper: BiometricHelper,
    private val importSshKey: ImportSshKeyUseCase,
    private val generateSshKey: GenerateSshKeyUseCase,
    private val exportVaultUseCase: ExportVaultUseCase,
    private val importVaultUseCase: ImportVaultUseCase,
    private val documentDeleter: DocumentDeleter,
    private val keyUsageReader: KeyUsageReader,
    private val appLock: AppLock,
    private val publicKeyDeriver: PublicKeyDeriver = SshPublicKeyDeriver,
    @DefaultDispatcher private val derivationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * The public key line to copy or save for one key ([publicKeyForExport]): [line] is null when there is
     * nothing to copy. [notice] is the sentence to show, or null when the plain "copied" message is enough.
     */
    class PublicKeyForExport(val line: String?, val notice: MessageRes?)

    data class VaultUiState(
        val sshKeys: List<SshKey> = emptyList(),
        val storedCredentialCount: Int = 0,
        val isLoading: Boolean = true,
        val isGenerating: Boolean = false,
        /**
         * True from the moment the export passphrase is confirmed (the backup is being
         * encrypted) until the file is written or the export is abandoned.
         */
        val isExporting: Boolean = false,
        /**
         * True once the backup is encrypted and held in memory: the screen must now open the
         * location picker, once, then call [onExportPickerLaunched]. The bytes themselves never
         * appear in the state.
         */
        val exportReadyToSave: Boolean = false,
        val isImporting: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
        /**
         * [error] and [successMessage] for the actions that have no Context (generating,
         * importing and deleting a key): the sentence stays a resource and the screen reads it
         * in the current language, see [errorText] and [successText].
         *
         * At most one of [error] and [errorRes] is set at a time, same for the success pair: the
         * ViewModel only sets them through [withError], [withErrorRes], [withSuccess] and
         * [withSuccessRes], each of which clears the other one of its pair, so the message shown
         * is always the last one set.
         */
        val errorRes: MessageRes? = null,
        val successRes: MessageRes? = null,
        /**
         * Non-null when a passphrase is required to import the pending key.
         * Carries the label + raw PEM so the UI can re-invoke `importKey` once
         * the user supplies the passphrase via a dialog.
         */
        val pendingEncryptedImport: PendingImport? = null,
        /**
         * Non-null while the dialog that confirms the deletion of a key is open. It holds what
         * the key is still used for, read when the deletion was requested.
         */
        val pendingKeyDeletion: PendingKeyDeletion? = null,
        /**
         * Non-null while the summary of a finished import is on screen. It holds counts and at
         * most three `host:port` names, no secret; it lives here so the dialog survives a rotation.
         */
        val importReport: BackupImportReport? = null,
        /**
         * Above zero after a successful export while the warning about the snippets and themes
         * that a restore would refuse is on screen. A count, nothing else.
         */
        val exportNotRestorable: Int = 0,
    ) {
        /** The error to show, whichever way it was set. */
        fun errorText(context: Context): String? = error ?: errorRes?.resolve(context)

        /** The success message to show, whichever way it was set. */
        fun successText(context: Context): String? = successMessage ?: successRes?.resolve(context)

        /** True when a success message is set, in either form. */
        val hasSuccess: Boolean get() = successMessage != null || successRes != null

        /** Sets (or, with null, clears) the error as a string; a resource error is dropped. */
        fun withError(message: String?): VaultUiState = copy(error = message, errorRes = null)

        /** Sets (or, with null, clears) the error as a resource; a string error is dropped. */
        fun withErrorRes(message: MessageRes?): VaultUiState = copy(error = null, errorRes = message)

        /** Sets (or, with null, clears) the success message as a string; a resource is dropped. */
        fun withSuccess(message: String?): VaultUiState = copy(successMessage = message, successRes = null)

        /** Sets (or, with null, clears) the success message as a resource; a string is dropped. */
        fun withSuccessRes(message: MessageRes?): VaultUiState = copy(successMessage = null, successRes = message)
    }

    data class PendingImport(
        val label: String,
        val pem: String,
    )

    /**
     * A key whose deletion waits for the user's answer, with what still depends on it.
     *
     * [keyLabel] and [hostNames] are labels the user chose: they are shown on screen only, never
     * logged.
     *
     * @property hostNames the labels of the hosts that connect with the key, [MAX_NAMED_HOSTS] at
     * most, alphabetical.
     * @property otherHostCount how many hosts use the key beyond the named ones.
     * @property tunnelCount how many live tunnels go through these hosts.
     * @property usageCheckFailed true when the lookup of the hosts failed: the dialog is the plain
     * one plus a line saying that the check could not be made. The deletion stays possible.
     */
    data class PendingKeyDeletion(
        val keyId: String,
        val keyLabel: String,
        val hostNames: List<String> = emptyList(),
        val otherHostCount: Int = 0,
        val tunnelCount: Int = 0,
        val usageCheckFailed: Boolean = false,
    ) {
        /** Every host that uses the key, named or not. */
        val hostCount: Int get() = hostNames.size + otherHostCount

        /** False when nothing uses the key: the plain dialog is shown. */
        val isInUse: Boolean get() = hostCount > 0
    }

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        // Observe SSH keys from repository
        viewModelScope.launch {
            sshKeyRepository.observeAll().collect { keys ->
                _uiState.update { state ->
                    state.copy(
                        sshKeys = keys,
                        isLoading = false,
                    )
                }
            }
        }

        // Count the passwords the hosts use, and follow the hosts
        observeHostPasswordCount()
    }

    /**
     * Keeps [VaultUiState.storedCredentialCount] in step with the hosts: the count is made again on
     * Dispatchers.IO at each emission of the host list (a host created, a backup restored), also
     * while this ViewModel outlives the screen, and a count still running when a new list comes in
     * is dropped. A count that fails shows 0 and the next emission tries again; a host list that
     * cannot be read ends the watch with 0 shown. Leaving the screen is no failure and logs nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHostPasswordCount() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                hostRepository.observeAll()
                    .mapLatest { hosts -> countHostPasswordsOrZero(hosts) }
                    .collect { count ->
                        _uiState.update { it.copy(storedCredentialCount = count, isLoading = false) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportPasswordCountFailure(e)
                _uiState.update { it.copy(storedCredentialCount = 0, isLoading = false) }
            }
        }
    }

    private fun countHostPasswordsOrZero(hosts: List<Host>): Int = try {
        countHostPasswords(hosts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportPasswordCountFailure(e)
        0
    }

    /**
     * The number of distinct credential ids of the live [hosts] that sign in with a password and
     * have one in the vault. Each id is asked with [VaultManager.hasPassword], which decrypts no
     * value: listing the stored ids would decrypt every secret of the vault (passwords, private
     * keys, passphrases, certificates) at each opening of the screen, and would also count what no
     * host uses, such as the secrets of the devices enrolled for the LAN sync.
     */
    private fun countHostPasswords(hosts: List<Host>): Int =
        hosts
            .filter { it.authType == AuthType.PASSWORD }
            .map { it.credentialId }
            .distinct()
            .count { vaultManager.hasPassword(it) }

    /** Class chain only: the message of the failure is never logged. An expired vault window locks the app. */
    private fun reportPasswordCountFailure(e: Exception) {
        Timber.e("Vault access failed (${errorClass(e)})")
        lockIfVaultExpired(e)
    }

    fun generateKey(label: String, type: SshKeyType = SshKeyType.ED25519) {
        if (label.isBlank()) {
            _uiState.update { it.withErrorRes(MessageRes(R.string.vault_biometric_name_empty)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true).withError(null) }
            val result = generateSshKey(label.trim(), type)
            result.fold(
                onSuccess = { key ->
                    Timber.i("Key generated: id=${LogId.of(key.id)} type=${key.keyType}")
                    _uiState.update {
                        it.copy(isGenerating = false)
                            .withSuccessRes(MessageRes(R.string.vault_key_generated, listOf(key.keyType.name, key.label)))
                    }
                },
                onFailure = { e ->
                    // Class chain only: the message of the failure is never logged nor shown.
                    Timber.e("Key generation failed (${errorClass(e)})")
                    lockIfVaultExpired(e)
                    _uiState.update {
                        it.copy(isGenerating = false)
                            .withErrorRes(MessageRes(keyActionFailureRes(e, R.string.vault_key_generate_failed)))
                    }
                },
            )
        }
    }

    fun importKey(label: String, privateKeyPem: String, passphrase: CharArray? = null) {
        if (label.isBlank()) {
            _uiState.update { it.withErrorRes(MessageRes(R.string.vault_biometric_name_empty)) }
            passphrase?.fill('\u0000')
            return
        }
        if (privateKeyPem.isBlank()) {
            _uiState.update { it.withErrorRes(MessageRes(R.string.vault_key_pem_empty)) }
            passphrase?.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true).withError(null) }
            val trimmedLabel = label.trim()
            val trimmedPem = privateKeyPem.trim()
            val result = importSshKey(trimmedLabel, trimmedPem, passphrase)
            passphrase?.fill('\u0000')
            result.fold(
                onSuccess = { key ->
                    Timber.i("Key imported: id=${LogId.of(key.id)} type=${key.keyType}")
                    _uiState.update {
                        it.copy(isGenerating = false, pendingEncryptedImport = null)
                            .withSuccessRes(MessageRes(R.string.vault_key_imported, listOf(key.label)))
                    }
                },
                onFailure = { e ->
                    if (e is fr.techtical.nextsh.domain.usecase.PassphraseRequiredException) {
                        // Signal the UI to prompt for a passphrase; hold the PEM in state
                        // so the user doesn't have to re-pick the file.
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                pendingEncryptedImport = PendingImport(trimmedLabel, trimmedPem),
                            )
                        }
                    } else {
                        // Class chain only: the message of the failure can quote the file.
                        Timber.e("Key import failed (${errorClass(e)})")
                        lockIfVaultExpired(e)
                        _uiState.update {
                            it.copy(isGenerating = false).withErrorRes(MessageRes(keyImportFailureRes(e)))
                        }
                    }
                },
            )
        }
    }

    /** Cancel a pending encrypted-key import, discards the held PEM and resets the prompt state. */
    fun cancelEncryptedImport() {
        _uiState.update { it.copy(pendingEncryptedImport = null) }
    }

    // The lookup of what uses the key being deleted. One at a time: a second request replaces the
    // first, and an answer or a deletion cancels a lookup that is still running.
    private var keyUsageLookup: Job? = null

    /**
     * The user asked to delete [key]: looks up (read only) which hosts connect with it and which
     * tunnels go through them, then opens the confirmation dialog through
     * [VaultUiState.pendingKeyDeletion]. Nothing is deleted before [deleteKey].
     *
     * The dialog opens once the lookup is done, never before: showing the plain one first would
     * let the user confirm a deletion that the warning was about to describe. A lookup of the
     * hosts that fails does not block the deletion either: the plain dialog opens with a line
     * saying that the check could not be made ([PendingKeyDeletion.usageCheckFailed]), and only
     * the class of the failure is logged (the labels are the user's data). A failure of the count
     * of the tunnels alone is absorbed by [KeyUsageReader]: the hosts are still warned about.
     */
    fun requestKeyDeletion(key: SshKey) {
        keyUsageLookup?.cancel()
        keyUsageLookup = viewModelScope.launch {
            var usageCheckFailed = false
            val usage = try {
                keyUsageReader.usageOf(key.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Key usage lookup failed (${errorClass(e)})")
                usageCheckFailed = true
                KeyUsage.NONE
            }
            val named = usage.hostLabels.take(MAX_NAMED_HOSTS)
            _uiState.update {
                it.copy(
                    pendingKeyDeletion = PendingKeyDeletion(
                        keyId = key.id,
                        keyLabel = key.label,
                        hostNames = named,
                        otherHostCount = usage.hostLabels.size - named.size,
                        tunnelCount = usage.tunnelCount,
                        usageCheckFailed = usageCheckFailed,
                    ),
                )
            }
        }
    }

    /**
     * The public key line "Copy" and "Download" put out for [key], never the stored line taken on trust
     * ([SshPublicKeyExportRules]): a software key is derived from its private key in the vault (with its
     * stored passphrase, on [derivationDispatcher]) and the derived line wins; if the stored line was
     * wrong the key is saved corrected and the notice says so. A biometric key reads its public key from
     * the Keystore (no line when the entry is not on this device); a FIDO2 key gives its stored line. A
     * key that cannot be read is copied as stored, with a notice. Nothing of the private key, of the
     * passphrase or of the exception is logged: the class chain only.
     */
    suspend fun publicKeyForExport(key: SshKey): PublicKeyForExport {
        if (key.isBiometric) return biometricPublicKeyForExport(key)
        val derivation = withContext(derivationDispatcher) { derivePrivateKeyOf(key) }
        return when (val export = SshPublicKeyExportRules.resolve(key, derivation)) {
            is SshPublicKeyExport.Verified -> PublicKeyForExport(export.line, null)
            is SshPublicKeyExport.Hardware -> PublicKeyForExport(export.line, null)
            is SshPublicKeyExport.Corrected -> {
                try {
                    sshKeyRepository.update(export.correctedKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The copy still goes ahead with the right line; the key stays as it was.
                    Timber.e("Public key correction failed (${errorClass(e)})")
                }
                PublicKeyForExport(
                    export.line,
                    if (export.hadStoredLine) MessageRes(R.string.vault_message_pub_key_corrected) else null,
                )
            }
            is SshPublicKeyExport.Unverified ->
                PublicKeyForExport(export.line, MessageRes(R.string.vault_message_pub_key_unverified))
            SshPublicKeyExport.Unavailable ->
                PublicKeyForExport(null, MessageRes(R.string.vault_message_pub_key_unavailable))
        }
    }

    /** The derivation of the private key of [key] in the vault, or null when there is none or it cannot be read. */
    private fun derivePrivateKeyOf(key: SshKey): SshPublicKeyDerivation? {
        if (SshKeyRules.isHardwareKey(key)) return null
        var passphrase: CharArray? = null
        return try {
            val pem = vaultManager.getPrivateKey(key.id) ?: return null
            passphrase = vaultManager.getKeyPassphrase(key.id)
            publicKeyDeriver.derive(pem, passphrase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Private key read for the public key failed (${errorClass(e)})")
            lockIfVaultExpired(e)
            null
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    /** A biometric key: its public key is read from the Keystore, no authentication needed to read it. */
    private fun biometricPublicKeyForExport(key: SshKey): PublicKeyForExport {
        val line: String? = try {
            val publicKey = keystoreManager.getBiometricPublicKey(BiometricKeyAlias.forKeyId(key.id))
            sshKeyManager.formatKeystorePublicKeyOpenSsh(
                publicKey,
                SshPublicKeyLine.comment(key.publicKey) ?: BIOMETRIC_DEFAULT_COMMENT,
            )
        } catch (e: Exception) {
            Timber.e("Biometric public key read failed (${errorClass(e)})")
            null
        }
        if (line == null) return PublicKeyForExport(null, MessageRes(R.string.vault_message_pub_key_biometric_missing))
        return when (val export = SshPublicKeyExportRules.resolve(key.copy(publicKey = line), null)) {
            is SshPublicKeyExport.Hardware -> PublicKeyForExport(export.line, null)
            else -> PublicKeyForExport(null, MessageRes(R.string.vault_message_pub_key_unavailable))
        }
    }

    /** The user backed out of the confirmation dialog: nothing is deleted. */
    fun cancelKeyDeletion() {
        keyUsageLookup?.cancel()
        _uiState.update { it.copy(pendingKeyDeletion = null) }
    }

    /**
     * Deletes the key [keyId] and closes the confirmation dialog. The caller has asked the user
     * first (see [requestKeyDeletion]).
     */
    fun deleteKey(keyId: String) {
        keyUsageLookup?.cancel()
        _uiState.update { it.copy(pendingKeyDeletion = null) }
        viewModelScope.launch {
            try {
                // Vérifier si c'est une clé biométrique pour supprimer du Keystore
                val key = sshKeyRepository.getById(keyId)
                if (key?.isBiometric == true) {
                    // Only ever the key's own Keystore entry: the alias stored in the row is
                    // ignored. A row that carries the alias of another key (a legacy restore or
                    // sync) must never destroy that other key.
                    keystoreManager.deleteBiometricKey(BiometricKeyAlias.forKeyId(key.id))
                } else {
                    vaultManager.deletePrivateKey(keyId)
                    // La passphrase et le certificat vivent sous le même id que la clé : sans
                    // ces deux suppressions ils restent dans le vault et continuent de se
                    // synchroniser. Jamais deletePassword ici : pwd_<id> peut appartenir à un
                    // hôte PASSWORD qui a hérité de l'id de la clé (défaut de la 1.0.0).
                    vaultManager.deleteKeyPassphrase(keyId)
                    vaultManager.deleteCertificate(keyId)
                }
                sshKeyRepository.delete(keyId)
                _uiState.update { it.withSuccessRes(MessageRes(R.string.vault_key_deleted)) }
            } catch (e: Exception) {
                // Class chain only: the message of the failure is never logged nor shown.
                Timber.e("Key deletion failed (${errorClass(e)})")
                lockIfVaultExpired(e)
                _uiState.update {
                    it.withErrorRes(MessageRes(keyActionFailureRes(e, R.string.vault_key_delete_failed)))
                }
            }
        }
    }

    /**
     * Whether the phone can create a biometric key right now (a strong biometric is enrolled and
     * ready). The screen reads it to turn the option off, with the sentence from
     * [biometricKeyBlockedMessageRes], instead of letting the user fail at generation.
     */
    fun strongBiometricStatus(): StrongBiometricStatus = biometricHelper.checkStrongBiometric()

    /**
     * Génère une clé SSH biométrique dans le Keystore Android.
     * La clé privée ne quitte jamais le hardware (TEE/StrongBox).
     *
     * Refused up front when the phone has no strong biometric ready (the device PIN does not count,
     * these keys never accept it): the Keystore would refuse the key anyway, with a message that
     * says nothing about what to do. The sentence of [biometricKeyBlockedMessageRes] does.
     */
    fun generateBiometricKey(label: String, context: Context) {
        // Application context: the messages are read here, the Activity is not kept.
        val appContext = context.applicationContext
        if (label.isBlank()) {
            _uiState.update { it.withError(appContext.getString(R.string.vault_biometric_name_empty)) }
            return
        }
        val blockedMessage = biometricKeyBlockedMessageRes(biometricHelper.checkStrongBiometric())
        if (blockedMessage != null) {
            _uiState.update { it.withError(appContext.getString(blockedMessage)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true).withError(null) }
            try {
                val keyId = java.util.UUID.randomUUID().toString()
                val alias = BiometricKeyAlias.forKeyId(keyId)

                val publicKey = keystoreManager.generateBiometricSshKey(alias)
                val publicKeyOpenSsh = sshKeyManager.formatKeystorePublicKeyOpenSsh(publicKey)

                val sshKey = fr.techtical.nextsh.domain.model.SshKey(
                    id = keyId,
                    label = label.trim(),
                    keyType = fr.techtical.nextsh.domain.model.SshKeyType.ECDSA_256,
                    publicKey = publicKeyOpenSsh,
                    isBiometric = true,
                    keystoreAlias = alias,
                )
                sshKeyRepository.save(sshKey)

                Timber.i("Biometric key generated: id=${LogId.of(sshKey.id)}")
                _uiState.update {
                    it.copy(isGenerating = false)
                        .withSuccess(appContext.getString(R.string.vault_biometric_generated, sshKey.label))
                }
            } catch (e: Exception) {
                // Class chain only: a Keystore message may name the alias of the key.
                Timber.e("Biometric key generation failed (${errorClass(e)})")
                _uiState.update {
                    it.copy(isGenerating = false).withError(appContext.getString(R.string.vault_biometric_failed))
                }
            }
        }
    }

    // ── Export : la passphrase d'abord, l'emplacement ensuite ───────────────────
    //
    // Le sélecteur `CreateDocument` crée le fichier (vide) dès que l'utilisateur choisit
    // l'emplacement. Le demander avant la passphrase laissait donc un fichier vide quand on
    // annulait la saisie, et quand le chiffrement ou l'écriture échouait. Le déroulé est
    // maintenant : passphrase -> chiffrement en mémoire -> sélecteur -> écriture. Rien n'est
    // créé sur le disque tant que le backup chiffré n'est pas prêt.

    /**
     * Backup chiffré en attente d'un emplacement. Volontairement hors de [VaultUiState] (jamais
     * dans un Bundle ni un état sauvegardé) : seul le ViewModel le détient, et il est effacé dès
     * qu'il a été écrit, abandonné, ou que le ViewModel disparaît.
     */
    private val pendingExport = AtomicReference<ByteArray?>(null)

    /**
     * Nombre d'éléments (snippets, thèmes) que l'import refuserait dans le backup en attente : un
     * simple compte, montré après l'écriture réussie du fichier.
     */
    private val pendingNotRestorable = AtomicInteger(0)

    /**
     * Chiffre le vault avec [passphrase] puis le garde en mémoire, prêt à être écrit. La
     * passphrase est effacée dans tous les cas. Une fois le backup prêt, [VaultUiState.exportReadyToSave]
     * passe à vrai : l'écran ouvre alors le sélecteur d'emplacement.
     */
    fun startExport(passphrase: CharArray, context: Context) {
        if (_uiState.value.isExporting) {
            // Un export est déjà en cours : on ne prend pas une seconde passphrase.
            passphrase.fill('\u0000')
            return
        }
        // Contexte application : le chiffrement dure, ne pas retenir l'Activity pendant ce temps.
        val appContext = context.applicationContext
        _uiState.update { it.copy(isExporting = true, exportReadyToSave = false).withError(null) }
        // ATOMIC : même si le ViewModel a déjà disparu, le corps s'exécute et son `finally`
        // efface la passphrase (avec DEFAULT, un scope annulé n'exécuterait rien du tout).
        viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                ensureActive() // scope déjà annulé : ne rien chiffrer, juste effacer la passphrase
                exportVaultUseCase(passphrase).fold(
                    onSuccess = { export ->
                        // Publier d'abord, tester ensuite : si le ViewModel disparaît entre les
                        // deux, soit onCleared() voit déjà les octets, soit isActive est faux ici.
                        // (viewModelScope est annulé avant onCleared.)
                        pendingNotRestorable.set(export.notRestorable)
                        pendingExport.getAndSet(export.bytes)?.fill(0)
                        if (isActive) {
                            _uiState.update { it.copy(exportReadyToSave = true) }
                        } else {
                            discardPendingExport()
                        }
                    },
                    onFailure = { e -> failExport(e.javaClass.simpleName, appContext) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failExport(e.javaClass.simpleName, appContext)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    /** L'écran a ouvert le sélecteur d'emplacement : le signal "prêt" est consommé. */
    fun onExportPickerLaunched() {
        _uiState.update { it.copy(exportReadyToSave = false) }
    }

    /** Aucun sélecteur de fichiers n'a pu s'ouvrir : on abandonne l'export proprement. */
    fun onExportPickerUnavailable(context: Context) {
        discardPendingExport()
        failExport("no document picker", context.applicationContext)
    }

    /**
     * Résultat du sélecteur d'emplacement.
     *
     * @param uri l'emplacement choisi, ou `null` si l'utilisateur a annulé (aucun fichier n'existe
     * alors : on efface simplement le backup en mémoire, sans message).
     * @param belongsToExport vrai si l'écran savait qu'un export attendait cet emplacement (booléen
     * sauvegardé avec l'état de l'écran, sans secret). Un résultat qui n'appartient pas à un
     * export est ignoré : on ne touche jamais à un document qu'on n'a pas demandé.
     */
    fun onExportLocationChosen(uri: Uri?, belongsToExport: Boolean, context: Context) {
        if (!belongsToExport) return
        val appContext = context.applicationContext
        val data = pendingExport.getAndSet(null)
        val notRestorable = pendingNotRestorable.getAndSet(0)

        if (uri == null) {
            data?.fill(0)
            _uiState.update { it.copy(isExporting = false, exportReadyToSave = false) }
            return
        }

        if (data == null) {
            // L'activité a été détruite pendant le sélecteur : ce ViewModel est neuf, le backup
            // chiffré a disparu avec l'ancien. Le fichier vient pourtant d'être créé : on le
            // retire s'il est vide (jamais un fichier existant qui contiendrait des données de
            // l'utilisateur), et on dit de recommencer l'export.
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                documentDeleter.deleteIfEmpty(uri)
                _uiState.update {
                    it.copy(isExporting = false, exportReadyToSave = false)
                        .withError(appContext.getString(R.string.vault_export_interrupted))
                }
            }
            return
        }

        // ATOMIC : même si le ViewModel a disparu entre-temps, le `finally` efface les octets et
        // traite le document créé, sans rien écrire (ensureActive).
        viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var streamOpened = false
            var written = false
            try {
                ensureActive()
                val stream = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Output stream unavailable")
                streamOpened = true
                stream.use { it.write(data) }
                written = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nom de classe seul : le message d'une exception d'E/S peut citer le chemin.
                Timber.e("Backup export write failed: %s", e.javaClass.simpleName)
            } finally {
                data.fill(0)
                if (!written) cleanUpCreatedDocument(uri, streamOpened)
            }
            _uiState.update {
                val finished = it.copy(isExporting = false, exportReadyToSave = false)
                if (written) {
                    // Exported, but some snippets or themes would be refused by a restore: say so.
                    finished.copy(exportNotRestorable = notRestorable)
                        .withSuccess(appContext.getString(R.string.vault_export_success))
                } else {
                    finished.withError(appContext.getString(R.string.vault_export_failed))
                }
            }
        }
    }

    /**
     * Retire le document créé par le sélecteur quand l'écriture n'a pas abouti (au mieux, l'échec
     * de la suppression est ignoré). Une fois le flux ouvert, le contenu est tronqué ou partiel :
     * le fichier ne vaut plus rien, on le supprime. Sinon on ne sait pas ce qu'il contient : on
     * ne le supprime que s'il est connu pour être vide, pour ne jamais détruire un fichier
     * existant de l'utilisateur.
     */
    private fun cleanUpCreatedDocument(uri: Uri, streamOpened: Boolean) {
        if (streamOpened) documentDeleter.delete(uri) else documentDeleter.deleteIfEmpty(uri)
    }

    /** [logReason] : un nom de classe d'exception, jamais son message (il peut citer des données). */
    private fun failExport(logReason: String, context: Context) {
        Timber.e("Backup export failed: %s", logReason)
        _uiState.update {
            it.copy(isExporting = false, exportReadyToSave = false)
                .withError(context.getString(R.string.vault_export_failed))
        }
    }

    /** Efface le backup chiffré en attente, s'il y en a un. */
    private fun discardPendingExport() {
        pendingNotRestorable.set(0)
        pendingExport.getAndSet(null)?.fill(0)
    }

    override fun onCleared() {
        discardPendingExport()
        super.onCleared()
    }

    fun importVault(passphrase: CharArray, inputUri: Uri, context: Context) {
        // Contexte application : ne pas retenir l'Activity pendant l'import.
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isImporting = true, importReport = null).withError(null) }
            try {
                // The size is checked while reading: a file over the limit is dropped as soon as
                // it is known to be over, never held whole in memory.
                val data = try {
                    appContext.contentResolver.openInputStream(inputUri)?.use { readAtMost(it) }
                } catch (_: BackupFileTooLargeException) {
                    _uiState.update {
                        it.copy(isImporting = false).withError(appContext.getString(R.string.vault_import_file_too_large))
                    }
                    return@launch
                }
                if (data == null) {
                    _uiState.update {
                        it.copy(isImporting = false).withError(appContext.getString(R.string.vault_import_file_unreadable))
                    }
                    return@launch
                }
                val result = importVaultUseCase(data, passphrase)
                result.fold(
                    onSuccess = { report ->
                        // The summary is a dialog: tunnels skipped, fingerprints kept and the
                        // like need more than the few seconds of a message.
                        Timber.i("Backup imported: %s", report)
                        _uiState.update {
                            it.copy(isImporting = false, importReport = report)
                        }
                    },
                    onFailure = { e ->
                        lockIfVaultExpired(e)
                        _uiState.update {
                            it.copy(isImporting = false).withError(importFailureMessage(e, appContext))
                        }
                    },
                )
            } catch (e: Exception) {
                lockIfVaultExpired(e)
                _uiState.update {
                    it.copy(isImporting = false).withError(importFailureMessage(e, appContext))
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    /**
     * Message shown for a failed import. A backup that could not be read gets a fixed localized
     * text chosen from its typed reason: the message of such a failure is never displayed, and it
     * never carries any content of the file anyway (see [VaultBackupReadException]). An expired
     * vault window and any other failure get a fixed sentence too: the message of an exception is
     * never displayed.
     */
    /**
     * An expired vault window locks the app: the lock screen opens the vault again (see AppLock),
     * which is what the sentence of [keyActionFailureRes] asks the user to wait for.
     */
    private fun lockIfVaultExpired(e: Throwable) {
        if (isVaultAuthExpired(e)) appLock.lock()
    }

    private fun importFailureMessage(e: Throwable, context: Context): String = when (e) {
        is VaultBackupReadException -> when (e.reason) {
            VaultBackupReadException.Reason.WRONG_PASSPHRASE ->
                context.getString(R.string.error_backup_wrong_passphrase)
            VaultBackupReadException.Reason.MALFORMED ->
                context.getString(R.string.error_backup_malformed)
            VaultBackupReadException.Reason.UNSUPPORTED_VERSION ->
                context.getString(R.string.error_backup_unsupported_version)
        }
        else -> context.getString(keyActionFailureRes(e, R.string.vault_import_failed))
    }

    /** The user closed the warning shown after an export. */
    fun dismissExportWarning() {
        _uiState.update { it.copy(exportNotRestorable = 0) }
    }

    /** The user closed the import summary. */
    fun dismissImportReport() {
        _uiState.update { it.copy(importReport = null) }
    }

    fun clearMessage() {
        _uiState.update { it.withError(null).withSuccess(null) }
    }

    companion object {
        /** Hosts named in the dialog that warns before a key is deleted; the others are counted. */
        const val MAX_NAMED_HOSTS = 3
    }
}
