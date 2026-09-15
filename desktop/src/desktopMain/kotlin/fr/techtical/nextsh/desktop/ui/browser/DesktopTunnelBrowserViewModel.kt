// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

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

class DesktopTunnelBrowserViewModel(
    private val tunnelRepository: TunnelRepository,
    private val tunnelManager: DesktopTunnelManager,
    private val stopTunnel: StopTunnelUseCase,
    val sessionHolder: DesktopBrowserSessionHolder,
    private val appScope: AppScope,
) {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var currentTunnelId: String? = null
    // Cancelled on every loadTunnel / onBrowserClosed to prevent a previous collect
    // from writing into _uiState after the active tunnel changed: appScope lives
    // for the whole app, so uncancelled collects leak indefinitely.
    private var tunnelStateJob: Job? = null

    fun loadTunnel(tunnelId: String) {
        if (tunnelId == currentTunnelId) return
        currentTunnelId = tunnelId
        tunnelStateJob?.cancel()
        appScope.coroutineScope.launch {
            val config = tunnelRepository.getById(tunnelId) ?: return@launch
            val url = "http://127.0.0.1:${config.localPort}"
            _uiState.update {
                it.copy(tunnelConfig = config, initialUrl = url, currentUrl = url)
            }
        }
        tunnelStateJob = appScope.coroutineScope.launch {
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
            appScope.coroutineScope.launch { stopTunnel(config.id) }
        }
        tunnelStateJob?.cancel()
        tunnelStateJob = null
        currentTunnelId = null
        _uiState.value = BrowserUiState()
    }

    fun isLocalhostUrl(url: String): Boolean {
        return try {
            val host = URI(url).host?.lowercase() ?: return false
            host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pure decision function used by [DesktopBrowserOverlay]'s CefRequestHandler
     * to decide whether a navigation should be cancelled.
     *
     * Returns true (cancel) for external URLs and false (allow) for localhost.
     * Side-effect: triggers the external-nav warning dialog for blocked URLs.
     *
     * Exposed as a separate function so unit tests can verify routing logic
     * without instantiating CEF handlers.
     */
    fun shouldCancelNavigation(url: String): Boolean {
        if (isLocalhostUrl(url)) return false
        onNavigationRequested(url)
        return true
    }
}
