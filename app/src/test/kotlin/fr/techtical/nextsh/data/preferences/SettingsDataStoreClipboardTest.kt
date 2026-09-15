// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for SettingsDataStore clipboard timeout functionality (Phase 2).
 *
 * Tests verify:
 * - Default clipboard timeout is 60 seconds
 * - Settings data class correctly stores and retrieves clipboard timeout
 * - Clipboard timeout can be updated to different values
 */
class SettingsDataStoreClipboardTest {

    @Test
    fun `default clipboard timeout is 60 seconds`() {
        val settings = SettingsDataStore.Settings()
        assertEquals(60, settings.clipboardClearTimeout)
    }

    @Test
    fun `clipboard timeout can be set to custom values`() {
        val testTimeouts = listOf(0, 30, 60, 90, 120, 180, 300)

        for (timeout in testTimeouts) {
            val settings = SettingsDataStore.Settings(clipboardClearTimeout = timeout)
            assertEquals(timeout, settings.clipboardClearTimeout)
        }
    }

    @Test
    fun `clipboard timeout zero disables auto-clear`() {
        val settings = SettingsDataStore.Settings(clipboardClearTimeout = 0)
        assertEquals(0, settings.clipboardClearTimeout)
    }

    @Test
    fun `clipboard timeout retains value with other settings`() {
        val settings = SettingsDataStore.Settings(
            terminalFontSize = 18,
            scrollbackLines = 5000,
            keepAliveInterval = 20,
            autoReconnect = false,
            connectionTimeout = 15,
            clipboardClearTimeout = 90,
        )

        assertEquals(90, settings.clipboardClearTimeout)
        assertEquals(18, settings.terminalFontSize)
        assertEquals(5000, settings.scrollbackLines)
        assertEquals(20, settings.keepAliveInterval)
        assertEquals(false, settings.autoReconnect)
        assertEquals(15, settings.connectionTimeout)
    }

    @Test
    fun `all default Settings values are correct`() {
        val settings = SettingsDataStore.Settings()

        assertEquals(24, settings.terminalFontSize)
        assertEquals(10000, settings.scrollbackLines)
        assertEquals(30, settings.keepAliveInterval)
        assertEquals(true, settings.autoReconnect)
        assertEquals(10, settings.connectionTimeout)
        assertEquals(60, settings.clipboardClearTimeout)
    }

    @Test
    fun `Settings data class clipboard timeout can be modified via copy`() {
        val originalSettings = SettingsDataStore.Settings(clipboardClearTimeout = 60)
        val modifiedSettings = originalSettings.copy(clipboardClearTimeout = 120)

        assertEquals(60, originalSettings.clipboardClearTimeout)
        assertEquals(120, modifiedSettings.clipboardClearTimeout)
    }

    @Test
    fun `clipboard timeout edge cases`() {
        // Test very small values
        assertEquals(1, SettingsDataStore.Settings(clipboardClearTimeout = 1).clipboardClearTimeout)

        // Test large values
        assertEquals(3600, SettingsDataStore.Settings(clipboardClearTimeout = 3600).clipboardClearTimeout)

        // Test 0 (no auto-clear)
        assertEquals(0, SettingsDataStore.Settings(clipboardClearTimeout = 0).clipboardClearTimeout)
    }
}
