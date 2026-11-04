// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
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
import fr.techtical.nextsh.shared.util.randomUuid
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
    val error: String? = null,
) {
    val isExisting: Boolean get() = hostId != null
    val usesKeyAuth: Boolean get() = authType == AuthType.SSH_KEY || authType == AuthType.CERTIFICATE
}

class HostDetailViewModel(
    private val repository: HostRepository = DesktopContainer.hostRepository,
    private val sshKeyRepository: SshKeyRepository = DesktopContainer.sshKeyRepository,
    private val hostFolderRepository: fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository =
        DesktopContainer.hostFolderRepository,
    private val vaultManager: VaultManager = DesktopContainer.vaultManager,
    private val customThemeRepository: CustomTerminalThemeRepository = DesktopContainer.customThemeRepository,
    private val appScope: AppScope = DesktopContainer.appScope,
) {
    private val _state = MutableStateFlow(HostDetailFormState(credentialId = randomUuid()))
    val state: StateFlow<HostDetailFormState> = _state.asStateFlow()

    /** User-defined terminal themes, observed for the per-host theme selector. */
    val customThemes: StateFlow<List<CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(appScope.coroutineScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun load(hostId: String?) {
        appScope.coroutineScope.launch {
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
            _state.value = _state.value.copy(isLoading = true, error = null)
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
                    error = "Hôte introuvable",
                )
            }
        }
    }

    fun onLabel(v: String) { _state.value = _state.value.copy(label = v, error = null) }
    fun onHostname(v: String) { _state.value = _state.value.copy(hostname = v, error = null) }
    fun onPort(v: String) {
        if (v.all { it.isDigit() } && v.length <= 5) _state.value = _state.value.copy(port = v, error = null)
    }
    fun onUsername(v: String) { _state.value = _state.value.copy(username = v, error = null) }
    fun onGroup(v: String) { _state.value = _state.value.copy(group = v, error = null) }

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
            error = null,
            pendingCertPem = if (v == AuthType.CERTIFICATE) cur.pendingCertPem else null,
            pendingCertFileName = if (v == AuthType.CERTIFICATE) cur.pendingCertFileName else null,
        )
    }

    fun onSelectKey(keyId: String) {
        _state.value = _state.value.copy(credentialId = keyId, error = null)
    }

    fun onCertLoaded(pem: String, fileName: String) {
        _state.value = _state.value.copy(
            pendingCertPem = pem,
            pendingCertFileName = fileName,
            error = null,
        )
    }

    fun onTerminalTheme(v: String) { _state.value = _state.value.copy(terminalTheme = v, error = null) }

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

    fun save(password: CharArray?, onDone: () -> Unit) {
        val s = _state.value
        val validation = validate(s)
        if (validation != null) {
            _state.value = s.copy(error = validation)
            password?.let { Arrays.fill(it, '\u0000') }
            return
        }
        _state.value = s.copy(isSaving = true, error = null)
        appScope.coroutineScope.launch {
            try {
                // Re-read the stored host so fields the form does not own are current.
                // Null means it was deleted meanwhile: write nothing (host or vault);
                // the `finally` still wipes the password.
                val storedId = s.hostId
                val stored = if (storedId != null) {
                    repository.getById(storedId) ?: run {
                        _state.value = _state.value.copy(
                            isSaving = false,
                            error = "Cet hôte a été supprimé entre-temps, rien n'a été enregistré.",
                        )
                        return@launch
                    }
                } else {
                    null
                }
                val host = buildHost(s, stored, randomUuid())
                if (stored != null) repository.update(host) else repository.save(host)

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
            } finally {
                password?.let { Arrays.fill(it, '\u0000') }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val s = _state.value
        val id = s.hostId ?: return
        appScope.coroutineScope.launch {
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

    private fun validate(s: HostDetailFormState): String? {
        if (s.label.isBlank()) return "Le label est requis"
        if (s.hostname.isBlank()) return "Le hostname est requis"
        val port = s.port.toIntOrNull()
        if (port == null || port !in 1..65535) return "Le port doit être entre 1 et 65535"
        if (s.username.isBlank()) return "Le nom d'utilisateur est requis"
        return when (s.authType) {
            AuthType.SSH_KEY, AuthType.CERTIFICATE -> when {
                s.credentialId.isBlank() -> "Sélectionnez une clé SSH dans le vault"
                s.availableKeys.none { it.id == s.credentialId } -> "La clé sélectionnée n'est plus dans le vault"
                s.authType == AuthType.CERTIFICATE && s.pendingCertPem == null && !s.certAlreadyStored ->
                    "Sélectionnez le fichier -cert.pub du certificat"
                else -> null
            }
            AuthType.FIDO2 -> when {
                s.credentialId.isBlank() -> "Sélectionnez une clé FIDO2 (sk-*) dans le vault"
                s.availableFido2Keys.none { it.id == s.credentialId } -> "La clé FIDO2 sélectionnée n'est plus dans le vault"
                else -> null
            }
            AuthType.BIOMETRIC_KEY ->
                "Auth biométrique non disponible côté Desktop (Android uniquement)"
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
