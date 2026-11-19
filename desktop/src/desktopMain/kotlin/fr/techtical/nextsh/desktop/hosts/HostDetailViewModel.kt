// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.scope.ScreenViewModel
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.shared.core.backup.BackupFieldProblem
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Arrays

data class HostDetailFormState(
    val hostId: String? = null,
    val label: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val terminalTheme: String = "TECHTICAL_DARK",
    val credentialId: String = "",
    /**
     * Free-form folder/group label (parity with Android Solo). Used by
     * the sidebar host tree to bucket hosts. Blank → "Sans groupe" bucket.
     */
    val group: String = "",
    /**
     * Distinct non-blank `host.group` values existing in the DB, used by
     * the group field as autocomplete suggestions. Refreshed on `load()`.
     */
    val availableGroups: List<String> = emptyList(),
    /**
     * Keys available for SSH_KEY / CERTIFICATE authentication. Filtered to
     * exportable keys only: biometric TEE and FIDO2 hardware-bound keys are
     * excluded because their private part is not in the vault.
     */
    val availableKeys: List<SshKey> = emptyList(),
    /**
     * FIDO2 SK-* keys available for FIDO2 authentication. Filtered to
     * keys with keyType SK_ED25519 or SK_ECDSA_256 and fido2CredentialId non-null.
     */
    val availableFido2Keys: List<SshKey> = emptyList(),
    /** For CERTIFICATE auth: pasted/chosen cert content not yet committed to vault. */
    val pendingCertPem: String? = null,
    /** Display name of the cert file picked via JFileChooser (for feedback only). */
    val pendingCertFileName: String? = null,
    /** True if a cert is already stored in the vault for this credentialId (edit flow). */
    val certAlreadyStored: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: HostFormError? = null,
    /**
     * Why the last save was refused by the data layer, or null. Typed so that the screen localizes it
     * ([error] is the form validation, typed the same way); cleared when the user edits the form.
     */
    val saveError: HostSaveError? = null,
) {
    /**
     * True when the typed host name could not be restored from a backup (inner space, control or invisible
     * character, over 255 chars). Judged after trimming, like the saved value. An empty field is not flagged
     * here: the required-field check covers it.
     */
    val hostnameInvalid: Boolean
        get() = hostname.isNotBlank() && !BackupValidator.isValidHostName(hostname.trim())

    /**
     * Why the typed user name is refused, or null. Judged after trimming, like the saved value. A user name
     * with a control character could never connect, and a restore cleans it: the editor refuses it so that
     * what is saved is what a backup gives back. An empty field is not flagged: the required-field check
     * covers it.
     */
    val usernameProblem: BackupFieldProblem?
        get() {
            val trimmed = username.trim()
            return when {
                trimmed.length > BackupLimits.MAX_FREE_TEXT_LENGTH -> BackupFieldProblem.TOO_LONG
                trimmed.any { it.isISOControl() } -> BackupFieldProblem.CONTROL_CHARACTER
                else -> null
            }
        }

    val isExisting: Boolean get() = hostId != null
    val usesKeyAuth: Boolean get() = authType == AuthType.SSH_KEY || authType == AuthType.CERTIFICATE
}

/** Why the form was refused before any write. Typed so that the screen localizes it. */
enum class HostFormError {
    HOST_NOT_FOUND,
    HOST_DELETED,
    LABEL_REQUIRED,
    HOSTNAME_REQUIRED,
    PORT_OUT_OF_RANGE,
    USERNAME_REQUIRED,
    KEY_REQUIRED,
    KEY_MISSING,
    CERT_REQUIRED,
    FIDO2_KEY_REQUIRED,
    FIDO2_KEY_MISSING,
    BIOMETRIC_UNSUPPORTED,
}

/** A save that passed the form validation but could not be written. */
enum class HostSaveError {
    /** The host could not be written. Nothing was saved. */
    SAVE_FAILED,

    /** The host row was written, but its password or certificate could not be stored in the vault. */
    VAULT_FAILED,
}

private const val TAG = "HostDetailViewModel"

/**
 * ViewModel du formulaire d'un hôte. Le chargement du formulaire et l'observation des thèmes vivent dans le
 * scope de l'écran ; l'enregistrement, la suppression et la gestion des thèmes restent dans le scope de
 * l'app pour aller au bout même si l'écran est quitté juste après.
 */
