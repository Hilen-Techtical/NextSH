// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.settings

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.desktop.core.vault.VaultPinManager
import fr.techtical.nextsh.desktop.data.db.DesktopDatabase
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.service.DesktopTunnelTray
import fr.techtical.nextsh.desktop.sync.LanSyncServer
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.desktop.vault.VaultError
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A failed generation of the recovery phrase is kept as a typed error for the screen to show, until dismissed. */
class SettingsViewModelRecoveryErrorTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @AfterTest
    fun tearDown() {
        app.onDestroy()
    }

    private fun newViewModel(pinManager: VaultPinManager): SettingsViewModel =
        SettingsViewModel(
            store = mockk<DesktopSettingsStore>().also {
                every { it.settings } returns MutableStateFlow(DesktopSettingsStore.Settings())
            },
            vaultManager = mockk<VaultManager>(),
            database = mockk<DesktopDatabase>(),
            knownHostsStore = mockk<DesktopKnownHostsStore>(),
            appScope = app,
            syncLifecycle = mockk<LanSyncServerLifecycle>(),
            syncServer = mockk<LanSyncServer>().also {
                every { it.state } returns MutableStateFlow<LanSyncServer.State>(LanSyncServer.State.Stopped)
            },
            syncScheduler = mockk<DesktopSyncScheduler>().also {
                every { it.syncState } returns MutableStateFlow(SyncState(SyncStatus.IDLE, null))
            },
            customThemeRepository = mockk<CustomTerminalThemeRepository>().also {
                every { it.observeAll() } returns flowOf(emptyList())
            },
            pinManager = pinManager,
            tunnelTray = mockk<DesktopTunnelTray>(),
        )

    @Test
    fun `a failed generation sets the typed error and dismissing clears it`() = runBlocking {
        val pinManager = mockk<VaultPinManager>().also {
            every { it.hasRecoveryPhrase() } returns false
            every { it.setupRecoveryPhrase() } returns Result.failure(IllegalStateException("boom"))
        }
        val vm = newViewModel(pinManager)

        vm.generateRecoveryPhrase()

        val failed = withTimeout(5_000L) { vm.recoveryState.first { it.error != null } }
        assertEquals(VaultError.RECOVERY_GENERATION_FAILED, failed.error)

        vm.dismissRecoveryError()

        assertNull(vm.recoveryState.value.error)
    }
}
