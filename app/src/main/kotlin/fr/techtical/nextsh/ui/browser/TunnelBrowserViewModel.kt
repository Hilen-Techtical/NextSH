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
import java.net.URI
import javax.inject.Inject

/**
 * WebView-free handle on one pending HTTP authentication challenge (the screen
 * wraps an `HttpAuthHandler`). Exactly one of the two methods must be called, once.
 *
 * The passwords are plain `String`s: `HttpAuthHandler.proceed` takes `String`, so the
 * CharArray-and-wipe rule cannot apply on this path.
 */
interface HttpAuthResponder {
    /**
     * True when the platform says this challenge repeats one the user already
     * answered for the same request (the server refused the credentials just
     * submitted). Fixed for the lifetime of the handler.
     */
    val isRetry: Boolean

    fun proceed(username: String, password: String)
    fun cancel()
}

/**
 * What the credentials dialog needs to render. Holds no secret: the typed values
 * live only in the dialog's own state until they are handed to the responder.
 *
 * @property id identifies this very prompt: answers ([TunnelBrowserViewModel.submitAuth],
 *   [TunnelBrowserViewModel.cancelAuth]) carry it so a late tap on a dialog that was
 *   already replaced cannot answer the next challenge.
 * @property origin lowercase host of the tunnelled service asking for credentials,
 *   followed by `:port` when the page URL gave an explicit one.
 * @property realm sanitized server-provided realm, or null when absent or blank.
 * @property rejected true when the platform reports this challenge as a retry
 *   ([HttpAuthResponder.isRetry]), i.e. the credentials just typed were refused, so
 *   the dialog can say so.
 */
data class HttpAuthPrompt(
    val id: Long,
    val origin: String,
    val realm: String?,
    val rejected: Boolean,
)

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
    val authPrompt: HttpAuthPrompt? = null,
)

