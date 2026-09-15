// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package fr.techtical.nextsh.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class HostListUiState(
    val hosts: List<Host> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val connectingHostId: String? = null,
    val sshKeys: List<SshKey> = emptyList(),
)

data class HostFormState(
    val id: String? = null,
    val credentialId: String? = null,
    val label: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val group: String = "",
    val keepAliveSeconds: String = "30",
    val autoReconnect: Boolean = true,
    val selectedKeyId: String? = null,
    val fido2Mode: Fido2Mode = Fido2Mode.HARDWARE_KEY,
    /**
     * Raw [Host.terminalTheme] value: either a [TerminalThemeId] enum NAME for a
     * preset, or a custom theme UUID. Persisted verbatim: see [resolveThemePalette].
     */
    val terminalTheme: String = TerminalThemeId.TECHTICAL_DARK.name,
) {
    val isValid: Boolean
        get() = label.isNotBlank() && hostname.isNotBlank() && username.isNotBlank()
                && port.toIntOrNull() != null
                && (authType !in listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2) || selectedKeyId != null)
}

sealed class HostEvent {
    data class Connected(val sessionId: String) : HostEvent()
    data class SftpReady(val sessionId: String, val hostLabel: String) : HostEvent()
    data class Error(val message: String) : HostEvent()
    data object HostSaved : HostEvent()
    data object HostDeleted : HostEvent()
}

