// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.Transport
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopTunnelManager].
 *
 * We mock the SSHJ [SSHClient] entirely: the real `newLocalPortForwarder`
 * returns a `LocalPortForwarder` that binds natively in tests and pollutes
 * ports; we stub it with a relaxed mock whose `listen()` blocks (we don't
 * actually forward any bytes) so the `scope.launch` inside the manager can
 * park without progressing.
 *
 * Scenarios covered :
 *  - LOCAL_FORWARD START success → STARTING → ACTIVE → STOPPED
 *  - START failure (factory returns Error) → STARTING → ERROR
 *  - PORT_IN_USE detected before factory is called (port already bound)
 *  - REMOTE_FORWARD happy path (using mocked remotePortForwarder)
 *  - DYNAMIC_SOCKS5 happy path (SOCKS5 server starts on local port)
 *  - stop on unknown id returns Success (idempotent)
 *  - LOCAL_FORWARD listen loop: a REAL `LocalPortForwarder` (not a mock) must
 *    return from `listen()` on stop / releaseForReconnect / onTunnelLost, and a
 *    genuine IOException must still send the tunnel to RECONNECTING
 *  - reservation of in-flight starts: a Start during STARTING is ignored, and
 *    the reservation is released on every exit of startTunnel
 *  - defensive replacement of a live entry (`registerActive`)
 *  - a Stop that lands after the handshake (before the registration, right after
 *    it, before the ACTIVE write) : nothing stays alive, the state ends STOPPED
 *    and the start reports a quiet success, for the three tunnel types ; plus a
 *    start / stop race on real threads
 *  - the client stays in `startingClients` through the forwarder setup (a Stop
 *    during a blocked remote bind aborts it at once) and is gone on every exit
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTunnelManagerTest {

    private val testHost = Host(
        id = "host-1",
        label = "Test Host",
        hostname = "test.example.com",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private val openedServerSockets = mutableListOf<ServerSocket>()
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        openedServerSockets.forEach { try { it.close() } catch (_: Exception) {} }
        openedServerSockets.clear()
        // Never leave a spinning listener behind when an assertion failed.
        cleanups.forEach { try { it() } catch (_: Exception) {} }
        cleanups.clear()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Polls [condition] for at most [timeoutMs] (real time, never hangs). */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /**
     * Real SSHJ forwarder on the manager's real `ServerSocket`, observed from
     * outside: [returned] is released when `listen()` gives control back,
     * [failure] holds what it threw, if anything.
     */
    private class TrackingForwarder(
        connection: Connection,
        params: Parameters,
        serverSocket: ServerSocket,
    ) : LocalPortForwarder(connection, params, serverSocket, LoggerFactory.DEFAULT) {
        val returned = CountDownLatch(1)

        @Volatile var listenThread: Thread? = null

        @Volatile var failure: Throwable? = null

        override fun listen() {
            listenThread = Thread.currentThread()
            try {
                super.listen()
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                returned.countDown()
            }
        }
    }

    /** `listen()` that waits for [gate], then dies with an IOException. */
    private class FailingForwarder(
        connection: Connection,
        params: Parameters,
        serverSocket: ServerSocket,
        private val gate: CountDownLatch,
    ) : LocalPortForwarder(connection, params, serverSocket, LoggerFactory.DEFAULT) {
        override fun listen() {
            gate.await(5, TimeUnit.SECONDS)
            throw IOException("boom")
        }
    }

    /**
     * `listen()` that parks until interrupted, then dies with an IOException
     * (what a channel open torn down by a disconnect does). [interrupted] is
     * released as soon as the interrupt reaches the loop thread.
     */
    private class InterruptSensitiveForwarder(
        connection: Connection,
        params: Parameters,
        serverSocket: ServerSocket,
    ) : LocalPortForwarder(connection, params, serverSocket, LoggerFactory.DEFAULT) {
        val listening = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val ended = CountDownLatch(1)
        private val never = CountDownLatch(1)

        override fun listen() {
            listening.countDown()
            try {
                never.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw IOException("interrupted", e)
            } finally {
                ended.countDown()
            }
        }
    }

    private class TunnelFixture<F : LocalPortForwarder>(
        val manager: DesktopTunnelManager,
        val config: TunnelConfig,
        val client: SSHClient,
        val forwarder: F,
        val disconnectListener: DisconnectListener,
    )

    /**
     * Starts a LOCAL_FORWARD through the real manager path. The mocked client
     * hands back the forwarder built by [build] on the socket the manager bound.
     */
    private suspend fun <F : LocalPortForwarder> startTunnelWith(
        id: String,
        onDisconnect: () -> Unit = {},
        build: (Connection, Parameters, ServerSocket) -> F,
    ): TunnelFixture<F> {
        val config = TunnelConfig(
            id = id, label = "loop-$id", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        )
        val listenerSlot = slot<DisconnectListener>()
        val transport = mockk<Transport>(relaxed = true)
        every { transport.setDisconnectListener(capture(listenerSlot)) } just Runs
        val mockClient = mockk<SSHClient>(relaxed = true)
        every { mockClient.transport } returns transport
        every { mockClient.disconnect() } answers { onDisconnect() }

        var built: F? = null
        every { mockClient.newLocalPortForwarder(any(), any()) } answers {
            build(mockk(relaxed = true), firstArg(), secondArg()).also { built = it }
        }

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }
        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Success, "start must succeed, got $result")
        val forwarder = assertNotNull(built, "the manager must ask the client for a forwarder")
        (forwarder as? TrackingForwarder)?.let { cleanups += { if (it.returned.count > 0) it.listenThread?.interrupt() } }
        return TunnelFixture(manager, config, mockClient, forwarder, listenerSlot.captured)
    }

    // ── LOCAL_FORWARD ────────────────────────────────────────────────────────

    @Test
    fun `startTunnel LOCAL_FORWARD emits STARTING then ACTIVE on success`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-1", label = "local-tun", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port, remoteHost = "127.0.0.1", remotePort = 80,
        )

        val mockClient = mockk<SSHClient>(relaxed = true)
        // Return a relaxed LocalPortForwarder: `listen()` is called in a coroutine,
        // we don't need it to do anything.
        val paramsSlot = slot<Parameters>()
        val forwarderMock = mockk<LocalPortForwarder>(relaxed = true)
        every { mockClient.newLocalPortForwarder(capture(paramsSlot), any()) } returns forwarderMock

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        val result = manager.startTunnel(config) { _ -> testHost }

        assertTrue(result is SshResult.Success, "start must succeed, got $result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("tun-1")?.status)
        assertEquals(port, paramsSlot.captured.localPort)
        assertTrue(manager.activeIds().contains("tun-1"))

        // Stop
        val stopResult = manager.stopTunnel("tun-1")
        assertTrue(stopResult is SshResult.Success)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("tun-1")?.status)
        assertFalse(manager.activeIds().contains("tun-1"))
    }

    // ── LOCAL_FORWARD listen loop ────────────────────────────────────────────
    // SSHJ's `LocalPortForwarder.listen()` swallows the SocketException thrown
    // by `accept()` on a closed server socket and only exits once its thread is
    // interrupted: closing the socket alone spins the IO worker at 100% of a core.

    @Test
    fun `stopTunnel ends the SSHJ listen loop`() = runTest(UnconfinedTestDispatcher()) {
        val fx = startTunnelWith("tun-loop-stop") { c, p, s -> TrackingForwarder(c, p, s) }
        assertTrue(awaitUntil { fx.forwarder.isRunning }, "the forwarder must be listening")

        assertTrue(fx.manager.stopTunnel(fx.config.id) is SshResult.Success)

        assertTrue(
            fx.forwarder.returned.await(5, TimeUnit.SECONDS),
            "listen() must return after stopTunnel, not spin on the closed socket",
        )
        assertNull(fx.forwarder.failure, "a deliberate stop must end listen() quietly")
        assertEquals(TunnelStatus.STOPPED, fx.manager.getTunnelState(fx.config.id)?.status)
    }

    @Test
    fun `releaseForReconnect ends the SSHJ listen loop without touching the state`() = runTest(UnconfinedTestDispatcher()) {
        val fx = startTunnelWith("tun-loop-release") { c, p, s -> TrackingForwarder(c, p, s) }
        assertTrue(awaitUntil { fx.forwarder.isRunning }, "the forwarder must be listening")

        fx.manager.releaseForReconnect(fx.config.id)

        assertTrue(
            fx.forwarder.returned.await(5, TimeUnit.SECONDS),
            "listen() must return after releaseForReconnect, not spin on the closed socket",
        )
        assertNull(fx.forwarder.failure, "a release for reconnect must end listen() quietly")
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
        assertFalse(fx.manager.wasManuallyStopped(fx.config.id), "a release must not flag a manual stop")
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
    }

    @Test
    fun `a lost tunnel goes RECONNECTING and its listen loop ends`() = runTest(UnconfinedTestDispatcher()) {
        val fx = startTunnelWith("tun-loop-lost") { c, p, s -> TrackingForwarder(c, p, s) }
        assertTrue(awaitUntil { fx.forwarder.isRunning }, "the forwarder must be listening")

        fx.disconnectListener.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "lost")

        assertTrue(
            fx.forwarder.returned.await(5, TimeUnit.SECONDS),
            "listen() must return once the tunnel is lost",
        )
        assertEquals(TunnelStatus.RECONNECTING, fx.manager.getTunnelState(fx.config.id)?.status)
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
    }

    @Test
    fun `a genuine IOException in the listen loop still sends the tunnel to RECONNECTING`() = runTest(UnconfinedTestDispatcher()) {
        // The loop thread can run before `active` is filled in : hold it until
        // startTunnel has returned so the guard in the catch block sees the tunnel.
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val fx = startTunnelWith("tun-loop-ioe") { c, p, s -> FailingForwarder(c, p, s, gate) }

        gate.countDown()

        assertTrue(
            awaitUntil { fx.manager.getTunnelState(fx.config.id)?.status == TunnelStatus.RECONNECTING },
            "an unexpected IOException must still be reported as a lost tunnel",
        )
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
    }

    @Test
    fun `stopTunnel cancels the job before the disconnect and an IOException from the interrupt stays STOPPED`() = runTest(UnconfinedTestDispatcher()) {
        val holder = AtomicReference<InterruptSensitiveForwarder>()
        val interruptedBeforeDisconnect = AtomicBoolean(false)
        val fx = startTunnelWith(
            "tun-loop-ioe-stop",
            onDisconnect = { interruptedBeforeDisconnect.set(holder.get().interrupted.await(5, TimeUnit.SECONDS)) },
        ) { c, p, s -> InterruptSensitiveForwarder(c, p, s).also { holder.set(it) } }
        assertTrue(fx.forwarder.listening.await(5, TimeUnit.SECONDS), "the forwarder must be listening")

        assertTrue(fx.manager.stopTunnel(fx.config.id) is SshResult.Success)

        assertTrue(fx.forwarder.ended.await(5, TimeUnit.SECONDS), "listen() must end after stopTunnel")
        assertTrue(
            interruptedBeforeDisconnect.get(),
            "the job must be cancelled (loop interrupted) before client.disconnect() runs",
        )
        // Let the catch block of the listen coroutine run : it must not report a
        // deliberate stop as a lost tunnel.
        Thread.sleep(300)
        assertEquals(TunnelStatus.STOPPED, fx.manager.getTunnelState(fx.config.id)?.status)
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
    }

    @Test
    fun `releaseForReconnect cancels the job before the disconnect and an IOException from the interrupt triggers no reconnect`() = runTest(UnconfinedTestDispatcher()) {
        val holder = AtomicReference<InterruptSensitiveForwarder>()
        val interruptedBeforeDisconnect = AtomicBoolean(false)
        val fx = startTunnelWith(
            "tun-loop-ioe-release",
            onDisconnect = { interruptedBeforeDisconnect.set(holder.get().interrupted.await(5, TimeUnit.SECONDS)) },
        ) { c, p, s -> InterruptSensitiveForwarder(c, p, s).also { holder.set(it) } }
        assertTrue(fx.forwarder.listening.await(5, TimeUnit.SECONDS), "the forwarder must be listening")

        fx.manager.releaseForReconnect(fx.config.id)

        assertTrue(fx.forwarder.ended.await(5, TimeUnit.SECONDS), "listen() must end after releaseForReconnect")
        assertTrue(
            interruptedBeforeDisconnect.get(),
            "the job must be cancelled (loop interrupted) before client.disconnect() runs",
        )
        Thread.sleep(300)
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
        assertFalse(fx.manager.wasManuallyStopped(fx.config.id))
    }

    @Test
    fun `startTunnel fails with ERROR status when factory returns error`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-err", label = "fail", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port, remoteHost = "127.0.0.1", remotePort = 80,
        )

        val manager = DesktopTunnelManager(scope) { _, _ ->
            SshResult.Error(SshErrorCode.AUTH_FAILED, "boom")
        }

        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("tun-err")?.status)
        assertEquals("boom", manager.getTunnelState("tun-err")?.errorMessage)
    }

    @Test
    fun `startTunnel detects PORT_IN_USE before opening SSH client`() = runTest(UnconfinedTestDispatcher()) {
        val squatter = ServerSocket(0)
        openedServerSockets += squatter
        val config = TunnelConfig(
            id = "tun-pbusy", label = "port-busy", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = squatter.localPort, remoteHost = "127.0.0.1", remotePort = 80,
        )

        var factoryCalled = false
        val manager = DesktopTunnelManager(scope) { _, _ ->
            factoryCalled = true
            SshResult.Success(mockk(relaxed = true))
        }

        val result = manager.startTunnel(config) { _ -> testHost }

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.PORT_IN_USE, result.code)
        assertFalse(factoryCalled, "factory must NOT be called if the port is already bound")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("tun-pbusy")?.status)
    }

    // ── REMOTE_FORWARD ───────────────────────────────────────────────────────

    @Test
    fun `startTunnel REMOTE_FORWARD binds remote forwarder`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-remote", label = "remote", hostId = testHost.id,
            type = TunnelType.REMOTE_FORWARD,
            localPort = 22, remoteHost = "0.0.0.0", remotePort = 8080,
        )

        val mockClient = mockk<SSHClient>(relaxed = true)
        // `remotePortForwarder.bind` returns a `RemotePortForwarder.Forward` in SSHJ:
        // the relaxed mock auto-returns a default mock of that type, so we only
        // need to wire the getter. `justRun` would force Unit and trigger a
        // ClassCastException at the call site.
        val mockRemoteForwarder = mockk<net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder>(relaxed = true)
        every { mockClient.remotePortForwarder } returns mockRemoteForwarder

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Success, "remote forward should succeed, got $result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("tun-remote")?.status)

        val stopResult = manager.stopTunnel("tun-remote")
        assertTrue(stopResult is SshResult.Success)
    }

    // ── SOCKS5 ───────────────────────────────────────────────────────────────

    @Test
    fun `startTunnel DYNAMIC_SOCKS5 starts server on local port`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-socks5", label = "socks5", hostId = testHost.id,
            type = TunnelType.DYNAMIC_SOCKS5,
            localPort = port, remoteHost = "0.0.0.0", remotePort = 0,
        )
        val mockClient = mockk<SSHClient>(relaxed = true)

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Success, "socks5 start should succeed, got $result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("tun-socks5")?.status)

        // Port is now bound: a fresh bind should fail (proves the SOCKS5 server runs).
        var boundPortFree = false
        try { ServerSocket(port).close(); boundPortFree = true } catch (_: Exception) {}
        assertFalse(boundPortFree, "SOCKS5 server should have bound the port")

        val stopResult = manager.stopTunnel("tun-socks5")
        assertTrue(stopResult is SshResult.Success)
        // After stop the port is free again.
        try { ServerSocket(port).close(); boundPortFree = true } catch (_: Exception) {}
        assertTrue(boundPortFree, "SOCKS5 server should have released the port after stop")
    }

    // ── Reservation of in-flight starts ──────────────────────────────────────
    // A Start issued while a previous start of the same tunnel has not returned
    // yet is a no-op (Success, nothing touched). The reservation is released on
    // every exit of startTunnel so the next Start really starts again.

    private fun reservationConfig(id: String, port: Int = freePort()) = TunnelConfig(
        id = id, label = "res-$id", hostId = testHost.id,
        type = TunnelType.LOCAL_FORWARD,
        localPort = port, remoteHost = "127.0.0.1", remotePort = 80,
    )

    /** A client whose `newLocalPortForwarder` hands back a forwarder that returns at once. */
    private fun quietClient(): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns mockk<LocalPortForwarder>(relaxed = true)
        return client
    }

    @Test
    fun `a second startTunnel while the first is STARTING is ignored and opens no second client`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-dbl")
        val gate = CompletableDeferred<Unit>()
        val opens = AtomicInteger(0)
        val client = quietClient()
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            gate.await()
            SshResult.Success(client)
        }

        val first = async { manager.startTunnel(config) { _ -> testHost } }
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)
        assertTrue(manager.isStarting(config.id), "the id is reserved while the start is in flight")

        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start during STARTING is a no-op success, got $second")
        assertEquals(1, opens.get(), "the second Start must not open a second SSHClient")
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)

        gate.complete(Unit)
        assertTrue(first.await() is SshResult.Success)
        assertFalse(manager.isStarting(config.id), "the reservation is released once the start returned")
        assertEquals(1, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)
        assertEquals(setOf(config.id), manager.activeIds())
        verify(exactly = 1) { client.newLocalPortForwarder(any(), any()) }

        // Once up, a further Start stays a no-op, and its attempt leaves no reservation behind.
        assertTrue(manager.startTunnel(config) { _ -> testHost } is SshResult.Success)
        assertEquals(1, opens.get())
        assertFalse(manager.isStarting(config.id), "a Start refused because the tunnel is active must release the id")

        manager.stopTunnel(config.id)
    }

    @Test
    fun `the reservation is released after an open failure`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-err")
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            if (opens.incrementAndGet() == 1) SshResult.Error(SshErrorCode.AUTH_FAILED, "boom")
            else SshResult.Success(quietClient())
        }

        val first = manager.startTunnel(config) { _ -> testHost }
        assertTrue(first is SshResult.Error)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)

        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start after a failed one must start again, got $second")
        assertEquals(2, opens.get(), "the reservation must not outlive the failed start")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `the reservation is released after a forwarder failure`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-res-fwd", label = "res-fwd", hostId = testHost.id,
            type = TunnelType.REMOTE_FORWARD,
            localPort = 22, remoteHost = "0.0.0.0", remotePort = 8080,
        )
        val binds = AtomicInteger(0)
        val remoteForwarder = mockk<RemotePortForwarder>(relaxed = true)
        every { remoteForwarder.bind(any(), any()) } answers {
            if (binds.incrementAndGet() == 1) throw IOException("bind refused")
            firstArg<RemotePortForwarder.Forward>()
        }
        val client = mockk<SSHClient>(relaxed = true)
        every { client.remotePortForwarder } returns remoteForwarder
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(client)
        }

        val first = manager.startTunnel(config) { _ -> testHost }
        assertTrue(first is SshResult.Error)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)

        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start after a forwarder failure must start again, got $second")
        assertEquals(2, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `a LOCAL_FORWARD that fails after the bind closes its ServerSocket and frees the port`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-leak")
        val bound = AtomicReference<ServerSocket>()
        val forwarderCalls = AtomicInteger(0)
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } answers {
            if (forwarderCalls.incrementAndGet() == 1) {
                bound.set(secondArg())
                throw IOException("forwarder refused")
            }
            mockk<LocalPortForwarder>(relaxed = true)
        }
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(client) }

        val first = manager.startTunnel(config) { _ -> testHost }

        assertTrue(first is SshResult.Error)
        val socket = assertNotNull(bound.get(), "the manager must have bound the socket before the forwarder failed")
        assertTrue(socket.isClosed, "the bound socket must be closed when the start fails")
        assertFalse(manager.activeIds().contains(config.id))

        // The port is really free : the retry passes the PORT_IN_USE pre-check and binds again.
        val second = manager.startTunnel(config) { _ -> testHost }
        assertTrue(second is SshResult.Success, "the retry must not hit a leaked port, got $second")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `the reservation is released after host-not-found`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-nohost")
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = manager.startTunnel(config) { _ -> null }
        assertTrue(first is SshResult.Error)
        assertEquals(0, opens.get(), "no client may be opened for an unknown host")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)

        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start after host-not-found must start again, got $second")
        assertEquals(1, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `the reservation is released after port-in-use`() = runTest(UnconfinedTestDispatcher()) {
        val squatter = ServerSocket(0)
        openedServerSockets += squatter
        val config = reservationConfig("tun-res-pbusy", squatter.localPort)
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = manager.startTunnel(config) { _ -> testHost }
        assertTrue(first is SshResult.Error)
        assertEquals(SshErrorCode.PORT_IN_USE, first.code)
        assertEquals(0, opens.get())

        squatter.close()
        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start once the port is free must start again, got $second")
        assertEquals(1, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `the reservation is released when the start is cancelled`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-cancel")
        val gate = CompletableDeferred<Unit>()
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            if (opens.incrementAndGet() == 1) {
                gate.await()
                SshResult.Success(quietClient())
            } else {
                SshResult.Success(quietClient())
            }
        }

        val first = async { manager.startTunnel(config) { _ -> testHost } }
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)
        first.cancel()
        first.join()
        assertTrue(first.isCancelled)

        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start after a cancelled one must start again, got $second")
        assertEquals(2, opens.get(), "the reservation must not outlive the cancelled start")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `stop then start during the handshake neither revives the first start nor blocks a later one`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-stop")
        val gate = CompletableDeferred<Unit>()
        val opens = AtomicInteger(0)
        val handshakeClient = mockk<SSHClient>(relaxed = true)
        val liveClient = quietClient()
        val manager = DesktopTunnelManager(scope) { _, onCreated ->
            if (opens.incrementAndGet() == 1) {
                onCreated(handshakeClient)
                gate.await()
                SshResult.Success(handshakeClient)
            } else {
                SshResult.Success(liveClient)
            }
        }

        val first = async { manager.startTunnel(config) { _ -> testHost } }
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)

        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        verify(atLeast = 1) { handshakeClient.disconnect() }
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)

        // The first start has not returned yet : this Start is ignored, and it must
        // neither open a second client nor clear the stop flag the first start reads.
        val ignored = manager.startTunnel(config) { _ -> testHost }
        assertTrue(ignored is SshResult.Success)
        assertEquals(1, opens.get())
        assertTrue(manager.wasManuallyStopped(config.id), "an ignored Start must not clear the manual stop")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)

        gate.complete(Unit)
        assertTrue(first.await() is SshResult.Success, "an explicit cancel is a success, not an error")
        assertFalse(manager.activeIds().contains(config.id), "the stopped start must not bring the tunnel up")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)

        // The first start has returned : the reservation is gone.
        val later = manager.startTunnel(config) { _ -> testHost }
        assertTrue(later is SshResult.Success, "a Start after the aborted one must start again, got $later")
        assertEquals(2, opens.get())
        assertFalse(manager.wasManuallyStopped(config.id))
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `fifty concurrent startTunnel calls on real threads open exactly one client`() = runBlocking {
        // Several rounds : a race window of a few nanoseconds is only caught by repetition.
        repeat(20) { round ->
            val config = reservationConfig("tun-par-$round")
            val gate = CompletableDeferred<Unit>()
            val opens = AtomicInteger(0)
            val manager = DesktopTunnelManager(scope) { _, _ ->
                opens.incrementAndGet()
                gate.await()
                SshResult.Success(quietClient())
            }

            // All the calls are released at once, on the Default pool.
            val go = CompletableDeferred<Unit>()
            val calls = List(50) {
                async(Dispatchers.Default) {
                    go.await()
                    manager.startTunnel(config) { _ -> testHost }
                }
            }
            go.complete(Unit)

            // The winner reaches its handshake (the factory parks it on the gate), then
            // 49 calls must come back on their own while it is parked there.
            assertTrue(awaitUntil { opens.get() >= 1 }, "round $round: one start must reach its handshake")
            assertTrue(
                awaitUntil { calls.count { it.isCompleted } >= 49 },
                "round $round: the calls that lost the reservation must return while the first one is parked " +
                    "(returned=${calls.count { it.isCompleted }}, opens=${opens.get()})",
            )
            assertEquals(1, opens.get(), "round $round: exactly one SSHClient may be opened")
            assertEquals(49, calls.count { it.isCompleted }, "round $round: the parked start must not have returned")
            assertTrue(manager.isStarting(config.id))

            gate.complete(Unit)
            val results = withTimeout(5_000) { calls.awaitAll() }
            assertTrue(results.all { it is SshResult.Success }, "round $round: every call reports success")
            assertEquals(1, opens.get())
            assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)
            assertFalse(manager.isStarting(config.id))

            manager.stopTunnel(config.id)
        }
    }

    @Test
    fun `a Stop that lands during the host lookup bails the start out before any handshake`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-stop-lookup")
        val lookupGate = CompletableDeferred<Unit>()
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = async {
            manager.startTunnel(config) { _ ->
                lookupGate.await()
                testHost
            }
        }
        assertTrue(manager.isStarting(config.id))

        // No client exists yet : stopTunnel has nothing to disconnect, it only sets the flag.
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)

        lookupGate.complete(Unit)
        assertTrue(first.await() is SshResult.Success, "an explicit cancel is a success, not an error")
        assertEquals(0, opens.get(), "the handshake must not even begin after a Stop")
        assertFalse(manager.isStarting(config.id))
        assertFalse(manager.activeIds().contains(config.id))
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)

        val later = manager.startTunnel(config) { _ -> testHost }
        assertTrue(later is SshResult.Success)
        assertEquals(1, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `a Stop that lands before the client is handed over aborts the start even if the factory lets the abort escape`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-stop-created")
        val gate = CompletableDeferred<Unit>()
        val handedOver = mockk<SSHClient>(relaxed = true)
        val continuedAfterHandOver = AtomicBoolean(false)
        val manager = DesktopTunnelManager(scope) { _, onCreated ->
            // The client is not built yet when the Stop arrives. This factory does not
            // catch what onCreated throws : the manager must, and close the client.
            gate.await()
            onCreated(handedOver)
            continuedAfterHandOver.set(true)
            SshResult.Success(handedOver)
        }

        val first = async { manager.startTunnel(config) { _ -> testHost } }
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        verify(exactly = 0) { handedOver.disconnect() }

        gate.complete(Unit)
        assertTrue(first.await() is SshResult.Success, "an explicit cancel is a success, not an error")
        assertFalse(continuedAfterHandOver.get(), "the hand-over must have aborted the open")
        verify(exactly = 1) { handedOver.disconnect() }
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertFalse(manager.isStarting(config.id))
        assertFalse(manager.activeIds().contains(config.id))
    }

    @Test
    fun `a Stop that lands before the client is handed over means connect never runs`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-no-connect")
        val gate = CompletableDeferred<Unit>()
        val connects = AtomicInteger(0)
        val client = quietClient()
        // Same shape as VaultSshClientFactory : hand the client over, then connect ;
        // an IOException from the hand-over is a failed open, never a connect.
        val manager = DesktopTunnelManager(scope) { _, onCreated ->
            gate.await()
            try {
                onCreated(client)
                connects.incrementAndGet()
                SshResult.Success(client)
            } catch (e: IOException) {
                runCatching { client.disconnect() }
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "io")
            }
        }

        val first = async { manager.startTunnel(config) { _ -> testHost } }
        // The client is not built yet : there is nothing for stopTunnel to disconnect.
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        gate.complete(Unit)

        assertTrue(first.await() is SshResult.Success, "an explicit cancel is a success, not an error")
        assertEquals(0, connects.get(), "connect() must not run after a Stop that came first")
        verify(exactly = 1) { client.disconnect() }
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertFalse(manager.isStarting(config.id))
        assertFalse(manager.activeIds().contains(config.id))

        // startingClients was cleaned : a second Stop finds no stale client to disconnect again.
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        verify(exactly = 1) { client.disconnect() }

        // Control : without a Stop in the way the same factory does connect.
        val later = manager.startTunnel(config) { _ -> testHost }
        assertTrue(later is SshResult.Success, "got $later")
        assertEquals(1, connects.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `VaultSshClientFactory gives up before connect when the hand-over aborts`() = runBlocking<Unit> {
        ServerSocket(0).use { listener ->
            // A pending connection would sit in the backlog and be returned by accept().
            listener.soTimeout = 750
            val host = Host(
                id = "host-abort", label = "abort", hostname = "127.0.0.1", port = listener.localPort,
                username = "u", authType = AuthType.PASSWORD, credentialId = "c",
            )
            val factory = VaultSshClientFactory(
                vaultManager = mockk(relaxed = true),
                hostKeyVerifier = mockk(relaxed = true),
            )
            val handedOver = AtomicReference<SSHClient>()

            val result = factory.open(host) { client ->
                handedOver.set(client)
                throw IOException("stopped")
            }

            assertTrue(result is SshResult.Error, "an aborted hand-over must come back as an Error, got $result")
            assertNotNull(handedOver.get(), "the client must have been handed over before the abort")
            assertFailsWith<SocketTimeoutException>("no connection may have been attempted") { listener.accept() }
        }
    }

    @Test
    fun `a Stop that lands during the lookup of a missing host stays STOPPED instead of ending as ERROR`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-res-stop-nohost")
        val lookupGate = CompletableDeferred<Unit>()
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = async {
            manager.startTunnel(config) { _ ->
                lookupGate.await()
                null
            }
        }
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        lookupGate.complete(Unit)

        assertTrue(first.await() is SshResult.Success, "an explicit cancel is a success, not a host-not-found error")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
        assertEquals(0, opens.get())
        assertFalse(manager.isStarting(config.id))
    }

    @Test
    fun `a Stop that lands during the lookup of a busy port tunnel stays STOPPED instead of ending as ERROR`() = runTest(UnconfinedTestDispatcher()) {
        val squatter = ServerSocket(0)
        openedServerSockets += squatter
        val config = reservationConfig("tun-res-stop-busy", squatter.localPort)
        val lookupGate = CompletableDeferred<Unit>()
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = async {
            manager.startTunnel(config) { _ ->
                lookupGate.await()
                testHost
            }
        }
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        lookupGate.complete(Unit)

        assertTrue(first.await() is SshResult.Success, "an explicit cancel is a success, not a port-in-use error")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
        assertEquals(0, opens.get())
    }

    @Test
    fun `fifty concurrent startTunnel calls without a parked handshake still open one client per round`() = runBlocking {
        // No gate : the winner runs to the end (the entry is registered, the id released)
        // while the others are still arriving. A Start that reserves after the winner has
        // finished must find the registered entry and back off. The window is narrow, hence
        // many rounds.
        repeat(300) { round ->
            val config = TunnelConfig(
                id = "tun-free-$round", label = "free-$round", hostId = testHost.id,
                type = TunnelType.REMOTE_FORWARD,
                localPort = 22, remoteHost = "0.0.0.0", remotePort = 8080,
            )
            val opens = AtomicInteger(0)
            val client = mockk<SSHClient>(relaxed = true)
            every { client.remotePortForwarder } returns mockk<RemotePortForwarder>(relaxed = true)
            val manager = DesktopTunnelManager(scope) { _, _ ->
                opens.incrementAndGet()
                SshResult.Success(client)
            }

            val go = CompletableDeferred<Unit>()
            val calls = List(50) {
                async(Dispatchers.Default) {
                    go.await()
                    manager.startTunnel(config) { _ -> testHost }
                }
            }
            go.complete(Unit)

            val results = withTimeout(10_000) { calls.awaitAll() }
            assertTrue(results.all { it is SshResult.Success }, "round $round: every call reports success")
            assertEquals(1, opens.get(), "round $round: exactly one SSHClient may be opened")
            assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)
            assertFalse(manager.isStarting(config.id), "round $round: no reservation may be left behind")

            manager.stopTunnel(config.id)
        }
    }

    @Test
    fun `a failure after the listen job started cancels the job and closes the socket`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-post-launch")
        val holder = AtomicReference<InterruptSensitiveForwarder>()
        val bound = AtomicReference<ServerSocket>()
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } answers {
            bound.set(secondArg())
            InterruptSensitiveForwarder(mockk(relaxed = true), firstArg(), secondArg()).also { holder.set(it) }
        }
        // The disconnect listener is installed right after the listen job is launched,
        // and its try/catch only swallows Exceptions : an Error escapes it. Wait until
        // the loop is parked so the cancellation has an interrupt to deliver.
        every { client.transport } answers {
            holder.get().listening.await(5, TimeUnit.SECONDS)
            throw AssertionError("transport exploded")
        }
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(client) }

        assertFailsWith<AssertionError> { manager.startTunnel(config) { _ -> testHost } }

        assertTrue(
            holder.get().interrupted.await(5, TimeUnit.SECONDS),
            "the listen job must be cancelled (loop interrupted) when the start fails after the launch",
        )
        assertTrue(assertNotNull(bound.get()).isClosed, "the bound socket must be closed too")
        assertFalse(manager.isStarting(config.id), "the reservation is released on any Throwable")
        assertFalse(manager.activeIds().contains(config.id))
    }

    @Test
    fun `updateStatusIf only overwrites a status the condition accepts and reports what it saw`() {
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        val config = reservationConfig("tun-cas")
        val notLive = { s: TunnelStatus -> s != TunnelStatus.STARTING && s != TunnelStatus.ACTIVE }

        assertNull(manager.updateStatusIf(config.id, TunnelStatus.RECONNECTING) { true }, "unknown tunnel")
        assertNull(manager.getTunnelState(config.id), "an unknown tunnel must not be created")

        manager.markStarting(config)
        assertEquals(TunnelStatus.STARTING, manager.updateStatusIf(config.id, TunnelStatus.RECONNECTING, condition = notLive))
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status, "STARTING must be left alone")

        manager.markError(config, "boom")
        assertEquals(TunnelStatus.ERROR, manager.updateStatusIf(config.id, TunnelStatus.RECONNECTING, condition = notLive))
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(config.id)?.status, "ERROR must become RECONNECTING")
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
    }

    // ── Defensive replacement of a live entry ────────────────────────────────
    // The reservation makes a replacement unreachable through startTunnel, so the
    // helper is exercised directly (`registerActive` and `ActiveTunnel` are
    // `internal` for that reason).

    @Test
    fun `registerActive closes the live entry it replaces, cancelling its job before the disconnect`() = runTest(UnconfinedTestDispatcher()) {
        val holder = AtomicReference<InterruptSensitiveForwarder>()
        val interruptedBeforeDisconnect = AtomicBoolean(false)
        val fx = startTunnelWith(
            "tun-replace",
            onDisconnect = { interruptedBeforeDisconnect.set(holder.get().interrupted.await(5, TimeUnit.SECONDS)) },
        ) { c, p, s -> InterruptSensitiveForwarder(c, p, s).also { holder.set(it) } }
        assertTrue(fx.forwarder.listening.await(5, TimeUnit.SECONDS), "the forwarder must be listening")

        fx.manager.registerActive(ActiveTunnel(config = fx.config, client = mockk(relaxed = true), job = Job()))

        assertTrue(fx.forwarder.ended.await(5, TimeUnit.SECONDS), "the replaced listen loop must end")
        assertTrue(
            interruptedBeforeDisconnect.get(),
            "the replaced job must be cancelled (loop interrupted) before its client.disconnect() runs",
        )
        verify(exactly = 1) { fx.client.disconnect() }
        // The listen loop of the replaced tunnel died on an IOException : it must
        // not be reported as a lost tunnel and take the new entry down with it.
        Thread.sleep(300)
        assertTrue(fx.manager.activeIds().contains(fx.config.id))
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
    }

    @Test
    fun `a late disconnect callback of a replaced client does not drop the new entry`() = runTest(UnconfinedTestDispatcher()) {
        val fx = startTunnelWith("tun-stale") { c, p, s -> TrackingForwarder(c, p, s) }
        assertTrue(awaitUntil { fx.forwarder.isRunning }, "the forwarder must be listening")
        fx.manager.registerActive(ActiveTunnel(config = fx.config, client = mockk(relaxed = true), job = Job()))
        assertTrue(fx.forwarder.returned.await(5, TimeUnit.SECONDS), "the replaced listen loop must end")

        // The old client's SSHJ transport reports its disconnect after the replacement.
        fx.disconnectListener.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "late")

        assertTrue(fx.manager.activeIds().contains(fx.config.id), "the new entry must survive a stale callback")
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
    }

    // ── A Stop that lands after the handshake ────────────────────────────────
    // Once `open()` returned, the start binds the port (or sends the remote
    // forward request, a network round trip), registers the tunnel and writes
    // ACTIVE. A Stop can land at any point of that stretch and must win : nothing
    // may stay alive and the state must end STOPPED, never ACTIVE.

    /**
     * One tunnel of a given type wired to mocks so that everything it opens can be
     * observed from outside : the local socket (LOCAL_FORWARD), the remote forward
     * bound then cancelled (REMOTE_FORWARD), the port (DYNAMIC_SOCKS5), the
     * disconnects of the client. [onTransport] runs when the manager reads
     * `client.transport` : after the forwarder is set up, before the tunnel is
     * registered. [onRemoteBind] runs inside the remote `bind` call.
     */
    private inner class RaceRig(
        type: TunnelType,
        id: String,
        val rigScope: CoroutineScope = scope,
    ) {
        val config = TunnelConfig(
            id = id, label = "race-$id", hostId = testHost.id, type = type,
            localPort = if (type == TunnelType.REMOTE_FORWARD) 22 else freePort(),
            remoteHost = if (type == TunnelType.REMOTE_FORWARD) "0.0.0.0" else "127.0.0.1",
            remotePort = if (type == TunnelType.REMOTE_FORWARD) 8080 else 80,
        )
        val opens = AtomicInteger(0)
        val disconnects = AtomicInteger(0)

        /** Released by the first `client.disconnect()` : what ends a blocked remote request. */
        val disconnected = CountDownLatch(1)
        val remoteBinds = AtomicInteger(0)
        val remoteCancels = AtomicInteger(0)
        val localSocket = AtomicReference<ServerSocket>()
        val localForwarder = AtomicReference<TrackingForwarder>()
        val disconnectListener = slot<DisconnectListener>()

        @Volatile var onTransport: () -> Unit = {}

        @Volatile var onRemoteBind: () -> Unit = {}

        val client = mockk<SSHClient>(relaxed = true)
        val manager: DesktopTunnelManager

        init {
            val transport = mockk<Transport>(relaxed = true)
            every { transport.setDisconnectListener(capture(disconnectListener)) } just Runs
            every { client.transport } answers {
                onTransport()
                transport
            }
            every { client.disconnect() } answers {
                disconnects.incrementAndGet()
                disconnected.countDown()
                Unit
            }
            val remoteForwarder = mockk<RemotePortForwarder>(relaxed = true)
            every { client.remotePortForwarder } returns remoteForwarder
            every { remoteForwarder.bind(any(), any()) } answers {
                onRemoteBind()
                remoteBinds.incrementAndGet()
                firstArg<RemotePortForwarder.Forward>()
            }
            every { remoteForwarder.cancel(any<RemotePortForwarder.Forward>()) } answers {
                remoteCancels.incrementAndGet()
                Unit
            }
            every { client.newLocalPortForwarder(any(), any()) } answers {
                localSocket.set(secondArg())
                TrackingForwarder(mockk(relaxed = true), firstArg(), secondArg()).also { localForwarder.set(it) }
            }
            // Same shape as VaultSshClientFactory : hand the client over, an IOException
            // from the hand-over is a failed open.
            manager = DesktopTunnelManager(rigScope) { _, onCreated ->
                opens.incrementAndGet()
                try {
                    onCreated(client)
                    SshResult.Success(client)
                } catch (e: IOException) {
                    runCatching { client.disconnect() }
                    SshResult.Error(SshErrorCode.UNKNOWN, "stopped")
                }
            }
            cleanups += {
                // A failed assertion must leave nothing bound : the SOCKS5 server has its own
                // accept thread, only stopTunnel closes it (idempotent when already stopped).
                runCatching { runBlocking { manager.stopTunnel(config.id) } }
                localForwarder.get()?.listenThread?.interrupt()
                localSocket.get()?.close()
                rigScope.cancel()
            }
        }

        /** A Stop issued from inside the start, on the thread that runs it. */
        fun stopFromInside() {
            val result = runBlocking { manager.stopTunnel(config.id) }
            check(result is SshResult.Success) { "the Stop itself must succeed, got $result" }
        }

        /** Is the resource of this tunnel's type still held ? */
        fun resourceHeld(): Boolean = when (config.type) {
            TunnelType.LOCAL_FORWARD -> localSocket.get()?.let { !it.isClosed } ?: false
            TunnelType.REMOTE_FORWARD -> remoteBinds.get() > remoteCancels.get()
            TunnelType.DYNAMIC_SOCKS5 -> try {
                ServerSocket(config.localPort).close()
                false
            } catch (e: IOException) {
                true
            }
        }

        /** Everything the tunnel opened is closed once the dust settled, and the state is STOPPED. */
        fun assertStoppedAndReleased() {
            val id = config.id
            val type = config.type
            assertFalse(manager.activeIds().contains(id), "$type: no live entry may remain")
            assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(id)?.status, "$type: the state must be STOPPED")
            assertNull(manager.getTunnelState(id)?.errorMessage, "$type: a deliberate stop carries no error")
            assertFalse(manager.isStarting(id), "$type: the reservation must be released")
            assertTrue(manager.wasManuallyStopped(id), "$type: the stop flag stays set")
            assertFalse(resourceHeld(), "$type: the local port / remote forward must be released")
            assertTrue(disconnects.get() >= 1, "$type: the SSH client must be disconnected")
            assertTrue(noCoroutineLeft(rigScope), "$type: the job and the watchdog must be cancelled")
        }
    }

    /** True once no child of [target] is active any more (the 3 s watchdog delay is far above the 1.5 s bound). */
    private fun noCoroutineLeft(target: CoroutineScope): Boolean =
        awaitUntil(1_500) { target.coroutineContext[Job]!!.children.none { it.isActive } }

    private fun spinNanos(nanos: Long) {
        val end = System.nanoTime() + nanos
        while (System.nanoTime() < end) Thread.onSpinWait()
    }

    @Test
    fun `a Stop that lands after the handshake and before the registration leaves nothing alive`() = runTest(UnconfinedTestDispatcher()) {
        for (type in TunnelType.entries) {
            val rig = RaceRig(type, "tun-race-pre-$type")
            val fired = AtomicBoolean(false)
            // The forwarder is set up (socket bound / remote forward bound / SOCKS5 started) and
            // the entry is not registered yet : stopTunnel finds neither client nor tunnel.
            rig.onTransport = { if (fired.compareAndSet(false, true)) rig.stopFromInside() }

            val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

            assertTrue(fired.get(), "$type: the Stop must have fired")
            assertTrue(result is SshResult.Success, "$type: an explicit stop is a quiet success, got $result")
            rig.assertStoppedAndReleased()
            assertEquals(
                2, rig.disconnects.get(),
                "$type: one disconnect through startingClients (it aborts a setup in flight), " +
                    "one by the teardown of whoever took the entry, none more",
            )
            if (type == TunnelType.REMOTE_FORWARD) assertEquals(1, rig.remoteCancels.get(), "the remote forward is cancelled once")
        }
    }

    @Test
    fun `a Stop that lands right after the registration tears the tunnel down once`() = runTest(UnconfinedTestDispatcher()) {
        for (type in TunnelType.entries) {
            val rig = RaceRig(type, "tun-race-reg-$type")
            val fired = AtomicBoolean(false)
            // The Stop finds the entry the start just registered and closes it itself : the start,
            // which then sees the flag, must not close it a second time.
            rig.manager.afterRegisterHook = { if (fired.compareAndSet(false, true)) rig.stopFromInside() }

            val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

            assertTrue(fired.get(), "$type: the Stop must have fired")
            assertTrue(result is SshResult.Success, "$type: an explicit stop is a quiet success, got $result")
            rig.assertStoppedAndReleased()
            assertEquals(
                2, rig.disconnects.get(),
                "$type: one disconnect through startingClients (it aborts a setup in flight), " +
                    "one by the teardown of whoever took the entry, none more",
            )
            if (type == TunnelType.REMOTE_FORWARD) assertEquals(1, rig.remoteCancels.get(), "the remote forward is cancelled once")
        }
    }

    @Test
    fun `a Stop that lands between the registration and the ACTIVE write ends STOPPED, never ACTIVE`() = runTest(UnconfinedTestDispatcher()) {
        for (type in TunnelType.entries) {
            val rig = RaceRig(type, "tun-race-confirm-$type")
            val fired = AtomicBoolean(false)
            // The start already read the flag (clear) : the Stop closes the registered tunnel and
            // writes STOPPED, the start must then not write ACTIVE over it.
            rig.manager.beforeConfirmHook = { if (fired.compareAndSet(false, true)) rig.stopFromInside() }

            val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

            assertTrue(fired.get(), "$type: the Stop must have fired")
            assertTrue(result is SshResult.Success, "$type: an explicit stop is a quiet success, got $result")
            rig.assertStoppedAndReleased()
            assertEquals(
                2, rig.disconnects.get(),
                "$type: one disconnect through startingClients (it aborts a setup in flight), " +
                    "one by the teardown of whoever took the entry, none more",
            )
            if (type == TunnelType.REMOTE_FORWARD) assertEquals(1, rig.remoteCancels.get(), "the remote forward is cancelled once")
        }
    }

    @Test
    fun `a start that fails after a Stop landed stays STOPPED and reports no error`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-race-fail")
        // The Stop lands during the remote bind (a network round trip) and the server then
        // refuses the bind : nothing is registered, the catch of the start runs.
        rig.onRemoteBind = {
            rig.stopFromInside()
            throw IOException("bind refused")
        }

        val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

        assertTrue(result is SshResult.Success, "an explicit stop is a quiet success even if the setup failed, got $result")
        assertEquals(TunnelStatus.STOPPED, rig.manager.getTunnelState(rig.config.id)?.status)
        assertNull(rig.manager.getTunnelState(rig.config.id)?.errorMessage)
        assertFalse(rig.manager.activeIds().contains(rig.config.id))
        assertTrue(rig.disconnects.get() >= 1, "the half-built tunnel's client must be disconnected")
        assertFalse(rig.manager.isStarting(rig.config.id))
    }

    @Test
    fun `a start with no Stop in the way still ends ACTIVE for every tunnel type`() = runTest(UnconfinedTestDispatcher()) {
        for (type in TunnelType.entries) {
            val rig = RaceRig(type, "tun-race-none-$type")
            val steps = mutableListOf<String>()
            rig.manager.afterRegisterHook = { steps += "registered" }
            rig.manager.beforeConfirmHook = { steps += "confirming" }

            val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

            assertTrue(result is SshResult.Success, "$type: got $result")
            assertEquals(listOf("registered", "confirming"), steps, "$type: both seams run once, in order")
            assertEquals(TunnelStatus.ACTIVE, rig.manager.getTunnelState(rig.config.id)?.status, "$type")
            assertEquals(setOf(rig.config.id), rig.manager.activeIds(), "$type")
            assertTrue(rig.resourceHeld(), "$type: the resource must be held")
            assertEquals(0, rig.disconnects.get(), "$type: nothing may be closed")

            assertTrue(rig.manager.stopTunnel(rig.config.id) is SshResult.Success)
            rig.assertStoppedAndReleased()
            assertEquals(
                1, rig.disconnects.get(),
                "$type: a successful start keeps nothing in startingClients, so the Stop closes the client once",
            )
        }
    }

    @Test
    fun `a Stop during a blocked remote bind disconnects the client and the start returns at once`() = runBlocking<Unit> {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-race-bind-blocked")
        val bindEntered = CountDownLatch(1)
        // What SSHJ does on a dead route : the request waits for a reply (up to the SSH
        // timeout) until the transport is torn down by disconnect(), then fails.
        rig.onRemoteBind = {
            bindEntered.countDown()
            rig.disconnected.await(10, TimeUnit.SECONDS)
            throw IOException("bind aborted by the disconnect")
        }

        val start = async(Dispatchers.Default) { rig.manager.startTunnel(rig.config) { _ -> testHost } }
        assertTrue(bindEntered.await(5, TimeUnit.SECONDS), "the start must reach the remote bind")

        assertTrue(rig.manager.stopTunnel(rig.config.id) is SshResult.Success)

        // Without the disconnect the bind would hold the start for the whole 10 s.
        val result = withTimeout(5_000) { start.await() }

        assertTrue(result is SshResult.Success, "an explicit stop is a quiet success, got $result")
        assertEquals(TunnelStatus.STOPPED, rig.manager.getTunnelState(rig.config.id)?.status)
        assertNull(rig.manager.getTunnelState(rig.config.id)?.errorMessage)
        assertFalse(rig.manager.activeIds().contains(rig.config.id))
        assertFalse(rig.manager.isStarting(rig.config.id))
        assertFalse(rig.resourceHeld(), "the forward never got bound")
        assertTrue(noCoroutineLeft(rig.rigScope), "no job or watchdog may be left behind")
    }

    @Test
    fun `a failed start keeps no client in startingClients, a later Stop disconnects nothing more`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-race-failed-clean")
        rig.onRemoteBind = { throw IOException("bind refused") }

        val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

        assertTrue(result is SshResult.Error, "no Stop in the way : the failure is reported, got $result")
        assertEquals(TunnelStatus.ERROR, rig.manager.getTunnelState(rig.config.id)?.status)
        assertEquals(1, rig.disconnects.get(), "the start closes its half-built client itself")

        assertTrue(rig.manager.stopTunnel(rig.config.id) is SshResult.Success)
        assertEquals(1, rig.disconnects.get(), "nothing stale in startingClients : the Stop disconnects nothing more")
    }

    @Test
    fun `a cancelled start keeps no client in startingClients`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-cancel-clean")
        val gate = CompletableDeferred<Unit>()
        val client = quietClient()
        // The client was handed over, then the start is cancelled while the handshake is parked.
        // The cancelled start closes the client it held (the handshake may have just succeeded,
        // and nobody else would close it), once : a later Stop finds no stale reference.
        val manager = DesktopTunnelManager(scope) { _, onCreated ->
            onCreated(client)
            gate.await()
            SshResult.Success(client)
        }

        val first = async { manager.startTunnel(config) { _ -> testHost } }
        assertTrue(manager.isStarting(config.id))
        first.cancel()
        first.join()
        assertTrue(first.isCancelled)

        verify(exactly = 1) { client.disconnect() }
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `a tunnel lost between the registration and the ACTIVE write stays RECONNECTING`() = runTest(UnconfinedTestDispatcher()) {
        for (type in TunnelType.entries) {
            val rig = RaceRig(type, "tun-race-lost-$type")
            val fired = AtomicBoolean(false)
            // onTunnelLost removes the entry then writes RECONNECTING : the start must not
            // write ACTIVE over that, the service reconnects from RECONNECTING.
            rig.manager.beforeConfirmHook = {
                if (fired.compareAndSet(false, true)) {
                    rig.disconnectListener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "lost")
                }
            }

            val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

            assertTrue(fired.get(), "$type: the loss must have fired")
            assertTrue(result is SshResult.Success, "$type: got $result")
            assertEquals(TunnelStatus.RECONNECTING, rig.manager.getTunnelState(rig.config.id)?.status, "$type")
            assertFalse(rig.manager.activeIds().contains(rig.config.id), "$type: the lost entry is gone")
            assertFalse(rig.manager.wasManuallyStopped(rig.config.id), "$type: a loss is not a manual stop")
        }
    }

    @Test
    fun `a release for reconnect between the registration and the ACTIVE write leaves the status alone`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-race-release")
        // A network drop turned STARTING into RECONNECTING, then the service released the
        // entry the start had just registered : releaseForReconnect takes it out and writes
        // no status, the start must not write ACTIVE for a tunnel that is gone.
        rig.manager.beforeConfirmHook = {
            rig.manager.updateStatus(rig.config.id, TunnelStatus.RECONNECTING)
            runBlocking { rig.manager.releaseForReconnect(rig.config.id) }
        }

        val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

        assertTrue(result is SshResult.Success, "got $result")
        assertEquals(TunnelStatus.RECONNECTING, rig.manager.getTunnelState(rig.config.id)?.status)
        assertFalse(rig.manager.activeIds().contains(rig.config.id), "the released entry is gone")
        assertFalse(rig.manager.wasManuallyStopped(rig.config.id), "a release is not a manual stop")
        assertEquals(1, rig.remoteCancels.get(), "the release closed the forward")
    }

    @Test
    fun `ACTIVE follows the live entry, not a STOPPED left by an earlier Stop that was still closing`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-race-late-stopped")
        // A Stop of the PREVIOUS run closes its tunnel slowly (the reservation does not wait
        // for it) and writes STOPPED after this start reserved : the tunnel this start brought
        // up is live and must show ACTIVE.
        rig.manager.beforeConfirmHook = { rig.manager.updateStatus(rig.config.id, TunnelStatus.STOPPED) }

        val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

        assertTrue(result is SshResult.Success, "got $result")
        assertEquals(TunnelStatus.ACTIVE, rig.manager.getTunnelState(rig.config.id)?.status)
        assertTrue(rig.manager.activeIds().contains(rig.config.id))

        rig.manager.stopTunnel(rig.config.id)
    }

    @Test
    fun `a Stop racing a start on real threads never leaves a live tunnel or a lying state behind`() = runBlocking<Unit> {
        val types = TunnelType.entries
        // The window is a few hundred microseconds wide : many rounds, with a jittered Stop.
        repeat(90) { round ->
            val type = types[round % types.size]
            val roundScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
            val rig = RaceRig(type, "tun-conc-$round", roundScope)
            val id = rig.config.id
            val jitter = java.util.Random(round.toLong())
            val tag = "round $round ($type)"

            val start = async(Dispatchers.Default) { rig.manager.startTunnel(rig.config) { _ -> testHost } }
            // The Stop goes out once the start reserved the id (a Stop before that is a Stop
            // BEFORE the start, and legitimately loses to it).
            val deadline = System.nanoTime() + 5_000_000_000L
            while (rig.manager.getTunnelState(id)?.status != TunnelStatus.STARTING &&
                !start.isCompleted && System.nanoTime() < deadline
            ) {
                Thread.onSpinWait()
            }
            val delayNs = jitter.nextInt(600_000).toLong()
            val stop = async(Dispatchers.Default) {
                spinNanos(delayNs)
                rig.manager.stopTunnel(id)
            }

            assertTrue(withTimeout(10_000) { start.await() } is SshResult.Success, "$tag: the start reports success")
            assertTrue(withTimeout(10_000) { stop.await() } is SshResult.Success, "$tag: the stop reports success")

            val live = id in rig.manager.activeIds()
            val status = rig.manager.getTunnelState(id)?.status
            assertFalse(rig.manager.isStarting(id), "$tag: no reservation may be left behind")
            if (rig.manager.wasManuallyStopped(id)) {
                // The Stop came after the reservation : the tunnel must be down, whatever the
                // phase the start was in.
                assertFalse(live, "$tag: a stopped tunnel must have no live entry")
                assertEquals(TunnelStatus.STOPPED, status, "$tag: a stopped tunnel must read STOPPED")
            }
            assertTrue(status != TunnelStatus.ACTIVE || live, "$tag: ACTIVE needs a live entry")
            assertEquals(live, rig.resourceHeld(), "$tag: resources must be held exactly while the tunnel is live")
            if (live) {
                assertEquals(0, rig.disconnects.get(), "$tag: a live tunnel's client must not be disconnected")
            } else if (rig.opens.get() > 0) {
                assertTrue(rig.disconnects.get() >= 1, "$tag: the client of a stopped tunnel must be disconnected")
            }

            // Whatever the outcome, a final Stop leaves nothing behind (no job, no watchdog).
            assertTrue(rig.manager.stopTunnel(id) is SshResult.Success)
            assertTrue(noCoroutineLeft(roundScope), "$tag: no coroutine may outlive the tunnel")
            roundScope.cancel()
        }
    }

    // ── stop idempotency ─────────────────────────────────────────────────────

    @Test
    fun `stopTunnel on unknown id returns Success and clears any lingering state`() = runTest(UnconfinedTestDispatcher()) {
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        val result = manager.stopTunnel("nope")
        assertTrue(result is SshResult.Success, "stop on unknown id must be idempotent Success, got $result")
        assertNull(manager.getTunnelState("nope"))
    }

    @Test
    fun `shared interface startLocalForward errors out, use service instead`() = runTest(UnconfinedTestDispatcher()) {
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        val result = manager.startLocalForward(
            TunnelConfig(
                id = "x", label = "x", hostId = "h", type = TunnelType.LOCAL_FORWARD,
                localPort = 0, remoteHost = "", remotePort = 0,
            ),
            sessionId = "session-x",
        )
        assertTrue(result is SshResult.Error)
    }
}
