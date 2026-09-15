// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

/** UI language preference, stored as the enum name string. */
enum class LanguagePref { SYSTEM, FR, EN }

/**
 * Desktop settings store backed by [java.util.prefs.Preferences] (stored by the JVM
 * in the user registry on Windows, ~/.java/.userPrefs on Linux/macOS).
 * Mirrors the keys and defaults of the Android SettingsDataStore.
 */
class DesktopSettingsStore {

    /**
     * Window geometry persisted between sessions. Sentinel value -1 means
     * "not yet set". First launch falls back to defaults (maximized on a
     * 1280×800 floating size centered on the primary display).
     *
     * Stored in **DP** (logical pixels), not raw screen pixels: Compose
     * Desktop's `WindowState.position`/`size` use Dp, and these prefs
     * round-trip those values directly to survive HiDPI displays.
     *
     * `maximized` reflects our custom "maximized" state (snapped to the
     * OS `maximumWindowBounds`, i.e. screen minus taskbar), NOT
     * `WindowPlacement.Maximized` (which goes fullscreen-borderless on
     * undecorated windows and covers the taskbar).
     */
    data class WindowGeometry(
        val x: Int = -1,
        val y: Int = -1,
        val width: Int = -1,
        val height: Int = -1,
        val maximized: Boolean = true,
    ) {
        /** True when no prior session has written to these prefs yet. */
        val isUnset: Boolean get() = x == -1 || y == -1 || width <= 0 || height <= 0
    }

    data class Settings(
        val terminalFontSize: Int = 14,
        val connectionTimeout: Int = 10,
        val clipboardClearTimeout: Int = 60,
        val syncEnabled: Boolean = false,
        val languagePreference: LanguagePref = LanguagePref.SYSTEM,
        /** Exclude the NextSH window from screen captures (Windows only). Default: false. */
        val hideFromScreenCapture: Boolean = false,
        /**
         * Opt-in: hide the window to the system tray instead of exiting when the
         * user clicks the OS close button (X). Default: false. Closing the
         * window quits the app, matching the behavior before this setting
         * existed. Ignored (falls back to the normal close path) when the tray
         * icon could not be registered on this system: see
         * [fr.techtical.nextsh.desktop.service.DesktopTunnelTray.isRegistered].
         */
        val minimizeToTrayOnClose: Boolean = false,
        /**
         * Last-known window geometry. Default = unset → first launch starts
         * maximized at the OS usable bounds, with a remembered "floating"
         * size of 1280×800 centered. Subsequent launches restore exactly
         * what the user left, including the maximized flag.
         */
        val windowGeometry: WindowGeometry = WindowGeometry(),
    )

    private val prefs: Preferences = Preferences.userRoot().node("fr/techtical/nextsh")

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun updateTerminalFontSize(size: Int) {
        prefs.putInt(KEY_FONT_SIZE, size)
        _settings.value = _settings.value.copy(terminalFontSize = size)
    }

    fun updateConnectionTimeout(seconds: Int) {
        prefs.putInt(KEY_CONNECTION_TIMEOUT, seconds)
        _settings.value = _settings.value.copy(connectionTimeout = seconds)
    }

    fun updateClipboardClearTimeout(seconds: Int) {
        prefs.putInt(KEY_CLIPBOARD_CLEAR, seconds)
        _settings.value = _settings.value.copy(clipboardClearTimeout = seconds)
    }

