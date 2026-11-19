// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.SshClientFactory
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_network_lost
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_session_closed
import fr.techtical.nextsh.desktop.ui.browser.DesktopBrowserSessionHolder
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
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.Transport
import org.jetbrains.compose.resources.getString
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopTunnelService].
 *
 * These tests use **real time** (`runBlocking`) rather than `runTest` because
 * [DesktopNetworkMonitor] runs a `while (isActive) delay(pollInterval)` loop:
 * the test scheduler's `advanceUntilIdle` never converges on an infinite loop,
 * even when the delay is virtual. Using real time with a short poll interval
 * (50 ms) keeps the plain tests under 3 s each. The reconnect scenarios wait
 * out the service's 1 s initial backoff, so they take a few seconds more
 * (about 3 to 4 s for the in-flight start case, which sits through two rounds).
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

        /** When set, observeByHost throws it, like a database that cannot be read. */
        var observeByHostFailure: Exception? = null

        override fun observeAll(): Flow<List<TunnelConfig>> = tunnels.asStateFlow()
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> {
            observeByHostFailure?.let { throw it }
            return MutableStateFlow(tunnels.value.filter { it.hostId == hostId }).asStateFlow()
        }
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
    fun `an auto-start with openBrowserOnConnect opens the in-app browser once the tunnel is ACTIVE`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "auto-browser", label = "auto-browser", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true, openBrowserOnConnect = true,
        )
        val holder = DesktopBrowserSessionHolder()
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(buildMockClient()) }
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
            browserSessionHolder = holder,
        )

        service.onVaultUnlocked()

        assertTrue(waitFor(5_000L) { holder.hasSession(config.id) }, "the browser must open for an ACTIVE auto-start")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `an auto-start the user stops during the handshake does not open the in-app browser`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val stopped = TunnelConfig(
            id = "auto-stopped", label = "auto-stopped", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true, openBrowserOnConnect = true,
        )
        // Started right after the first one : the auto-start loop is sequential, so once this
        // one is ACTIVE the loop is done with `stopped` (and its browser decision is taken).
        val sentinel = TunnelConfig(
            id = "auto-sentinel", label = "auto-sentinel", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val opens = AtomicInteger(0)
        val firstOpenEntered = CompletableDeferred<Unit>()
        val releaseFirstOpen = CompletableDeferred<Unit>()
        val factory = SshClientFactory { _, _ ->
            if (opens.incrementAndGet() == 1) {
                firstOpenEntered.complete(Unit)
                releaseFirstOpen.await()
            }
            SshResult.Success(buildMockClient())
        }
        val holder = DesktopBrowserSessionHolder()
        val manager = DesktopTunnelManager(scope, factory)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L),
            tunnelRepository = FakeTunnelRepository(listOf(stopped, sentinel)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
            browserSessionHolder = holder,
        )

        service.onVaultUnlocked()
        withTimeout(5_000L) { firstOpenEntered.await() }
        // The user clicks Stop while the handshake is in flight : the start then answers
        // Success (a deliberate stop is not an error) for a tunnel that is not up.
        service.stopTunnel(stopped.id)
        releaseFirstOpen.complete(Unit)

        assertTrue(
            waitFor(5_000L) { manager.getTunnelState(sentinel.id)?.status == TunnelStatus.ACTIVE },
            "the auto-start loop must go on to the next tunnel",
        )
        assertFalse(holder.hasSession(stopped.id), "a tunnel the user stopped must not pop the browser")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(stopped.id)?.status)

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
    fun `stopTunnelsOfHost stops the running tunnels of that host and leaves the others running`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val host2 = host1.copy(id = "host-2", label = "h2")
        fun tunnel(id: String, host: Host) = TunnelConfig(
            id = id, label = id, hostId = host.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val onHost1a = tunnel("t1a", host1)
        val onHost1b = tunnel("t1b", host1)
        val onHost2 = tunnel("t2", host2)

        val factory = SshClientFactory { _, _ -> SshResult.Success(buildMockClient()) }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = FakeTunnelRepository(listOf(onHost1a, onHost1b, onHost2)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1, host2.id to host2)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.activeIds().size == 3 }, "all three tunnels must become active")

        service.stopTunnelsOfHost(host1.id)

        assertEquals(setOf("t2"), manager.activeIds())
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("t1a")?.status)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("t1b")?.status)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("t2")?.status)

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `stopTunnelsOfHost also stops a RECONNECTING tunnel and cancels its reconnect loop`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "reco", label = "reco", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val factoryCount = AtomicInteger(0)
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
        assertTrue(waitFor(5_000L) { manager.getTunnelState("reco")?.status == TunnelStatus.ACTIVE })
        val opensBefore = factoryCount.get()
        online.set(false)
        assertTrue(waitFor(3_000L) { manager.getTunnelState("reco")?.status == TunnelStatus.RECONNECTING })

        service.stopTunnelsOfHost(host1.id)

        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("reco")?.status)
        // Back online: a reconnect loop that survived the stop would reopen the tunnel (first backoff 1 s).
        online.set(true)
        delay(1_800L)
        assertEquals(opensBefore, factoryCount.get(), "no reconnect after the stop")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("reco")?.status)

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `stopTunnelsOfHost stops a tunnel moved to that host by an edit while it was running`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val host2 = host1.copy(id = "host-2", label = "h2")
        val moved = TunnelConfig(
            id = "moved", label = "moved", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val stays = TunnelConfig(
            id = "stays", label = "stays", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val repository = FakeTunnelRepository(listOf(moved, stays))
        val factory = SshClientFactory { _, _ -> SshResult.Success(buildMockClient()) }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = repository,
            hostRepository = FakeHostRepository(mapOf(host1.id to host1, host2.id to host2)),
            appScope = TestAppScope(scope),
        )
        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.activeIds().size == 2 }, "both tunnels must become active")
        // The edit moves the tunnel to host-2 in the database. Its running state still holds host-1.
        repository.update(moved.copy(hostId = host2.id))
        assertEquals(host1.id, manager.getTunnelState("moved")?.config?.hostId)

        service.stopTunnelsOfHost(host2.id)

        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("moved")?.status)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("stays")?.status)

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `stopTunnelsOfHost still stops what the running state designates when the database cannot be read`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "t1", label = "t1", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val repository = FakeTunnelRepository(listOf(config))
        val factory = SshClientFactory { _, _ -> SshResult.Success(buildMockClient()) }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = repository,
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )
        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState("t1")?.status == TunnelStatus.ACTIVE })
        repository.observeByHostFailure = IllegalStateException("disk I/O error")

        service.stopTunnelsOfHost(host1.id)

        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("t1")?.status)

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `stopTunnelIfRunning stops a running tunnel and ignores one that is not running`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val running = TunnelConfig(
            id = "running", label = "running", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val factory = SshClientFactory { _, _ -> SshResult.Success(buildMockClient()) }
        val manager = DesktopTunnelManager(scope, factory)
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = monitor,
            tunnelRepository = FakeTunnelRepository(listOf(running)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState("running")?.status == TunnelStatus.ACTIVE })

        service.stopTunnelIfRunning("running")
        service.stopTunnelIfRunning("never-started")

        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("running")?.status)
        assertTrue(manager.activeIds().isEmpty())
        assertEquals(null, manager.getTunnelState("never-started"))
        assertEquals(false, manager.wasManuallyStopped("never-started"), "a tunnel that never ran is not flagged")

        service.onAppExitSuspend()
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
        // The reason is read at the instant RECONNECTING is first seen : the network loss text.
        var firstReason: String? = null
        assertTrue(
            waitFor(3_000L) {
                val state = manager.getTunnelState("net-t")
                if (state?.status == TunnelStatus.RECONNECTING) { firstReason = state.errorMessage; true } else false
            },
            "tunnel must be RECONNECTING after network drop (status=${manager.getTunnelState("net-t")?.status})",
        )
        val networkLost = getString(Res.string.tunnels_error_network_lost)
        assertEquals(networkLost, firstReason)

        // Stay offline past the first backoff (1 s) : no attempt is made, so no host or timeout text
        // replaces the network reason, and the factory is not asked.
        val opensBeforeOutage = factoryCount.get()
        var replaced: String? = null
        waitFor(1_800L) {
            val state = manager.getTunnelState("net-t")
            if (state?.errorMessage != networkLost) replaced = "${state?.status}/${state?.errorMessage}"
            false
        }
        assertEquals(null, replaced, "the network reason must stay for the whole outage")
        assertEquals(opensBeforeOutage, factoryCount.get(), "no connection attempt while offline")

        // Recovery: attemptReconnect starts with delay(1000ms), so we wait up
        // to 4s for the factory to be invoked again.
        online.set(true)
        assertTrue(
            waitFor(6_000L) { factoryCount.get() > initialOpens },
            "factory must be invoked again on recovery (was ${factoryCount.get()}, initial=$initialOpens)",
        )
        assertTrue(
            waitFor(5_000L) { manager.getTunnelState("net-t")?.status == TunnelStatus.ACTIVE },
            "tunnel must be ACTIVE again",
        )
        assertEquals(null, manager.getTunnelState("net-t")?.errorMessage, "the reason goes away once ACTIVE")

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `the reason of a loss stays on the card through the reconnect attempts and goes away once ACTIVE`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "reason-t", label = "reason-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        // The first client hands over the DisconnectListener the manager installs on it.
        val listenerSlot = io.mockk.slot<DisconnectListener>()
        val transport = mockk<Transport>(relaxed = true)
        every { transport.setDisconnectListener(capture(listenerSlot)) } just Runs
        val firstClient = buildMockClient()
        every { firstClient.transport } returns transport

        val opens = AtomicInteger(0)
        // First open succeeds, the next one fails, then it succeeds again : one failed attempt is
        // enough to go through every transition (about 3 s with the 1 s and 2 s backoff).
        val factory = SshClientFactory { _, _ ->
            when (opens.incrementAndGet()) {
                1 -> SshResult.Success(firstClient)
                2 -> SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "texte-essai")
                else -> SshResult.Success(buildMockClient())
            }
        }
        val manager = DesktopTunnelManager(scope, factory)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE })

        listenerSlot.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "server text")
        val afterLoss = manager.getTunnelState(config.id)
        assertEquals(TunnelStatus.RECONNECTING, afterLoss?.status)
        assertEquals(getString(Res.string.tunnels_error_session_closed), afterLoss?.errorMessage)

        // Sampled every 25 ms until the tunnel is back : while it reconnects, the card always has a reason.
        var withoutReason = 0
        var sawAttemptReason = false
        val back = waitFor(15_000L) {
            val state = manager.getTunnelState(config.id)
            if (state?.status == TunnelStatus.RECONNECTING || state?.status == TunnelStatus.STARTING) {
                if (state.errorMessage.isNullOrBlank()) withoutReason++
                if (state.errorMessage == "texte-essai") sawAttemptReason = true
            }
            state?.status == TunnelStatus.ACTIVE
        }
        assertTrue(back, "the tunnel must come back")
        assertEquals(0, withoutReason, "a reconnecting card must always carry a reason")
        assertTrue(sawAttemptReason, "the reason of the failed attempt must replace the cause of the loss")
        assertEquals(null, manager.getTunnelState(config.id)?.errorMessage, "the reason goes away once ACTIVE")

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `a reconnect attempt landing during an in-flight start waits, and retries if that start fails`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "inflight-t", label = "inflight-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = AtomicInteger(0)
        val firstOpenEntered = CompletableDeferred<Unit>()
        val releaseFirstOpen = CompletableDeferred<Unit>()
        // The first open (the auto-start) hangs in its handshake until released,
        // then fails ; every later open succeeds.
        val factory = SshClientFactory { _, _ ->
            if (opens.incrementAndGet() == 1) {
                firstOpenEntered.complete(Unit)
                releaseFirstOpen.await()
                SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "handshake timeout")
            } else {
                SshResult.Success(buildMockClient())
            }
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
        withTimeout(5_000L) { firstOpenEntered.await() }
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)

        // The network drops while the handshake is in flight : STARTING flips to
        // RECONNECTING and a reconnect loop is scheduled next to the pending start.
        online.set(false)
        assertTrue(
            waitFor(3_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "the in-flight tunnel must be flagged RECONNECTING (status=${manager.getTunnelState(config.id)?.status})",
        )
        // The link is back right away : the loop makes no attempt while the monitor says offline,
        // and these attempts are what the test is about. The reconnect job already exists, so the
        // network recovery does not start a second path.
        online.set(true)

        // The loop's first attempt comes after the 1 s backoff, and again 1 s later.
        // Both land while the first start is still pending : they must open nothing.
        delay(2_500L)
        assertEquals(1, opens.get(), "a reconnect attempt must not open a client next to the pending start")

        // The pending start fails. If the loop had taken the ignored Start for a
        // successful reconnect it would be gone, and the tunnel would stay in ERROR.
        releaseFirstOpen.complete(Unit)
        assertTrue(
            waitFor(6_000L) { opens.get() == 2 && manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the reconnect loop must retry once the pending start failed " +
                "(opens=${opens.get()}, status=${manager.getTunnelState(config.id)?.status})",
        )

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `a reconnect loop leaves a user start in flight alone and ends once it is ACTIVE`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "user-start-t", label = "user-start-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = AtomicInteger(0)
        val userOpenEntered = CompletableDeferred<Unit>()
        val releaseUserOpen = CompletableDeferred<Unit>()
        // Open 1 is the auto-start. Open 2 is the Start the user issues while the
        // loop waits out its backoff : it parks in its handshake. No third open is expected.
        val factory = SshClientFactory { _, _ ->
            if (opens.incrementAndGet() == 2) {
                userOpenEntered.complete(Unit)
                releaseUserOpen.await()
            }
            SshResult.Success(buildMockClient())
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
        assertTrue(
            waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the auto-start must come up",
        )
        online.set(false)
        assertTrue(
            waitFor(3_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "the tunnel must be RECONNECTING after the network drop",
        )
        // Wait for the loop to release the old session : a Start before that would be
        // ignored as "already active".
        assertTrue(waitFor(3_000L) { config.id !in manager.activeIds() }, "the old session must be released")
        // Back online (the loop makes no attempt while offline) : the rounds below must be skipped
        // because of the start in flight, not because of the outage.
        online.set(true)

        val userStart = async(Dispatchers.Default) { service.startTunnel(config) }
        withTimeout(5_000L) { userOpenEntered.await() }
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)

        // Two loop rounds (1 s each) go by : they must neither open a client nor
        // paint RECONNECTING over the user's STARTING.
        delay(2_500L)
        assertEquals(2, opens.get(), "the loop must not open a client next to the user's start")
        assertEquals(
            TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status,
            "the loop must not overwrite the status of a start in flight",
        )

        // The user's start succeeds : the loop must end on ACTIVE, not overwrite it either.
        releaseUserOpen.complete(Unit)
        assertTrue(userStart.await() is SshResult.Success)
        delay(1_500L)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status, "ACTIVE must survive the loop")
        assertEquals(2, opens.get())

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `a stale ACTIVE status without a live tunnel does not end the reconnect loop`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "stale-active-t", label = "stale-active-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = AtomicInteger(0)
        val factory = SshClientFactory { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(buildMockClient())
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
        assertTrue(
            waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the auto-start must come up",
        )
        online.set(false)
        assertTrue(
            waitFor(3_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "the tunnel must be RECONNECTING after the network drop",
        )
        assertTrue(waitFor(3_000L) { config.id !in manager.activeIds() }, "the old session must be released")
        // Back online : the loop makes no attempt while the monitor says offline.
        online.set(true)

        // An ACTIVE status written with no live entry behind it (here by hand, during the
        // loop's first backoff) is not a tunnel : the loop must go on and start for real.
        manager.updateStatus(config.id, TunnelStatus.ACTIVE)

        assertTrue(
            waitFor(4_000L) { opens.get() == 2 && config.id in manager.activeIds() },
            "the loop must start the tunnel for real (opens=${opens.get()}, active=${manager.activeIds()})",
        )

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `a reconnect start cancelled by a manual stop ends the loop and leaves the tunnel STOPPED`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "manual-stop-t", label = "manual-stop-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = AtomicInteger(0)
        val reconnectOpenEntered = CompletableDeferred<Unit>()
        val releaseReconnectOpen = CompletableDeferred<Unit>()
        // Open 1 is the auto-start. Open 2 is the loop's first attempt : it parks in its handshake.
        val factory = SshClientFactory { _, _ ->
            if (opens.incrementAndGet() == 2) {
                reconnectOpenEntered.complete(Unit)
                releaseReconnectOpen.await()
            }
            SshResult.Success(buildMockClient())
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
        assertTrue(
            waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the auto-start must come up",
        )
        online.set(false)
        assertTrue(
            waitFor(3_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "the tunnel must be RECONNECTING after the network drop",
        )
        // Back online : the loop makes no attempt while the monitor says offline.
        online.set(true)
        withTimeout(5_000L) { reconnectOpenEntered.await() }

        // Stop through the manager (the service's own stopTunnel would cancel the loop
        // first): the in-flight start then returns Success without a live tunnel.
        manager.stopTunnel(config.id)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        releaseReconnectOpen.complete(Unit)

        // The loop must read that Success as a stop, not paint RECONNECTING over STOPPED.
        delay(1_500L)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertEquals(2, opens.get())
        assertFalse(manager.activeIds().contains(config.id))

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `a reconnect loop survives a start that throws an unexpected exception and brings the tunnel back`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "boom-t", label = "boom-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )

        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = AtomicInteger(0)
        // Open 1 is the auto-start. Open 2 is the loop's first attempt : the factory blows up with
        // something nobody planned for, instead of returning an Error. Every later open succeeds.
        val factory = SshClientFactory { _, _ ->
            if (opens.incrementAndGet() == 2) throw IllegalStateException("factory exploded")
            SshResult.Success(buildMockClient())
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
        assertTrue(
            waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the auto-start must come up",
        )
        online.set(false)
        assertTrue(
            waitFor(3_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "the tunnel must be RECONNECTING after the network drop",
        )
        // Back online : the loop makes no attempt while the monitor says offline.
        online.set(true)

        // First attempt after the 1 s backoff : the exception becomes an Error result, the loop doubles
        // its backoff (2 s) and tries again. Without that, the exception would end the loop's job and
        // leave the tunnel on STARTING for good.
        assertTrue(
            waitFor(8_000L) { opens.get() == 3 && manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the loop must carry on after the exception " +
                "(opens=${opens.get()}, status=${manager.getTunnelState(config.id)?.status})",
        )
        assertTrue(manager.activeIds().contains(config.id))
        assertFalse(manager.wasManuallyStopped(config.id), "a failed start is not a manual stop")
        assertEquals(3, opens.get(), "one attempt blew up, one succeeded, no other")

        service.onAppExitSuspend()
        scope.cancel()
    }

    /** A factory whose opens after the first fail with [failureMessage], flagged network-level or not. */
    private class FailingAfterFirstFactory(
        private val first: SSHClient,
        private val failureMessage: String,
        private val networkLevel: Boolean,
    ) : SshClientFactory {
        val opens = AtomicInteger(0)

        @Volatile var secondOpenAt = 0L

        @Volatile var thirdOpenAt = 0L

        override suspend fun open(host: Host, onCreated: (SSHClient) -> Unit): SshResult<SSHClient> =
            when (opens.incrementAndGet()) {
                1 -> SshResult.Success(first)
                2 -> {
                    secondOpenAt = System.nanoTime()
                    SshResult.Error(SshErrorCode.HOST_UNREACHABLE, failureMessage)
                }
                else -> {
                    if (thirdOpenAt == 0L) thirdOpenAt = System.nanoTime()
                    SshResult.Error(SshErrorCode.HOST_UNREACHABLE, failureMessage)
                }
            }

        override fun lastFailureWasNetworkLevel(host: Host): Boolean = networkLevel
    }

    /** A running tunnel on a client whose DisconnectListener the test can fire. */
    private fun clientWithListener(listener: io.mockk.CapturingSlot<DisconnectListener>): SSHClient {
        val transport = mockk<Transport>(relaxed = true)
        every { transport.setDisconnectListener(capture(listener)) } just Runs
        val client = buildMockClient()
        every { client.transport } returns transport
        return client
    }

    /** The text the card shows after the first failed attempt of the reconnect loop. */
    private fun reasonAfterFailedAttempt(networkLevel: Boolean): String? = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "fail-reason-$networkLevel", label = "fail-reason", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val listener = io.mockk.slot<DisconnectListener>()
        val factory = FailingAfterFirstFactory(clientWithListener(listener), "texte-essai", networkLevel)
        val manager = DesktopTunnelManager(scope, factory)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L, interfaceSnapshot = { emptySet() }),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )
        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE })
        listener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, null)

        val sessionClosed = getString(Res.string.tunnels_error_session_closed)
        var reason: String? = null
        assertTrue(
            waitFor(6_000L) {
                val message = manager.getTunnelState(config.id)?.errorMessage
                if (factory.opens.get() >= 2 && message != null && message != sessionClosed) { reason = message; true } else false
            },
            "the failed attempt must replace the reason of the loss",
        )
        service.onAppExitSuspend()
        scope.cancel()
        reason
    }

    @Test
    fun `a reconnect attempt that fails on a network-level error shows the network reason`() {
        assertEquals(runBlocking { getString(Res.string.tunnels_error_network_lost) }, reasonAfterFailedAttempt(networkLevel = true))
    }

    @Test
    fun `a reconnect attempt that fails on anything else, a timeout included, keeps its own text`() {
        assertEquals("texte-essai", reasonAfterFailedAttempt(networkLevel = false))
    }

    @Test
    fun `a change of the network interfaces wakes the reconnect loop before its delay ends and restarts the backoff`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "wake-t", label = "wake-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val listener = io.mockk.slot<DisconnectListener>()
        // Every attempt fails: the first one after 1 s, the next one would wait 2 s.
        val factory = FailingAfterFirstFactory(clientWithListener(listener), "texte-essai", networkLevel = false)
        val manager = DesktopTunnelManager(scope, factory)
        val interfaces = java.util.concurrent.atomic.AtomicReference(setOf("wlan0|192.168.1.10", "vmnet1|192.168.56.1"))
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L, interfaceSnapshot = { interfaces.get() }),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )
        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE })
        listener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, null)

        assertTrue(waitFor(5_000L) { factory.opens.get() >= 2 }, "the first attempt must fail")
        // The Wi-Fi comes back: the set of interfaces changes. The next attempt must not wait the 2 s.
        interfaces.set(setOf("wlan0|192.168.1.99", "vmnet1|192.168.56.1"))
        assertTrue(waitFor(1_500L) { factory.thirdOpenAt != 0L }, "the loop must be woken by the change")
        val waitedMs = (factory.thirdOpenAt - factory.secondOpenAt) / 1_000_000L
        assertTrue(waitedMs < 1_500L, "the attempt must follow the change at once, waited $waitedMs ms")

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `a loopback host name is recognised by its spelling, without any resolution`() {
        for (name in listOf("127.0.0.1", "127.1.2.3", "127.255.255.255", "localhost", "LocalHost", "localhost.", "::1", "[::1]", " 127.0.0.1 ")) {
            assertTrue(isLoopbackHostname(name), name)
        }
        for (name in listOf("a.example.com", "10.0.0.1", "128.0.0.1", "127.0.0.256", "127.0.0", "localhost.example.com", "::2", "")) {
            assertFalse(isLoopbackHostname(name), name)
        }
    }

    @Test
    fun `a tunnel on a loopback host still reconnects while the network monitor says offline`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val loopbackHost = host1.copy(id = "host-lo", hostname = "127.0.0.1")
        val config = TunnelConfig(
            id = "lo-t", label = "lo-t", hostId = loopbackHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            autoStart = true,
        )
        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val opens = AtomicInteger(0)
        val factory = SshClientFactory { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(buildMockClient())
        }
        val manager = DesktopTunnelManager(scope, factory)
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { online.get() }, pollIntervalMs = 50L),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(loopbackHost.id to loopbackHost)),
            appScope = TestAppScope(scope),
        )

        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE })
        val initialOpens = opens.get()

        online.set(false)
        assertTrue(
            waitFor(5_000L) { opens.get() > initialOpens && manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "a loopback host is reconnected even while offline (opens=${opens.get()})",
        )

        service.onAppExitSuspend()
        scope.cancel()
    }

    @Test
    fun `restartReconnectingTunnels reconnects a RECONNECTING tunnel instead of flagging it as manually stopped`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = TunnelConfig(
            id = "restart-t", label = "restart-t", hostId = host1.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        )
        val opens = AtomicInteger(0)
        val factory = SshClientFactory { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(buildMockClient())
        }
        val manager = DesktopTunnelManager(scope, factory)
        // The tunnel is started through the manager, not the service : service.startTunnel
        // starts the state watch, whose own reconnect job would race restartReconnectingTunnels
        // and hide a regression. Without it, no job holds the tunnel, which is the case
        // restartReconnectingTunnels exists for.
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )
        assertTrue(manager.startTunnel(config) { _ -> host1 } is SshResult.Success)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)
        manager.updateStatus(config.id, TunnelStatus.RECONNECTING)

        service.restartReconnectingTunnels()

        // attemptReconnect waits out its 1 s backoff, then starts for real. With stopTunnel
        // in front of it the manual-stop flag would end the loop and nothing would reopen.
        assertTrue(
            waitFor(5_000L) { opens.get() == 2 && manager.getTunnelState(config.id)?.status == TunnelStatus.ACTIVE },
            "the tunnel must be reconnected (opens=${opens.get()}, status=${manager.getTunnelState(config.id)?.status})",
        )
        assertTrue(manager.activeIds().contains(config.id))
        assertFalse(manager.wasManuallyStopped(config.id), "a reconnect is not a manual stop")

        service.onAppExitSuspend()
        scope.cancel()
    }
}
