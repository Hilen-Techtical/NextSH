// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Stop wins over Start. The user presses Stop while the card says STARTING: the connection goes on
 * (seconds, a minute behind a key prompt), but the start must then register nothing, write no state
 * (neither ACTIVE nor ERROR), give back its reference on the SSH session, and leak nothing (a bound
 * socket, a listening thread, a remote forward, a SOCKS server).
 *
 * The starts are the real `launch*` of [SshTunnelManager] with a mocked SSH client; the stop that
 * lands in the middle of them is made by the mock itself, on the very thread that builds the tunnel.
 */
class SshTunnelManagerStopDuringStartTest {

    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private val release = CountDownLatch(1)
    private val sessions = mockk<SshSessionManager>(relaxed = true)

    @AfterEach
    fun cleanup() {
        release.countDown()
        listenerThreads.forEach { it.interrupt() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun managerWith(client: SSHClient? = mockk(relaxed = true)): SshTunnelManager {
        every { sessions.getClient("s1") } returns client
        return SshTunnelManager(sessionManager = sessions)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun config(
        id: String = "t1",
        type: TunnelType = TunnelType.LOCAL_FORWARD,
        localPort: Int = freePort(),
        remotePort: Int = 8080,
    ) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = remotePort,
    )

    /** A forwarder whose `listen()` blocks until it is interrupted, as the real one does, and notes its thread. */
    private fun blockingForwarder(): LocalPortForwarder {
        val forwarder = mockk<LocalPortForwarder>()
        every { forwarder.listen() } answers {
            listenerThreads += Thread.currentThread()
            try {
                release.await(WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return forwarder
    }

    private fun clientWithForwarder(onBuild: (ServerSocket) -> Unit = {}): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        val forwarder = blockingForwarder()
        every { client.newLocalPortForwarder(any(), any()) } answers {
            val socket = secondArg<ServerSocket>()
            sockets += socket
            onBuild(socket)
            forwarder
        }
        return client
    }

    private fun assertStopped(result: SshResult<Unit>) {
        assertTrue(result is SshResult.Error, "$result")
        assertEquals(TUNNEL_START_STOPPED_MESSAGE, (result as SshResult.Error).message)
    }

    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun SshTunnelManager.stopNow(id: String) = runBlocking { stopTunnel(id) }

    /**
     * After a start faded out, the next start of the same tunnel, with another row, is not mistaken
     * for a replaced one: a failure of it that comes before its `markStarting` (a port that cannot
     * be used, a host that is gone) is written and told as usual. The faded start is finished, its
     * `stopped` kept; left in flight it was read as "a more recent start is on" and silenced them.
     */
    private fun assertLaterStartsAreHeard(manager: SshTunnelManager, faded: TunnelConfig) {
        assertTrue(manager.isStartAbandoned(faded), "the faded start is still known as abandoned")
        val later = config(id = faded.id, localPort = 80)

        val refusal = manager.refuseInvalidPorts(later)

        assertNotNull(refusal)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(faded.id)?.status)
        assertEquals(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, manager.getTunnelState(faded.id)?.errorMessage)
        assertFalse(manager.isStartAbandoned(later), "and the error of that start is told")
        val hostGone = config(id = faded.id)
        manager.markError(hostGone, TUNNEL_HOST_NOT_FOUND_MESSAGE)
        assertEquals(TUNNEL_HOST_NOT_FOUND_MESSAGE, manager.getTunnelState(faded.id)?.errorMessage)
    }

    // ── The stop comes while the connection is still on ───────────────────────

    @Test
    fun `a local start that goes on after the stop registers nothing, writes nothing and gives its reference back`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val cfg = config()

        manager.markStarting(cfg)
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.stopNow(cfg.id) is SshResult.Success)
        assertNull(manager.getTunnelState(cfg.id), "the card is gone")

        // The connection ends and the start goes on.
        val result = manager.startLocalForward(cfg, sessionId = "s1")

        assertStopped(result)
        assertTrue(manager.activeTunnels.value.isEmpty())
        assertNull(manager.getTunnelState(cfg.id), "neither ACTIVE nor ERROR comes back")
        verify(exactly = 1) { sessions.release("s1") }
        // Nothing was opened: the port is still free.
        ServerSocket(cfg.localPort).close()
        verify(exactly = 0) { client.newLocalPortForwarder(any(), any()) }
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "and the reconnection leaves it alone")
        assertLaterStartsAreHeard(manager, cfg)
    }

