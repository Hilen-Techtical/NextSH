// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.shared.core.sync.SyncInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nextsh_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val SCROLLBACK_LINES = intPreferencesKey("scrollback_lines")
        val KEEP_ALIVE_INTERVAL = intPreferencesKey("keep_alive_interval")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        val CLIPBOARD_CLEAR_TIMEOUT = intPreferencesKey("clipboard_clear_timeout")
        /** Whether LAN sync is enabled. Persisted as Boolean; default false (opt-in). */
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        /** Sync interval in minutes. Stored as Int matching [SyncInterval.minutes]. */
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        /**
         * Whether the first-launch onboarding stepper has been completed.
         * Default false → onboarding is shown once on first launch. Set true
         * when the user finishes (or skips) the final step, gating the flow so
         * it never reappears on subsequent launches.
         */
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    data class Settings(
        val terminalFontSize: Int = 24,
        val scrollbackLines: Int = 10000,
        val keepAliveInterval: Int = 30,
        val autoReconnect: Boolean = true,
        val connectionTimeout: Int = 10,
        val clipboardClearTimeout: Int = 60,
        val syncEnabled: Boolean = false,
        val syncInterval: SyncInterval = SyncInterval.DEFAULT,
    )

    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            terminalFontSize = prefs[Keys.TERMINAL_FONT_SIZE] ?: 24,
            scrollbackLines = prefs[Keys.SCROLLBACK_LINES] ?: 10000,
            keepAliveInterval = prefs[Keys.KEEP_ALIVE_INTERVAL] ?: 30,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            connectionTimeout = prefs[Keys.CONNECTION_TIMEOUT] ?: 10,
            clipboardClearTimeout = prefs[Keys.CLIPBOARD_CLEAR_TIMEOUT] ?: 60,
            syncEnabled = prefs[Keys.SYNC_ENABLED] ?: false,
            syncInterval = SyncInterval.fromMinutes(prefs[Keys.SYNC_INTERVAL_MINUTES] ?: SyncInterval.DEFAULT.minutes),
        )
    }

    val terminalFontSizeFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.TERMINAL_FONT_SIZE] ?: 24
    }

    /**
     * Emits whether first-launch onboarding has already been completed.
     * Used by the NavGraph to decide whether to show the onboarding stepper
     * or route straight to the vault unlock screen.
     */
    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun updateTerminalFontSize(size: Int) {
        context.dataStore.edit { it[Keys.TERMINAL_FONT_SIZE] = size }
    }

    suspend fun updateScrollbackLines(lines: Int) {
        context.dataStore.edit { it[Keys.SCROLLBACK_LINES] = lines }
    }

    suspend fun updateKeepAliveInterval(seconds: Int) {
        context.dataStore.edit { it[Keys.KEEP_ALIVE_INTERVAL] = seconds }
    }

    suspend fun updateAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun updateConnectionTimeout(seconds: Int) {
        context.dataStore.edit { it[Keys.CONNECTION_TIMEOUT] = seconds }
    }

    suspend fun updateClipboardClearTimeout(seconds: Int) {
        context.dataStore.edit { it[Keys.CLIPBOARD_CLEAR_TIMEOUT] = seconds }
    }

    suspend fun updateSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SYNC_ENABLED] = enabled }
    }

    suspend fun updateSyncInterval(interval: SyncInterval) {
        context.dataStore.edit { it[Keys.SYNC_INTERVAL_MINUTES] = interval.minutes }
    }
}
