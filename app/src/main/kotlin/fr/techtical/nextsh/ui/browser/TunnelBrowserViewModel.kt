// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.*
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserUiState(
    val tunnelConfig: TunnelConfig? = null,
    val tunnelStatus: TunnelStatus? = null,
    val initialUrl: String = "",
    val currentUrl: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val showExternalNavWarning: Boolean = false,
    val pendingExternalUrl: String? = null,
    val webError: String? = null,
)

@HiltViewModel
class TunnelBrowserViewModel @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val tunnelManager: SshTunnelManager,
    private val stopTunnel: StopTunnelUseCase,
    val sessionHolder: BrowserSessionHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var currentTunnelId: String? = null

    fun loadTunnel(tunnelId: String) {
        if (tunnelId == currentTunnelId) return
        currentTunnelId = tunnelId
        viewModelScope.launch {
            val config = tunnelRepository.getById(tunnelId) ?: return@launch
            val url = "http://127.0.0.1:${config.localPort}"
            _uiState.update {
                it.copy(tunnelConfig = config, initialUrl = url, currentUrl = url)
            }
        }
        viewModelScope.launch {
            tunnelManager.tunnelStates.collect { states ->
                val state = states[tunnelId]
                _uiState.update { it.copy(tunnelStatus = state?.status) }
            }
        }
    }

    fun onUrlChanged(url: String) {
        _uiState.update { it.copy(currentUrl = url) }
    }

    fun onLoadingChanged(loading: Boolean) {
        _uiState.update { it.copy(isLoading = loading) }
    }

    fun onWebError(description: String?) {
        _uiState.update { it.copy(webError = description, isLoading = false) }
    }

    fun clearWebError() {
        _uiState.update { it.copy(webError = null) }
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    /**
     * Returns true if navigation should be allowed, false if blocked (external URL).
     */
    fun onNavigationRequested(url: String): Boolean {
        if (isLocalhostUrl(url)) return true
        _uiState.update {
            it.copy(showExternalNavWarning = true, pendingExternalUrl = url)
        }
        return false
    }

    fun confirmExternalNavigation() {
        _uiState.update {
            it.copy(showExternalNavWarning = false, pendingExternalUrl = null)
        }
    }

    fun cancelExternalNavigation() {
        _uiState.update {
            it.copy(showExternalNavWarning = false, pendingExternalUrl = null)
        }
    }

    fun onBrowserClosed() {
        val config = _uiState.value.tunnelConfig ?: return
        if (!config.keepAliveAfterBrowserClose) {
            viewModelScope.launch { stopTunnel(config.id) }
        }
        currentTunnelId = null
        _uiState.value = BrowserUiState()
    }

    fun isLocalhostUrl(url: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        } catch (_: Exception) {
            false
        }
    }
}
