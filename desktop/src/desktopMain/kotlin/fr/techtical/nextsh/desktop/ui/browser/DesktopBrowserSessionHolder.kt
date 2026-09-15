// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the lifecycle state of the in-app browser overlay for a single tunnel
 * session.
 *
 * The browser instance is typed as [Any?] intentionally: Phase 3 will narrow
 * this to `KCEFBrowser` once the composable integration is in place. Keeping it
 * opaque here ensures this class and its tests compile without a KCEF runtime
 * dependency.
 *
 * Disposal is delegated to [onDispose]: the owner sets this callback before
 * calling [close], e.g.:
 *   holder.onDispose = { (it as? KCEFBrowser)?.dispose() }
 *
 * Thread-safety: [MutableStateFlow] assignments are atomic; all mutating calls
 * are expected from the main/UI thread.
 */
class DesktopBrowserSessionHolder {

    private val _visibleTunnelId = MutableStateFlow<String?>(null)
    val visibleTunnelId: StateFlow<String?> = _visibleTunnelId.asStateFlow()

    private val _activeTunnelId = MutableStateFlow<String?>(null)
    val activeTunnelId: StateFlow<String?> = _activeTunnelId.asStateFlow()

    private var _kcefBrowser: Any? = null

    // Tunnels the user closed via the Close button. The auto-open logic
    // (openBrowserOnConnect + reconnects) checks this set and skips tunnels in it,
    // so the browser does not pop back up on every RECONNECTING → ACTIVE cycle
    // after the user explicitly dismissed it. Cleared when the user re-opens the
    // browser manually (show) or when the tunnel transitions to STOPPED: a full
    // stop/restart is treated as a fresh opt-in.
    private val userDismissedTunnelIds: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Called with the current [_kcefBrowser] value (possibly null) when [close]
     * is invoked. Phase 3 wires in the real disposal:
     *   holder.onDispose = { (it as? KCEFBrowser)?.dispose() }
     */
    var onDispose: ((Any?) -> Unit)? = null

    fun show(tunnelId: String) {
        // Manual open → the user clearly wants to see this tunnel again.
        userDismissedTunnelIds.remove(tunnelId)
        _activeTunnelId.value = tunnelId
        _visibleTunnelId.value = tunnelId
    }

    fun hide() {
        _visibleTunnelId.value = null
    }

    fun hasSession(): Boolean = _activeTunnelId.value != null

    fun hasSession(tunnelId: String): Boolean = _activeTunnelId.value == tunnelId

    fun isUserDismissed(tunnelId: String): Boolean =
        userDismissedTunnelIds.contains(tunnelId)

    /**
     * Called by the tunnel observers when a tunnel goes fully STOPPED so the
     * next start can re-trigger auto-open if the user configured it.
     */
    fun clearUserDismissed(tunnelId: String) {
        userDismissedTunnelIds.remove(tunnelId)
    }

    fun setKcefBrowser(browser: Any?) {
        _kcefBrowser = browser
    }

    fun getKcefBrowser(): Any? = _kcefBrowser

    fun close() {
        val id = _activeTunnelId.value
        if (id != null) userDismissedTunnelIds.add(id)
        _visibleTunnelId.value = null
        _activeTunnelId.value = null
        try {
            onDispose?.invoke(_kcefBrowser)
        } catch (_: Exception) { }
        _kcefBrowser = null
    }
}