    /** Persists the sync-enabled toggle. Default: false (opt-in). */
    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) { updateSyncEnabled(value) }

    fun updateSyncEnabled(enabled: Boolean) {
        prefs.putBoolean(KEY_SYNC_ENABLED, enabled)
        _settings.value = _settings.value.copy(syncEnabled = enabled)
    }

    fun updateLanguagePreference(pref: LanguagePref) {
        prefs.put(KEY_LANGUAGE, pref.name)
        _settings.value = _settings.value.copy(languagePreference = pref)
    }

    /** Persists the hide-from-screen-capture toggle. Default: false (opt-in). */
    fun updateHideFromScreenCapture(enabled: Boolean) {
        prefs.putBoolean(KEY_HIDE_FROM_CAPTURE, enabled)
        _settings.value = _settings.value.copy(hideFromScreenCapture = enabled)
    }

    /** Persists the minimize-to-tray-on-close toggle. Default: false (opt-in). */
    fun updateMinimizeToTrayOnClose(enabled: Boolean) {
        prefs.putBoolean(KEY_MINIMIZE_TO_TRAY, enabled)
        _settings.value = _settings.value.copy(minimizeToTrayOnClose = enabled)
    }

    /**
     * Persists the window geometry. Called on window close AND on every
     * meaningful change (resize end, move end, maximize toggle) so a
     * crashed JVM still leaves an up-to-date state on disk.
     *
     * All four position/size values must be co-written: persisting partial
     * geometry would create invalid combinations (e.g. width without x).
     */
    fun updateWindowGeometry(geometry: WindowGeometry) {
        prefs.putInt(KEY_WINDOW_X, geometry.x)
        prefs.putInt(KEY_WINDOW_Y, geometry.y)
        prefs.putInt(KEY_WINDOW_WIDTH, geometry.width)
        prefs.putInt(KEY_WINDOW_HEIGHT, geometry.height)
        prefs.putBoolean(KEY_WINDOW_MAXIMIZED, geometry.maximized)
        _settings.value = _settings.value.copy(windowGeometry = geometry)
    }

    private fun load(): Settings = Settings(
        terminalFontSize = prefs.getInt(KEY_FONT_SIZE, 14),
        connectionTimeout = prefs.getInt(KEY_CONNECTION_TIMEOUT, 10),
        clipboardClearTimeout = prefs.getInt(KEY_CLIPBOARD_CLEAR, 60),
        syncEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, false),
        languagePreference = runCatching {
            LanguagePref.valueOf(prefs.get(KEY_LANGUAGE, LanguagePref.SYSTEM.name))
        }.getOrDefault(LanguagePref.SYSTEM),
        hideFromScreenCapture = prefs.getBoolean(KEY_HIDE_FROM_CAPTURE, false),
        minimizeToTrayOnClose = prefs.getBoolean(KEY_MINIMIZE_TO_TRAY, false),
        windowGeometry = WindowGeometry(
            x = prefs.getInt(KEY_WINDOW_X, -1),
            y = prefs.getInt(KEY_WINDOW_Y, -1),
            width = prefs.getInt(KEY_WINDOW_WIDTH, -1),
            height = prefs.getInt(KEY_WINDOW_HEIGHT, -1),
            maximized = prefs.getBoolean(KEY_WINDOW_MAXIMIZED, true),
        ),
    )

    private companion object {
        const val KEY_FONT_SIZE = "terminal_font_size"
        const val KEY_CONNECTION_TIMEOUT = "connection_timeout"
        const val KEY_CLIPBOARD_CLEAR = "clipboard_clear_timeout"
        const val KEY_SYNC_ENABLED = "sync_enabled"
        const val KEY_LANGUAGE = "language_preference"
        const val KEY_HIDE_FROM_CAPTURE = "hide_from_screen_capture"
        const val KEY_MINIMIZE_TO_TRAY = "minimize_to_tray_on_close"
        // KEY_USE_COMPOSE_TERMINAL ("use_compose_terminal_renderer") retired
        // with the Swing renderer removal: Compose is now the only renderer,
        // so the toggle is gone. The key itself is left orphaned in existing
        // users' Preferences store (Windows registry / ~/.java/.userPrefs):
        // no purge routine, it's simply never read again.
        const val KEY_WINDOW_X = "window_x_dp"
        const val KEY_WINDOW_Y = "window_y_dp"
        const val KEY_WINDOW_WIDTH = "window_width_dp"
        const val KEY_WINDOW_HEIGHT = "window_height_dp"
        const val KEY_WINDOW_MAXIMIZED = "window_maximized"
    }
}
