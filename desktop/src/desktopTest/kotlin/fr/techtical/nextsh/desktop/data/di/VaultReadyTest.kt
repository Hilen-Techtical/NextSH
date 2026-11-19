// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.sync.LanSyncServer
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.desktop.sync.SyncServerControl
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [startVaultDependentServices] is what the PIN unlock and the two exits of the first launch all run: the
 * first session after the vault creation starts the same services as any other session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultReadyTest {

    private class FakeSyncServer : SyncServerControl {
        private val _state = MutableStateFlow<LanSyncServer.State>(LanSyncServer.State.Stopped)
        override val state: StateFlow<LanSyncServer.State> = _state.asStateFlow()
        var startCount = 0
            private set

        override fun start(shouldRun: () -> Boolean) {
            startCount++
            _state.value = LanSyncServer.State.Listening("192.168.1.10", 47731)
        }

        override fun stop() {
            _state.value = LanSyncServer.State.Stopped
        }
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    @Test
    fun `starts the tunnel service and tells the sync lifecycle the vault is open, from every path`() = runTest {
        val server = FakeSyncServer()
        val syncEnabled = AtomicBoolean(false) // a fresh vault: sync is opt-in
        val store = mockk<DesktopSettingsStore>(relaxed = true)
        every { store.syncEnabled } answers { syncEnabled.get() }
        every { store.updateSyncEnabled(any()) } answers { syncEnabled.set(firstArg()) }
        val lifecycle = LanSyncServerLifecycle(server, store, TestAppScope(this))
        val tunnelService = mockk<DesktopTunnelService>(relaxed = true)

        // FirstLaunch.onCompleted, FirstLaunch.onConfigureSync, VaultUnlock.onUnlocked.
        repeat(3) { startVaultDependentServices(lifecycle, tunnelService) }
        runCurrent()

        verify(exactly = 3) { tunnelService.onVaultUnlocked() } // idempotent in the service itself
        assertEquals(0, server.startCount, "sync is off: opening the vault must not start the sync server")
        // The lifecycle knows the vault is open, so the toggle of Settings works in this very session.
        lifecycle.applySettingsChange(enabled = true)
        runCurrent()
        assertEquals(1, server.startCount)

        // One watcher, however many paths asked for it: stopping once leaves nothing running, otherwise
        // runTest would never complete.
        lifecycle.stopNetworkWatcher()
    }
}
