// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.shared.domain.model.SshResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.*
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.service.TunnelServiceController
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class TunnelListUiState(
    val tunnels: List<TunnelConfig> = emptyList(),
    val tunnelStates: Map<String, TunnelState> = emptyMap(),
    val hosts: List<Host> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class TunnelFormState(
    val id: String? = null,
    val label: String = "",
    val hostId: String = "",
    val type: TunnelType = TunnelType.LOCAL_FORWARD,
    val localPort: String = "",
    val remoteHost: String = "127.0.0.1",
    val remotePort: String = "",
    val autoStart: Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
) {
    val isValid: Boolean
        get() = label.isNotBlank() && hostId.isNotBlank()
                && localPort.toIntOrNull() != null
                && (type == TunnelType.DYNAMIC_SOCKS5 || (remoteHost.isNotBlank() && remotePort.toIntOrNull() != null))
}

sealed class TunnelEvent {
    data class Error(val message: String) : TunnelEvent()
    data object TunnelSaved : TunnelEvent()
    data class TunnelStarted(val label: String) : TunnelEvent()
    data class TunnelStopped(val label: String) : TunnelEvent()
    data class OpenBrowser(val tunnelId: String) : TunnelEvent()

    /**
     * Le tunnel tourne, mais le système a refusé le service au premier plan :
     * il ne survivra pas au passage en arrière-plan. À signaler, sinon
     * l'utilisateur croit son tunnel protégé alors qu'il ne l'est pas.
     */
    data object ForegroundServiceRefused : TunnelEvent()
}

@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val hostRepository: HostRepository,
    private val tunnelManager: SshTunnelManager,
    private val startTunnel: StartTunnelUseCase,
    private val stopTunnel: StopTunnelUseCase,
    private val autoStartTunnelsUseCase: AutoStartTunnelsUseCase,
    private val browserSessionHolder: BrowserSessionHolder,
    private val tunnelService: TunnelServiceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TunnelListUiState())
    val uiState: StateFlow<TunnelListUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(TunnelFormState())
    val formState: StateFlow<TunnelFormState> = _formState.asStateFlow()

    private val _events = MutableSharedFlow<TunnelEvent>()
    val events: SharedFlow<TunnelEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            tunnelRepository.observeAll().collect { tunnels ->
                _uiState.update { it.copy(tunnels = tunnels, isLoading = false) }
            }
        }
        viewModelScope.launch {
            hostRepository.observeAll().collect { hosts ->
                _uiState.update { it.copy(hosts = hosts) }
            }
        }
        viewModelScope.launch {
            tunnelManager.tunnelStates.collect { states ->
                _uiState.update { it.copy(tunnelStates = states) }
            }
        }
    }

    private var autoStartDone = false

    /**
     * Signale au service au premier plan qu'un tunnel vient de démarrer.
     *
     * Sans cet appel, les tunnels tournent uniquement dans le process de l'app
     * et meurent dès que le système la met en cache. C'est le chaînon qui
     * manquait : le service était déclaré au manifest mais aucun code ne le
     * démarrait.
     *
     * Appelé depuis une action utilisateur à l'écran, donc le démarrage d'un
     * service au premier plan est autorisé sur Android 12+.
     */
    private suspend fun onTunnelStarted(config: TunnelConfig) {
        if (!tunnelService.ensureRunning(config.label)) {
            _events.emit(TunnelEvent.ForegroundServiceRefused)
        }
        _events.emit(TunnelEvent.TunnelStarted(config.label))
        if (config.openBrowserOnConnect && config.type == TunnelType.LOCAL_FORWARD) {
            _events.emit(TunnelEvent.OpenBrowser(config.id))
        }
    }

    fun autoStartTunnels() {
        if (autoStartDone) return
        autoStartDone = true
        viewModelScope.launch {
            val results = autoStartTunnelsUseCase()
            results.forEach { (tunnelId, result) ->
                when (result) {
                    is SshResult.Success -> {
                        val config = _uiState.value.tunnels.firstOrNull { it.id == tunnelId }
                            ?: tunnelRepository.getById(tunnelId)
                        if (config != null) onTunnelStarted(config)
                    }
                    is SshResult.Error -> _events.emit(TunnelEvent.Error(result.message))
                }
            }
        }
    }

    fun autoStartTunnelById(tunnelId: String) {
        viewModelScope.launch {
            val config = tunnelRepository.getById(tunnelId) ?: return@launch
            val currentState = _uiState.value.tunnelStates[tunnelId]
            val isRunning = currentState?.status in listOf(
                TunnelStatus.ACTIVE, TunnelStatus.STARTING, TunnelStatus.RECONNECTING
            )
            if (!isRunning) {
                when (val result = startTunnel(config)) {
                    is SshResult.Success -> onTunnelStarted(config)
                    is SshResult.Error -> _events.emit(TunnelEvent.Error(result.message))
                }
            }
        }
    }

    fun toggleTunnel(config: TunnelConfig) {
        viewModelScope.launch {
            val isRunning = _uiState.value.tunnelStates[config.id]?.let {
                it.status == TunnelStatus.ACTIVE || it.status == TunnelStatus.STARTING || it.status == TunnelStatus.RECONNECTING
            } ?: false
            if (isRunning) {
                // Arrêter
                when (val result = stopTunnel(config.id)) {
                    is SshResult.Success -> _events.emit(TunnelEvent.TunnelStopped(config.label))
                    is SshResult.Error -> _events.emit(TunnelEvent.Error(result.message))
                }
            } else {
                // Démarrer
                when (val result = startTunnel(config)) {
                    is SshResult.Success -> onTunnelStarted(config)
                    is SshResult.Error -> _events.emit(TunnelEvent.Error(result.message))
                }
            }
        }
    }

    // ── Form management ──────────────────────────────────────────────────────

    fun loadTunnelForEdit(tunnelId: String) {
        viewModelScope.launch {
            val config = tunnelRepository.getById(tunnelId) ?: return@launch
            _formState.value = TunnelFormState(
                id = config.id,
                label = config.label,
                hostId = config.hostId,
                type = config.type,
                localPort = config.localPort.toString(),
                remoteHost = config.remoteHost,
                remotePort = config.remotePort.toString(),
                autoStart = config.autoStart,
                openBrowserOnConnect = config.openBrowserOnConnect,
                keepAliveAfterBrowserClose = config.keepAliveAfterBrowserClose,
            )
        }
    }

    fun resetForm() {
        _formState.value = TunnelFormState()
    }

    fun updateForm(update: TunnelFormState.() -> TunnelFormState) {
        _formState.update { it.update() }
    }

    fun saveTunnel() {
        val form = _formState.value
        if (!form.isValid) return

        viewModelScope.launch {
            val existingFavorite = if (form.id != null) {
                tunnelRepository.getById(form.id)?.isFavorite ?: false
            } else false

            val config = TunnelConfig(
                id = form.id ?: UUID.randomUUID().toString(),
                label = form.label.trim(),
                hostId = form.hostId,
                type = form.type,
                localPort = form.localPort.toInt(),
                remoteHost = form.remoteHost.trim(),
                remotePort = form.remotePort.toInt(),
                autoStart = form.autoStart,
                openBrowserOnConnect = form.openBrowserOnConnect,
                keepAliveAfterBrowserClose = form.keepAliveAfterBrowserClose,
                isFavorite = existingFavorite,
            )

            if (form.id != null) {
                tunnelRepository.update(config)
            } else {
                tunnelRepository.save(config)
            }

            _events.emit(TunnelEvent.TunnelSaved)
            resetForm()
        }
    }

    val browserActiveTunnelId = browserSessionHolder.activeTunnelId

    fun toggleFavorite(tunnel: TunnelConfig) {
        viewModelScope.launch {
            tunnelRepository.setFavorite(tunnel.id, !tunnel.isFavorite)
        }
    }

    fun deleteTunnel(tunnelId: String) {
        viewModelScope.launch {
            val isRunning = _uiState.value.tunnelStates[tunnelId]?.let {
                it.status == TunnelStatus.ACTIVE || it.status == TunnelStatus.STARTING || it.status == TunnelStatus.RECONNECTING
            } ?: false
            if (isRunning) {
                stopTunnel(tunnelId)
            }
            tunnelRepository.delete(tunnelId)
        }
    }
}
