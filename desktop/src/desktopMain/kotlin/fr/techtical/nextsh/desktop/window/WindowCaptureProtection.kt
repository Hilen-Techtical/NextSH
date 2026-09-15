// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import fr.techtical.nextsh.desktop.core.security.WindowSecurityHelper
import fr.techtical.nextsh.desktop.core.security.WindowSecurityState
import java.awt.Window

/**
 * Applies the "hide from screen capture" setting to a **secondary** window
 * (any `DialogWindow` / standalone `Window` composable of this module).
 *
 * ## Why this exists
 * `SetWindowDisplayAffinity` is a **per-HWND** flag: excluding the main window
 * from capture says nothing about the dialogs the app spawns. Every secondary
 * window is a separate top-level HWND owned by the OS, so it stayed fully
 * visible in screenshots / screen recordings / Teams-Zoom-Discord screen
 * sharing even with the setting ON, and several of them render exactly the
 * data the setting is meant to protect (host labels and addresses in the Cmd+K
 * palette and the host picker, command bodies in the snippet picker/editor, SSH
 * key fingerprints in the TOFU host-key prompt, remote paths in the SFTP
 * dialogs). Calling this composable inside the window content lambda closes
 * that gap.
 *
 * ## Usage
 * ```
 * DialogWindow(...) {
 *     WindowCaptureProtection(window)   // `window` from DialogWindowScope
 *     ...
 * }
 * ```
 *
 * ## Reactivity
 * The single-argument overload observes [WindowSecurityState.hideFromCapture],
 * the same source of truth the main window and the Settings toggle use, so a
 * window that is already open follows a mid-session toggle in both directions
 * (the affinity is re-applied on every state change, not only at creation).
 *
 * ## Ordering vs the other window helpers
 * Independent of [PopupRoundedCorners] (`Window.setShape`) and
 * [WindowsToolWindow] (`WS_EX_TOOLWINDOW`): display affinity is neither a
 * window style nor a region, so the three can be applied in any order. The
 * only real precondition is a live HWND, and
 * [WindowSecurityHelper.applyHideFromCapture] already defers itself via
 * `SwingUtilities.invokeLater` when the AWT peer is not realized yet, same
 * pattern as [WindowsDarkTitleBar] and [WindowsToolWindow].
 *
 * No-op on non-Windows platforms (gated inside [WindowSecurityHelper]).
 */
@Composable
fun WindowCaptureProtection(window: Window) {
    val hiddenFromCapture by WindowSecurityState.hideFromCapture.collectAsState()
    WindowCaptureProtection(window, hiddenFromCapture)
}

/**
 * Explicit-value overload of [WindowCaptureProtection]: applies
 * [hiddenFromCapture] to [window] and re-applies it whenever either argument
 * changes. Use it when the caller already holds the flag; prefer the
 * single-argument overload otherwise so there is only one source of truth.
 *
 * Nothing is reverted on dispose: the window is destroyed with its HWND, and
 * the affinity flag dies with it.
 */
@Composable
fun WindowCaptureProtection(window: Window, hiddenFromCapture: Boolean) {
    // DisposableEffect (not LaunchedEffect): it runs synchronously while the
    // composition changes are applied, i.e. before the window's first frame is
    // painted. A coroutine-dispatched effect could leave a single frame of
    // unprotected content on screen, small, but capturable.
    DisposableEffect(window, hiddenFromCapture) {
        WindowSecurityHelper.applyHideFromCapture(window, hiddenFromCapture)
        // Nothing to undo: the flag lives on the HWND, which dies with the window.
        onDispose { }
    }
}
