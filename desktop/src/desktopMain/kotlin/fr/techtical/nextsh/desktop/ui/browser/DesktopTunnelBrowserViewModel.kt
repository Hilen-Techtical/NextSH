// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import fr.techtical.nextsh.shared.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

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
    // HTTP sign-in the tunnelled service is waiting for, or null. Holds no secret.
    val authPrompt: HttpAuthPrompt? = null,
    // Localized message the overlay shows about an authentication it cannot handle.
    val authNotice: AuthNotice? = null,
)

class DesktopTunnelBrowserViewModel(
    private val tunnelRepository: TunnelRepository,
    private val tunnelManager: DesktopTunnelManager,
    private val stopTunnel: StopTunnelUseCase,
    val sessionHolder: DesktopBrowserSessionHolder,
    private val appScope: AppScope,
    // Wall clock in milliseconds, injectable so the sign-in cancel cooldown is testable.
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var currentTunnelId: String? = null
    // Cancelled on every loadTunnel / onBrowserClosed to prevent a previous collect
    // from writing into _uiState after the active tunnel changed: appScope lives
    // for the whole app, so uncancelled collects leak indefinitely.
    private var tunnelStateJob: Job? = null

    // The HTTP authentication coordinator of the live browser, if any. One per browser:
    // attachAuth replaces it, detachAuth / onBrowserClosed / a tunnel switch end it. The
    // generation lets a coordinator that was replaced or ended stay silent: its late
    // callbacks must not write a prompt for a browser that is gone.
    private val authLock = Any()
    private var authSession: BrowserAuthCoordinator? = null
    @Volatile
    private var authGeneration = 0L

    // Prompt ids come from one counter for the whole view model: a new browser's first prompt
    // never reuses the id of the previous browser's panel, so the panel never keeps its typed
    // text for a different challenge.
    private val promptIds = AtomicLong()

    fun loadTunnel(tunnelId: String) {
        if (tunnelId == currentTunnelId) return
        currentTunnelId = tunnelId
        // A challenge raised by the previous tunnel's service must not outlive it, and
        // neither may the credentials typed for it.
        detachAuth()
        tunnelStateJob?.cancel()
        appScope.coroutineScope.launch {
            val config = tunnelRepository.getById(tunnelId) ?: return@launch
            // The path may carry a secret in its query string: this URL is never logged. A stored
            // path that fails validation (sync peer, old backup) opens the site root instead.
            val url = TunnelBrowserPath.buildBrowserUrl(config.localPort, config.browserPath)
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

    /**
     * Starts HTTP authentication handling for a freshly created browser: [sender] is its
     * DevTools channel and [localPort] the port of the tunnel the browser was created for
     * (the only origin allowed to receive credentials). Returns the coordinator the
     * browser's event listener must feed.
     *
     * Replaces the previous coordinator, which is ended: its pending requests are
     * cancelled and its credentials wiped. The sign-in prompt and notice start clean.
     */
    fun attachAuth(localPort: Int, sender: CdpSender): BrowserAuthCoordinator {
        val previous: BrowserAuthCoordinator?
        val created: BrowserAuthCoordinator
        synchronized(authLock) {
            previous = authSession
            val generation = ++authGeneration
            created = BrowserAuthCoordinator(
                tunnelOrigin = tunnelAuthOrigin(localPort),
                sender = sender,
                listener = AuthListener(generation),
                clock = clock,
                newPromptId = promptIds::incrementAndGet,
            )
            authSession = created
            // Cleared under the same lock as the generation bump: see AuthListener.
            _uiState.update { it.copy(authPrompt = null, authNotice = null) }
        }
        previous?.close()
        return created
    }

    /**
     * Ends HTTP authentication handling: pending requests are cancelled, credentials
     * wiped, prompt and notice cleared. Called when the browser goes away (closed, tunnel
     * switched, overlay left the composition). Safe to call when nothing is attached.
     */
    fun detachAuth() {
        val session = synchronized(authLock) {
            authGeneration++
            // Cleared under the same lock as the generation bump: see AuthListener.
            _uiState.update { it.copy(authPrompt = null, authNotice = null) }
            authSession.also { authSession = null }
        }
        // Outside the lock: the coordinator calls its listener, which takes authLock, while
        // holding its own lock, so the two must always be taken in that order.
        session?.close()
    }

    /**
     * The user confirmed the sign-in prompt [promptId]. Takes ownership of the arrays: they
     * are wiped when no prompt matches, otherwise kept by the coordinator in memory and
     * wiped when the browser is closed or the tunnel switched.
     */
    fun submitAuth(promptId: Long, username: CharArray, password: CharArray) {
        val session = synchronized(authLock) { authSession }
        if (session == null) {
            username.fill('\u0000')
            password.fill('\u0000')
            return
        }
        session.submit(promptId, username, password)
    }

    /** The user dismissed the sign-in prompt [promptId]. */
    fun cancelAuth(promptId: Long) {
        val session = synchronized(authLock) { authSession }
        session?.cancel(promptId)
    }

    /** The user navigates (back, forward, reload, new URL). */
    fun onUserNavigation() {
        val session = synchronized(authLock) { authSession }
        session?.onUserNavigation()
    }

    fun dismissAuthNotice() {
        _uiState.update { it.copy(authNotice = null) }
    }

    /**
     * Sign-in handling could not be switched on for the live browser (no DevTools client,
     * `Fetch.enable` failed or timed out). The page loads anyway, but Chromium may show its
     * own sign-in window, and the user is told so.
     */
    fun onAuthInterceptionUnavailable() {
        _uiState.update { it.copy(authNotice = AuthNotice.INTERCEPTION_UNAVAILABLE) }
    }

    /**
     * What to do with a popup the page asks for (`window.open`, `target=_blank`, a link opened
     * in a new tab). A popup would be a second browser without the authentication channel, so
     * none is ever created. Returns the URL to load in the SAME browser, or null.
     *
     * Only http(s) URLs on a loopback address are followed. Other http(s) URLs go through the
     * usual external-navigation warning; anything else (`javascript:`, `about:`, `data:`) is
     * dropped silently. Never opens anything itself.
     */
    fun resolvePopupTarget(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val scheme = try {
            URI(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        }
        if (scheme != "http" && scheme != "https") return null
        if (isLocalhostUrl(url)) return url
        onNavigationRequested(url)
        return null
    }

    // Writes into the UI state only while its coordinator is the current one. The check and
    // the write are one step under authLock, and detachAuth / attachAuth bump the generation
    // and clear the state under the same lock: a late callback of an ended coordinator can
    // never write a prompt after the clear. Lock order: coordinator lock, then authLock.
    private inner class AuthListener(private val generation: Long) : BrowserAuthListener {
        override fun onPromptChanged(prompt: HttpAuthPrompt?) {
            synchronized(authLock) {
                if (generation != authGeneration) return
                _uiState.update { it.copy(authPrompt = prompt) }
            }
        }

        override fun onNotice(notice: AuthNotice) {
            synchronized(authLock) {
                if (generation != authGeneration) return
                _uiState.update { it.copy(authNotice = notice) }
            }
        }
    }

    fun onBrowserClosed() {
        // Whatever the tunnel state: the browser is gone, so are its pending requests and
        // the credentials typed for it.
        detachAuth()
        val config = _uiState.value.tunnelConfig
        if (config == null) {
            // Nothing to stop or reset, but the next loadTunnel (even of the same id)
            // must run again.
            tunnelStateJob?.cancel()
            tunnelStateJob = null
            currentTunnelId = null
            return
        }
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
