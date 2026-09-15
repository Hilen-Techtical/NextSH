// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class SettingsViewModelTest {

    @MockK
    private lateinit var settingsDataStore: SettingsDataStore

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: SettingsViewModel

    private val defaultSettings = SettingsDataStore.Settings()
    private val settingsFlow = MutableStateFlow(defaultSettings)
    private val syncStateFlow = MutableStateFlow(SyncState(SyncStatus.IDLE, null))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { settingsDataStore.settingsFlow } returns settingsFlow
        every { syncScheduler.syncState } returns syncStateFlow

        viewModel = SettingsViewModel(
            settingsDataStore = settingsDataStore,
            vaultManager = vaultManager,
            syncScheduler = syncScheduler,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects default settings from SettingsDataStore`() = runTest {
        val state = viewModel.state.value

        assertEquals(defaultSettings.terminalFontSize, state.terminalFontSize)
        assertEquals(defaultSettings.scrollbackLines, state.scrollbackLines)
        assertEquals(defaultSettings.keepAliveInterval, state.keepAliveInterval)
        assertEquals(defaultSettings.autoReconnect, state.autoReconnect)
        assertEquals(defaultSettings.connectionTimeout, state.connectionTimeout)
    }

    @Test
    fun `initial state autoReconnect is true by default`() = runTest {
        val state = viewModel.state.value
        assertTrue(state.autoReconnect)
    }

    @Test
    fun `wipeVault calls vaultManager wipeVault`() = runTest {
        every { vaultManager.wipeVault() } returns Unit

        viewModel.wipeVault()

        verify(exactly = 1) { vaultManager.wipeVault() }
    }
}