    @Test
    fun `a stop that lands while a local start builds its listener makes the start undo it`() = runTest {
        lateinit var manager: SshTunnelManager
        val cfg = config()
        val client = clientWithForwarder { manager.stopNow(cfg.id) } // the stop lands after every check of the start
        manager = managerWith(client)

        manager.markStarting(cfg)
        val result = manager.startLocalForward(cfg, sessionId = "s1")

        assertStopped(result)
        assertTrue(manager.activeTunnels.value.isEmpty())
        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(sockets.single().isClosed, "the socket is no longer bound to the port")
        assertTrue(awaitUntil { listenerThreads.size == 1 }, "the listening thread ran")
        val thread = listenerThreads.single()
        assertTrue(awaitUntil { !thread.isAlive }, "the listening thread ended")
        verify(exactly = 1) { sessions.release("s1") }
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "the end of the listener is not taken for a cut")
        ServerSocket(cfg.localPort).close()
        assertLaterStartsAreHeard(manager, cfg)
    }

    @Test
    fun `a stop that lands while a remote start binds makes the start cancel the remote forward`() = runTest {
        lateinit var manager: SshTunnelManager
        val cfg = config(type = TunnelType.REMOTE_FORWARD)
        val forwarder = mockk<RemotePortForwarder>(relaxed = true)
        every { forwarder.bind(any(), any()) } answers {
            manager.stopNow(cfg.id)
            firstArg<RemotePortForwarder.Forward>()
        }
        val client = mockk<SSHClient>(relaxed = true)
        every { client.remotePortForwarder } returns forwarder
        manager = managerWith(client)

        manager.markStarting(cfg)
        val result = manager.startRemoteForward(cfg, sessionId = "s1")

        assertStopped(result)
        assertTrue(manager.activeTunnels.value.isEmpty())
        assertNull(manager.getTunnelState(cfg.id))
        // The server was already listening: without the cancel it would keep the port.
        verify(exactly = 1) { forwarder.cancel(any<RemotePortForwarder.Forward>()) }
        verify(exactly = 1) { sessions.release("s1") }
    }

    @Test
    fun `a remote start and a SOCKS start that go on after the stop open nothing`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        val manager = managerWith(client)
        val remote = config(id = "r", type = TunnelType.REMOTE_FORWARD)
        val socks = config(id = "s", type = TunnelType.DYNAMIC_SOCKS5)
        manager.markStarting(remote)
        manager.markStarting(socks)
        manager.stopNow(remote.id)
        manager.stopNow(socks.id)

        val remoteResult = manager.startRemoteForward(remote, sessionId = "s1")
        val socksResult = manager.startDynamicForward(socks, sessionId = "s1")

        assertStopped(remoteResult)
        assertStopped(socksResult)
        verify(exactly = 0) { client.remotePortForwarder }
        ServerSocket(socks.localPort).close()
        assertTrue(manager.tunnelStates.value.isEmpty())
        verify(exactly = 2) { sessions.release("s1") }
    }

    @Test
    fun `activating a start that the stop beat stops the server it built, one that nobody stopped is kept`() {
        val manager = managerWith()
        val stopped = config(id = "stopped", type = TunnelType.DYNAMIC_SOCKS5)
        val kept = config(id = "kept", type = TunnelType.DYNAMIC_SOCKS5)
        val stoppedServer = mockk<Socks5Server>(relaxed = true)
        val keptServer = mockk<Socks5Server>(relaxed = true)
        manager.markStarting(stopped)
        manager.markStarting(kept)
        manager.stopNow(stopped.id)

        val first = manager.activateOrUndo(
            ActiveTunnel(config = stopped, sessionId = "s1", instanceId = 1, socks5Server = stoppedServer),
            undo = { stoppedServer.stop() },
        )
        val second = manager.activateOrUndo(
            ActiveTunnel(config = kept, sessionId = "s1", instanceId = 2, socks5Server = keptServer),
            undo = { keptServer.stop() },
        )

        assertFalse(first)
        verify(exactly = 1) { stoppedServer.stop() }
        assertTrue(second)
        verify(exactly = 0) { keptServer.stop() }
        assertEquals(setOf("kept"), manager.activeTunnels.value.keys)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("kept")?.status)
        assertNull(manager.getTunnelState("stopped"))
    }

    // ── The start that fails after the stop does not bring the card back ──────

    @Test
    fun `a connection that fails after the stop does not put the card back in error`() {
        val manager = managerWith()
        val cfg = config()
        manager.markStarting(cfg)
        manager.stopNow(cfg.id)

        manager.markError(cfg, "Authentication failed") // what the shared start does when the connection fails

        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(manager.isStartAbandoned(cfg), "the caller knows there is nothing to tell")
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `a failure of a start nobody retained is written as it always was`() {
        val manager = managerWith()
        val cfg = config()

        manager.markError(cfg, "reason") // no markStarting: the auto-start marking the other tunnels of a refused host

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("reason", manager.getTunnelState(cfg.id)?.errorMessage)
        assertFalse(manager.isStartAbandoned(cfg))
    }

    @Test
    fun `an ordinary failure of the start is written and is not reported as an abandoned start`() {
        val manager = managerWith()
        val cfg = config()
        manager.markStarting(cfg)

        manager.markError(cfg, "Authentication failed")

        assertEquals("Authentication failed", manager.getTunnelState(cfg.id)?.errorMessage)
        assertFalse(manager.isStartAbandoned(cfg))
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a failure that the launch meets after a stop that landed in the middle of it writes nothing`() = runTest {
        lateinit var manager: SshTunnelManager
        val cfg = config()
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } answers {
            manager.stopNow(cfg.id)
            throw java.io.IOException("boom")
        }
        manager = managerWith(client)
        manager.markStarting(cfg)

        val result = manager.startLocalForward(cfg, sessionId = "s1")

        assertStopped(result)
        assertNull(manager.getTunnelState(cfg.id), "the card of a tunnel the user stopped does not come back in error")
        verify(exactly = 1) { sessions.release("s1") }
        ServerSocket(cfg.localPort).close()
        assertLaterStartsAreHeard(manager, cfg)
    }

    // ── A session that is gone before the start ───────────────────────────────

    @Test
    fun `a session that is gone before the start puts the card in error, whatever the type`() = runTest {
        val manager = managerWith(client = null)
        for (type in TunnelType.values()) {
            val cfg = config(id = type.name, type = type)
            manager.markStarting(cfg)

            val result = when (type) {
                TunnelType.LOCAL_FORWARD -> manager.startLocalForward(cfg, "s1")
                TunnelType.REMOTE_FORWARD -> manager.startRemoteForward(cfg, "s1")
                TunnelType.DYNAMIC_SOCKS5 -> manager.startDynamicForward(cfg, "s1")
            }

            assertEquals(SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_SESSION_NOT_FOUND_MESSAGE), result, cfg.id)
            // The card, not only the result: it used to stay in STARTING for ever, out of the reconnection.
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status, cfg.id)
            assertEquals(TUNNEL_SESSION_NOT_FOUND_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage, cfg.id)
        }
        assertEquals(TunnelType.values().map { it.name }.toSet(), manager.getTunnelsForReconnect().map { it.id }.toSet(), "retryable")
        verify(exactly = 3) { sessions.release("s1") }
    }

    @Test
    fun `a session that is gone writes nothing for a start that was stopped or replaced`() = runTest {
        val manager = managerWith(client = null)
        val stopped = config(id = "stopped")
        manager.markStarting(stopped)
        manager.stopNow(stopped.id)
        val older = config(id = "replaced")
        val newer = older.copy()
        manager.markStarting(older)
        manager.markStarting(newer)

        assertStopped(manager.startLocalForward(stopped, sessionId = "s1"))
        assertStopped(manager.startLocalForward(older, sessionId = "s1"))

        assertNull(manager.getTunnelState("stopped"), "the card stays closed")
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState("replaced")?.status, "the more recent start goes on")
        verify(exactly = 2) { sessions.release("s1") }
    }

    // ── What a faded start says ───────────────────────────────────────────────

    @Test
    fun `the result of a start that faded out says nothing even when its replacement has already finished`() = runTest {
        val manager = managerWith(clientWithForwarder())
        val older = config()
        val newer = older.copy()
        manager.markStarting(older)
        manager.stopNow(older.id)
        manager.markStarting(newer)
        assertTrue(manager.startLocalForward(newer, sessionId = "s1") is SshResult.Success) // the replacement ran first

        val fadedResult = manager.startLocalForward(older, sessionId = "s1")

        assertStopped(fadedResult)
        // The manager can no longer tell which of the two is the more recent...
        assertFalse(manager.isStartAbandoned(older))
        // ...but the result of a start that faded out says it by itself.
        assertTrue(manager.isStartWithdrawn(older, fadedResult as SshResult.Error))
        assertFalse(manager.isStartWithdrawn(older, SshResult.Error(SshErrorCode.UNKNOWN, "Authentication failed")))
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(older.id)?.status)
        manager.stopNow(older.id)
    }

    @Test
    fun `a failure of a connection after the stop is withdrawn by the manager, an ordinary one is not`() {
        val manager = managerWith()
        val cfg = config()
        manager.markStarting(cfg)
        val failure = SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentication failed")
        assertFalse(manager.isStartWithdrawn(cfg, failure))

        manager.stopNow(cfg.id)

        assertTrue(manager.isStartWithdrawn(cfg, failure))
    }

    // ── Stop and start again: the most recent start alone succeeds ────────────

    @Test
    fun `stopped then started again during the first connection, the first start fades and the second succeeds`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val first = config()
        val second = first.copy() // another row instance: the user starts the tunnel again

        manager.markStarting(first)
        manager.stopNow(first.id)
        manager.markStarting(second)

        // The first connection ends late: it must write nothing over the second start.
        val firstResult = manager.startLocalForward(first, sessionId = "s1")
        assertStopped(firstResult)
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(first.id)?.status)
        assertSame(second, manager.getTunnelState(first.id)?.config)
        assertTrue(manager.isStartAbandoned(first))
        verify(exactly = 1) { sessions.release("s1") }

        val secondResult = manager.startLocalForward(second, sessionId = "s1")
        assertTrue(secondResult is SshResult.Success, "$secondResult")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(first.id)?.status)
        assertSame(second, manager.activeTunnels.value.getValue(first.id).config)
        verify(exactly = 1) { sessions.release("s1") } // the running tunnel keeps its reference
        assertFalse(manager.isStartAbandoned(second))

        manager.stopNow(first.id)
    }

    @Test
    fun `a start replaced by a more recent one that is still on is told so, and writes nothing`() {
        val manager = managerWith()
        val older = config()
        val newer = older.copy()
        manager.markStarting(older)
        manager.markStarting(newer)

        manager.markError(older, "late failure of the older connection")

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(older.id)?.status)
        assertNull(manager.getTunnelState(older.id)?.errorMessage)
        assertTrue(manager.isStartAbandoned(older))
        assertFalse(manager.isStartAbandoned(newer))
    }

    @Test
    fun `a late failure of an older start does not mask the tunnel a more recent start brought up`() {
        val manager = managerWith()
        val older = config()
        val newer = older.copy()
        manager.markStarting(older)
        manager.markStarting(newer)
        manager.injectActiveForTest(newer, sessionId = "s1")

        manager.markError(older, "late failure of the older connection")

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(older.id)?.status)
    }

    // ── Stop everything ───────────────────────────────────────────────────────

    @Test
    fun `stopping all tunnels during a start stops that start too`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val running = config(id = "running", type = TunnelType.DYNAMIC_SOCKS5)
        val starting = config(id = "starting")
        manager.markStarting(running)
        manager.injectActiveForTest(running, sessionId = "s2")
        manager.markStarting(starting)

        manager.stopAllTunnels()

        assertTrue(manager.tunnelStates.value.isEmpty(), "every card is gone, the one in STARTING too")
        val result = manager.startLocalForward(starting, sessionId = "s1")
        assertStopped(result)
        assertTrue(manager.activeTunnels.value.isEmpty())
        assertNull(manager.getTunnelState(starting.id))
        verify(exactly = 1) { sessions.release("s1") }
        verify(exactly = 1) { sessions.release("s2") }
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        // The failure of the connection, if that is how it ended, writes nothing either.
        manager.markError(starting, "Authentication failed")
        assertNull(manager.getTunnelState(starting.id))
    }

    @Test
    fun `a start made after the stop of all tunnels goes through`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val old = config()
        manager.markStarting(old)
        manager.stopAllTunnels()
        val again = config()

        manager.markStarting(again)
        val result = manager.startLocalForward(again, sessionId = "s1")

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(again.id)?.status)
        manager.stopNow(again.id)
    }

    @Test
    fun `stopping a tunnel that is only reserved removes it and is a success`() {
        val manager = managerWith()
        val cfg = config()
        // Reserved by the auto-start: no state yet (the shared start has not marked it STARTING).
        assertTrue(manager.reserveAutoStart(cfg))

        val result = manager.stopNow(cfg.id)

        assertTrue(result is SshResult.Success, "$result")
        assertTrue(manager.isStartAbandoned(cfg))
    }

    // ── A manual start lifts the exclusions, as it always did ─────────────────

    @Test
    fun `a stopped start that fades does not lift the manual stop, a manual start does`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val first = config()
        manager.markStarting(first)
        manager.stopNow(first.id)
        assertStopped(manager.startLocalForward(first, sessionId = "s1"))

        // The stop still holds: a cut of this tunnel is not taken for a failure to reconnect from
        // (the detection of a failure ignores a tunnel stopped by hand).
        val probe = config()
        manager.injectActiveForTest(probe, sessionId = "s9")
        manager.simulateUnexpectedStopForTest(probe.id, "cut")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(probe.id)?.status, "the faded start did not lift the stop")

        val byHand = config()
        manager.markStarting(byHand)
        manager.injectActiveForTest(byHand, sessionId = "s9")
        manager.simulateUnexpectedStopForTest(byHand.id, "cut")

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(byHand.id)?.status, "lifted by the manual start")
        assertEquals(listOf(byHand.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a manual start lifts the hold that a failure had put on the tunnel`() {
        val manager = managerWith()
        val cfg = config()
        manager.markStarting(cfg)
        manager.markError(cfg, "Authentication failed")
        assertTrue(manager.holdReconnect(cfg.id, "Authentication failed"))
        assertTrue(manager.getTunnelsForReconnect().isEmpty())

        val again = cfg.copy()
        manager.markStarting(again)
        manager.markError(again, "unreachable")

        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    // ── A start reserved by the reconnection or the auto-start ────────────────

    @Test
    fun `the reconnection reserves a tunnel that waits for it, not one that was stopped, held or started`() {
        val manager = managerWith()
        val waiting = config(id = "waiting")
        manager.markStarting(waiting)
        manager.markError(waiting, "Connexion SSH perdue")
        assertTrue(manager.reserveReconnectStart(waiting))

        val stopped = config(id = "stopped")
        manager.markStarting(stopped)
        manager.markError(stopped, "x")
        manager.stopNow(stopped.id)
        assertFalse(manager.reserveReconnectStart(stopped), "stopped by hand")

        val held = config(id = "held")
        manager.markStarting(held)
        manager.markError(held, "x")
        manager.holdReconnect(held.id, "x")
        assertFalse(manager.reserveReconnectStart(held), "held")

        val starting = config(id = "starting")
        manager.markStarting(starting)
        assertFalse(manager.reserveReconnectStart(starting), "someone is already starting it")
        assertFalse(manager.reserveReconnectStart(config(id = "unknown")), "unknown")
    }

    @Test
    fun `the auto-start does not reserve a tunnel that runs or is on its way`() {
        val manager = managerWith()
        for ((index, status) in listOf(TunnelStatus.ACTIVE, TunnelStatus.STARTING, TunnelStatus.RECONNECTING).withIndex()) {
            val cfg = config(id = "t$index")
            manager.markStarting(cfg)
            if (status == TunnelStatus.ACTIVE) manager.injectActiveForTest(cfg, sessionId = "s1")
            if (status == TunnelStatus.RECONNECTING) manager.updateTunnelStatus(cfg.id, TunnelStatus.RECONNECTING)
            assertFalse(manager.reserveAutoStart(cfg), "$status")
        }
        assertTrue(manager.reserveAutoStart(config(id = "stopped")), "a tunnel that is not running is reserved")
        val failed = config(id = "failed")
        manager.markStarting(failed)
        manager.markError(failed, "x")
        assertTrue(manager.reserveAutoStart(failed), "a tunnel in error is reserved")
    }

    @Test
    fun `a reserved start that a stop caught writes nothing and lifts nothing when the shared start announces it`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val cfg = config()
        manager.markStarting(cfg)
        manager.markError(cfg, "Connexion SSH perdue")
        val attempt = cfg.copy() // the row the reconnection reads and hands to the shared start
        assertTrue(manager.reserveReconnectStart(attempt))

        manager.stopNow(cfg.id) // lands while the shared start reads the host
        manager.markStarting(attempt) // then the shared start marks STARTING, as it does

        assertNull(manager.getTunnelState(cfg.id), "no resurrection of the card")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "the manual stop is not lifted")
        assertTrue(manager.isStartAbandoned(attempt))
        assertStopped(manager.startLocalForward(attempt, sessionId = "s1"))
        assertNull(manager.getTunnelState(cfg.id))
        manager.markError(attempt, "Authentication failed")
        assertNull(manager.getTunnelState(cfg.id))
    }

    @Test
    fun `a reserved start that nobody stopped goes on and lifts the exclusions`() = runTest {
        val client = clientWithForwarder()
        val manager = managerWith(client)
        val cfg = config()
        manager.markStarting(cfg)
        manager.markError(cfg, "Connexion SSH perdue")
        val attempt = cfg.copy()
        assertTrue(manager.reserveReconnectStart(attempt))

        manager.markStarting(attempt)
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        val result = manager.startLocalForward(attempt, sessionId = "s1")

        assertTrue(result is SshResult.Success, "$result")
        assertSame(attempt, manager.activeTunnels.value.getValue(cfg.id).config)
        manager.stopNow(cfg.id)
    }

    // ── Atomicity of the stop against the activation ──────────────────────────

    @Test
    fun `a stop and an activation that race leave either a running tunnel with its card or nothing at all`() {
        val manager = managerWith()
        val rounds = 1_500
        var orphans = 0
        repeat(rounds) { round ->
            val cfg = config(id = "t$round", type = TunnelType.DYNAMIC_SOCKS5)
            val server = mockk<Socks5Server>(relaxed = true)
            manager.markStarting(cfg)
            val go = CountDownLatch(1)
            val activator = thread {
                go.await()
                manager.activateOrUndo(
                    ActiveTunnel(config = cfg, sessionId = "s1", instanceId = round.toLong(), socks5Server = server),
                    undo = { server.stop() },
                )
            }
            val stopper = thread {
                go.await()
                manager.stopNow(cfg.id)
            }
            go.countDown()
            activator.join(WAIT_MS)
            stopper.join(WAIT_MS)

            val active = manager.activeTunnels.value.containsKey(cfg.id)
            val card = manager.getTunnelState(cfg.id) != null
            // Whichever won: the tunnel is stopped, with no card, or it was activated and then stopped by the stop.
            // A tunnel left active (with or without a card) is the defect.
            if (active || card) orphans++
        }
        assertEquals(0, orphans, "a stop that raced the activation of a start left a tunnel behind")
    }

    // ── Ports that cannot be used ─────────────────────────────────────────────

    @Test
    fun `a tunnel with a port that cannot be used goes to error, held, with a text that names no port`() {
        val manager = managerWith()
        val cases = listOf(
            config(id = "a", localPort = 70_000) to TUNNEL_LOCAL_PORT_INVALID_MESSAGE,
            config(id = "b", localPort = 80) to TUNNEL_LOCAL_PORT_RESERVED_MESSAGE,
            config(id = "c", type = TunnelType.DYNAMIC_SOCKS5, localPort = 443) to TUNNEL_LOCAL_PORT_RESERVED_MESSAGE,
            config(id = "d", type = TunnelType.REMOTE_FORWARD, localPort = 8080, remotePort = 70_000) to TUNNEL_REMOTE_PORT_INVALID_MESSAGE,
            config(id = "e", type = TunnelType.REMOTE_FORWARD, localPort = 0) to TUNNEL_LOCAL_PORT_INVALID_MESSAGE,
        )
        for ((cfg, message) in cases) {
            manager.markStarting(cfg)

            val refusal = manager.refuseInvalidPorts(cfg)

            assertEquals(SshResult.Error(SshErrorCode.UNKNOWN, message), refusal, cfg.id)
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status, cfg.id)
            assertEquals(message, manager.getTunnelState(cfg.id)?.errorMessage, cfg.id)
            assertTrue(manager.isReconnectHeld(cfg.id), "${cfg.id}: a port does not mend by itself")
            assertTrue(manager.getTunnelsForReconnect().none { it.id == cfg.id }, cfg.id)
            assertNotNullRes(message)
            assertFalse(message.any { it.isDigit() }, "$message names a port")
        }
    }

    @Test
    fun `a tunnel whose ports can be used is not refused`() {
        val manager = managerWith()
        listOf(
            config(type = TunnelType.LOCAL_FORWARD, localPort = 1024),
            config(type = TunnelType.DYNAMIC_SOCKS5, localPort = 65535),
            config(type = TunnelType.REMOTE_FORWARD, localPort = 80, remotePort = 22),
        ).forEach { assertNull(manager.refuseInvalidPorts(it)) }
        assertTrue(manager.tunnelStates.value.isEmpty())
    }

    @Test
    fun `a manual start lifts the hold of a refused port, as it lifts any other`() {
        val manager = managerWith()
        val cfg = config(localPort = 80)
        manager.markStarting(cfg)
        manager.refuseInvalidPorts(cfg)
        assertTrue(manager.isReconnectHeld(cfg.id))

        manager.markStarting(cfg.copy(localPort = 8080))

        assertFalse(manager.isReconnectHeld(cfg.id))
    }

    @Test
    fun `a refusal of a port for a start that a stop caught writes nothing`() {
        val manager = managerWith()
        val cfg = config(localPort = 80)
        assertTrue(manager.reserveAutoStart(cfg))
        manager.stopNow(cfg.id)

        manager.refuseInvalidPorts(cfg)

        assertNull(manager.getTunnelState(cfg.id))
        assertFalse(manager.isReconnectHeld(cfg.id))
        assertTrue(manager.isStartAbandoned(cfg))
    }

    @Test
    fun `the starts refuse a port that cannot be used instead of crashing on it, and open no connection`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        val manager = managerWith(client)

        val local = config(id = "a", localPort = 70_000)
        val socks = config(id = "b", type = TunnelType.DYNAMIC_SOCKS5, localPort = 99)
        val remote = config(id = "c", type = TunnelType.REMOTE_FORWARD, remotePort = -4)
        val results = listOf(
            local.also(manager::markStarting).let { manager.startLocalForward(it, "s1") },
            socks.also(manager::markStarting).let { manager.startDynamicForward(it, "s1") },
            remote.also(manager::markStarting).let { manager.startRemoteForward(it, "s1") },
        )

        assertEquals(TUNNEL_LOCAL_PORT_INVALID_MESSAGE, (results[0] as SshResult.Error).message)
        assertEquals(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, (results[1] as SshResult.Error).message)
        assertEquals(TUNNEL_REMOTE_PORT_INVALID_MESSAGE, (results[2] as SshResult.Error).message)
        verify(exactly = 0) { client.newLocalPortForwarder(any(), any()) }
        verify(exactly = 0) { client.remotePortForwarder }
        listOf("a", "b", "c").forEach { assertTrue(manager.isReconnectHeld(it), it) }
        verify(exactly = 3) { sessions.release("s1") }
    }

    private fun assertNotNullRes(message: String) {
        assertTrue(tunnelMessageRes(message) != null, "$message has no string resource")
    }

    // ── What the start says when the local port is taken at the last moment ───

    @Test
    fun `a bind that fails because the port was just taken is the port in use, and the socket is released`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } throws java.net.BindException("Address already in use")
        val manager = managerWith(client)
        val cfg = config()
        manager.markStarting(cfg)

        val result = manager.startLocalForward(cfg, sessionId = "s1")

        assertEquals(SshResult.Error(SshErrorCode.PORT_IN_USE, TUNNEL_PORT_IN_USE_MESSAGE), result)
        assertEquals(TUNNEL_PORT_IN_USE_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        // Retryable: the other application may let the port go.
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
        verify(exactly = 1) { sessions.release("s1") }
        ServerSocket(cfg.localPort).close()
    }

    // ── What the start says when the server refuses a remote forward ──────────

    @Test
    fun `a remote forward that the server refuses says so, and stays retryable`() = runTest {
        val refusal = net.schmizz.sshj.connection.ConnectionException("Global request [global req for tcpip-forward] failed")
        val forwarder = mockk<RemotePortForwarder>(relaxed = true)
        every { forwarder.bind(any(), any()) } throws refusal
        val client = mockk<SSHClient>(relaxed = true)
        every { client.remotePortForwarder } returns forwarder
        every { client.isConnected } returns true
        val manager = managerWith(client)
        val cfg = config(type = TunnelType.REMOTE_FORWARD)
        manager.markStarting(cfg)

        val result = manager.startRemoteForward(cfg, sessionId = "s1")

        assertEquals(SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE), result)
        assertEquals(TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "the server may free the port of a cut session")
    }

    @Test
    fun `a remote forward that times out or loses the transport keeps the generic text`() = runTest {
        val timeout = net.schmizz.sshj.connection.ConnectionException(java.util.concurrent.TimeoutException("Timeout expired: 30000 MILLISECONDS"))
        val lost = net.schmizz.sshj.connection.ConnectionException(net.schmizz.sshj.transport.TransportException("Broken transport"))
        for ((index, failure) in listOf(timeout, lost).withIndex()) {
            val forwarder = mockk<RemotePortForwarder>(relaxed = true)
            every { forwarder.bind(any(), any()) } throws failure
            val client = mockk<SSHClient>(relaxed = true)
            every { client.remotePortForwarder } returns forwarder
            every { client.isConnected } returns (index == 0) // a timeout leaves the connection up, a lost transport does not
            val manager = managerWith(client)
            val cfg = config(type = TunnelType.REMOTE_FORWARD)
            manager.markStarting(cfg)

            val result = manager.startRemoteForward(cfg, sessionId = "s1")

            assertEquals(TUNNEL_START_FAILED_MESSAGE, (result as SshResult.Error).message, "case $index")
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