@HiltViewModel
class TunnelBrowserViewModel internal constructor(
    private val tunnelRepository: TunnelRepository,
    private val tunnelManager: SshTunnelManager,
    private val stopTunnel: StopTunnelUseCase,
    val sessionHolder: BrowserSessionHolder,
    // Monotonic clock in milliseconds, injectable so the cancel cooldown is testable.
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        tunnelRepository: TunnelRepository,
        tunnelManager: SshTunnelManager,
        stopTunnel: StopTunnelUseCase,
        sessionHolder: BrowserSessionHolder,
    ) : this(
        tunnelRepository,
        tunnelManager,
        stopTunnel,
        sessionHolder,
        { System.nanoTime() / 1_000_000L },
    )

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var currentTunnelId: String? = null

    private data class AuthKey(val origin: String, val realm: String?)

    private class PendingAuth(val id: Long, val responder: HttpAuthResponder, val key: AuthKey)

    // Defence in depth: every Android caller (WebView callbacks, dialog, teardown,
    // onCleared) runs on the main thread. Every field below and the authPrompt
    // published in _uiState still change together under this lock, so a pending
    // responder can never exist without its prompt (or the reverse), whatever the
    // caller. Responder callbacks are invoked outside the lock.
    private val authLock = Any()
    private var pendingAuth: PendingAuth? = null
    // Origin and realm the user last dismissed on purpose, with the time of that
    // dismissal: a page that keeps re-challenging would otherwise bring the dialog
    // straight back (see AUTH_CANCEL_COOLDOWN_MS).
    private var lastCancelledKey: AuthKey? = null
    private var lastCancelledAt = 0L
    // False between the loss of the WebView (browser closed, activity recreated) and
    // the attachment of the next one: a late challenge from a WebView that is going
    // away must not leave a dialog nobody can answer.
    private var authEnabled = true
    private var nextPromptId = 0L

    fun loadTunnel(tunnelId: String) {
        if (tunnelId == currentTunnelId) return
        currentTunnelId = tunnelId
        // A challenge raised by the previous tunnel's service must not outlive it.
        // A new session also means challenges are welcome again.
        releaseAuth(resetUiState = false, enable = true)
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

    /**
     * Entry point for the WebView's HTTP authentication challenges (401 Basic or
     * Digest, never TLS client certificates). Always ends up calling the responder
     * exactly once, either now (refusal) or later through [submitAuth] /
     * [cancelAuth] / a teardown.
     *
     * Only loopback services are ever prompted for, since the browser only talks to
     * tunnelled services. The challenge is cancelled instead when it targets another
     * host, arrives while no WebView is attached, arrives while the dialog is already
     * open (one dialog at a time, the first request keeps it), or repeats a challenge
     * the user just dismissed (cooldown, lifted by [onUserNavigation]).
     *
     * Known limitations, both accepted:
     *  - the platform supersedes a handler when the page navigates or aborts while
     *    the dialog is open: the dialog then becomes inert (answering it does
     *    nothing) until it is dismissed;
     *  - the WebView keeps accepted credentials in its process-wide HTTP auth cache
     *    until the app process ends and no public API clears it. NextSH itself
     *    never writes them to disk.
     *
     * @param host host of the challenging server as reported by the WebView, which
     *   gives it without a port: a value that carries one (`127.0.0.1:8081`) is not a
     *   loopback host and is cancelled.
     * @param currentUrl URL of the page shown by the WebView, only used to learn the
     *   port to display (see [resolveAuthOrigin]).
     */
    fun onAuthRequested(
        host: String?,
        realm: String?,
        currentUrl: String?,
        responder: HttpAuthResponder,
    ) {
        if (host == null || !isLoopbackHost(host)) {
            responder.cancel()
            return
        }
        val origin = resolveAuthOrigin(host, currentUrl)
        val cleanRealm = sanitizeAuthRealm(realm)
        val key = AuthKey(origin, cleanRealm)
        val accepted = synchronized(authLock) {
            if (!authEnabled || pendingAuth != null || isInCancelCooldown(key)) {
                false
            } else {
                val id = ++nextPromptId
                pendingAuth = PendingAuth(id, responder, key)
                val prompt = HttpAuthPrompt(
                    id = id,
                    origin = origin,
                    realm = cleanRealm,
                    rejected = responder.isRetry,
                )
                _uiState.update { it.copy(authPrompt = prompt) }
                true
            }
        }
        if (!accepted) responder.cancel()
    }

    // Caller holds authLock.
    private fun isInCancelCooldown(key: AuthKey): Boolean {
        if (key != lastCancelledKey) return false
        // A negative elapsed time never counts as cooldown.
        return clock() - lastCancelledAt in 0 until AUTH_CANCEL_COOLDOWN_MS
    }

    /**
     * Hands the typed credentials to the WebView. The values are forwarded as-is and
     * never stored or logged here.
     *
     * [promptId] is the id of the dialog the user answered. A call that does not match
     * the pending prompt (already answered, replaced by a newer challenge, torn down)
     * is ignored, so a double tap or a late tap can never feed credentials typed for
     * one challenge to another.
     */
    fun submitAuth(promptId: Long, username: String, password: String) {
        val pending = synchronized(authLock) {
            val p = pendingAuth
            if (p == null || p.id != promptId) return
            pendingAuth = null
            _uiState.update { it.copy(authPrompt = null) }
            p
        }
        pending.responder.proceed(username, password)
    }

    /**
     * The user dismissed the credentials dialog (Cancel, back): the WebView then shows
     * the server's 401 page. Same id rule as [submitAuth]. Starts the cooldown for this
     * origin and realm, so a page that immediately challenges again does not bring the
     * dialog back in a loop.
     */
    fun cancelAuth(promptId: Long) {
        val pending = synchronized(authLock) {
            val p = pendingAuth
            if (p == null || p.id != promptId) return
            pendingAuth = null
            lastCancelledKey = p.key
            lastCancelledAt = clock()
            _uiState.update { it.copy(authPrompt = null) }
            p
        }
        pending.responder.cancel()
    }

    /**
     * The user explicitly asked to navigate (refresh, back, forward): the cooldown that
     * follows a dismissed dialog is lifted, so the next challenge is prompted for.
     * Challenges the page raises by itself stay throttled.
     */
    fun onUserNavigation() {
        synchronized(authLock) { lastCancelledKey = null }
    }

    /** A WebView was attached to the overlay: challenges may be prompted for. */
    fun onWebViewAttached() {
        synchronized(authLock) { authEnabled = true }
    }

    /**
     * The WebView is going away (released by the overlay, activity recreated): a
     * handler it handed out can no longer be answered. The pending challenge is
     * cancelled and later ones are refused until a WebView is attached again or a
     * tunnel is loaded.
     */
    fun onWebViewReleased() {
        releaseAuth(resetUiState = false, enable = false)
    }

    fun onBrowserClosed() {
        val config = _uiState.value.tunnelConfig
        if (config == null) {
            // Nothing to stop or reset, but the challenge must still be answered and
            // the next loadTunnel (even of the same id) must run and re-enable prompts.
            currentTunnelId = null
            releaseAuth(resetUiState = false, enable = false)
            return
        }
        if (!config.keepAliveAfterBrowserClose) {
            viewModelScope.launch { stopTunnel(config.id) }
        }
        currentTunnelId = null
        releaseAuth(resetUiState = true, enable = false)
    }

    override fun onCleared() {
        releaseAuth(resetUiState = false, enable = false)
    }

    // Teardown shared by every path that ends a browser session or tunnel: takes the
    // pending responder, clears the prompt (or the whole state) and the markers in
    // one critical section, then cancels the responder outside of it. Resetting the
    // whole state under the same lock prevents a challenge slipping in between and
    // leaving an orphan responder behind. Not a user decision, so it starts no
    // cooldown and wipes the existing one.
    private fun releaseAuth(resetUiState: Boolean, enable: Boolean) {
        val pending = synchronized(authLock) {
            val p = pendingAuth
            pendingAuth = null
            lastCancelledKey = null
            authEnabled = enable
            if (resetUiState) {
                _uiState.value = BrowserUiState()
            } else {
                _uiState.update { it.copy(authPrompt = null) }
            }
            p
        }
        pending?.responder?.cancel()
    }

    fun isLocalhostUrl(url: String): Boolean {
        return try {
            isLoopbackHost(android.net.Uri.parse(url).host)
        } catch (_: Exception) {
            false
        }
    }
}

