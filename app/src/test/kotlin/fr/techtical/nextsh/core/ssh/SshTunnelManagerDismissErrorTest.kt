// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * [SshTunnelManager.dismissError]: the cross of the error banner, and the save of an edited tunnel,
 * put a card in error back to stopped. It acts only on a tunnel that is still in error at the moment
 * it decides, and then like a stop by hand: the state goes (the foreground service can stop), and the
 * reconnection leaves the tunnel alone until the user starts it again.
 */
class SshTunnelManagerDismissErrorTest {

    private val sessions = mockk<SshSessionManager>(relaxed = true)

    private fun manager() = SshTunnelManager(sessionManager = sessions)

    private fun config(id: String = "t1", localPort: Int = 8080) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    /** A tunnel in ERROR after a start that failed, as the shared start leaves it. */
    private fun SshTunnelManager.failed(cfg: TunnelConfig, message: String = "Authentication failed") {
        markStarting(cfg)
        markError(cfg, message)
    }

    private fun SshTunnelManager.stopNow(id: String) = runBlocking { stopTunnel(id) }

    /** A running tunnel, as after a successful start. */
    private fun SshTunnelManager.running(cfg: TunnelConfig, sessionId: String = "s1") {
        markStarting(cfg)
        injectActiveForTest(cfg, sessionId = sessionId)
    }

    // ── What it does to a tunnel in error ─────────────────────────────────────

    @Test
    fun `a tunnel in error is cleared, its card goes and the other tunnels stay`() {
        val manager = manager()
        val failed = config("failed")
        val other = config("other")
        manager.failed(failed)
        manager.running(other)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(failed.id)?.status)

        assertTrue(manager.dismissError(failed.id))

