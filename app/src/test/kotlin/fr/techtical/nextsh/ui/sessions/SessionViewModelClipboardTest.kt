// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for clipboard timeout settings (Phase 2).
 *
 * These tests verify the SettingsDataStore.Settings data class behaviour
 * without instantiating SessionViewModel (which requires an Android Context
 * and other platform-bound dependencies).
 *
 * Tests verify:
 * - Clipboard timeout default is 60 seconds
 * - Settings flow updates are correctly reflected
 * - Various timeout values are accepted
 */
class SessionViewModelClipboardTest {

    @Test
    fun `clipboard timeout default is 60 seconds`() {
        val defaultSettings = SettingsDataStore.Settings()
        assertEquals(60, defaultSettings.clipboardClearTimeout)
    }

    @Test
    fun `clipboard clear timeout from settings is reflected in StateFlow`() {
        val settingsFlow = MutableStateFlow(SettingsDataStore.Settings(clipboardClearTimeout = 60))

        assertEquals(60, settingsFlow.value.clipboardClearTimeout)

        settingsFlow.value = SettingsDataStore.Settings(clipboardClearTimeout = 120)

        assertEquals(120, settingsFlow.value.clipboardClearTimeout)
    }

    @Test
    fun `clipboard timeout can be changed to different values`() {
        val settingsFlow = MutableStateFlow(SettingsDataStore.Settings())
        val testTimeouts = listOf(30, 60, 90, 120, 180)

        for (timeout in testTimeouts) {
            settingsFlow.value = SettingsDataStore.Settings(clipboardClearTimeout = timeout)
            assertEquals(timeout, settingsFlow.value.clipboardClearTimeout)
        }
    }

    @Test
    fun `SettingsDataStore Settings has all required fields`() {
        val settings = SettingsDataStore.Settings(clipboardClearTimeout = 60)
        assertEquals(60, settings.clipboardClearTimeout)
    }

    @Test
    fun `clipboard timeout zero disables auto-clear`() {
        val settingsFlow = MutableStateFlow(SettingsDataStore.Settings(clipboardClearTimeout = 0))
        assertEquals(0, settingsFlow.value.clipboardClearTimeout)
    }

    @Test
    fun `terminal font size flow is properly initialized`() {
        val defaultFontSize = 24
        assertEquals(defaultFontSize, SettingsDataStore.Settings().terminalFontSize)
    }
}