class HostDetailViewModel(
    private val repository: HostRepository = DesktopContainer.hostRepository,
    private val sshKeyRepository: SshKeyRepository = DesktopContainer.sshKeyRepository,
    private val hostFolderRepository: fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository =
        DesktopContainer.hostFolderRepository,
    private val vaultManager: VaultManager = DesktopContainer.vaultManager,
    private val customThemeRepository: CustomTerminalThemeRepository = DesktopContainer.customThemeRepository,
    appScope: AppScope = DesktopContainer.appScope,
    private val tunnelService: DesktopTunnelService = DesktopContainer.tunnelService,
) : ScreenViewModel(appScope) {
    private val _state = MutableStateFlow(HostDetailFormState(credentialId = randomUuid()))
    val state: StateFlow<HostDetailFormState> = _state.asStateFlow()

    /** User-defined terminal themes, observed for the per-host theme selector. */
    val customThemes: StateFlow<List<CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(screenScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun load(hostId: String?) {
        screenScope.launch {
            val allKeys = sshKeyRepository.observeAll().first()
            // Exportable keys: exclude biometric TEE (Android-only) and FIDO2 hardware keys.
            // Both have `null` PEM in the vault, so auth would fail anyway: hiding them
            // upfront is cleaner UX.
            val keys = allKeys.filter { !it.isBiometric && it.fido2CredentialId == null }
            // FIDO2 SK-* keys: only those with a credentialId (the hardware key reference)
            val fido2Keys = allKeys.filter {
                (it.keyType == SshKeyType.SK_ED25519 || it.keyType == SshKeyType.SK_ECDSA_256) &&
                    it.fido2CredentialId != null
            }
            val groups = hostFolderRepository.observeAllGroups().first()

            if (hostId == null) {
                _state.value = HostDetailFormState(
                    credentialId = randomUuid(),
                    availableKeys = keys,
                    availableFido2Keys = fido2Keys,
                    availableGroups = groups,
                )
                return@launch
            }
            _state.value = _state.value.copy(isLoading = true, error = null, saveError = null)
            val host = repository.getById(hostId)
            _state.value = if (host != null) {
                val certStored = host.authType == AuthType.CERTIFICATE &&
                    vaultManager.getCertificate(host.credentialId) != null
                HostDetailFormState(
                    hostId = host.id,
                    label = host.label,
                    hostname = host.hostname,
                    port = host.port.toString(),
                    username = host.username,
                    authType = host.authType,
                    terminalTheme = host.terminalTheme,
                    credentialId = host.credentialId,
                    group = host.group ?: "",
                    availableGroups = groups,
                    availableKeys = keys,
                    availableFido2Keys = fido2Keys,
                    certAlreadyStored = certStored,
                )
            } else {
                HostDetailFormState(
                    credentialId = randomUuid(),
                    availableKeys = keys,
                    availableFido2Keys = fido2Keys,
                    availableGroups = groups,
                    error = HostFormError.HOST_NOT_FOUND,
                )
            }
        }
    }

    fun onLabel(v: String) { _state.value = _state.value.copy(label = v, error = null, saveError = null) }
    fun onHostname(v: String) { _state.value = _state.value.copy(hostname = v, error = null, saveError = null) }
    fun onPort(v: String) {
        if (v.all { it.isDigit() } && v.length <= 5) _state.value = _state.value.copy(port = v, error = null, saveError = null)
    }
    fun onUsername(v: String) { _state.value = _state.value.copy(username = v, error = null, saveError = null) }
    fun onGroup(v: String) { _state.value = _state.value.copy(group = v, error = null, saveError = null) }

    fun onAuthType(v: AuthType) {
        val cur = _state.value
        if (cur.authType == v) return
        val newCredentialId = when (v) {
            // Switching back to password → fresh UUID if we don't already have a
            // password-style id (existing host editing keeps its id, which is
            // itself the vault key). For new hosts, always allocate a fresh UUID.
            AuthType.PASSWORD -> if (cur.isExisting && cur.authType == AuthType.PASSWORD) cur.credentialId else randomUuid()
            // Key auth modes: credentialId will be chosen via key picker, clear until selected.
            AuthType.SSH_KEY, AuthType.CERTIFICATE -> ""
            // FIDO2: credentialId points to the SshKey.id (SK-* key in vault), clear until selected.
            AuthType.FIDO2 -> ""
            // BIOMETRIC_KEY: Android-only, keep current id (disabled in UI).
            AuthType.BIOMETRIC_KEY -> cur.credentialId
        }
        _state.value = cur.copy(
            authType = v,
            credentialId = newCredentialId,
            error = null, saveError = null,
            pendingCertPem = if (v == AuthType.CERTIFICATE) cur.pendingCertPem else null,
            pendingCertFileName = if (v == AuthType.CERTIFICATE) cur.pendingCertFileName else null,
        )
    }

    fun onSelectKey(keyId: String) {
        _state.value = _state.value.copy(credentialId = keyId, error = null, saveError = null)
    }

    fun onCertLoaded(pem: String, fileName: String) {
        _state.value = _state.value.copy(
            pendingCertPem = pem,
            pendingCertFileName = fileName,
            error = null, saveError = null,
        )
    }

    fun onTerminalTheme(v: String) { _state.value = _state.value.copy(terminalTheme = v, error = null, saveError = null) }

    /**
     * The password text lives in the screen, not in [state] (never keep a secret in a state that can be
     * logged or copied). The screen calls this on every keystroke so the stale error banner goes away.
     */
    fun onPasswordEdited() { _state.value = _state.value.copy(error = null, saveError = null) }

    /**
     * Create or update a custom terminal theme from the Host detail screen, no
     * live SSH session required. Persists via the repo (which CRDT-syncs it), then
     * triggers live-apply on the session manager so any OPEN session currently
     * rendering this theme re-paints instantly with the edit. The reactive
     * [customThemes] flow updates the per-host selector on its own.
     */
    fun saveCustomTheme(theme: CustomTerminalTheme) {
        appScope.coroutineScope.launch {
            customThemeRepository.save(theme)
            // Hand the session manager the up-to-date list (merge the saved theme
            // into the current snapshot) so live-apply doesn't race the async
            // observeAll() emission feeding its internal snapshot.
            val merged = customThemes.value.filterNot { it.id == theme.id } + theme
            DesktopContainer.sessionManager.refreshCustomThemes(merged, theme.id)
        }
    }

    /** Delete a custom theme from the Host detail screen. */
    fun deleteCustomTheme(id: String) {
        appScope.coroutineScope.launch {
            customThemeRepository.delete(id)
        }
    }

    /**
     * Save entry point of the screen. [typedPassword] is the text of the password field: it is copied into
     * a fresh CharArray for each attempt (which [save] wipes), and the field itself must be cleared by
     * [onSaved] only, that is once the save went through. If the save is refused the screen still holds
     * what the user typed, so a retry stores the password too: after a vault failure on a NEW host the
     * form has turned into an existing-host form, and an empty field would mean "keep the stored password",
     * that is no password at all.
     */
    fun saveForm(typedPassword: String, onSaved: () -> Unit) {
        val chars = if (_state.value.authType == AuthType.PASSWORD && typedPassword.isNotEmpty()) {
            typedPassword.toCharArray()
        } else {
            null
        }
        save(chars, onSaved)
    }

    fun save(password: CharArray?, onDone: () -> Unit) {
        val s = _state.value
        val validation = validate(s)
        if (validation != null) {
            _state.value = s.copy(error = validation)
            password?.let { Arrays.fill(it, '\u0000') }
            return
        }
        if (s.hostnameInvalid || s.usernameProblem != null) {
            // The reason is already under the field: nothing is written.
            password?.let { Arrays.fill(it, '\u0000') }
            return
        }
        _state.value = s.copy(isSaving = true, error = null, saveError = null)
        appScope.coroutineScope.launch {
            var hostWritten = false
            try {
                // Re-read the stored host so fields the form does not own are current.
                // Null means it was deleted meanwhile: write nothing (host or vault);
                // the `finally` still wipes the password.
                val storedId = s.hostId
                val stored = if (storedId != null) {
                    repository.getById(storedId) ?: run {
                        _state.value = _state.value.copy(
                            isSaving = false,
                            error = HostFormError.HOST_DELETED,
                        )
                        return@launch
                    }
                } else {
                    null
                }
                val host = buildHost(s, stored, randomUuid())
                if (stored != null) {
                    repository.update(host)
                } else {
                    repository.save(host)
                    // The host exists from now on. Remember its id so that a retry (after a vault failure
                    // below, say) goes through update instead of creating a second, duplicate host.
                    _state.value = _state.value.copy(hostId = host.id)
                }
                hostWritten = true

                when (s.authType) {
                    AuthType.PASSWORD -> {
                        if (password != null && password.isNotEmpty()) {
                            vaultManager.storePassword(s.credentialId, password)
                        }
                    }
                    AuthType.CERTIFICATE -> {
                        // credentialId points to an existing SshKey.id: the PEM is already
                        // in the vault from key import. We only persist the cert here.
                        s.pendingCertPem?.let { cert ->
                            vaultManager.storeCertificate(s.credentialId, cert)
                        }
                    }
                    AuthType.SSH_KEY, AuthType.FIDO2, AuthType.BIOMETRIC_KEY -> Unit
                }
                _state.value = _state.value.copy(isSaving = false)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refused write used to leave isSaving stuck on true, with the Save button disabled for
                // good. The two cases are told apart: the host row may already be written when the vault
                // write is the one that failed. Class name only in the log: an exception message can quote
                // row values.
                Logger.e(TAG, "save: failed (${errorClass(e)}, hostWritten=$hostWritten)")
                _state.value = _state.value.copy(
                    isSaving = false,
                    saveError = if (hostWritten) HostSaveError.VAULT_FAILED else HostSaveError.SAVE_FAILED,
                )
            } finally {
                password?.let { Arrays.fill(it, '\u0000') }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val s = _state.value
        val id = s.hostId ?: return
        appScope.coroutineScope.launch {
            // The host's tunnels go with it: stop the running ones first (the delete only touches the
            // database and would leave them forwarding, with no row in the list).
            try {
                tunnelService.stopTunnelsOfHost(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stopping is best effort: the delete goes on. Class name only in the log.
                Logger.e(TAG, "delete: stopping the host tunnels failed (${errorClass(e)})")
            }
            repository.delete(id)
            // SECURITY: only purge what this host *owns*, and only that type. A PASSWORD
            // host owns its password slot, and nothing else: its credentialId may be the
            // id of an SSH key (a host created on Android and switched from key to
            // password keeps it), so the private key, passphrase and certificate stored
            // under the same id must survive. For SSH_KEY / CERTIFICATE the credentialId
            // points to a SshKey shared by potentially many hosts, and the certificate is
            // stored under that same key id: they belong to the key in the vault, deleting
            // them here would orphan the other hosts silently.
            if (s.authType == AuthType.PASSWORD) {
                vaultManager.deletePassword(s.credentialId)
            }
            onDone()
        }
    }

    private fun validate(s: HostDetailFormState): HostFormError? {
        if (s.label.isBlank()) return HostFormError.LABEL_REQUIRED
        if (s.hostname.isBlank()) return HostFormError.HOSTNAME_REQUIRED
        val port = s.port.toIntOrNull()
        if (port == null || port !in 1..65535) return HostFormError.PORT_OUT_OF_RANGE
        if (s.username.isBlank()) return HostFormError.USERNAME_REQUIRED
        return when (s.authType) {
            AuthType.SSH_KEY, AuthType.CERTIFICATE -> when {
                s.credentialId.isBlank() -> HostFormError.KEY_REQUIRED
                s.availableKeys.none { it.id == s.credentialId } -> HostFormError.KEY_MISSING
                s.authType == AuthType.CERTIFICATE && s.pendingCertPem == null && !s.certAlreadyStored ->
                    HostFormError.CERT_REQUIRED
                else -> null
            }
            AuthType.FIDO2 -> when {
                s.credentialId.isBlank() -> HostFormError.FIDO2_KEY_REQUIRED
                s.availableFido2Keys.none { it.id == s.credentialId } -> HostFormError.FIDO2_KEY_MISSING
                else -> null
            }
            AuthType.BIOMETRIC_KEY ->
                HostFormError.BIOMETRIC_UNSUPPORTED
            AuthType.PASSWORD -> null
        }
    }
}

/**
 * Builds the [Host] to persist from the Desktop form. On edit ([stored] non-null)
 * only the form-owned fields are overwritten: rebuilding from scratch would reset
 * the others (favorite, keep-alive, ...) and the newest CRDT write would push that
 * reset to Android. `fido2Mode` is null unless FIDO2; for FIDO2 it is kept only if
 * the stored host is FIDO2 with the same credential, else [Fido2Mode.HARDWARE_KEY].
 */
internal fun buildHost(
    form: HostDetailFormState,
    stored: Host?,
    newId: String,
): Host {
    // New host: blank base, the form-owned fields are overwritten below.
    val base = stored ?: Host(
        id = newId,
        label = "",
        hostname = "",
        username = "",
        authType = form.authType,
        credentialId = "",
    )
    return base.copy(
        label = form.label.trim(),
        hostname = form.hostname.trim(),
        port = form.port.toInt(),
        username = form.username.trim(),
        authType = form.authType,
        credentialId = form.credentialId,
        group = form.group.trim().takeIf { it.isNotBlank() },
        terminalTheme = form.terminalTheme,
        fido2Mode = if (form.authType == AuthType.FIDO2) {
            stored
                ?.takeIf { it.authType == AuthType.FIDO2 && it.credentialId == form.credentialId }
                ?.fido2Mode
                ?: Fido2Mode.HARDWARE_KEY
        } else {
            null
        },
    )
}
