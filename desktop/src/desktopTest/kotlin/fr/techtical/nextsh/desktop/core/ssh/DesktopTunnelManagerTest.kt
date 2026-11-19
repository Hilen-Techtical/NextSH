// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.assertNothingScrubbed
import fr.techtical.nextsh.desktop.captureLogs
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_host_not_found
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_listener_stopped
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_local_port_reserved
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_port_in_use
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_no_answer
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_remote_forward_refused
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_session_closed
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_setup_timeout
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_socks_stopped
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_start_failed
import fr.techtical.nextsh.desktop.taggedBy
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.transport.TransportException
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
 *  - DYNAMIC_SOCKS5 happy path (SOCKS5 server starts on local port and answers a greeting) ;
 *    the accept loop starts once the tunnel is registered, a failed accept sends the tunnel to
 *    RECONNECTING with the translated text (log: LogId and class only), a Stop reports no loss
 *  - reasons of a loss on the card: a disconnect (server text never shown, null message tolerated)
 *    and a keepalive without answer carry a translated text ; `updateStatusIf` with
 *    `keepErrorMessage` ; a start from RECONNECTING carries the reason into STARTING
 *  - stop on unknown id returns Success (idempotent)
 *  - LOCAL_FORWARD listen loop: a REAL `LocalPortForwarder` (not a mock) must
 *    return from `listen()` on stop / releaseForReconnect / onTunnelLost, and a
 *    genuine IOException must still send the tunnel to RECONNECTING, with the
 *    translated text and not the message of the exception
 *  - LOCAL_FORWARD client connection whose channel open fails: refused by the
 *    server (`OpenFailException` out of `listen()`) or no answer in time
 *    (`ConnectionException` caused by a `TimeoutException`). While the SSH
 *    connection is alive the tunnel stays ACTIVE and listening (also when the
 *    failure comes before the registration), `listen()` is entered again, no
 *    SSH disconnect ; on a connection that is gone it still goes to
 *    RECONNECTING ; a refusal racing a Stop (with the interrupt flag up or
 *    not), or a job cancelled with the socket still open, ends with one single
 *    `listen()` ; the first failure is one warning, the next ones debug lines,
 *    with the LogId and the class only
 *  - reservation of in-flight starts: a Start during STARTING is ignored, and
 *    the reservation is released on every exit of startTunnel
 *  - defensive replacement of a live entry (`registerActive`)
 *  - a Stop that lands after the handshake (before the registration, right after
 *    it, before the ACTIVE write) : nothing stays alive, the state ends STOPPED
 *    and the start reports a quiet success, for the three tunnel types ; plus a
 *    start / stop race on real threads
 *  - the client stays in `startingClients` through the forwarder setup (a Stop
 *    during a blocked remote bind aborts it at once) and is gone on every exit
 *  - an unexpected exception (host lookup, client factory) ends as a translated
 *    ERROR and an `Error` result, a cancellation as STOPPED, never as a STARTING
 *    that stays for good ; a state written by someone else meanwhile is kept
 *  - a forwarder setup that fails (local port taken, remote forward refused, timeout, lost
 *    transport, anything else) ends as the translated text of its category, and the log names the
 *    category and the class of the exception, never its message (the classification itself is in
 *    `TunnelSetupFailureTest`) ; a Stop that fails midway still closes the tunnel it removed,
 *    leaves the card STOPPED and answers Success, since the tunnel is down
 *  - the check of the local port before the connection (taken, held by a service, or reserved to
 *    administrators on Linux and macOS) answers for the system through the `osName`, `portProbe` and
 *    `portListens` seams ; an open that fails is logged with its code only, and not at all when a
 *    Stop caused it
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

    /** The refusal of a channel open by the server, as SSHJ raises it out of `listen()`. */
    private fun channelRefusal() =
        OpenFailException("direct-tcpip", OpenFailException.Reason.CONNECT_FAILED, "db.interne.example:5432 refused")

    /**
     * `listen()` that refuses the first [refusals] client connections the way SSHJ does when the
     * server answers a channel open with a failure : it throws [failure] out of `listen()`, each time
     * after waiting for [gate] when there is one. From then on it listens for real on the manager's
     * socket, like [TrackingForwarder]. [calls] counts the entries into `listen()`, [returned] is
     * released when a real `listen()` gives control back.
     */
    private class RefusingForwarder(
        connection: Connection,
        params: Parameters,
        private val socket: ServerSocket,
        private val refusals: Int,
        private val gate: CountDownLatch? = null,
        private val failure: () -> IOException,
    ) : LocalPortForwarder(connection, params, socket, LoggerFactory.DEFAULT) {
        val calls = AtomicInteger(0)
        val returned = CountDownLatch(1)

        @Volatile private var listenThread: Thread? = null

        @Volatile private var listeningForReal = false

        override fun listen() {
            val call = calls.incrementAndGet()
            if (call <= refusals) {
                gate?.await(5, TimeUnit.SECONDS)
                throw failure()
            }
            listenThread = Thread.currentThread()
            listeningForReal = true
            try {
                super.listen()
            } finally {
                listeningForReal = false
                returned.countDown()
            }
        }

        /**
         * Ends a listener a failed test left behind : interrupt, then close, in the order of a stop. The
         * thread is only interrupted while it is inside the real `listen()` : after a refusal it may have
         * gone back to the pool and run something else.
         */
        fun abort() {
            if (listeningForReal) listenThread?.interrupt()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * `listen()` that parks until interrupted, then dies with a refusal of the server (a channel open
     * torn down by the disconnect, that the server answered at the same moment). [reraiseInterrupt]
     * puts the interrupt flag back before throwing, as SSHJ's `Promise` does ; without it the flag is
     * gone when the refusal comes out, and only the state of the job tells that the tunnel is being
     * stopped. [calls] counts the entries into `listen()`.
     */
    private class InterruptRefusingForwarder(
        connection: Connection,
        params: Parameters,
        serverSocket: ServerSocket,
        private val reraiseInterrupt: Boolean,
        private val failure: () -> IOException,
    ) : LocalPortForwarder(connection, params, serverSocket, LoggerFactory.DEFAULT) {
        val calls = AtomicInteger(0)
        val listening = CountDownLatch(1)
        val ended = CountDownLatch(1)
        private val never = CountDownLatch(1)

        override fun listen() {
            calls.incrementAndGet()
            listening.countDown()
            try {
                never.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                if (reraiseInterrupt) Thread.currentThread().interrupt()
                throw failure()
            } finally {
                ended.countDown()
            }
        }
    }

    /**
     * Starts a LOCAL_FORWARD through the real manager path. The mocked client
     * hands back the forwarder built by [build] on the socket the manager bound.
     *
     * [connected] is what the mocked client answers to `isConnected` : a relaxed mock answers false,
     * and the listen loop reads it to tell a refusal it can survive (the SSH connection is alive)
     * from a connection that is gone.
     */
    private suspend fun <F : LocalPortForwarder> startTunnelWith(
        id: String,
        onDisconnect: () -> Unit = {},
        connected: Boolean = false,
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
        every { mockClient.isConnected } returns connected
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
        (forwarder as? RefusingForwarder)?.let { cleanups += { it.abort() } }
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
        // The state carries the translated text, never the message of the exception (SSHJ English text
        // that can name a host or a port).
        val errorMessage = fx.manager.getTunnelState(fx.config.id)?.errorMessage
        assertEquals(getString(Res.string.tunnels_error_listener_stopped), errorMessage)
        assertFalse(errorMessage.orEmpty().contains("boom"), "the exception message must not reach the state: $errorMessage")
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

    // ── LOCAL_FORWARD: a client connection whose channel cannot be opened ────
    // SSHJ ends `listen()` with an exception when the channel of ONE client connection cannot be
    // opened : the server refuses it (an OpenFailException : the target refuses, `AllowTcpForwarding
    // no`, `PermitOpen`) or does not answer in time (a ConnectionException caused by a
    // TimeoutException : sshd waits for its own connection to the target, which a firewall that drops
    // the packets makes long). That used to end the tunnel, and the service reconnected it with a
    // whole new SSH connection : a client that retries made a storm of them. While the SSH connection
    // is alive the tunnel now stays ACTIVE and goes on listening ; what really breaks it (a
    // connection that is gone) still sends it to RECONNECTING, and a failure that comes out while the
    // tunnel is being stopped is a stop.

    @Test
    fun `a client connection the server refuses leaves the local tunnel ACTIVE and listening`() = runTest(UnconfinedTestDispatcher()) {
        // Held until startTunnel has returned, so that the refusals land on a registered tunnel.
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val fx = startTunnelWith("tun-refused-survive", connected = true) { c, p, s ->
            RefusingForwarder(c, p, s, refusals = 3, gate = gate, failure = { channelRefusal() })
        }

        gate.countDown()

        assertTrue(awaitUntil { fx.forwarder.isRunning }, "after its three refusals the forwarder must listen again")
        assertEquals(4, fx.forwarder.calls.get(), "three refused client connections, then a real listen()")
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
        assertTrue(fx.manager.activeIds().contains(fx.config.id))
        verify(exactly = 0) { fx.client.disconnect() }

        assertTrue(fx.manager.stopTunnel(fx.config.id) is SshResult.Success)

        assertTrue(
            fx.forwarder.returned.await(5, TimeUnit.SECONDS),
            "listen() must return after stopTunnel, not spin on the closed socket",
        )
        assertEquals(TunnelStatus.STOPPED, fx.manager.getTunnelState(fx.config.id)?.status)
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
    }

    @Test
    fun `a refusal before the registration does not end the listen loop`() = runTest(UnconfinedTestDispatcher()) {
        // No gate : the job starts before the tunnel is registered, so the refusal can come out in that
        // window. It used to be swallowed there (nothing registered yet), leaving an ACTIVE tunnel with
        // a dead listener.
        val fx = startTunnelWith("tun-refused-early", connected = true) { c, p, s ->
            RefusingForwarder(c, p, s, refusals = 1, failure = { channelRefusal() })
        }

        assertTrue(awaitUntil { fx.forwarder.isRunning }, "the listener must be back in listen() after the refusal")
        assertEquals(2, fx.forwarder.calls.get(), "one refused client connection, then a real listen()")
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
        assertTrue(fx.manager.activeIds().contains(fx.config.id))
    }

    @Test
    fun `a refusal on a connection that is gone sends the tunnel to RECONNECTING with the translated text`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val fx = startTunnelWith("tun-refused-gone", connected = false) { c, p, s ->
            RefusingForwarder(c, p, s, refusals = 1, gate = gate, failure = { channelRefusal() })
        }

        gate.countDown()

        assertTrue(
            awaitUntil { fx.manager.getTunnelState(fx.config.id)?.status == TunnelStatus.RECONNECTING },
            "a refusal on a dead SSH connection is a lost tunnel",
        )
        val errorMessage = fx.manager.getTunnelState(fx.config.id)?.errorMessage
        assertEquals(getString(Res.string.tunnels_error_listener_stopped), errorMessage)
        assertFalse(
            errorMessage.orEmpty().contains("db.interne") || errorMessage.orEmpty().contains("5432"),
            "the server text must not reach the state: $errorMessage",
        )
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
        assertEquals(1, fx.forwarder.calls.get(), "no point in listening on a dead connection")
    }

    /** The channel open that SSHJ gives up on after its delay (30 s), as `Promise` raises it. */
    private fun channelOpenTimeout() =
        ConnectionException("Timeout expired", TimeoutException("Timeout expired: 30000 MILLISECONDS"))

    @Test
    fun `a channel open that times out leaves the local tunnel ACTIVE while the connection is alive`() = runTest(UnconfinedTestDispatcher()) {
        // sshd answers a direct-tcpip only once its own connection to the target is done or failed :
        // behind a firewall that drops the packets that takes about two minutes, and SSHJ gives up at
        // 30 s on a healthy transport (the keepalive of the watchdog is answered). Ending the tunnel
        // there killed it on every client attempt.
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val fx = startTunnelWith("tun-timeout-alive", connected = true) { c, p, s ->
            RefusingForwarder(c, p, s, refusals = 1, gate = gate, failure = { channelOpenTimeout() })
        }

        gate.countDown()

        assertTrue(awaitUntil { fx.forwarder.isRunning }, "after the timeout the forwarder must listen again")
        assertEquals(2, fx.forwarder.calls.get(), "one client connection without an answer, then a real listen()")
        assertEquals(TunnelStatus.ACTIVE, fx.manager.getTunnelState(fx.config.id)?.status)
        assertTrue(fx.manager.activeIds().contains(fx.config.id))
        verify(exactly = 0) { fx.client.disconnect() }
    }

    @Test
    fun `a channel open that times out on a connection that is gone sends the local tunnel to RECONNECTING`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val fx = startTunnelWith("tun-timeout-gone", connected = false) { c, p, s ->
            RefusingForwarder(c, p, s, refusals = 1, gate = gate, failure = { channelOpenTimeout() })
        }

        gate.countDown()

        assertTrue(
            awaitUntil { fx.manager.getTunnelState(fx.config.id)?.status == TunnelStatus.RECONNECTING },
            "a channel open that did not answer on a dead SSH connection must end the tunnel",
        )
        assertEquals(getString(Res.string.tunnels_error_listener_stopped), fx.manager.getTunnelState(fx.config.id)?.errorMessage)
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
        assertEquals(1, fx.forwarder.calls.get(), "no point in listening on a dead connection")
    }

    /**
     * A refusal that comes out of `listen()` because of the Stop itself : `listen()` is not entered
     * again, the state ends STOPPED and nothing is reported as a loss. With [reraiseInterrupt] the
     * interrupt flag is still up when the refusal comes out ; without it the flag is gone, and only the
     * state of the job tells that the tunnel is being stopped.
     *
     * A real Stop closes the listening socket right after cancelling the job, and the loop ends on
     * `serverSocket.isClosed` whatever it reads from the job. [assertRefusalAfterJobCancelled] is the
     * variant that leaves the socket open.
     */
    private suspend fun assertRefusalRacingStopEndsStopped(id: String, reraiseInterrupt: Boolean) {
        val fx = startTunnelWith(id, connected = true) { c, p, s ->
            InterruptRefusingForwarder(c, p, s, reraiseInterrupt) { channelRefusal() }
        }
        assertTrue(fx.forwarder.listening.await(5, TimeUnit.SECONDS), "the forwarder must be listening")

        assertTrue(fx.manager.stopTunnel(fx.config.id) is SshResult.Success)

        assertTrue(fx.forwarder.ended.await(5, TimeUnit.SECONDS), "listen() must end after stopTunnel")
        // Let the listen coroutine finish : a loop that came back into listen() would park there for
        // seconds and show as a second call, and a loss would show as RECONNECTING.
        Thread.sleep(300)
        assertEquals(1, fx.forwarder.calls.get(), "listen() must not be entered again on a tunnel that is being stopped")
        assertEquals(TunnelStatus.STOPPED, fx.manager.getTunnelState(fx.config.id)?.status)
        assertFalse(fx.manager.activeIds().contains(fx.config.id))
    }

    /**
     * The job of the listen loop is cancelled and NOTHING else happens : the listening socket stays
     * open and the SSH connection stays alive (`connected = true`), so that neither `isClosed` nor
     * `clientConnected` can end the loop. Only what the loop reads from the job (the interrupt flag
     * when [reraiseInterrupt] puts it back, `stopRequested` otherwise) tells it that the tunnel is
     * being stopped. A loop that does not read it goes back into `listen()`, which shows as a second
     * call. The cancelled scope carries the job of the loop, so cancelling it is a cancellation of
     * that job, and of nothing the manager closes.
     */
    private suspend fun assertRefusalAfterJobCancelled(id: String, reraiseInterrupt: Boolean) {
        val socket = AtomicReference<ServerSocket>()
        val fx = startTunnelWith(id, connected = true) { c, p, s ->
            socket.set(s)
            InterruptRefusingForwarder(c, p, s, reraiseInterrupt) { channelRefusal() }
        }
        // The manager does not close it in this test : close it, or the port stays taken.
        cleanups += { try { socket.get()?.close() } catch (_: Exception) {} }
        assertTrue(fx.forwarder.listening.await(5, TimeUnit.SECONDS), "the forwarder must be listening")

        scope.cancel()

        assertTrue(fx.forwarder.ended.await(5, TimeUnit.SECONDS), "the cancellation must interrupt listen()")
        // A loop that came back into listen() parks there for seconds and shows as a second call.
        Thread.sleep(300)
        assertEquals(1, fx.forwarder.calls.get(), "listen() must not be entered again once the job is cancelled")
        assertFalse(socket.get().isClosed, "this test leaves the listening socket open")
        assertFalse(
            fx.manager.getTunnelState(fx.config.id)?.status == TunnelStatus.RECONNECTING,
            "a failure that comes out of a cancelled job is not a loss",
        )
    }

    @Test
    fun `a refusal racing a Stop ends STOPPED and listen is not entered again`() = runTest(UnconfinedTestDispatcher()) {
        assertRefusalRacingStopEndsStopped("tun-refused-stop", reraiseInterrupt = false)
    }

    @Test
    fun `a refusal racing a Stop with the interrupt still up ends STOPPED and listen is not entered again`() = runTest(UnconfinedTestDispatcher()) {
        assertRefusalRacingStopEndsStopped("tun-refused-stop-flag", reraiseInterrupt = true)
    }

    @Test
    fun `a refusal after the job is cancelled, the socket open and no interrupt flag, does not enter listen again`() = runTest(UnconfinedTestDispatcher()) {
        // Only the state of the job speaks here : the flag is gone (InterruptedException cleared it),
        // the socket is open and the connection alive. Without the stopRequested of the loop this
        // would go back into listen().
        assertRefusalAfterJobCancelled("tun-refused-cancel", reraiseInterrupt = false)
    }

    @Test
    fun `a refusal after the job is cancelled, the socket open and the interrupt flag up, does not enter listen again`() = runTest(UnconfinedTestDispatcher()) {
        assertRefusalAfterJobCancelled("tun-refused-cancel-flag", reraiseInterrupt = true)
    }

    @Test
    fun `a failed client connection is logged once as a warning, with the LogId and the class only`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val tunnelId = "tun-refused-log"
        var label = ""
        var localPort = 0
        // A refusal, then a channel open without an answer, then a refusal again : the class of the
        // exception is what tells them apart in the log.
        val calls = AtomicInteger(0)
        val failures = listOf<() -> IOException>({ channelRefusal() }, { channelOpenTimeout() }, { channelRefusal() })

        val captured = captureLogs {
            val fx = startTunnelWith(tunnelId, connected = true) { c, p, s ->
                RefusingForwarder(c, p, s, refusals = 3, gate = gate, failure = { failures[calls.getAndIncrement()]() })
            }
            label = fx.config.label
            localPort = fx.config.localPort
            gate.countDown()
            // A failure is logged before the listener goes back into listen() : once the fourth entry
            // is there, the three failures are in the log.
            assertTrue(awaitUntil { fx.forwarder.calls.get() >= 4 }, "the three failures must have been survived")
            fx.manager.stopTunnel(fx.config.id)
        }

        // The sink is global : judge the manager's own lines for content, every line for the leak.
        val lines = captured.taggedBy("DesktopTunnelManager")
        val id = LogId.of(tunnelId)
        val warnings = lines.filter { it.startsWith("W/") && it.contains(id) }
        assertEquals(1, warnings.size, "only the first failure is a warning, got $lines")
        assertTrue(warnings.single().contains("OpenFailException"), "the class of the exception is named: $warnings")
        val debugLines = lines.filter { it.startsWith("D/") && it.contains(id) && it.contains("ouverture du canal") }
        assertEquals(2, debugLines.size, "the next failures are debug lines, got $lines")
        assertTrue(debugLines[0].contains("2 depuis") && debugLines[1].contains("3 depuis"), "the count is logged: $debugLines")
        assertTrue(debugLines[0].contains("TimeoutException"), "the timeout is told from a refusal by its class: $debugLines")
        assertTrue(debugLines[1].contains("OpenFailException"), "the class of the exception is named: $debugLines")
        val leaked = listOf("db.interne", "5432", "direct-tcpip", "Timeout expired", "MILLISECONDS", label, tunnelId, localPort.toString())
        assertTrue(captured.none { line -> leaked.any { line.contains(it) } }, "a label, a target, an id or server text leaked: $captured")
        assertNothingScrubbed(lines)
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
    fun `startTunnel with an unknown host fails with a localized message that holds no id`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-nohost", label = "no-host", hostId = "9b0c2d9e-5a52-4f55-9d5e-0a1b2c3d4e5f",
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        )
        var factoryCalled = false
        val manager = DesktopTunnelManager(scope) { _, _ ->
            factoryCalled = true
            SshResult.Success(mockk(relaxed = true))
        }

        val result = manager.startTunnel(config) { _ -> null }

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, result.code)
        // The text depends on the machine language: compare with the resource, never with a literal.
        assertEquals(getString(Res.string.tunnels_error_host_not_found), result.message)
        assertFalse(result.message.contains(config.hostId), "a UUID tells the user nothing")
        assertFalse(factoryCalled)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("tun-nohost")?.status)
        assertEquals(result.message, manager.getTunnelState("tun-nohost")?.errorMessage)
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
        // The text depends on the machine language: compare with the resource, never with a literal. The
        // positional `%1$d` must have been filled in, a non-positional one would show up as is.
        assertEquals(getString(Res.string.tunnels_error_port_in_use, squatter.localPort), result.message)
        assertTrue(result.message.contains(squatter.localPort.toString()), "the port must be named, got ${result.message}")
        assertFalse(result.message.contains("%"), "no format specifier may be left in ${result.message}")
        assertEquals(result.message, manager.getTunnelState("tun-pbusy")?.errorMessage)
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

        // The accept loop runs (it starts once the tunnel is registered): a real SOCKS5 greeting is answered.
        java.net.Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x00)); flush() }
            val reply = ByteArray(2)
            var read = 0
            while (read < reply.size) {
                val n = socket.getInputStream().read(reply, read, reply.size - read)
                assertTrue(n != -1, "the server closed the connection before answering")
                read += n
            }
            assertEquals(listOf<Byte>(0x05, 0x00), reply.toList())
        }

        val stopResult = manager.stopTunnel("tun-socks5")
        assertTrue(stopResult is SshResult.Success)
        // After stop the port is free again.
        try { ServerSocket(port).close(); boundPortFree = true } catch (_: Exception) {}
        assertTrue(boundPortFree, "SOCKS5 server should have released the port after stop")
    }

    /**
     * A listening socket on the loopback whose `accept()` waits for [gate] (when there is one), then throws
     * [failure] ; [accepts] counts the entries into `accept()`. Without [failure] it accepts for real.
     */
    private class FailingAcceptSocket(
        private val gate: CountDownLatch?,
        private val failure: (() -> Throwable)?,
    ) : ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) {
        val accepts = AtomicInteger(0)

        override fun accept(): java.net.Socket {
            accepts.incrementAndGet()
            if (failure == null) return super.accept()
            gate?.await(5, TimeUnit.SECONDS)
            throw failure.invoke()
        }
    }

    private fun socksConfig(id: String, port: Int = freePort()) = TunnelConfig(
        id = id, label = "socks-$id", hostId = testHost.id,
        type = TunnelType.DYNAMIC_SOCKS5,
        localPort = port, remoteHost = "0.0.0.0", remotePort = 0,
    )

    @Test
    fun `an accept failure of the SOCKS5 server sends the tunnel to RECONNECTING with the translated text`() = runTest(UnconfinedTestDispatcher()) {
        val config = socksConfig("tun-socks-fail")
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val mockClient = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }
        val socket = FailingAcceptSocket(gate) { SocketException("Too many open files") }
        openedServerSockets += socket
        manager.socks5ServerSocket = { socket }

        manager.startTunnel(config) { _ -> testHost }
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        gate.countDown()

        assertTrue(
            awaitUntil { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "a dead accept loop must send the tunnel to RECONNECTING",
        )
        val message = manager.getTunnelState(config.id)?.errorMessage
        assertEquals(getString(Res.string.tunnels_error_socks_stopped), message)
        assertFalse(message.orEmpty().contains("Too many"), "got $message")
        assertFalse(manager.activeIds().contains(config.id))
        verify(atLeast = 1) { mockClient.disconnect() }
        assertTrue(awaitUntil { socket.isClosed }, "the listening socket must be released")
        // The seam handed out [socket] whatever the port: its own port is the one that must come free.
        assertTrue(awaitUntil { runCatching { ServerSocket(socket.localPort).close() }.isSuccess }, "the port must be free")
    }

    @Test
    fun `a SOCKS5 accept failure is logged with the LogId and the class, never the message`() = runTest(UnconfinedTestDispatcher()) {
        val config = socksConfig("tun-socks-log")
        val gate = CountDownLatch(1)
        cleanups += { gate.countDown() }
        val mockClient = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }
        val socket = FailingAcceptSocket(gate) { SocketException("Too many open files") }
        openedServerSockets += socket
        manager.socks5ServerSocket = { socket }

        val captured = captureLogs {
            manager.startTunnel(config) { _ -> testHost }
            gate.countDown()
            assertTrue(awaitUntil { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING })
        }

        val lines = captured.taggedBy("DesktopTunnelManager")
        val id = LogId.of(config.id)
        assertTrue(
            lines.any { it.contains("Tunnel $id perdu : SOCKS5 accept loop died (SocketException)") },
            "got $lines",
        )
        assertTrue(captured.none { it.contains("Too many open files") }, "the message leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `the SOCKS5 accept loop starts only once the tunnel is registered`() = runTest(UnconfinedTestDispatcher()) {
        val config = socksConfig("tun-socks-late")
        val mockClient = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }
        val socket = FailingAcceptSocket(null, null)
        openedServerSockets += socket
        manager.socks5ServerSocket = { socket }
        val seenBeforeConfirm = AtomicInteger(-1)
        manager.beforeConfirmHook = {
            // Give a loop started too early the time to enter accept(), so the regression cannot
            // pass by luck of timing.
            awaitUntil(300) { socket.accepts.get() > 0 }
            seenBeforeConfirm.set(socket.accepts.get())
        }

        manager.startTunnel(config) { _ -> testHost }

        assertEquals(0, seenBeforeConfirm.get(), "no accept() before the tunnel is registered")
        assertTrue(awaitUntil { socket.accepts.get() >= 1 }, "the accept loop must start once the tunnel is up")
        manager.stopTunnel(config.id)
    }

    @Test
    fun `a Stop of a SOCKS5 tunnel reports no loss`() = runTest(UnconfinedTestDispatcher()) {
        val config = socksConfig("tun-socks-stop")
        val mockClient = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        manager.startTunnel(config) { _ -> testHost }
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)

        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
        Thread.sleep(300)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status, "no RECONNECTING after a Stop")
    }

    // ── Log content ──────────────────────────────────────────────────────────

    @Test
    fun `tunnel logs carry a LogId and never the label, the target or the server text`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-log", label = "prod-db-tunnel", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port, remoteHost = "db-primary", remotePort = 5432,
        )
        val mockClient = mockk<SSHClient>(relaxed = true)
        val transport = mockk<Transport>(relaxed = true)
        every { mockClient.transport } returns transport
        val listener = slot<DisconnectListener>()
        every { transport.disconnectListener = capture(listener) } just Runs
        every { mockClient.newLocalPortForwarder(any(), any()) } returns mockk<LocalPortForwarder>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }
        val serverText = "alice was refused by db-primary"

        val captured = captureLogs {
            manager.startTunnel(config) { _ -> testHost }
            // The server drops the session and says why: the text reaches neither the tunnel state nor the log.
            listener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, serverText)
            manager.stopTunnel("tun-log")
        }

        // The sink is global: judge the manager's own lines for content, every line for the leak.
        val lines = captured.taggedBy("DesktopTunnelManager")
        val id = LogId.of("tun-log")
        assertTrue(lines.any { it.contains("Tunnel LOCAL démarré : $id") }, "got $lines")
        assertTrue(lines.any { it.contains("Tunnel $id perdu : SSH session closed (CONNECTION_LOST)") }, "got $lines")
        val leaked = listOf("prod-db-tunnel", "db-primary", "5432", "alice", "refused", port.toString())
        assertTrue(captured.none { line -> leaked.any { line.contains(it) } }, "a label, a target or server text leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `a server disconnect text never reaches the tunnel state, which carries the translated reason`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-ui", label = "ui", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        )
        val mockClient = mockk<SSHClient>(relaxed = true)
        val transport = mockk<Transport>(relaxed = true)
        every { mockClient.transport } returns transport
        val listener = slot<DisconnectListener>()
        every { transport.disconnectListener = capture(listener) } just Runs
        every { mockClient.newLocalPortForwarder(any(), any()) } returns mockk<LocalPortForwarder>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        manager.startTunnel(config) { _ -> testHost }
        listener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "maintenance window")

        val state = manager.getTunnelState("tun-ui")
        assertEquals(TunnelStatus.RECONNECTING, state?.status)
        assertEquals(getString(Res.string.tunnels_error_session_closed), state?.errorMessage)
        assertFalse(state?.errorMessage.orEmpty().contains("maintenance window"), "got ${state?.errorMessage}")
        assertFalse(state?.errorMessage.orEmpty().contains("CONNECTION_LOST"), "got ${state?.errorMessage}")
    }

    @Test
    fun `a disconnect callback without a message still sends the tunnel to RECONNECTING`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-ui-null", label = "ui", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        )
        val mockClient = mockk<SSHClient>(relaxed = true)
        val transport = mockk<Transport>(relaxed = true)
        every { mockClient.transport } returns transport
        val listener = slot<DisconnectListener>()
        every { transport.disconnectListener = capture(listener) } just Runs
        every { mockClient.newLocalPortForwarder(any(), any()) } returns mockk<LocalPortForwarder>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        manager.startTunnel(config) { _ -> testHost }
        // SSHJ passes SSHException.getMessage(), which can be null.
        listener.captured.notifyDisconnect(DisconnectReason.UNKNOWN, null)

        val state = manager.getTunnelState("tun-ui-null")
        assertEquals(TunnelStatus.RECONNECTING, state?.status)
        assertEquals(getString(Res.string.tunnels_error_session_closed), state?.errorMessage)
    }

    @Test
    fun `a Stop landing while the disconnect of a lost tunnel blocks is not overwritten by RECONNECTING`() = runTest(UnconfinedTestDispatcher()) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        cleanups += { release.countDown() }
        val fx = startTunnelWith(
            "tun-lost-stop",
            onDisconnect = { entered.countDown(); release.await(5, TimeUnit.SECONDS) },
        ) { c, p, s -> TrackingForwarder(c, p, s) }

        // SSHJ reports the loss from its reader thread ; the disconnect then blocks (write lock).
        val reporter = Thread { fx.disconnectListener.notifyDisconnect(DisconnectReason.CONNECTION_LOST, null) }
        reporter.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the disconnect must be reached")
        assertEquals(TunnelStatus.RECONNECTING, fx.manager.getTunnelState(fx.config.id)?.status)

        assertTrue(fx.manager.stopTunnel(fx.config.id) is SshResult.Success)
        assertEquals(TunnelStatus.STOPPED, fx.manager.getTunnelState(fx.config.id)?.status)

        release.countDown()
        reporter.join(5_000)
        assertEquals(
            TunnelStatus.STOPPED,
            fx.manager.getTunnelState(fx.config.id)?.status,
            "a stale RECONNECTING must not land after the Stop",
        )
    }

    @Test
    fun `a network-level open failure shows the network reason only when the caller asks for it`() = runTest(UnconfinedTestDispatcher()) {
        val factory = object : SshClientFactory {
            override suspend fun open(host: Host, onCreated: (SSHClient) -> Unit): SshResult<SSHClient> =
                SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "own text")

            override fun lastFailureWasNetworkLevel(host: Host): Boolean = true
        }
        val manager = DesktopTunnelManager(scope, factory)
        val config = reservationConfig("tun-net-reason")

        // A manual start keeps the text of the failure.
        val manual = manager.startTunnel(config) { _ -> testHost }
        assertEquals("own text", (manual as SshResult.Error).message)
        assertEquals("own text", manager.getTunnelState(config.id)?.errorMessage)

        // The reconnect loop asks for the network reason.
        val fromLoop = manager.startTunnel(config, networkLostReason = "reseau perdu") { _ -> testHost }
        assertEquals("reseau perdu", (fromLoop as SshResult.Error).message)
        assertEquals("reseau perdu", manager.getTunnelState(config.id)?.errorMessage)
    }

    @Test
    fun `a keepalive without answer sends the tunnel to RECONNECTING with the translated text`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-ka", label = "ka", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        )
        val mockClient = mockk<SSHClient>(relaxed = true)
        val connection = mockk<Connection>(relaxed = true)
        every { mockClient.connection } returns connection
        every { connection.sendGlobalRequest(any(), any(), any()) } throws TransportException("dead")
        every { mockClient.newLocalPortForwarder(any(), any()) } returns mockk<LocalPortForwarder>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        manager.startTunnel(config) { _ -> testHost }

        // The first probe goes out after 3 s (real time, like the watchdog itself).
        assertTrue(
            awaitUntil(timeoutMs = 10_000) { manager.getTunnelState(config.id)?.status == TunnelStatus.RECONNECTING },
            "a probe that fails must send the tunnel to RECONNECTING",
        )
        val state = manager.getTunnelState(config.id)
        assertEquals(getString(Res.string.tunnels_error_no_answer), state?.errorMessage)
        assertFalse(state?.errorMessage.orEmpty().contains("dead"), "got ${state?.errorMessage}")
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
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status, "a cancelled start must not stay STARTING")

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
    fun `VaultSshClientFactory gives up before connect when the hand-over aborts or is cancelled`() = runBlocking<Unit> {
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

            // A Stop that came first : an IOException from the hand-over is a failed open, an Error.
            val result = factory.open(host) { client ->
                handedOver.set(client)
                throw IOException("stopped")
            }

            assertTrue(result is SshResult.Error, "an aborted hand-over must come back as an Error, got $result")
            assertNotNull(handedOver.get(), "the client must have been handed over before the abort")

            // A cancellation from the hand-over is not an error : it goes up as it came, so that the
            // manager writes STOPPED instead of an ERROR. Same wait on the listener for both.
            val handedOverToCancel = AtomicReference<SSHClient>()
            val cancellation = CancellationException("cancelled during the hand-over")
            val thrown = assertFailsWith<CancellationException> {
                factory.open(host) { client ->
                    handedOverToCancel.set(client)
                    throw cancellation
                }
            }

            assertNotNull(handedOverToCancel.get(), "the client must have been handed over before the cancellation")
            assertTrue(thrown.message == cancellation.message, "the cancellation must go up, not an error built from it")
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
        // The Error went up untouched, but the card must not stay on "Starting". It was thrown before
        // the registration, so the state is still the STARTING of the reservation : it becomes ERROR.
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)
        assertEquals(getString(Res.string.tunnels_error_start_failed), manager.getTunnelState(config.id)?.errorMessage)
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

        // With keepErrorMessage the text of the current state stays, whatever errorMessage says.
        manager.markError(config, "boom")
        assertEquals(
            TunnelStatus.ERROR,
            manager.updateStatusIf(config.id, TunnelStatus.RECONNECTING, "ignored", keepErrorMessage = true, condition = notLive),
        )
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(config.id)?.status)
        assertEquals("boom", manager.getTunnelState(config.id)?.errorMessage)
    }

    @Test
    fun `a start issued while the tunnel is RECONNECTING carries its reason into STARTING, a fresh start does not`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-carry", label = "carry", hostId = testHost.id,
            type = TunnelType.REMOTE_FORWARD,
            localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 8080,
        )
        val release = CompletableDeferred<Unit>()
        val mockClient = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            release.await()
            SshResult.Success(mockClient)
        }
        manager.markError(config, "old error")
        manager.updateStatus(config.id, TunnelStatus.RECONNECTING, "motif")

        val reconnect = async(Dispatchers.Default) { manager.startTunnel(config) { _ -> testHost } }
        assertTrue(
            awaitUntil { manager.getTunnelState(config.id)?.status == TunnelStatus.STARTING },
            "the start must write STARTING",
        )
        assertEquals("motif", manager.getTunnelState(config.id)?.errorMessage)

        release.complete(Unit)
        assertTrue(reconnect.await() is SshResult.Success)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)

        // From STOPPED : STARTING without text.
        manager.stopTunnel(config.id)
        val second = CompletableDeferred<Unit>()
        val fresh = DesktopTunnelManager(scope) { _, _ ->
            second.await()
            SshResult.Success(mockClient)
        }
        fresh.markError(config, "boom")
        fresh.updateStatus(config.id, TunnelStatus.STOPPED, "left behind")
        val freshStart = async(Dispatchers.Default) { fresh.startTunnel(config) { _ -> testHost } }
        assertTrue(
            awaitUntil { fresh.getTunnelState(config.id)?.status == TunnelStatus.STARTING },
            "the start must write STARTING",
        )
        assertNull(fresh.getTunnelState(config.id)?.errorMessage)
        second.complete(Unit)
        freshStart.await()
        fresh.stopTunnel(config.id)

        // From ERROR : STARTING without text as well, the old error is not carried over.
        val third = CompletableDeferred<Unit>()
        val fromError = DesktopTunnelManager(scope) { _, _ ->
            third.await()
            SshResult.Success(mockClient)
        }
        fromError.markError(config, "boom")
        val errorStart = async(Dispatchers.Default) { fromError.startTunnel(config) { _ -> testHost } }
        assertTrue(
            awaitUntil { fromError.getTunnelState(config.id)?.status == TunnelStatus.STARTING },
            "the start must write STARTING",
        )
        assertNull(fromError.getTunnelState(config.id)?.errorMessage)
        third.complete(Unit)
        errorStart.await()
        fromError.stopTunnel(config.id)
    }

    @Test
    fun `an Error raised while a SOCKS5 tunnel is being set up goes up and leaves the port released`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.DYNAMIC_SOCKS5, "tun-socks-error")
        rig.onTransport = { throw StackOverflowError() }

        assertFailsWith<StackOverflowError> { rig.manager.startTunnel(rig.config) { _ -> testHost } }

        assertFalse(rig.resourceHeld(), "the SOCKS5 server must not keep the port")
        assertFalse(rig.manager.activeIds().contains(rig.config.id))
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

    // ── Unexpected exceptions and cancellation ───────────────────────────────
    // The reservation writes STARTING before anything can fail. Whatever ends the
    // start without a result of its own must not leave that STARTING behind : an
    // unexpected exception is an ERROR the caller can read (an `Error`, never an
    // exception up to the UI), a cancellation is STOPPED. Both leave a state that
    // somebody else wrote in the meantime alone.

    @Test
    fun `an exception from the host lookup ends as a translated ERROR and the next Start goes through`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-boom-lookup")
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = manager.startTunnel(config) { _ -> throw IllegalStateException("database closed") }

        assertTrue(first is SshResult.Error, "an exception must come back as an Error, got $first")
        assertEquals(SshErrorCode.UNKNOWN, first.code)
        // The text depends on the machine language: compare with the resource, never with a literal.
        assertEquals(getString(Res.string.tunnels_error_start_failed), first.message)
        assertFalse(first.message.contains("database closed"), "the exception text must not reach the user")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)
        assertEquals(first.message, manager.getTunnelState(config.id)?.errorMessage)
        assertFalse(manager.isStarting(config.id), "the reservation must be released")
        assertFalse(manager.activeIds().contains(config.id))
        assertEquals(0, opens.get())

        val second = manager.startTunnel(config) { _ -> testHost }

        assertTrue(second is SshResult.Success, "a Start after the failed one must start again, got $second")
        assertEquals(1, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `an exception from the client factory ends as a translated ERROR and closes the client it was handed`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-boom-factory")
        val handedOver = quietClient()
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, onCreated ->
            if (opens.incrementAndGet() == 1) {
                onCreated(handedOver)
                throw IllegalStateException("vault locked")
            }
            SshResult.Success(quietClient())
        }

        val first = manager.startTunnel(config) { _ -> testHost }

        assertTrue(first is SshResult.Error, "an exception must come back as an Error, got $first")
        assertEquals(SshErrorCode.UNKNOWN, first.code)
        assertEquals(getString(Res.string.tunnels_error_start_failed), first.message)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)
        assertEquals(first.message, manager.getTunnelState(config.id)?.errorMessage)
        assertFalse(manager.isStarting(config.id))
        verify(exactly = 1) { handedOver.disconnect() }
        // Nothing stale in startingClients : a Stop disconnects nothing more.
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        verify(exactly = 1) { handedOver.disconnect() }

        val second = manager.startTunnel(config) { _ -> testHost }
        assertTrue(second is SshResult.Success, "a Start after the failed one must start again, got $second")
        assertEquals(2, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `a forwarder setup failure that carries no message falls back to the translated start-failed text`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-nomsg")
        rig.onRemoteBind = { throw IOException() }

        val result = rig.manager.startTunnel(rig.config) { _ -> testHost }

        assertTrue(result is SshResult.Error, "got $result")
        assertEquals(getString(Res.string.tunnels_error_start_failed), result.message)
        assertEquals(TunnelStatus.ERROR, rig.manager.getTunnelState(rig.config.id)?.status)
        assertEquals(result.message, rig.manager.getTunnelState(rig.config.id)?.errorMessage)
        assertTrue(rig.disconnects.get() >= 1, "the half-built tunnel's client must be disconnected")
    }

    @Test
    fun `the log of an unexpected exception names the class and the LogId, never the message`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-boom-log")
        val boom = IOException("cannot read /home/alice/.nextsh/hosts.db for prod-db-host")
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(quietClient()) }

        val captured = captureLogs {
            manager.startTunnel(config) { _ -> throw boom }
        }

        val lines = captured.taggedBy("DesktopTunnelManager")
        val id = LogId.of(config.id)
        assertTrue(lines.any { it.contains(id) && it.contains(errorClass(boom)) }, "got $lines")
        val leaked = listOf("alice", "hosts.db", "prod-db-host", "cannot read", config.id, config.hostId)
        assertTrue(captured.none { line -> leaked.any { line.contains(it) } }, "the message, the host or the raw id leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `a JVM Error from the client factory goes up untouched but leaves the card in ERROR`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-boom-error")
        val opens = AtomicInteger(0)
        // What a jlink runtime that lacks a module throws, at the first use of the missing class.
        val missing = NoClassDefFoundError("javax/crypto/secret/Thing")
        val manager = DesktopTunnelManager(scope) { _, _ ->
            if (opens.incrementAndGet() == 1) throw missing
            SshResult.Success(quietClient())
        }

        val captured = captureLogs {
            val thrown = assertFailsWith<NoClassDefFoundError> { manager.startTunnel(config) { _ -> testHost } }
            assertTrue(thrown === missing, "the Error must be rethrown as it came, not wrapped")
        }

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status, "not a STARTING that stays for good")
        assertEquals(getString(Res.string.tunnels_error_start_failed), manager.getTunnelState(config.id)?.errorMessage)
        assertFalse(manager.isStarting(config.id), "the reservation must be released")
        assertFalse(manager.activeIds().contains(config.id))
        val lines = captured.taggedBy("DesktopTunnelManager")
        assertTrue(lines.any { it.contains(LogId.of(config.id)) && it.contains(errorClass(missing)) }, "got $lines")
        assertTrue(captured.none { it.contains("javax/crypto") }, "the Error message leaked: $captured")

        val second = manager.startTunnel(config) { _ -> testHost }
        assertTrue(second is SshResult.Success, "a Start after the Error must start again, got $second")
        assertEquals(2, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `a start cancelled during the host lookup ends STOPPED instead of staying STARTING`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-cancel-lookup")
        val opens = AtomicInteger(0)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            opens.incrementAndGet()
            SshResult.Success(quietClient())
        }

        val first = async { manager.startTunnel(config) { _ -> awaitCancellation() } }
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(config.id)?.status)
        assertTrue(manager.isStarting(config.id))
        first.cancel()
        first.join()

        assertTrue(first.isCancelled, "the cancellation must still reach the caller")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
        assertFalse(manager.isStarting(config.id))
        assertFalse(manager.activeIds().contains(config.id))
        assertFalse(manager.wasManuallyStopped(config.id), "a cancellation is not the user's Stop : no reconnect veto")
        assertEquals(0, opens.get())

        val later = manager.startTunnel(config) { _ -> testHost }
        assertTrue(later is SshResult.Success, "a Start after the cancelled one must start again, got $later")
        assertEquals(1, opens.get())
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(config.id)?.status)

        manager.stopTunnel(config.id)
    }

    @Test
    fun `a cancellation surfacing in the forwarder setup is not turned into an ERROR`() = runTest(UnconfinedTestDispatcher()) {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-cancel-setup")
        // `bind` is a blocking SSHJ call that a cancellation cannot interrupt : it surfaces at the
        // `withContext` boundary around the setup instead. Throwing it from the bind is the same
        // exception reaching the same `catch`.
        rig.onRemoteBind = { throw CancellationException("cancelled during the bind") }

        assertFailsWith<CancellationException> { rig.manager.startTunnel(rig.config) { _ -> testHost } }

        assertEquals(TunnelStatus.STOPPED, rig.manager.getTunnelState(rig.config.id)?.status, "not ERROR, not STARTING")
        assertNull(rig.manager.getTunnelState(rig.config.id)?.errorMessage)
        assertTrue(rig.disconnects.get() >= 1, "the half-built tunnel's client must be disconnected")
        assertEquals(0, rig.remoteBinds.get())
        assertFalse(rig.manager.isStarting(rig.config.id))
        assertFalse(rig.manager.activeIds().contains(rig.config.id))
        assertFalse(rig.manager.wasManuallyStopped(rig.config.id))

        rig.onRemoteBind = {}
        val later = rig.manager.startTunnel(rig.config) { _ -> testHost }
        assertTrue(later is SshResult.Success, "a Start after the cancelled one must start again, got $later")
        assertEquals(TunnelStatus.ACTIVE, rig.manager.getTunnelState(rig.config.id)?.status)

        rig.manager.stopTunnel(rig.config.id)
    }

    @Test
    fun `a Stop that landed during a host lookup that then throws stays STOPPED and reports no error`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-stop-boom")
        val lookupGate = CompletableDeferred<Unit>()
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(quietClient()) }

        val first = async {
            manager.startTunnel(config) { _ ->
                lookupGate.await()
                throw IllegalStateException("database closed")
            }
        }
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        lookupGate.complete(Unit)

        assertTrue(first.await() is SshResult.Success, "an explicit stop is a quiet success, not an error")
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
        assertFalse(manager.isStarting(config.id))
    }

    @Test
    fun `an unexpected exception writes no ERROR while a Stop is on its way`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-flag-boom")
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(quietClient()) }

        val result = manager.startTunnel(config) { _ ->
            // The Stop set its flag, the STOPPED that follows is not written yet : stopTunnel writes both
            // in one go, so the instant in between is put back by hand (STARTING under a set flag).
            manager.stopTunnel(config.id)
            manager.updateStatus(config.id, TunnelStatus.STARTING)
            throw IllegalStateException("database closed")
        }

        assertTrue(result is SshResult.Success, "an explicit stop is a quiet success, got $result")
        assertTrue(manager.wasManuallyStopped(config.id))
        // No ERROR over it : the Stop's own write is what ends this state, whatever it is.
        assertNotEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status)
        assertNull(manager.getTunnelState(config.id)?.errorMessage)
        assertFalse(manager.isStarting(config.id))
    }

    @Test
    fun `an unexpected exception or a cancellation never overwrites a state somebody else wrote`() = runTest(UnconfinedTestDispatcher()) {
        // ACTIVE : a tunnel that came up on its own. RECONNECTING : a network drop turned STARTING into it.
        // STOPPED : a Stop that wrote it (the flag is not set here : only the status is under test).
        for (written in listOf(TunnelStatus.ACTIVE, TunnelStatus.RECONNECTING, TunnelStatus.STOPPED)) {
            val failingConfig = reservationConfig("tun-keep-boom-$written")
            val failing = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(quietClient()) }
            val failed = failing.startTunnel(failingConfig) { _ ->
                failing.updateStatus(failingConfig.id, written)
                throw IllegalStateException("database closed")
            }
            assertTrue(failed is SshResult.Success, "$written: the status in place already says it, got $failed")
            assertEquals(written, failing.getTunnelState(failingConfig.id)?.status, "$written must survive an exception")
            assertNull(failing.getTunnelState(failingConfig.id)?.errorMessage)
            assertFalse(failing.isStarting(failingConfig.id))

            val cancelledConfig = reservationConfig("tun-keep-cancel-$written")
            val cancelled = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(quietClient()) }
            val start = async {
                cancelled.startTunnel(cancelledConfig) { _ ->
                    cancelled.updateStatus(cancelledConfig.id, written)
                    awaitCancellation()
                }
            }
            start.cancel()
            start.join()
            assertTrue(start.isCancelled)
            assertEquals(written, cancelled.getTunnelState(cancelledConfig.id)?.status, "$written must survive a cancellation")
            assertFalse(cancelled.isStarting(cancelledConfig.id))
        }
    }

    // ── Texts of a failed setup ──────────────────────────────────────────────
    // A forwarder setup that fails (the local bind, the remote forward request, the SOCKS5 server)
    // ends as the translated text of its category (see `TunnelSetupFailure`), never as what SSHJ or
    // the JDK say. The category and the class of the exception are in the log, the message is not.

    /** A REMOTE_FORWARD rig whose client answers `isConnected` like a live one : true until disconnected. */
    private fun liveRemoteRig(id: String): RaceRig {
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, id)
        // Read AFTER the disconnect it would be false : the start has to classify before it disconnects.
        every { rig.client.isConnected } answers { rig.disconnects.get() == 0 }
        return rig
    }

    /** Starts [rig] with the remote bind failing on [failure]; returns the result and the captured logs. */
    private suspend fun startWithBindFailure(rig: RaceRig, failure: Throwable): Pair<SshResult<Unit>, List<String>> {
        rig.onRemoteBind = { throw failure }
        var started: SshResult<Unit>? = null
        val captured = captureLogs { started = rig.manager.startTunnel(rig.config) { _ -> testHost } }
        return assertNotNull(started) to captured
    }

    private fun assertEndedAsError(rig: RaceRig, result: SshResult<Unit>, code: SshErrorCode, message: String) {
        assertTrue(result is SshResult.Error, "got $result")
        assertEquals(code, result.code)
        assertEquals(message, result.message)
        assertEquals(TunnelStatus.ERROR, rig.manager.getTunnelState(rig.config.id)?.status)
        assertEquals(message, rig.manager.getTunnelState(rig.config.id)?.errorMessage)
        assertTrue(rig.disconnects.get() >= 1, "the half-built tunnel's client must be disconnected")
        assertFalse(rig.manager.isStarting(rig.config.id), "the reservation must be released")
        assertFalse(rig.manager.activeIds().contains(rig.config.id))
    }

    @Test
    fun `a refused remote forward ends as a translated ERROR that names the remote port`() = runTest(UnconfinedTestDispatcher()) {
        val rig = liveRemoteRig("tun-fail-refused")
        val refusal = ConnectionException("Global request [tcpip-forward] failed")

        val (result, captured) = startWithBindFailure(rig, refusal)

        val message = getString(Res.string.tunnels_error_remote_forward_refused, rig.config.remotePort)
        assertEndedAsError(rig, result, SshErrorCode.UNKNOWN, message)
        assertTrue(message.contains(rig.config.remotePort.toString()), "the remote port must be named, got $message")
        assertFalse(message.contains("%"), "no format specifier may be left in $message")
        assertFalse(message.contains("tcpip-forward") || message.contains("Global request"), "SSHJ text in $message")

        // The log : the LogId, the category and the class of the exception, never the text of SSHJ.
        val lines = captured.taggedBy("DesktopTunnelManager")
        val id = LogId.of(rig.config.id)
        assertTrue(
            lines.any { it.contains(id) && it.contains("REMOTE_FORWARD_REFUSED") && it.contains(errorClass(refusal)) },
            "got $lines",
        )
        assertTrue(captured.none { it.contains("tcpip-forward") || it.contains("Global request") }, "SSHJ text leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `a remote bind that times out ends as the translated timeout text`() = runTest(UnconfinedTestDispatcher()) {
        val rig = liveRemoteRig("tun-fail-timeout")

        val (result, _) = startWithBindFailure(rig, ConnectionException(TimeoutException("Timeout expired: 30000 MILLISECONDS")))

        val message = getString(Res.string.tunnels_error_setup_timeout)
        assertEndedAsError(rig, result, SshErrorCode.TIMEOUT, message)
        assertFalse(message.contains("Timeout expired") || message.contains("MILLISECONDS"), "JDK text in $message")
    }

    @Test
    fun `a remote bind on a lost transport ends as the translated connection lost text`() = runTest(UnconfinedTestDispatcher()) {
        val rig = liveRemoteRig("tun-fail-lost")

        val (result, _) = startWithBindFailure(rig, TransportException("Broken transport"))

        assertEndedAsError(rig, result, SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_connection_lost))
    }

    @Test
    fun `a remote bind failure with an unclassified text falls back to start failed and never quotes it`() = runTest(UnconfinedTestDispatcher()) {
        val rig = liveRemoteRig("tun-fail-generic")
        val serverText = "bind refused by gw-prod-01"

        val (result, captured) = startWithBindFailure(rig, IOException(serverText))

        val message = getString(Res.string.tunnels_error_start_failed)
        assertEndedAsError(rig, result, SshErrorCode.UNKNOWN, message)
        assertFalse(message.contains("gw-prod-01"), "the server text reached the user: $message")
        val lines = captured.taggedBy("DesktopTunnelManager")
        assertTrue(lines.any { it.contains(LogId.of(rig.config.id)) && it.contains("GENERIC") }, "got $lines")
        assertTrue(captured.none { it.contains("gw-prod-01") || it.contains("bind refused") }, "the server text leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `the refusal text on a client that is not connected is the generic failure`() = runTest(UnconfinedTestDispatcher()) {
        // The shape of the refusal is only trusted on a live connection. The tests above flip
        // `isConnected` to false at the first `disconnect()`, so they also prove that the start
        // classifies BEFORE it disconnects : this one is the same exception on a client that was
        // already gone.
        val rig = RaceRig(TunnelType.REMOTE_FORWARD, "tun-fail-disconnected")
        every { rig.client.isConnected } returns false

        val (result, _) = startWithBindFailure(rig, ConnectionException("Global request [tcpip-forward] failed"))

        assertEndedAsError(rig, result, SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_start_failed))
    }

    @Test
    fun `a local port taken between the check and the bind ends as the translated port in use`() = runTest(UnconfinedTestDispatcher()) {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            val config = TunnelConfig(
                id = "tun-late-$type", label = "late-$type", hostId = testHost.id, type = type,
                localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
            )
            val client = quietClient()
            val manager = DesktopTunnelManager(scope) { _, _ ->
                // The pre-check passed : another application grabs the port while the handshake runs.
                // On the very address the production code listens on ("127.0.0.1"), whatever the
                // preferred loopback of the machine is.
                openedServerSockets += ServerSocket(config.localPort, 50, InetAddress.getByName("127.0.0.1"))
                SshResult.Success(client)
            }

            val result = manager.startTunnel(config) { _ -> testHost }

            assertTrue(result is SshResult.Error, "$type: got $result")
            assertEquals(SshErrorCode.PORT_IN_USE, result.code, "$type")
            assertEquals(getString(Res.string.tunnels_error_port_in_use, config.localPort), result.message, "$type")
            assertFalse(result.message.contains("Address already in use"), "$type: JDK text in ${result.message}")
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status, "$type")
            assertEquals(result.message, manager.getTunnelState(config.id)?.errorMessage, "$type")
            verify(atLeast = 1) { client.disconnect() }
            assertFalse(manager.isStarting(config.id), "$type: the reservation must be released")
            assertFalse(manager.activeIds().contains(config.id), "$type")
        }
    }

    @Test
    fun `a local port that the system keeps for administrators ends as the translated reserved port before any connection`() = runTest(UnconfinedTestDispatcher()) {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            val config = TunnelConfig(
                id = "tun-reserved-$type", label = "reserved-$type", hostId = testHost.id, type = type,
                localPort = 80, remoteHost = "127.0.0.1", remotePort = 8080,
            )
            var factoryCalled = false
            val manager = DesktopTunnelManager(scope) { _, _ ->
                factoryCalled = true
                SshResult.Success(quietClient())
            }
            // Answers for the system : a permission error on a port below 1024, on a Linux, and nobody
            // listening on that port.
            manager.osName = "Linux"
            manager.portProbe = { throw BindException("Permission denied") }
            manager.portListens = { false }

            val result = manager.startTunnel(config) { _ -> testHost }

            assertTrue(result is SshResult.Error, "$type: got $result")
            assertEquals(SshErrorCode.UNKNOWN, result.code, "$type: nobody holds a reserved port")
            assertEquals(getString(Res.string.tunnels_error_local_port_reserved, 80), result.message, "$type")
            assertFalse(result.message.contains("Permission denied"), "$type: JDK text in ${result.message}")
            assertFalse(factoryCalled, "$type: a reserved port must not cost an authentication")
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(config.id)?.status, "$type")
            assertEquals(result.message, manager.getTunnelState(config.id)?.errorMessage, "$type")
            assertFalse(manager.isStarting(config.id), "$type: the reservation must be released")
        }
    }

    @Test
    fun `the same failure of the check is a port in use on Windows and from 1024 on`() = runTest(UnconfinedTestDispatcher()) {
        for ((osName, port) in listOf("Windows 11" to 80, "Linux" to 8080, "Mac OS X" to 1024)) {
            val config = TunnelConfig(
                id = "tun-busy-$port", label = "busy-$port", hostId = testHost.id, type = TunnelType.LOCAL_FORWARD,
                localPort = port, remoteHost = "127.0.0.1", remotePort = 8080,
            )
            val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(quietClient()) }
            manager.osName = osName
            manager.portProbe = { throw BindException("Permission denied") }
            // Nothing to connect to here: the system keeps no such port, or the port is not below 1024.
            manager.portListens = { throw AssertionError("nobody has to be asked about this port") }

            val result = manager.startTunnel(config) { _ -> testHost }

            assertTrue(result is SshResult.Error, "$osName $port: got $result")
            assertEquals(SshErrorCode.PORT_IN_USE, result.code, "$osName $port")
            assertEquals(getString(Res.string.tunnels_error_port_in_use, port), result.message, "$osName $port")
        }
    }

    @Test
    fun `a port below 1024 that a service holds is a port in use even on a system that reserves such ports`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-service-80", label = "service-80", hostId = testHost.id, type = TunnelType.LOCAL_FORWARD,
            localPort = 80, remoteHost = "127.0.0.1", remotePort = 8080,
        )
        var factoryCalled = false
        val manager = DesktopTunnelManager(scope) { _, _ ->
            factoryCalled = true
            SshResult.Success(quietClient())
        }
        manager.osName = "Linux"
        manager.portProbe = { throw BindException("Address already in use") }
        // Something answers on the port: a service run as root, or with CAP_NET_BIND_SERVICE.
        manager.portListens = { true }

        val result = manager.startTunnel(config) { _ -> testHost }

        assertTrue(result is SshResult.Error, "got $result")
        assertEquals(SshErrorCode.PORT_IN_USE, result.code)
        assertEquals(getString(Res.string.tunnels_error_port_in_use, 80), result.message)
        assertFalse(factoryCalled, "a port in use must not cost an authentication")
    }

    @Test
    fun `an open that fails logs the LogId and the code of the error, never its text`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-open-fails")
        val manager = DesktopTunnelManager(scope) { _, _ ->
            SshResult.Error(SshErrorCode.AUTH_FAILED, "refused for the host gw-prod-01")
        }

        val captured = captureLogs { manager.startTunnel(config) { _ -> testHost } }

        val lines = captured.taggedBy("DesktopTunnelManager")
        val id = LogId.of(config.id)
        assertTrue(lines.any { it.startsWith("W/") && it.contains(id) && it.contains("AUTH_FAILED") }, "got $lines")
        assertTrue(captured.none { it.contains("gw-prod-01") }, "the text of the error leaked: $captured")
        assertNothingScrubbed(lines)
    }

    @Test
    fun `an open that fails because of a Stop logs no warning`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-open-stopped")
        val gate = CompletableDeferred<Unit>()
        // What the factory returns once the Stop closed the socket under the handshake.
        val manager = DesktopTunnelManager(scope) { _, _ ->
            gate.await()
            SshResult.Error(SshErrorCode.UNKNOWN, "socket closed")
        }
        val first = async { manager.startTunnel(config) { _ -> testHost } }

        val captured = captureLogs {
            assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
            gate.complete(Unit)
            assertTrue(first.await() is SshResult.Success, "an explicit stop is a quiet success")
        }

        val id = LogId.of(config.id)
        assertTrue(captured.none { it.startsWith("W/") && it.contains(id) }, "a deliberate Stop was logged as a failure: $captured")
    }

    @Test
    fun `a Stop whose cancel throws still stops the tunnel, answers Success and logs the class, never the message`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-stop-fails")
        val boom = IllegalStateException("exploded at /home/alice/.nextsh")
        val job = mockk<Job>()
        every { job.cancel(any()) } throws boom
        val client = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        manager.registerActive(ActiveTunnel(config = config, client = client, job = job))
        manager.markStarting(config)
        manager.updateStatus(config.id, TunnelStatus.ACTIVE)

        var stopped: SshResult<Unit>? = null
        val captured = captureLogs { stopped = manager.stopTunnel(config.id) }

        // The tunnel IS down: an Error would invite a retry, and a click on a STOPPED card starts the tunnel.
        assertTrue(assertNotNull(stopped) is SshResult.Success, "the tunnel is stopped, got $stopped")
        // The entry is out of `active` : whatever failed, nobody else would ever close it.
        verify(exactly = 1) { client.disconnect() }
        assertFalse(manager.activeIds().contains(config.id))
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState(config.id)?.status, "not ACTIVE over a dead tunnel")
        assertNull(manager.getTunnelState(config.id)?.errorMessage)

        val lines = captured.taggedBy("DesktopTunnelManager")
        assertTrue(lines.any { it.startsWith("E/") && it.contains(LogId.of(config.id)) && it.contains(errorClass(boom)) }, "got $lines")
        // Not ".nextsh" alone: the safe stack trace names the fr.techtical.nextsh classes.
        assertTrue(captured.none { it.contains("alice") || it.contains("exploded") || it.contains("/.nextsh") }, "the message leaked: $captured")
        assertNothingScrubbed(lines)

        // A second Stop finds nothing to close, answers Success, and disconnects nothing more.
        assertTrue(manager.stopTunnel(config.id) is SshResult.Success)
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `a watchdog that cannot be cancelled does not keep the client of a stopped tunnel connected`() = runTest(UnconfinedTestDispatcher()) {
        val config = reservationConfig("tun-stop-watchdog")
        val watchdog = mockk<Job>()
        every { watchdog.cancel(any()) } throws IllegalStateException("exploded")
        val client = mockk<SSHClient>(relaxed = true)
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        manager.registerActive(ActiveTunnel(config = config, client = client, job = Job(), livenessJob = watchdog))

        val result = manager.stopTunnel(config.id)

        assertTrue(result is SshResult.Success, "got $result")
        verify(exactly = 1) { client.disconnect() }
        assertFalse(manager.activeIds().contains(config.id))
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
