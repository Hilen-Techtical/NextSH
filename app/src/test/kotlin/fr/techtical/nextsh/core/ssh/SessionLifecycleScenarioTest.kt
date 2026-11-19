// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.usecase.StartTunnelUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The users of a session together, on a real [SshSessionManager] and its fake links: who keeps a
 * session open, and what closes it. Each test is a story of the app (a tab, a tunnel, a transfer)
 * rather than a rule of one class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleScenarioTest {

    private val harness = SessionHarness()
    private val manager = harness.manager

    private val window = 1_000L

    private fun TestScope.newHandoff() =
        SessionHandoff(
            manager, CoroutineScope(StandardTestDispatcher(testScheduler)), window, AppVisibility { true },
            SessionHandoff.HIDDEN_CAP_MS,
        )

    // ── A transfer outlives the tab and the browser that started it ───────────

    private val sftpManager = SftpManager(manager)

    /** The SFTP channel the session [sessionId] opens. */
    private fun sftpChannelOf(sessionId: String): SFTPClient =
        mockk<SFTPClient>(relaxed = true).also {
            every { harness.linkOf(sessionId).client.newSFTPClient() } returns it
        }

    @Test
    fun `a transfer survives the close of the tab and the exit of the browser`() = runTest {
        // The tab connects: it owns the first reference. The browser opens over it, with its own.
        val tab = harness.connect()
        val sftp = sftpChannelOf(tab.id)
        assertTrue(manager.acquire(tab.id))
        assertTrue(sftpManager.openSftp(tab.id) is SshResult.Success)
        // The user starts a download: the service reserves the session for the transfer.
        val reservation = (sftpManager.reserve(tab.id) as SshResult.Success).data

        manager.release(tab.id) // the tab is closed
        assertTrue(harness.isRegistered(tab.id), "the transfer and the browser still hold the session")

        sftpManager.closeSftp(tab.id) // the browser is left...
        manager.release(tab.id)
        assertTrue(harness.isRegistered(tab.id), "...and the transfer still holds it")
        assertTrue(sftpManager.isOpen(tab.id), "its SFTP client is the transfer's now")
        verify(exactly = 0) { sftp.close() }
        assertEquals(0, harness.closedLinks())

        reservation.release() // the transfer is over

        assertFalse(sftpManager.isOpen(tab.id))
        verify(exactly = 1) { sftp.close() }
        assertFalse(harness.isRegistered(tab.id), "the last holder closes the session")
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `two queued transfers of one session keep it open between them when nobody else holds it`() = runTest {
        // What the service does for a drain: one reservation per session, kept while a request waits.
        val reservations = SftpReservations(sftpManager)
        val tab = harness.connect()
        val sftp = sftpChannelOf(tab.id)
        assertTrue(manager.acquire(tab.id)) // the browser
        assertTrue(sftpManager.openSftp(tab.id) is SshResult.Success)

        // Transfer 1 starts and reserves the session...
        assertTrue(reservations.obtain(tab.id) is SshResult.Success)
        // ...then the last others leave: the browser, and the tab.
        sftpManager.closeSftp(tab.id)
        manager.release(tab.id)
        manager.release(tab.id)
        // Transfer 1 ends with transfer 2 waiting for its turn: the reservation stays.
        reservations.releaseIdle(setOf(tab.id))

        assertTrue(harness.isRegistered(tab.id), "the second transfer finds its session")
        assertTrue(sftpManager.isOpen(tab.id))
        // Transfer 2 uses the same reservation, it takes no second one.
        assertTrue(reservations.obtain(tab.id) is SshResult.Success)
        verify(exactly = 0) { sftp.close() }
        // It ends with nothing queued for the session: both are given back, the session closes.
        reservations.releaseIdle(emptySet())

        assertFalse(sftpManager.isOpen(tab.id))
        verify(exactly = 1) { sftp.close() }
        assertFalse(harness.isRegistered(tab.id))
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `the link that dies between two queued transfers fails the next one before it touches its file`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val tab = harness.connect()
        sftpChannelOf(tab.id)
        // Transfer 1 reserves the session; the others leave; transfer 2 and 3 wait in the queue.
        assertTrue(reservations.obtain(tab.id) is SshResult.Success)
        manager.release(tab.id)
        reservations.releaseIdle(setOf(tab.id))

        // The Wi-Fi drops during transfer 1: the session, and its SFTP client, are gone.
        harness.linkOf(tab.id).cut()

        // What the service does for transfer 2: ask for the reservation, and only then open the file.
        var destinationOpened = false
        val second = reservations.obtain(tab.id)
        if (second is SshResult.Success) destinationOpened = true

        assertTrue(second is SshResult.Error, "the stale reservation is not handed out again")
        assertFalse(destinationOpened, "no local file was truncated for a transfer that cannot run")
        // And the next ones fail the same way, with nothing left to give back.
        assertTrue(reservations.obtain(tab.id) is SshResult.Error)
        reservations.releaseAll()
        assertFalse(harness.isRegistered(tab.id))
    }

    @Test
    fun `a session with nothing queued is released after any transfer, not only after its own`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val first = harness.connect()
        sftpChannelOf(first.id)
        val second = harness.connect()
        sftpChannelOf(second.id)
        // Queue [S1-a, S2-b, S1-c]: both sessions are reserved by the time S2-b runs.
        assertTrue(reservations.obtain(first.id) is SshResult.Success)
        assertTrue(reservations.obtain(second.id) is SshResult.Success)
        manager.release(first.id)
        manager.release(second.id)

        // S1-c is cancelled while S2-b runs; S2-b ends with nothing left for S1.
        reservations.releaseIdle(emptySet())

        assertFalse(harness.isRegistered(first.id), "S1 does not wait for the end of the drain")
        assertFalse(harness.isRegistered(second.id))
        assertEquals(2, harness.closedLinks())
    }

    @Test
    fun `a session that something still queues is kept, the others are released`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val first = harness.connect()
        sftpChannelOf(first.id)
        val second = harness.connect()
        sftpChannelOf(second.id)
        assertTrue(reservations.obtain(first.id) is SshResult.Success)
        assertTrue(reservations.obtain(second.id) is SshResult.Success)
        manager.release(first.id)
        manager.release(second.id)

        reservations.releaseIdle(setOf(second.id))

        assertFalse(harness.isRegistered(first.id))
        assertTrue(harness.isRegistered(second.id), "S2 still has a request waiting")
        reservations.releaseAll()
        assertFalse(harness.isRegistered(second.id))
    }

    @Test
    fun `the reservations are given back even when the coroutine that releases them is cancelled`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val tab = harness.connect()
        sftpChannelOf(tab.id)
        assertTrue(reservations.obtain(tab.id) is SshResult.Success)
        manager.release(tab.id)

        // A drain cancelled (service destroyed) while it sweeps: the reservation must not be lost.
        val sweeping = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel()
            reservations.releaseIdle(emptySet())
        }
        sweeping.join()

        assertFalse(harness.isRegistered(tab.id), "taken out of the map and given back, not dropped")
    }

    @Test
    fun `the end of a drain gives back every session it still holds, each once`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val first = harness.connect()
        sftpChannelOf(first.id)
        val second = harness.connect()
        sftpChannelOf(second.id)
        // Both sessions belong to the transfers alone from here on.
        assertTrue(reservations.obtain(first.id) is SshResult.Success)
        assertTrue(reservations.obtain(second.id) is SshResult.Success)
        manager.release(first.id)
        manager.release(second.id)
        assertTrue(harness.isRegistered(first.id))
        assertTrue(harness.isRegistered(second.id))

        reservations.releaseAll()
        reservations.releaseAll()

        assertFalse(harness.isRegistered(first.id))
        assertFalse(harness.isRegistered(second.id))
        assertEquals(2, harness.closedLinks())
    }

    @Test
    fun `a drain whose session is already gone reserves nothing and holds nothing`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val tab = harness.connect()
        manager.release(tab.id)

        assertTrue(reservations.obtain(tab.id) is SshResult.Error)
        // Nothing to give back, nothing breaks.
        reservations.releaseIdle(emptySet())
        reservations.releaseAll()
    }

    @Test
    fun `a pane that outlives its tab does not keep the session open`() = runTest {
        // The pane borrows the session of the tab: it opens SFTP over it and holds no reference.
        val tab = harness.connect()
        sftpChannelOf(tab.id)
        assertTrue(sftpManager.openSftp(tab.id) is SshResult.Success)

        manager.release(tab.id) // the tab is closed, the pane's ViewModel is still there

        assertFalse(harness.isRegistered(tab.id), "nothing but the tab held the session")
        assertEquals(1, harness.closedLinks())
        assertFalse(sftpManager.isOpen(tab.id), "its SFTP client went with it")
    }

    @Test
    fun `a transfer on a session that is gone reserves nothing`() = runTest {
        val tab = harness.connect()
        manager.release(tab.id)

        val reserved = sftpManager.reserve(tab.id)

        assertTrue(reserved is SshResult.Error)
        assertFalse(sftpManager.isOpen(tab.id))
    }

    @Test
    fun `an SFTP channel that does not open leaves the session as it was`() = runTest {
        val tab = harness.connect()
        every { harness.linkOf(tab.id).client.newSFTPClient() } throws java.io.IOException("channel refused")

        val reserved = sftpManager.reserve(tab.id)

        assertTrue(reserved is SshResult.Error)
        // The reference the reservation took went back: the tab, alone, closes the session.
        assertTrue(harness.isRegistered(tab.id))
        manager.release(tab.id)
        assertFalse(harness.isRegistered(tab.id))
    }

    @Test
    fun `a reservation is given back once, whoever calls it`() = runTest {
        val tab = harness.connect()
        sftpChannelOf(tab.id)
        val reservation = (sftpManager.reserve(tab.id) as SshResult.Success).data

        reservation.release()
        reservation.release()

        // The second call took nothing from the tab, which still owns its reference.
        assertTrue(harness.isRegistered(tab.id))
        manager.release(tab.id)
        assertFalse(harness.isRegistered(tab.id))
    }

    @Test
    fun `a link that dies under a transfer takes the session and the SFTP client, and the release is harmless`() = runTest {
        val tab = harness.connect()
        sftpChannelOf(tab.id)
        val reservation = (sftpManager.reserve(tab.id) as SshResult.Success).data

        harness.linkOf(tab.id).cut()

        assertFalse(harness.isRegistered(tab.id))
        assertFalse(sftpManager.isOpen(tab.id), "a dead session is not handed out as open")
        reservation.release()
        manager.release(tab.id)
        assertFalse(sftpManager.reserve(tab.id) is SshResult.Success)
    }

    // ── Tunnels share the session of a tab, or open their own ────────────────

    private val tunnelManager = SshTunnelManager(manager)
    private val hostRepository = mockk<HostRepository>()
    private val connectSession = mockk<ConnectSessionUseCase>()

    private fun tunnel(id: String, type: TunnelType = TunnelType.REMOTE_FORWARD, localPort: Int = 8080) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = harness.host.id,
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 9090,
    )

    /** The real start of a tunnel: the shared use case, the provider of the app, the tunnel manager. */
    private fun TestScope.newStartTunnel(): StartTunnelUseCase {
        val provider = AndroidTunnelSessionProvider(
            manager, connectSession, StandardTestDispatcher(testScheduler), { lingerOver.await() },
        )
        coEvery { hostRepository.getById(harness.host.id) } returns harness.host
        coEvery { connectSession(harness.host) } coAnswers { SshResult.Success(harness.connect()) }
        return StartTunnelUseCase(tunnelManager, hostRepository, provider)
    }

    private fun isActive(tunnelId: String) = tunnelManager.activeTunnels.value.containsKey(tunnelId)

    /**
     * The linger of the provider ends when a test says so, not with the clock: the test scheduler
     * skips delays whenever the test waits for a real thread (the connection, the tunnel start), which
     * would end the linger in the middle of the story.
     */
    private val lingerOver = kotlinx.coroutines.CompletableDeferred<Unit>()

    private fun TestScope.endLinger() {
        lingerOver.complete(Unit)
        runCurrent()
    }

    @Test
    fun `closing the tab whose session carries a tunnel keeps the tunnel alive`() = runTest {
        val startTunnel = newStartTunnel()
        // The terminal tab connected the host: it owns the first reference.
        val tab = harness.connect()
        val start = async { startTunnel(tunnel("t1")) }
        runCurrent()
        assertEquals(SshResult.Success(Unit), start.await())
        assertTrue(isActive("t1"))

        // The user closes the tab. That used to close the session and end the tunnel in error
        // (connection lost).
        manager.release(tab.id)

        assertTrue(isActive("t1"), "the tunnel holds the session")
        assertTrue(harness.isRegistered(tab.id))
        assertEquals(TunnelStatus.ACTIVE, tunnelManager.getTunnelState("t1")?.status)
        assertEquals(0, harness.closedLinks())
        // Stopping the tunnel is what closes the session now: it was the last holder.
        assertTrue(tunnelManager.stopTunnel("t1") is SshResult.Success)
        assertFalse(harness.isRegistered(tab.id))
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `stopping the last tunnel of a session opened for tunnels closes it`() = runTest {
        val startTunnel = newStartTunnel()
        val first = async { startTunnel(tunnel("t1")) }
        runCurrent()
        assertEquals(SshResult.Success(Unit), first.await())
        val second = async { startTunnel(tunnel("t2")) }
        runCurrent()
        assertEquals(SshResult.Success(Unit), second.await())
        val sessionId = tunnelManager.activeTunnels.value.getValue("t1").sessionId
        assertEquals(sessionId, tunnelManager.activeTunnels.value.getValue("t2").sessionId, "one session carries both")
        // Not one connection for each, and nothing is left holding it but the two tunnels.
        coVerify(exactly = 1) { connectSession(any()) }

        tunnelManager.stopTunnel("t1")
        assertTrue(harness.isRegistered(sessionId), "the other tunnel still needs it")
        tunnelManager.stopTunnel("t2")

        // The attempt that opened it still holds its reference for the linger: stopping the last
        // tunnel closes the session once that is over, not before.
        assertTrue(harness.isRegistered(sessionId), "lingering")
        endLinger()
        assertFalse(harness.isRegistered(sessionId), "no holder left")
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `a tunnel whose start fails does not close the session under the tunnels that follow`() = runTest {
        val startTunnel = newStartTunnel()
        val busy = java.net.ServerSocket(0)
        try {
            // Tunnel A is first in a serial auto-start, and its local port is used.
            val failing = async { startTunnel(tunnel("a", TunnelType.LOCAL_FORWARD, busy.localPort)) }
            runCurrent()
            val failure = failing.await()
            assertEquals(SshErrorCode.PORT_IN_USE, (failure as SshResult.Error).code)
            val sessionId = manager.activeSessions.value.keys.single()
            // Its reference was the last but the attempt's: the session is still there.
            assertTrue(harness.isRegistered(sessionId), "the session did not close under the wave")

            // Tunnel B follows: it reuses that session, with no second connection (no second prompt).
            val next = async { startTunnel(tunnel("b")) }
            runCurrent()
            assertEquals(SshResult.Success(Unit), next.await())
            coVerify(exactly = 1) { connectSession(any()) }
            assertEquals(sessionId, tunnelManager.activeTunnels.value.getValue("b").sessionId)

            // The linger is over: B alone holds the session, and stopping it closes it.
            endLinger()
            assertTrue(harness.isRegistered(sessionId))
            tunnelManager.stopTunnel("b")
            assertFalse(harness.isRegistered(sessionId))
            assertEquals(1, harness.closedLinks())
        } finally {
            busy.close()
        }
    }

    @Test
    fun `a session opened for a start that failed closes once the linger is over if no tunnel follows`() = runTest {
        val startTunnel = newStartTunnel()
        val busy = java.net.ServerSocket(0)
        try {
            val failing = async { startTunnel(tunnel("a", TunnelType.LOCAL_FORWARD, busy.localPort)) }
            runCurrent()
            failing.await()
            val sessionId = manager.activeSessions.value.keys.single()
            assertTrue(harness.isRegistered(sessionId), "lingering")

            endLinger()

            assertFalse(harness.isRegistered(sessionId), "nothing was left holding it")
            assertEquals(1, harness.closedLinks())
        } finally {
            busy.close()
        }
    }

    @Test
    fun `stopping every tunnel closes the sessions opened only for them`() = runTest {
        val startTunnel = newStartTunnel()
        val first = async { startTunnel(tunnel("t1")) }
        runCurrent()
        first.await()
        val second = async { startTunnel(tunnel("t2")) }
        runCurrent()
        second.await()
        val sessionId = tunnelManager.activeTunnels.value.getValue("t1").sessionId

        tunnelManager.stopAllTunnels()

        // Closed with the linger of the attempt that opened it, see the test above.
        endLinger()
        assertFalse(harness.isRegistered(sessionId))
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `a tunnel that ends on its own gives its session back, a tab keeps it`() = runTest {
        val startTunnel = newStartTunnel()
        val tab = harness.connect()
        val start = async { startTunnel(tunnel("t1")) }
        runCurrent()
        start.await()

        // The listening loop of the tunnel ends (not a manual stop): the tunnel is in error.
        tunnelManager.simulateUnexpectedStopForTest("t1", "Tunnel local interrompu")

        assertFalse(isActive("t1"))
        assertTrue(harness.isRegistered(tab.id), "the tab still owns its reference")
        manager.release(tab.id)
        assertFalse(harness.isRegistered(tab.id), "and nothing else holds it, the tunnel gave its reference back")
    }

    @Test
    fun `a link that dies ends the tunnels it carried, and they can be reconnected`() = runTest {
        val startTunnel = newStartTunnel()
        val start = async { startTunnel(tunnel("t1")) }
        runCurrent()
        start.await()
        val sessionId = tunnelManager.activeTunnels.value.getValue("t1").sessionId

        harness.linkOf(sessionId).cut()

        assertFalse(isActive("t1"))
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("t1")?.status)
        assertEquals(TUNNEL_SESSION_LOST_MESSAGE, tunnelManager.getTunnelState("t1")?.errorMessage)
        assertEquals(listOf("t1"), tunnelManager.getTunnelsForReconnect().map { it.id })
        assertFalse(harness.isRegistered(sessionId), "dead sessions do not stay in the registry")
        // The reconnection finds no live session and connects a new one.
        val again = async { startTunnel(tunnel("t1")) }
        runCurrent()
        assertEquals(SshResult.Success(Unit), again.await())
        assertTrue(isActive("t1"))
        coVerify(exactly = 2) { connectSession(any()) }
    }

    @Test
    fun `a lost transport does not ask a dead server to cancel the remote forward, a stop does`() = runTest {
        val tab = harness.connect()
        val forwarder = mockk<RemotePortForwarder>(relaxed = true)
        every { harness.linkOf(tab.id).client.remotePortForwarder } returns forwarder
        assertTrue(manager.acquire(tab.id))
        assertTrue(tunnelManager.startRemoteAnnounced(tunnel("t1"), tab.id) is SshResult.Success)
        assertTrue(manager.acquire(tab.id))
        assertTrue(tunnelManager.startRemoteAnnounced(tunnel("t2"), tab.id) is SshResult.Success)

        // The manual stop of a tunnel on a live transport does the exchange with the server.
        tunnelManager.stopTunnel("t2")
        verify(exactly = 1) { forwarder.cancel(any<RemotePortForwarder.Forward>()) }

        // The link dies: that exchange could only wait for the transport delay and fail.
        harness.linkOf(tab.id).cut()

        verify(exactly = 1) { forwarder.cancel(any<RemotePortForwarder.Forward>()) }
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("t1")?.status)
        assertFalse(isActive("t1"))
    }

    @Test
    fun `a start that fails gives its reference back, whatever the failure`() = runTest {
        val tab = harness.connect()
        val busy = java.net.ServerSocket(0)
        try {
            // Wrong type for the start that is called.
            assertTrue(manager.acquire(tab.id))
            val wrongType = tunnelManager.startRemoteAnnounced(tunnel("t1", TunnelType.LOCAL_FORWARD), tab.id)
            assertTrue(wrongType is SshResult.Error)
            // Local port already used.
            assertTrue(manager.acquire(tab.id))
            val portInUse = tunnelManager.startLocalAnnounced(tunnel("t2", TunnelType.LOCAL_FORWARD, busy.localPort), tab.id)
            assertEquals(SshErrorCode.PORT_IN_USE, (portInUse as SshResult.Error).code)
            // The session is gone.
            val gone = tunnelManager.startDynamicAnnounced(tunnel("t3", TunnelType.DYNAMIC_SOCKS5), "nobody")
            assertTrue(gone is SshResult.Error)
            // The channel refuses the forward.
            every { harness.linkOf(tab.id).client.remotePortForwarder } throws IllegalStateException("refused")
            assertTrue(manager.acquire(tab.id))
            val refused = tunnelManager.startRemoteAnnounced(tunnel("t4"), tab.id)
            assertTrue(refused is SshResult.Error)
        } finally {
            busy.close()
        }

        // Every reference taken for a start came back: the tab, alone, closes the session.
        assertTrue(harness.isRegistered(tab.id))
        manager.release(tab.id)
        assertFalse(harness.isRegistered(tab.id))
    }

    @Test
    fun `a start cancelled before it began gives its reference back`() = runTest {
        val tab = harness.connect()
        assertTrue(manager.acquire(tab.id))

        // withContext throws at its entry in a cancelled coroutine, without running the start.
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel()
            tunnelManager.startRemoteAnnounced(tunnel("t1"), tab.id)
        }
        cancelled.join()

        assertTrue(cancelled.isCancelled)
        assertFalse(isActive("t1"))
        manager.release(tab.id)
        assertFalse(harness.isRegistered(tab.id), "the reference of the cancelled start was not left behind")
    }

    @Test
    fun `a tunnel started again over a running one gives back the reference of the first`() = runTest {
        val tab = harness.connect()
        assertTrue(manager.acquire(tab.id))
        assertTrue(tunnelManager.startRemoteAnnounced(tunnel("t1"), tab.id) is SshResult.Success)
        assertTrue(manager.acquire(tab.id))
        assertTrue(tunnelManager.startRemoteAnnounced(tunnel("t1"), tab.id) is SshResult.Success)

        // One tunnel in the list, one reference for it.
        assertEquals(1, tunnelManager.activeTunnels.value.size)
        manager.release(tab.id)
        assertTrue(harness.isRegistered(tab.id), "the tunnel holds it")
        tunnelManager.stopTunnel("t1")
        assertFalse(harness.isRegistered(tab.id))
    }

    // ── The host list hands its session over ──────────────────────────────────

    @Test
    fun `an SFTP hand-over that nobody claims is released and the session closes`() = runTest {
        val handoff = newHandoff()
        // The list connected for the SFTP browser, then the user left it: the event is dropped.
        val session = harness.connect()
        handoff.offer(session.id)
        runCurrent()
        assertTrue(harness.isRegistered(session.id), "waiting for its receiver")

        advanceUntilIdle()

        assertFalse(harness.isRegistered(session.id))
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `a hand-over that is claimed stays open until its new owner releases it`() = runTest {
        val handoff = newHandoff()
        val session = harness.connect()
        handoff.offer(session.id)
        runCurrent()

        assertTrue(handoff.claim(session.id))
        advanceUntilIdle()
        assertTrue(harness.isRegistered(session.id), "the claimant owns the reference of the list")
        assertEquals(0, harness.closedLinks())

        manager.release(session.id)

        assertFalse(harness.isRegistered(session.id))
        assertEquals(1, harness.closedLinks())
    }

    @Test
    fun `a session that is claimed late finds nothing, and is not held open by an expired offer`() = runTest {
        val handoff = newHandoff()
        val session = harness.connect()
        handoff.offer(session.id)
        advanceTimeBy(window)
        runCurrent()

        assertFalse(handoff.claim(session.id))
        // The receiver then takes its own reference, which fails: the session is gone.
        assertFalse(manager.acquire(session.id))
    }

    @Test
    fun `a hand-over of a session another holder also uses leaves it open when it expires`() = runTest {
        val handoff = newHandoff()
        val session = harness.connect()
        // A tunnel took its own reference on the session of the list before anyone claimed it.
        assertTrue(manager.acquire(session.id))
        handoff.offer(session.id)

        advanceUntilIdle()

        assertTrue(harness.isRegistered(session.id), "the tunnel still holds it")
        manager.release(session.id)
        assertFalse(harness.isRegistered(session.id))
    }
}
