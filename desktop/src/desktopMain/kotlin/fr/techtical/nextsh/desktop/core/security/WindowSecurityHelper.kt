// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.security

import fr.techtical.nextsh.shared.util.Logger
import java.awt.Window
import java.util.Locale
import javax.swing.SwingUtilities

private const val TAG = "WindowSecurity"

/**
 * Helper to apply the Windows "exclude from screen capture" flag
 * ([User32DisplayAffinity.WDA_EXCLUDEFROMCAPTURE]) to AWT/Compose windows.
 *
 * ## Platform gating
 * All public functions are no-ops on non-Windows systems and return `false`.
 * The check is based on `os.name` system property.
 *
 * ## HWND retrieval
 * On Windows, each [java.awt.Window] has a native HWND. JNA's [com.sun.jna.Native]
 * exposes `getComponentPointer(component)` to get the raw peer pointer, but this
 * only works after the window is shown (peer created).
 *
 * If the peer is not yet available (window not yet shown), the call is deferred
 * via [SwingUtilities.invokeLater] so it runs after the next AWT EDT pass: by
 * which time the peer is guaranteed to exist.
 *
 * ## Multi-window
 * The flag is **per-HWND**: protecting the main window does nothing for the
 * dialogs the app spawns. [applyHideFromCapture] must therefore be invoked on
 * every [Window] the app creates. The main window goes through
 * [WindowSecurityState]; every secondary window (`DialogWindow` / standalone
 * `Window` composable) goes through
 * `fr.techtical.nextsh.desktop.window.WindowCaptureProtection`, which observes
 * [WindowSecurityState.hideFromCapture] so open windows follow a mid-session
 * toggle.
 */
object WindowSecurityHelper {

    /** `true` only on Windows: all operations are no-ops on other platforms. */
    val isPlatformSupported: Boolean =
        System.getProperty("os.name", "").lowercase(Locale.US).contains("windows")

    /**
     * Applies or removes the capture-exclusion flag on [window].
     *
     * - If [enabled] is `true`: window contents will appear black in screenshots,
     *   screen recordings and screen sharing (requires Win10 2004+).
     * - If [enabled] is `false`: restores default capture behaviour.
     *
     * @return `true` if the Windows API call succeeded, `false` on any
     *         non-Windows platform or if the call failed.
     */
    fun applyHideFromCapture(window: Window, enabled: Boolean): Boolean {
        if (!isPlatformSupported) return false

        val hwnd = getWindowHwnd(window)
        if (hwnd == null) {
            // Peer not ready yet: defer to the next AWT EDT cycle.
            Logger.d(TAG, "HWND not available yet for ${window::class.simpleName}, deferring via invokeLater")
            SwingUtilities.invokeLater {
                val deferredHwnd = getWindowHwnd(window)
                if (deferredHwnd != null) {
                    val affinity = if (enabled) User32DisplayAffinity.WDA_EXCLUDEFROMCAPTURE else User32DisplayAffinity.WDA_NONE
                    val ok = runCatching {
                        User32DisplayAffinity.INSTANCE.SetWindowDisplayAffinity(deferredHwnd, affinity)
                    }.getOrElse { e ->
                        Logger.w(TAG, "SetWindowDisplayAffinity (deferred) failed: ${e.message}")
                        false
                    }
                    Logger.d(TAG, "SetWindowDisplayAffinity (deferred) enabled=$enabled → $ok")
                } else {
                    Logger.w(TAG, "HWND still null after invokeLater for ${window::class.simpleName}")
                }
            }
            return false
        }

        val affinity = if (enabled) User32DisplayAffinity.WDA_EXCLUDEFROMCAPTURE else User32DisplayAffinity.WDA_NONE
        val ok = runCatching {
            User32DisplayAffinity.INSTANCE.SetWindowDisplayAffinity(hwnd, affinity)
        }.getOrElse { e ->
            Logger.w(TAG, "SetWindowDisplayAffinity failed: ${e.message}")
            false
        }
        Logger.d(TAG, "SetWindowDisplayAffinity enabled=$enabled → $ok")
        return ok
    }

    /**
     * Returns the native HWND pointer for [window], or `null` if the peer
     * is not yet created (window not yet shown on screen).
     *
     * Uses [com.sun.jna.Native.getWindowPointer] which maps to the AWT peer
     * HWND on Windows: available since JNA 4.0.
     */
    internal fun getWindowHwnd(window: Window): com.sun.jna.Pointer? {
        return runCatching {
            val ptr = com.sun.jna.Native.getWindowPointer(window)
            // getWindowPointer returns 0 / Pointer.NULL when the peer is not yet created.
            if (ptr == null || ptr == com.sun.jna.Pointer.NULL) null else ptr
        }.getOrElse { e ->
            Logger.w(TAG, "getWindowPointer failed: ${e.message}")
            null
        }
    }
}
