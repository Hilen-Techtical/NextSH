// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.lock.AutoLockDelay
import fr.techtical.nextsh.shared.core.backup.BackupAndroidSettings
import fr.techtical.nextsh.shared.core.backup.BackupCommonSettings
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import fr.techtical.nextsh.shared.core.sync.SyncInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The scrollback choices of the settings, in lines. The upper bound keeps one tab near 36 MB at worst. */
val SCROLLBACK_STEPS: List<Int> = listOf(1000, 2000, 5000, 10_000, 20_000)

/** The scrollback of a new tab when nothing is stored. */
const val DEFAULT_SCROLLBACK_LINES = 5000

/**
 * [lines] brought to the closest of [SCROLLBACK_STEPS] (the smaller one on a tie), so that a value
 * stored by the old continuous slider, or restored from a backup, always lands on a choice.
 */
fun nearestScrollbackStep(lines: Int): Int =
    SCROLLBACK_STEPS.minByOrNull { kotlin.math.abs(it - lines) } ?: DEFAULT_SCROLLBACK_LINES

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nextsh_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val SCROLLBACK_LINES = intPreferencesKey("scrollback_lines")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        val CLIPBOARD_CLEAR_TIMEOUT = intPreferencesKey("clipboard_clear_timeout")
        /**
         * Minutes out of the foreground before the app locks itself, see [AutoLockDelay]. A value
         * that is none of the known ones reads as the default.
         */
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
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
        val scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
        val autoReconnect: Boolean = true,
        val connectionTimeout: Int = 10,
        val clipboardClearTimeout: Int = 60,
        val autoLockDelay: AutoLockDelay = AutoLockDelay.DEFAULT,
        val syncEnabled: Boolean = false,
        val syncInterval: SyncInterval = SyncInterval.DEFAULT,
    )

    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            terminalFontSize = prefs[Keys.TERMINAL_FONT_SIZE] ?: 24,
            scrollbackLines = nearestScrollbackStep(prefs[Keys.SCROLLBACK_LINES] ?: DEFAULT_SCROLLBACK_LINES),
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            connectionTimeout = prefs[Keys.CONNECTION_TIMEOUT] ?: 10,
            clipboardClearTimeout = prefs[Keys.CLIPBOARD_CLEAR_TIMEOUT] ?: 60,
            autoLockDelay = AutoLockDelay.fromMinutes(prefs[Keys.AUTO_LOCK_MINUTES]),
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

    suspend fun updateAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun updateConnectionTimeout(seconds: Int) {
        context.dataStore.edit { it[Keys.CONNECTION_TIMEOUT] = seconds }
    }

    suspend fun updateClipboardClearTimeout(seconds: Int) {
        context.dataStore.edit { it[Keys.CLIPBOARD_CLEAR_TIMEOUT] = seconds }
    }

    suspend fun updateAutoLockDelay(delay: AutoLockDelay) {
        context.dataStore.edit { it[Keys.AUTO_LOCK_MINUTES] = delay.minutes }
    }

    suspend fun updateSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SYNC_ENABLED] = enabled }
    }

    suspend fun updateSyncInterval(interval: SyncInterval) {
        context.dataStore.edit { it[Keys.SYNC_INTERVAL_MINUTES] = interval.minutes }
    }

    // Backup .nextsh (allow-list)

    /**
     * The settings a backup carries: the `common` section and the `android` one, from the values in
     * effect now. Security settings (auto-lock, clipboard clearing), sync and onboarding are NEVER
     * part of it.
     */
    suspend fun backupSettings(): BackupSettings = backupSettingsOf(context.dataStore.data.first())

    /**
     * Applies the allow-listed settings of a backup (`common` plus `android`; the `desktop` section is
     * ignored) in one write. A value outside its domain is skipped. Returns the number of values
     * written. Never touches auto-lock, clipboard clearing, sync nor onboarding.
     */
    suspend fun applyBackupSettings(settings: BackupSettings): Int {
        var applied = 0
        context.dataStore.edit { applied = applyBackupSettingsTo(it, settings) }
        return applied
    }

    internal companion object {
        private val TIMEOUTS = setOf(5, 10, 15, 20, 25, 30)
        private val FONT_SIZES = 12..46
        private val SCROLLBACK = 1000..100_000

        /** Allow-list snapshot of [prefs] (defaults for absent keys); an out-of-domain stored value is left out. */
        internal fun backupSettingsOf(prefs: Preferences): BackupSettings {
            val timeout = (prefs[Keys.CONNECTION_TIMEOUT] ?: 10).takeIf { it in TIMEOUTS }
            val font = (prefs[Keys.TERMINAL_FONT_SIZE] ?: 24).takeIf { it in FONT_SIZES }
            val scrollback = (prefs[Keys.SCROLLBACK_LINES] ?: DEFAULT_SCROLLBACK_LINES).takeIf { it in SCROLLBACK }?.let(::nearestScrollbackStep)
            return BackupSettings(
                common = BackupCommonSettings(connectionTimeoutSeconds = timeout),
                android = BackupAndroidSettings(
                    terminalFontSize = font,
                    scrollbackLines = scrollback,
                    autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                ),
            )
        }

        /** Writes the allow-listed values of [settings] into [prefs]; returns how many. */
        internal fun applyBackupSettingsTo(prefs: MutablePreferences, settings: BackupSettings): Int {
            var applied = 0
            settings.common?.connectionTimeoutSeconds?.takeIf { it in TIMEOUTS }?.let {
                prefs[Keys.CONNECTION_TIMEOUT] = it
                applied++
            }
            settings.android?.let { android ->
                android.terminalFontSize?.takeIf { it in FONT_SIZES }?.let {
                    prefs[Keys.TERMINAL_FONT_SIZE] = it
                    applied++
                }
                android.scrollbackLines?.takeIf { it in SCROLLBACK }?.let {
                    prefs[Keys.SCROLLBACK_LINES] = nearestScrollbackStep(it)
                    applied++
                }
                android.autoReconnect?.let {
                    prefs[Keys.AUTO_RECONNECT] = it
                    applied++
                }
            }
            return applied
        }
    }
}