/** Pure loopback test shared by the navigation guard and the auth prompt. */
internal fun isLoopbackHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "localhost" || h == "127.0.0.1" || h == "[::1]" || h == "::1"
}

/** How long a challenge for the origin and realm the user just dismissed is auto-cancelled. */
internal const val AUTH_CANCEL_COOLDOWN_MS = 5_000L

internal const val AUTH_REALM_MAX_LENGTH = 80

// The realm is bounded before any per-character work: a hostile server can send a
// header of arbitrary size.
internal const val AUTH_REALM_RAW_LIMIT = 512

private val AUTH_REALM_ELLIPSIS = 0x2026.toChar()

/**
 * The realm comes straight from the server's `WWW-Authenticate` header and ends up in
 * a dialog the user trusts. Besides ISO controls, every code point of the Unicode
 * general categories Control, Format (bidi overrides and isolates, LRM/RLM/ALM,
 * zero-width characters, BOM, soft hyphen), Line separator, Paragraph separator,
 * Surrogate (a lone half of a pair), Private use and Unassigned is dropped, so the
 * text cannot be visually reordered, hidden, spread over several lines or rendered as
 * tofu. The result is trimmed and capped; null when nothing printable is left.
 */
internal fun sanitizeAuthRealm(raw: String?): String? {
    if (raw == null) return null
    val bounded = if (raw.length <= AUTH_REALM_RAW_LIMIT) {
        raw
    } else {
        var end = AUTH_REALM_RAW_LIMIT
        // Do not cut a surrogate pair in half.
        if (Character.isHighSurrogate(raw[end - 1])) end--
        raw.substring(0, end)
    }
    val filtered = StringBuilder(bounded.length)
    var i = 0
    while (i < bounded.length) {
        val cp = bounded.codePointAt(i)
        i += Character.charCount(cp)
        if (!isHiddenOrControlCodePoint(cp)) filtered.appendCodePoint(cp)
    }
    val cleaned = filtered.toString().trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.length <= AUTH_REALM_MAX_LENGTH) return cleaned
    var end = AUTH_REALM_MAX_LENGTH
    if (Character.isHighSurrogate(cleaned[end - 1])) end--
    return cleaned.substring(0, end) + AUTH_REALM_ELLIPSIS
}

private fun isHiddenOrControlCodePoint(cp: Int): Boolean {
    if (Character.isISOControl(cp)) return true
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE,
        Character.PRIVATE_USE,
        Character.UNASSIGNED,
        -> true
        else -> false
    }
}

/**
 * Origin shown in the dialog (and used as half of the cooldown key): the lowercase
 * challenge [host], plus `:port` when the WebView's current page URL points at that
 * same host (case-insensitive) with an explicit port. The WebView reports the
 * challenging host without its port; the URL is the only evidence of one, so when it
 * is missing, unparsable, on another host or without an explicit port, the bare host
 * is shown rather than a guess. Parsed with [URI], not android.net.Uri, to stay
 * testable on the JVM. The URL is only read for its host and port, never kept or logged.
 */
internal fun resolveAuthOrigin(host: String, currentUrl: String?): String {
    val h = host.lowercase()
    return formatAuthOrigin(h, explicitPortFor(h, currentUrl))
}

// 0 when the URL cannot vouch for a port of this host.
private fun explicitPortFor(host: String, url: String?): Int {
    if (url == null) return 0
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return 0
    }
    val uriHost = uri.host?.lowercase() ?: return 0
    if (uriHost.removeSurrounding("[", "]") != host.removeSurrounding("[", "]")) return 0
    // URI.getPort() is -1 when the URL has no explicit port.
    return maxOf(uri.port, 0)
}

// IPv6 literals get their brackets back so "::1:8081" cannot be misread. A port of 0
// or less means "unknown" and is omitted.
internal fun formatAuthOrigin(host: String, port: Int): String {
    val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return if (port > 0) "$h:$port" else h
}
