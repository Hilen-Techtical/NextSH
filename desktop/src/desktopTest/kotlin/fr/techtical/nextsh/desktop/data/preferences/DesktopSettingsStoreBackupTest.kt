// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.preferences

import fr.techtical.nextsh.shared.core.backup.BackupAndroidSettings
import fr.techtical.nextsh.shared.core.backup.BackupCommonSettings
import fr.techtical.nextsh.shared.core.backup.BackupDesktopSettings
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The allow-list of settings carried by a backup. Runs on an in-memory preferences node ([InMemoryPreferences]),
 * never on the real store of the user (the Windows registry).
 */
class DesktopSettingsStoreBackupTest {

    private lateinit var node: Preferences
    private lateinit var store: DesktopSettingsStore

    @BeforeTest
    fun setUp() {
        node = InMemoryPreferences()
        store = DesktopSettingsStore(node)
    }

    @Test
    fun `snapshot carries the timeout, the font size, and the language, not the tray toggle`() {
        store.updateConnectionTimeout(20)
        store.updateTerminalFontSize(16)
        store.updateLanguagePreference(LanguagePref.EN)

        val snapshot = store.snapshot()

        assertEquals(20, snapshot.common?.connectionTimeoutSeconds)
        assertEquals(BackupDesktopSettings(terminalFontSizePx = 16, language = "EN"), snapshot.desktop)
        assertNull(snapshot.android, "only the section of the own platform is written")
    }

    @Test
    fun `snapshot never mentions a security setting, even when they are set`() {
        store.updateClipboardClearTimeout(15)
        store.updateHideFromScreenCapture(true)
        store.updateSyncEnabled(true)

        val json = Json.encodeToString(BackupSettings.serializer(), store.snapshot())

        for (forbidden in listOf("clipboard", "hide", "capture", "sync", "window", "lock", "tray")) {
            assertFalse(json.contains(forbidden, ignoreCase = true), "the backup must not carry '$forbidden'")
        }
    }

    @Test
    fun `snapshot leaves out a stored value that the format would refuse`() {
        store.updateConnectionTimeout(7)
        store.updateTerminalFontSize(25)

        val snapshot = store.snapshot()

        assertNull(snapshot.common?.connectionTimeoutSeconds)
        assertNull(snapshot.desktop?.terminalFontSizePx)
    }

    @Test
    fun `apply writes common and desktop, ignores android and leaves the security settings alone`() {
        store.updateClipboardClearTimeout(15)
        store.updateHideFromScreenCapture(true)
        store.updateSyncEnabled(true)
        val geometryBefore = store.settings.value.windowGeometry

        val result = store.apply(
            BackupSettings(
                common = BackupCommonSettings(connectionTimeoutSeconds = 25),
                android = BackupAndroidSettings(terminalFontSize = 40, autoReconnect = false),
                desktop = BackupDesktopSettings(terminalFontSizePx = 18, language = "FR"),
            ),
        )

        assertEquals(3, result.applied, "1 common + 2 desktop values, the android section is not counted")
        assertTrue(result.languageChanged)
        val s = store.settings.value
        assertEquals(25, s.connectionTimeout)
        assertEquals(18, s.terminalFontSize, "the Android size (other scale) must not be applied")
        assertEquals(LanguagePref.FR, s.languagePreference)
        assertEquals(15, s.clipboardClearTimeout)
        assertTrue(s.hideFromScreenCapture)
        assertTrue(s.syncEnabled)
        assertEquals(geometryBefore, s.windowGeometry)
    }

    @Test
    fun `apply skips a value outside its domain instead of clamping it`() {
        store.updateConnectionTimeout(10)
        store.updateTerminalFontSize(14)

        val result = store.apply(
            BackupSettings(
                common = BackupCommonSettings(connectionTimeoutSeconds = 17),
                desktop = BackupDesktopSettings(terminalFontSizePx = 99, language = "KLINGON"),
            ),
        )

        assertEquals(0, result.applied)
        assertFalse(result.languageChanged)
        assertEquals(10, store.settings.value.connectionTimeout)
        assertEquals(14, store.settings.value.terminalFontSize)
        assertEquals(LanguagePref.SYSTEM, store.settings.value.languagePreference)
    }

    @Test
    fun `the language is reported as changed only when it differs`() {
        store.updateLanguagePreference(LanguagePref.EN)

        val same = store.apply(BackupSettings(desktop = BackupDesktopSettings(language = "EN")))
        val other = store.apply(BackupSettings(desktop = BackupDesktopSettings(language = "SYSTEM")))

        assertEquals(1, same.applied)
        assertFalse(same.languageChanged)
        assertTrue(other.languageChanged)
    }

    @Test
    fun `apply of nothing does nothing`() {
        val result = store.apply(null)

        assertEquals(0, result.applied)
        assertFalse(result.languageChanged)
    }

    @Test
    fun `a value written by apply survives a new store on the same node`() {
        store.apply(BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 30)))

        assertEquals(30, DesktopSettingsStore(node).settings.value.connectionTimeout)
    }
}
