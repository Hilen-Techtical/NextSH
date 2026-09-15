// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Window

/**
 * Singleton that holds the reference to the main Compose [Window] so that
 * [WindowSecurityHelper.applyHideFromCapture] can be called from the
 * [fr.techtical.nextsh.desktop.settings.SettingsViewModel] without requiring
 * Compose UI access from the ViewModel layer.
 *
 * The [mainWindow] is registered in `Main.kt` via a [LaunchedEffect] once the
 * AWT [Window] object is available (after the Compose `Window` block executes).
 *
 * ## Thread safety
 * [mainWindow] is accessed only on the AWT EDT or inside `LaunchedEffect`:
 * the `@Volatile` annotation covers cross-thread visibility for the initial
 * assignment.
 */
object WindowSecurityState {

    @Volatile
    private var mainWindow: Window? = null

    private val _hideFromCapture = MutableStateFlow(false)

    /** Current effective state of the hide-from-capture flag. */
    val hideFromCapture: StateFlow<Boolean> = _hideFromCapture.asStateFlow()

    /**
     * Register the main application window. Must be called once from `Main.kt`
     * after the `Window` composable is entered (so the AWT peer exists).
     */
    fun registerMainWindow(window: Window) {
        mainWindow = window
    }

    /**
     * Applies [enabled] to the main window (and updates the internal state).
     * No-op at the OS level if no window has been registered yet or if not on
     * Windows.
     *
     * Secondary windows are **not** touched here: each one applies the flag to
     * its own HWND by observing [hideFromCapture] through
     * `fr.techtical.nextsh.desktop.window.WindowCaptureProtection`, so already
     * open dialogs follow the toggle without this object having to keep a
     * registry of live windows.
     */
    fun apply(enabled: Boolean) {
        _hideFromCapture.value = enabled
        mainWindow?.let { WindowSecurityHelper.applyHideFromCapture(it, enabled) }
    }
}
