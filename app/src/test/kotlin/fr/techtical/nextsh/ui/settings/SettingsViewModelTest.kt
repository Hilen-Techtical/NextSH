// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.lock.AutoLockDelay
import fr.techtical.nextsh.core.vault.AndroidVaultWipe
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
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
import org.junit.jupiter.api.Assertions.assertFalse
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
    private lateinit var androidVaultWipe: AndroidVaultWipe

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    private val testDispatcher = UnconfinedTestDispatcher()

    /** The real lock, on a clock that never moves: it starts locked, like the app. */
    private val appLock = AppLock { 0L }

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
            androidVaultWipe = androidVaultWipe,
            syncScheduler = syncScheduler,
            appLock = appLock,
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
        assertEquals(defaultSettings.autoReconnect, state.autoReconnect)
        assertEquals(defaultSettings.connectionTimeout, state.connectionTimeout)
    }

    @Test
    fun `initial state autoReconnect is true by default`() = runTest {
        val state = viewModel.state.value
        assertTrue(state.autoReconnect)
    }

    @Test
    fun `forceSync swallows a failure of the scheduler instead of crashing`() = runTest {
        coEvery { syncScheduler.forceSync() } throws IllegalStateException("boom")

        viewModel.forceSync()

        coVerify(exactly = 1) { syncScheduler.forceSync() }
    }

    // ── The wipe of the vault ─────────────────────────────────────────────────

    @Test
    fun `wipeVault starts the wipe of the app and does nothing else`() = runTest {
        justRun { androidVaultWipe.start() }

        viewModel.wipeVault()

        verify(exactly = 1) { androidVaultWipe.start() }
    }

    @Test
    fun `the wipe asks for no authentication and does not lock the app`() = runTest {
        appLock.unlock()
        justRun { androidVaultWipe.start() }

        viewModel.wipeVault()

        assertFalse(appLock.isLocked(), "the wipe works on the raw preferences, the vault need not be open")
    }

    @Test
    fun `lock now locks the app`() = runTest {
        appLock.unlock()

        viewModel.lockNow()

        assertTrue(appLock.isLocked())
    }

    @Test
    fun `the delay of the automatic lock is written to the settings`() = runTest {
        coJustRun { settingsDataStore.updateAutoLockDelay(any()) }

        viewModel.updateAutoLockDelay(AutoLockDelay.FIFTEEN_MINUTES)

        coVerify(exactly = 1) { settingsDataStore.updateAutoLockDelay(AutoLockDelay.FIFTEEN_MINUTES) }
    }

    @Test
    fun `the delay of the automatic lock is five minutes until the settings say otherwise`() = runTest {
        assertEquals(AutoLockDelay.FIVE_MINUTES, viewModel.state.value.autoLockDelay)
    }
}