        assertNull(manager.getTunnelState(failed.id), "back to stopped")
        assertEquals(setOf(other.id), manager.tunnelStates.value.keys)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(other.id)?.status)
    }

    @Test
    fun `the last state going is what lets the foreground service stop`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)

        assertTrue(manager.dismissError(cfg.id))

        assertTrue(manager.tunnelStates.value.isEmpty())
    }

    @Test
    fun `a cleared tunnel is no longer a candidate of the automatic reconnection`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
        assertEquals(cfg.id, manager.getTunnelForReconnect(cfg.id)?.id)

        assertTrue(manager.dismissError(cfg.id))

        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        assertNull(manager.getTunnelForReconnect(cfg.id))
        assertFalse(manager.isAwaitingReconnect(cfg.id))
        assertFalse(manager.reserveReconnectStart(cfg), "the reconnection that was about to take it finds nothing to start")
    }

    @Test
    fun `the error of a tunnel that was cut is cleared, and its session reference was already given back`() {
        val manager = manager()
        val cfg = config()
        manager.running(cfg)
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.activeTunnels.value.isEmpty())

        assertTrue(manager.dismissError(cfg.id))

        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        verify(exactly = 1) { sessions.release("s1") } // by the cut, not a second time by the dismissal
    }

    @Test
    fun `the held error of a port that cannot be used is cleared too`() {
        val manager = manager()
        val cfg = config(localPort = 80)
        manager.markStarting(cfg)
        manager.refuseInvalidPorts(cfg)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.isReconnectHeld(cfg.id))

        assertTrue(manager.dismissError(cfg.id))

        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `the error of a start cancelled on the way is cleared`() {
        val manager = manager()
        val cfg = config()
        manager.markStarting(cfg)
        manager.markStartCancelled(cfg, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)

        assertTrue(manager.dismissError(cfg.id))

        assertNull(manager.getTunnelState(cfg.id))
    }

    @Test
    fun `a tunnel cleared once cannot be cleared again`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)

        assertTrue(manager.dismissError(cfg.id))
        assertFalse(manager.dismissError(cfg.id))
    }

    // ── A start by hand after the dismissal goes on as usual ──────────────────

    @Test
    fun `a start by hand after the dismissal lifts the exclusions, and the tunnel is a candidate again when it is cut`() {
        val manager = manager()
        val cfg = config(localPort = 80)
        manager.markStarting(cfg)
        manager.refuseInvalidPorts(cfg) // held, and about to be cleared
        assertTrue(manager.dismissError(cfg.id))

        val again = cfg.copy(localPort = 8080)
        manager.markStarting(again)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertFalse(manager.isReconnectHeld(cfg.id))
        manager.injectActiveForTest(again, sessionId = "s1")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        // The cut of this start is seen (the manual stop of the dismissal is lifted)...
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        // ...and the reconnection takes it again.
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    // ── What it leaves alone ──────────────────────────────────────────────────

    @Test
    fun `a running tunnel is not touched, and a later cut of it is still seen`() {
        val manager = manager()
        val cfg = config()
        manager.running(cfg)

        assertFalse(manager.dismissError(cfg.id))

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.activeTunnels.value.containsKey(cfg.id))
        verify(exactly = 0) { sessions.release(any()) }
        // Nothing was marked as stopped by hand: the detection of a failure still works.
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a tunnel that is starting is not touched, its start goes on`() {
        val manager = manager()
        val cfg = config()
        manager.markStarting(cfg)

        assertFalse(manager.dismissError(cfg.id))

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertFalse(manager.isStartAbandoned(cfg), "the start was not invalidated")
        // It was not marked as stopped by hand either: its failure is written and retried as usual.
        manager.markError(cfg, "late failure")
        assertEquals("late failure", manager.getTunnelState(cfg.id)?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a tunnel that is starting and brought up after the tap stays running`() {
        val manager = manager()
        val cfg = config()
        manager.markStarting(cfg)

        assertFalse(manager.dismissError(cfg.id))
        manager.injectActiveForTest(cfg, sessionId = "s1")

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.activeTunnels.value.containsKey(cfg.id))
    }

    @Test
    fun `a tunnel that is reconnecting is not touched`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.RECONNECTING)

        assertFalse(manager.dismissError(cfg.id))

        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.isAwaitingReconnect(cfg.id), "the reconnection goes on")
    }

    @Test
    fun `a tunnel that waits between two attempts, reconnecting with its reason, has no cross to press and keeps its reason`() {
        // The reconnection puts RECONNECTING back (with the reason) between two attempts, red only at
        // the end: the cross does not exist then, and a save of the tunnel form (dismissError) does not
        // end the reconnection.
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        assertTrue(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, "Hôte injoignable"))

        assertFalse(manager.dismissError(cfg.id))

        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Hôte injoignable", manager.getTunnelState(cfg.id)?.errorMessage)
        assertTrue(manager.isAwaitingReconnect(cfg.id))
        // The end of the loop gives the red card back, and then the cross works.
        assertTrue(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.ERROR, "Reconnexion échouée"))
        assertTrue(manager.dismissError(cfg.id))
    }

    @Test
    fun `a tunnel the manager does not know is not touched and nothing is marked for it`() {
        val manager = manager()

        assertFalse(manager.dismissError("ghost"))

        // Not marked as stopped by hand. The tunnel is registered without a markStarting (which would
        // lift the mark whatever dismissError did): had dismissError marked the id, this cut would be
        // taken for a stop by hand and ignored.
        val cfg = config("ghost")
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status, "the cut is seen")
        assertEquals(listOf("ghost"), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a state in error with a tunnel still registered is not touched`() {
        val manager = manager()
        val cfg = config()
        manager.running(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.ERROR, "x")

        assertFalse(manager.dismissError(cfg.id))

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.activeTunnels.value.containsKey(cfg.id))
    }

    // ── A start that was reserved, and that nobody announced ──────────────────

    @Test
    fun `an error wins over a restart reserved by the reconnection, which then fades out without bringing the card back`() = runTest {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        val attempt = cfg.copy() // the row the reconnection reserved
        assertTrue(manager.reserveReconnectStart(attempt))

        assertTrue(manager.dismissError(cfg.id), "the card is cleared, whatever is reserved")

        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(manager.isStartAbandoned(attempt), "the reserved start is invalidated, as by a stop")
        // The shared start goes on: it announces nothing, opens nothing and writes nothing.
        manager.markStarting(attempt)
        assertNull(manager.getTunnelState(cfg.id), "no STARTING: the card does not come back")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "and the exclusion is not lifted")
        val result = manager.startLocalForward(attempt, sessionId = "s1")
        assertEquals(TUNNEL_START_STOPPED_MESSAGE, (result as SshResult.Error).message)
        manager.markError(attempt, "Authentication failed")
        assertNull(manager.getTunnelState(cfg.id), "a late failure writes nothing either")
        assertTrue(manager.activeTunnels.value.isEmpty())
        assertTrue(manager.isStartWithdrawn(attempt, result), "and the caller says nothing")
    }

    @Test
    fun `an error wins over a restart reserved by the auto-start`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        val attempt = cfg.copy()
        assertTrue(manager.reserveAutoStart(attempt))

        assertTrue(manager.dismissError(cfg.id))

        assertNull(manager.getTunnelState(cfg.id))
        manager.markStarting(attempt)
        assertNull(manager.getTunnelState(cfg.id), "the reserved start fades out")
        assertTrue(manager.isStartAbandoned(attempt))
    }

    @Test
    fun `a start announced and still on its way is invalidated by the dismissal of an error`() {
        // Rare: an error over a start in flight (written by hand here). The user asked for the card
        // to go: the start of before fades out, as after a Stop.
        val manager = manager()
        val cfg = config()
        manager.markStarting(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.ERROR, "x")

        assertTrue(manager.dismissError(cfg.id))

        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(manager.isStartAbandoned(cfg))
    }

    @Test
    fun `a hold or an interruption of the reconnection does not end the reservation of another start, a dismissal then stops it`() {
        // The reconnection reserves the row R1, then the auto-start reserves R2 (it replaces R1 as
        // the retained start). The reconnection then holds the tunnel for the user (or is interrupted
        // between two attempts): that is not R2's doing, R2 must stay a start the card can stop.
        for (interruption in listOf(false, true)) {
            val manager = manager()
            val cfg = config()
            manager.failed(cfg)
            val reconnectionRow = cfg.copy()
            val autoStartRow = cfg.copy()
            assertTrue(manager.reserveReconnectStart(reconnectionRow))
            assertTrue(manager.reserveAutoStart(autoStartRow))

            if (interruption) {
                manager.markReconnectInterrupted(cfg.id, attempt = null, message = TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
            } else {
                assertTrue(manager.holdReconnect(cfg.id, TUNNEL_AUTH_REQUIRED_MESSAGE))
            }
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status, "interruption=$interruption")
            // The reconnection gives back its own row, which is no longer the retained one: no effect.
            manager.releaseReservation(reconnectionRow)

            // The user clears the card (or presses Stop): R2 is invalidated, not finished.
            assertTrue(manager.dismissError(cfg.id), "interruption=$interruption")
            assertTrue(manager.isStartAbandoned(autoStartRow), "interruption=$interruption")
            // The auto-start then announces its row: the tunnel must not start again.
            manager.markStarting(autoStartRow)

            assertNull(manager.getTunnelState(cfg.id), "no STARTING: the cleared card stays cleared (interruption=$interruption)")
            assertTrue(manager.getTunnelsForReconnect().isEmpty(), "and the manual stop is kept (interruption=$interruption)")
            assertFalse(manager.isAwaitingReconnect(cfg.id))
        }
    }

    @Test
    fun `a hold that finds the tunnel no longer waiting ends no reservation`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        manager.stopNow(cfg.id)
        val byUser = cfg.copy()
        manager.markStarting(byUser) // the user starts it again by hand: STARTING, announced

        assertFalse(manager.holdReconnect(cfg.id, TUNNEL_AUTH_REQUIRED_MESSAGE))

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertFalse(manager.dismissError(cfg.id))
        assertFalse(manager.isStartAbandoned(byUser))
    }

    @Test
    fun `an interrupted reconnection leaves alone the start the user announced meanwhile`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        val byUser = cfg.copy()
        manager.markStarting(byUser)

        manager.markReconnectInterrupted(cfg.id, attempt = null, message = TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertFalse(manager.isStartAbandoned(byUser))
    }

    @Test
    fun `giving a reservation back ends it, once, and only for the row that took it`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        val attempt = cfg.copy()
        assertTrue(manager.reserveReconnectStart(attempt))

        manager.releaseReservation(cfg.copy()) // another row: not its reservation
        assertTrue(manager.dismissError(cfg.id))
        assertTrue(manager.isStartAbandoned(attempt), "the reservation was still on: the other row did not end it")

        val other = config("other")
        manager.failed(other)
        val reserved = other.copy()
        assertTrue(manager.reserveReconnectStart(reserved))
        manager.releaseReservation(reserved)
        manager.releaseReservation(reserved) // idempotent
        assertTrue(manager.dismissError(other.id))
        assertFalse(manager.isStartAbandoned(reserved), "ended by the one who took it: nothing to invalidate")
    }

    @Test
    fun `giving a reservation back does not undo a stop that caught it`() {
        val manager = manager()
        val cfg = config()
        manager.failed(cfg)
        val attempt = cfg.copy()
        assertTrue(manager.reserveReconnectStart(attempt))
        manager.stopNow(cfg.id) // lands while the shared start reads the host

        manager.releaseReservation(attempt)

        // The stop still holds: the shared start announces the row and nothing comes back.
        manager.markStarting(attempt)
        assertNull(manager.getTunnelState(cfg.id))
        assertTrue(manager.isStartAbandoned(attempt))
    }

    // ── The decision is one step ──────────────────────────────────────────────

    @Test
    fun `a dismissal that races a restart never leaves a running tunnel without its card or its detection`() {
        val manager = manager()
        val rounds = 1_500
        var violations = 0
        repeat(rounds) { round ->
            val cfg = config("t$round")
            manager.failed(cfg)
            val again = cfg.copy() // another row instance: the user starts the tunnel again
            val go = CountDownLatch(1)
            val dismisser = thread {
                go.await()
                manager.dismissError(cfg.id)
            }
            val restarter = thread {
                go.await()
                manager.markStarting(again)
                manager.injectActiveForTest(again, sessionId = "s1")
            }
            go.countDown()
            dismisser.join(WAIT_MS)
            restarter.join(WAIT_MS)

            // Whichever came first, the restart ends running, with its card.
            val running = manager.getTunnelState(cfg.id)?.status == TunnelStatus.ACTIVE &&
                manager.activeTunnels.value.containsKey(cfg.id)
            // And a dismissal that came first did not leave a stop by hand behind: the cut is seen.
            manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
            val detected = manager.getTunnelState(cfg.id)?.status == TunnelStatus.ERROR
            if (!running || !detected) violations++
        }
        assertEquals(0, violations, "a dismissal that raced a restart stopped or hid the restarted tunnel")
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