@HiltViewModel
class HostViewModel @Inject constructor(
    private val hostRepository: HostRepository,
    private val connectSession: ConnectSessionUseCase,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val customThemeRepository: fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    /** Thèmes terminal personnalisés, observés pour le sélecteur de thème par hôte. */
    val customThemes: StateFlow<List<fr.techtical.nextsh.domain.model.CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Crée ou met à jour un thème personnalisé (la palette ANSI est validée au repo). */
    fun saveCustomTheme(theme: fr.techtical.nextsh.domain.model.CustomTerminalTheme) {
        viewModelScope.launch { customThemeRepository.save(theme) }
    }

    /**
     * Supprime un thème personnalisé. Les hôtes qui le référençaient retombent sur
     * le preset par défaut via [resolveThemePalette] : pas de migration nécessaire.
     */
    fun deleteCustomTheme(id: String) {
        viewModelScope.launch { customThemeRepository.delete(id) }
    }

    private val _uiState = MutableStateFlow(HostListUiState())
    val uiState: StateFlow<HostListUiState> = _uiState.asStateFlow()

    /** Live sync status forwarded from [SyncScheduler.syncState] for the banner. */
    val syncState: StateFlow<SyncState> = syncScheduler.syncState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncState(SyncStatus.IDLE, null),
    )

    private val _formState = MutableStateFlow(HostFormState())
    val formState: StateFlow<HostFormState> = _formState.asStateFlow()

    private val _events = MutableSharedFlow<HostEvent>()
    val events: SharedFlow<HostEvent> = _events.asSharedFlow()

    /**
     * Source de vérité du filtre actif. Drive un unique collecteur via
     * `flatMapLatest` (cf. init) : éviter de lancer un nouveau
     * `viewModelScope.launch { collect }` par appel `filterByGroup` qui
     * superposait plusieurs collecteurs concurrents (cause des
     * inconsistances "Tous ne montre pas tous les hôtes").
     */
    private val selectedGroup = MutableStateFlow<String?>(null)

    init {
        // Un SEUL collecteur d'hôtes, switche entre observeAll / observeByGroup
        // via flatMapLatest qui annule automatiquement la source précédente.
        viewModelScope.launch {
            selectedGroup.flatMapLatest { group ->
                if (group != null) hostRepository.observeByGroup(group)
                else hostRepository.observeAll()
            }.collect { hosts ->
                _uiState.update { it.copy(hosts = hosts, isLoading = false) }
            }
        }
        viewModelScope.launch {
            hostRepository.observeGroups().collect { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
        viewModelScope.launch {
            sshKeyRepository.observeAll().collect { keys ->
                _uiState.update { it.copy(sshKeys = keys) }
            }
        }
    }

    fun filterByGroup(group: String?) {
        selectedGroup.value = group
        _uiState.update { it.copy(selectedGroup = group) }
    }

    fun connectToHost(host: Host) {
        _uiState.update { it.copy(connectingHostId = host.id) }
        viewModelScope.launch {
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Connected(host.id))
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Error(result.message))
                }
            }
        }
    }

    fun connectForSftp(host: Host) {
        viewModelScope.launch {
            _uiState.update { it.copy(connectingHostId = host.id) }
            when (val result = connectSession(host)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    // result.data est la SshSession : utiliser son id comme sessionId
                    _events.emit(HostEvent.SftpReady(result.data.id, host.label))
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(connectingHostId = null) }
                    _events.emit(HostEvent.Error(result.message))
                }
            }
        }
    }

    // ── Form management ──────────────────────────────────────────────────────

    fun loadHostForEdit(hostId: String) {
        viewModelScope.launch {
            val host = hostRepository.getById(hostId) ?: return@launch
            _formState.value = HostFormState(
                id           = host.id,
                credentialId = host.credentialId,
                label        = host.label,
                hostname     = host.hostname,
                port         = host.port.toString(),
                username     = host.username,
                authType     = host.authType,
                group        = host.group ?: "",
                keepAliveSeconds = host.keepAliveSeconds.toString(),
                autoReconnect    = host.autoReconnect,
                // For SSH_KEY / CERTIFICATE auth, credentialId holds the SSH key ID
                selectedKeyId = if (host.authType in listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2)) host.credentialId else null,
                fido2Mode = host.fido2Mode ?: Fido2Mode.HARDWARE_KEY,
                terminalTheme = host.terminalTheme,
            )
        }
    }

    fun resetForm() {
        _formState.value = HostFormState()
    }

    fun updateForm(update: HostFormState.() -> HostFormState) {
        _formState.update { it.update() }
    }

    fun saveHost() {
        val form = _formState.value
        if (!form.isValid) return

        viewModelScope.launch {
            val existingFavorite = if (form.id != null) {
                hostRepository.getById(form.id)?.isFavorite ?: false
            } else false

            // For SSH_KEY / CERTIFICATE auth, credentialId is the SSH key ID from the vault.
            // For PASSWORD auth, preserve the existing credentialId or generate a new one.
            val credentialId = when (form.authType) {
                AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2 ->
                    form.selectedKeyId ?: form.credentialId ?: UUID.randomUUID().toString()
                else -> form.credentialId ?: UUID.randomUUID().toString()
            }
            val host = Host(
                id = form.id ?: UUID.randomUUID().toString(),
                label = form.label.trim(),
                hostname = form.hostname.trim(),
                port = form.port.toIntOrNull() ?: 22,
                username = form.username.trim(),
                authType = form.authType,
                credentialId = credentialId,
                group = form.group.trim().ifEmpty { null },
                keepAliveSeconds = form.keepAliveSeconds.toIntOrNull() ?: 30,
                autoReconnect = form.autoReconnect,
                terminalTheme = form.terminalTheme,
                fido2Mode = if (form.authType == AuthType.FIDO2) form.fido2Mode else null,
                isFavorite = existingFavorite,
            )

            // Stocker le mot de passe si auth password
            if (form.authType == AuthType.PASSWORD && form.password.isNotEmpty()) {
                vaultManager.storePassword(credentialId, form.password.toCharArray())
            }

            if (form.id != null) {
                hostRepository.update(host)
            } else {
                hostRepository.save(host)
            }

            _events.emit(HostEvent.HostSaved)
            resetForm()
        }
    }

    fun toggleFavorite(host: Host) {
        viewModelScope.launch {
            hostRepository.setFavorite(host.id, !host.isFavorite)
        }
    }

    fun deleteHost(hostId: String) {
        viewModelScope.launch {
            hostRepository.delete(hostId)
            _events.emit(HostEvent.HostDeleted)
        }
    }
}
