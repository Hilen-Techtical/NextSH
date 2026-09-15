// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.SshClientFactory
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopTunnelService].
 *
 * These tests use **real time** (`runBlocking`) rather than `runTest` because
 * [DesktopNetworkMonitor] runs a `while (isActive) delay(pollInterval)` loop:
 * the test scheduler's `advanceUntilIdle` never converges on an infinite loop,
 * even when the delay is virtual. Using real time with a short poll interval
 * (50 ms) keeps tests <3s each.
 *
 * Drives the service with fakes for [TunnelRepository] / [HostRepository], a
 * controllable [DesktopNetworkMonitor] via `networkPredicate`, and a real
 * [DesktopTunnelManager] backed by a scripted [SshClientFactory].
 */
class DesktopTunnelServiceTest {

    private val openedServerSockets = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        openedServerSockets.forEach { try { it.close() } catch (_: Exception) {} }
        openedServerSockets.clear()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeTunnelRepository(initial: List<TunnelConfig>) : TunnelRepository {
        private val tunnels = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<TunnelConfig>> = tunnels.asStateFlow()
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> = tunnels.asStateFlow()
        override suspend fun getById(id: String): TunnelConfig? = tunnels.value.firstOrNull { it.id == id }
        override suspend fun getAutoStartTunnels(): List<TunnelConfig> = tunnels.value.filter { it.autoStart }
        override suspend fun save(config: TunnelConfig) { tunnels.value = tunnels.value + config }
        override suspend fun update(config: TunnelConfig) {
            tunnels.value = tunnels.value.map { if (it.id == config.id) config else it }
        }
        override suspend fun delete(id: String) { tunnels.value = tunnels.value.filterNot { it.id == id } }
        override fun observeFavorites(): Flow<List<TunnelConfig>> = tunnels.asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeHostRepository(private val byId: Map<String, Host>) : HostRepository {
        override fun observeAll(): Flow<List<Host>> = MutableStateFlow(byId.values.toList()).asStateFlow()
        override fun observeByGroup(group: String): Flow<List<Host>> = observeAll()
        override fun observeGroups(): Flow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
        override suspend fun getById(id: String): Host? = byId[id]
        override suspend fun save(host: Host) = Unit
        override suspend fun update(host: Host) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun updateLastConnected(id: String) = Unit
        override fun observeFavorites(): Flow<List<Host>> = MutableStateFlow(emptyList<Host>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildMockClient(): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        val forwarderMock = mockk<LocalPortForwarder>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarderMock
        return client
    }

    /** Wait until [condition] returns true or the timeout elapses. Real time. */
    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25L)
        }
        return condition()
    }

    private val host1 = Host(
        id = "host-1", label = "h1", hostname = "a.example.com", port = 22,
        username = "u", authType = AuthType.PASSWORD, credentialId = "c1",
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `onVaultUnlocked only starts autoStart tunnels`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val port1 = freePort()
        val port2 = freePort()
        val autoT = TunnelConfig(
            id = "auto", label = "auto", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port1, remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val manualT = TunnelConfig(
            id = "manual", label = "manual", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port2, remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = false,
        )

        val factoryCount = AtomicInteger(0)
        val factory = SshClientFactory { _, _ ->
            factoryCount.incrementAndGet()
            SshResult.Success(buildMockClient())
        }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = FakeTunnelRepository(listOf(autoT, manualT)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        // Wait until auto-start finishes.
        assertTrue(waitFor(5_000L) { manager.getTunnelState("auto")?.status == TunnelStatus.ACTIVE },
            "auto-start tunnel must become ACTIVE within 5s")

        assertEquals(1, factoryCount.get(), "only autoStart=true tunnel should open an SSHClient")
        assertEquals(null, manager.getTunnelState("manual"))

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `onAppExit stops all running tunnels`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val port1 = freePort()
        val port2 = freePort()
        val t1 = TunnelConfig(
            id = "t1", label = "t1", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port1, remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val t2 = TunnelConfig(
            id = "t2", label = "t2", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port2, remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val factory = SshClientFactory { _, _ -> SshResult.Success(buildMockClient()) }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = FakeTunnelRepository(listOf(t1, t2)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        val ok = waitFor(5_000L) { manager.activeIds().size == 2 }
        if (!ok) {
            val dump = manager.tunnelStates.value.map { (k, v) -> "$k=${v.status} err=${v.errorMessage}" }
            kotlin.test.fail("both tunnels should be active within 5s, observed: $dump")
        }

        service.onAppExitSuspend()
        assertTrue(waitFor(1_000L) { manager.activeIds().isEmpty() },
            "activeIds should be empty after onAppExitSuspend")

        scope.cancel()
    }

    @Test
    fun `network loss marks ACTIVE as RECONNECTING then restart on recovery`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val port = freePort()
        val config = TunnelConfig(
            id = "net-t", label = "net-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port, remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        // Use AtomicReference for multi-thread visibility: the scheduled
        // network poll runs on Dispatchers.Default, the test body runs on the
        // main test thread; plain var wouldn't guarantee visibility.
        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val factoryCount = AtomicInteger(0)
        // Scripted factory: returns HOST_UNREACHABLE while offline, mock client otherwise.
        val factory = SshClientFactory { _, _ ->
            if (!online.get()) SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "offline")
            else { factoryCount.incrementAndGet(); SshResult.Success(buildMockClient()) }
        }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { online.get() }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState("net-t")?.status == TunnelStatus.ACTIVE },
            "tunnel should become ACTIVE within 5s")
        val initialOpens = factoryCount.get()

        // Network drops: the monitor poll (50ms) should notice within 200ms.
        online.set(false)
        assertTrue(
            waitFor(3_000L) { manager.getTunnelState("net-t")?.status == TunnelStatus.RECONNECTING },
            "tunnel must be RECONNECTING after network drop (status=${manager.getTunnelState("net-t")?.status})",
        )

        // Recovery: attemptReconnect starts with delay(1000ms), so we wait up
        // to 4s for the factory to be invoked again.
        online.set(true)
        assertTrue(
            waitFor(6_000L) { factoryCount.get() > initialOpens },
            "factory must be invoked again on recovery (was ${factoryCount.get()}, initial=$initialOpens)",
        )

        service.onAppExitSuspend()
        scope.cancel()
    }
}
