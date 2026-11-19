// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import fr.techtical.nextsh.shared.core.backup.BackupAndroidSettings
import fr.techtical.nextsh.shared.core.backup.BackupCommonSettings
import fr.techtical.nextsh.shared.core.backup.BackupDesktopSettings
import fr.techtical.nextsh.shared.core.backup.BackupPlatform
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The allow-list of the settings a backup carries on Android: snapshot (export) and application
 * (import). Security settings, sync and onboarding are never read into a backup and never written
 * from one.
 */
class SettingsBackupAllowListTest {

    private val fontSize = intPreferencesKey("terminal_font_size")
    private val scrollback = intPreferencesKey("scrollback_lines")
    /** The key of the removed global keepalive setting: still in the store of an upgraded device. */
    private val keepAlive = intPreferencesKey("keep_alive_interval")
    private val autoReconnect = booleanPreferencesKey("auto_reconnect")
    private val timeout = intPreferencesKey("connection_timeout")

    // Never carried.
    private val clipboard = intPreferencesKey("clipboard_clear_timeout")
    private val autoLock = intPreferencesKey("auto_lock_minutes")
    private val syncEnabled = booleanPreferencesKey("sync_enabled")
    private val syncInterval = intPreferencesKey("sync_interval_minutes")
    private val onboarding = booleanPreferencesKey("onboarding_completed")

    @Test
    fun `an empty store snapshots the defaults of the settings screen`() {
        val settings = SettingsDataStore.backupSettingsOf(mutablePreferencesOf())

        assertEquals(10, settings.common?.connectionTimeoutSeconds)
        assertEquals(24, settings.android?.terminalFontSize)
        assertEquals(5000, settings.android?.scrollbackLines)
        assertEquals(true, settings.android?.autoReconnect)
        assertNull(settings.desktop, "an Android export has no desktop section")
    }

    @Test
    fun `the snapshot reads the stored values and ignores every security and device setting`() {
        val prefs = mutablePreferencesOf(
            fontSize to 30, scrollback to 20000, autoReconnect to false, timeout to 15,
            clipboard to 0, autoLock to 0, syncEnabled to true, syncInterval to 1, onboarding to true,
        )

        val settings = SettingsDataStore.backupSettingsOf(prefs)

        assertEquals(
            BackupSettings(
                common = BackupCommonSettings(connectionTimeoutSeconds = 15),
                android = BackupAndroidSettings(
                    terminalFontSize = 30,
                    scrollbackLines = 20000,
                    autoReconnect = false,
                ),
            ),
            settings,
        )
        // 1 common + 3 android: nothing else could have slipped in.
        assertEquals(4, settings.countFor(BackupPlatform.ANDROID))
    }

    @Test
    fun `a stored value outside its domain is left out of the snapshot`() {
        val prefs = mutablePreferencesOf(fontSize to 99, scrollback to 5, timeout to 7)

        val settings = SettingsDataStore.backupSettingsOf(prefs)

        assertNull(settings.common?.connectionTimeoutSeconds)
        assertNull(settings.android?.terminalFontSize)
        assertNull(settings.android?.scrollbackLines)
    }

    @Test
    fun `applying writes the four allow-listed values and nothing else`() {
        val prefs = mutablePreferencesOf(
            clipboard to 60, autoLock to 5, syncEnabled to false, syncInterval to 15, onboarding to false,
        )
        val backup = BackupSettings(
            common = BackupCommonSettings(connectionTimeoutSeconds = 25),
            android = BackupAndroidSettings(
                terminalFontSize = 28,
                scrollbackLines = 20000,
                autoReconnect = false,
            ),
        )

        val applied = SettingsDataStore.applyBackupSettingsTo(prefs, backup)

        assertEquals(4, applied)
        assertEquals(25, prefs[timeout])
        assertEquals(28, prefs[fontSize])
        assertEquals(20000, prefs[scrollback])
        assertEquals(false, prefs[autoReconnect])
        // The security and device settings keep their value, whatever the file says.
        assertEquals(60, prefs[clipboard])
        assertEquals(5, prefs[autoLock])
        assertEquals(false, prefs[syncEnabled])
        assertEquals(15, prefs[syncInterval])
        assertEquals(false, prefs[onboarding])
        assertEquals(9, prefs.asMap().size)
    }

    @Test
    fun `the desktop section is never applied on Android`() {
        val prefs = mutablePreferencesOf()
        val backup = BackupSettings(desktop = BackupDesktopSettings(terminalFontSizePx = 16, language = "FR"))

        val applied = SettingsDataStore.applyBackupSettingsTo(prefs, backup)

        assertEquals(0, applied)
        assertEquals(0, prefs.asMap().size)
    }

    @Test
    fun `a value outside its domain is skipped, not clamped`() {
        val prefs = mutablePreferencesOf()
        val backup = BackupSettings(
            common = BackupCommonSettings(connectionTimeoutSeconds = 7),
            android = BackupAndroidSettings(terminalFontSize = 99, scrollbackLines = 5, autoReconnect = true),
        )

        val applied = SettingsDataStore.applyBackupSettingsTo(prefs, backup)

        assertEquals(1, applied)
        assertEquals(true, prefs[autoReconnect])
        assertNull(prefs[timeout])
        assertNull(prefs[fontSize])
        assertNull(prefs[scrollback])
    }

    @Test
    fun `a scrollback stored out of the steps is snapshotted on the nearest step`() {
        assertEquals(20000, SettingsDataStore.backupSettingsOf(mutablePreferencesOf(scrollback to 100000)).android?.scrollbackLines)
        assertEquals(10000, SettingsDataStore.backupSettingsOf(mutablePreferencesOf(scrollback to 7600)).android?.scrollbackLines)
        assertEquals(1000, SettingsDataStore.backupSettingsOf(mutablePreferencesOf(scrollback to 1000)).android?.scrollbackLines)
    }

    @Test
    fun `a scrollback restored out of the steps lands on the nearest step`() {
        val prefs = mutablePreferencesOf()

        SettingsDataStore.applyBackupSettingsTo(prefs, BackupSettings(android = BackupAndroidSettings(scrollbackLines = 37412)))

        assertEquals(20000, prefs[scrollback])
    }

    @Test
    fun `a keepalive interval in a backup is read without error and never applied`() {
        val prefs = mutablePreferencesOf(keepAlive to 45)
        // The shared format still carries the field (a v2 file may hold it, and BackupCodec reads with
        // ignoreUnknownKeys): Android reads it without error and neither applies nor writes it.
        val decoded = BackupSettings(android = BackupAndroidSettings(keepAliveIntervalSeconds = 90, autoReconnect = false))

        val applied = SettingsDataStore.applyBackupSettingsTo(prefs, decoded)

        assertEquals(1, applied)
        assertEquals(false, prefs[autoReconnect])
        assertEquals(45, prefs[keepAlive], "the legacy key is left alone")
    }

    @Test
    fun `an absent value leaves the stored one alone`() {
        val prefs = mutablePreferencesOf(fontSize to 20)

        val applied = SettingsDataStore.applyBackupSettingsTo(
            prefs,
            BackupSettings(android = BackupAndroidSettings(autoReconnect = false)),
        )

        assertEquals(1, applied)
        assertEquals(20, prefs[fontSize])
    }
}
