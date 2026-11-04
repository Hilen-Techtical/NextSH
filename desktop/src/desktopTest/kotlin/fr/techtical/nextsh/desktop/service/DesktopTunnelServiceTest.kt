// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.SshClientFactory
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
import io.mockk.every
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
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
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
        // The service is not started : no state watch holds a reconnect job for the tunnel,
        // which is the case restartReconnectingTunnels exists for.
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 50L),
            tunnelRepository = FakeTunnelRepository(listOf(config)),
            hostRepository = FakeHostRepository(mapOf(host1.id to host1)),
            appScope = TestAppScope(scope),
        )
        assertTrue(service.startTunnel(config) is SshResult.Success)
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
