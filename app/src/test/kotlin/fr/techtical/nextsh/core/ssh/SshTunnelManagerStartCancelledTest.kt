// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A start cancelled on the way leaves nothing to move the card on: the shared use case sets no
 * error on a cancellation. [SshTunnelManager.markStartCancelled] and
 * [SshTunnelManager.markReconnectInterrupted] are what put it in error, and only when the state is
 * still the one their own start or reconnection left.
 */
class SshTunnelManagerStartCancelledTest {

    private val manager = SshTunnelManager(sessionManager = mockk(relaxed = true))

    private fun config(id: String = "t1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    // ── A start the user abandoned ────────────────────────────────────────────

    @Test
    fun `a start cancelled in STARTING goes to error with the message`() {
        val cfg = config()
        manager.markStarting(cfg)

        manager.markStartCancelled(cfg, TUNNEL_START_CANCELLED_MESSAGE, hold = true)

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_START_CANCELLED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a start the user abandoned is held out of the automatic reconnection`() {
        val cfg = config()
        manager.markStarting(cfg)

        manager.markStartCancelled(cfg, TUNNEL_START_CANCELLED_MESSAGE, hold = true)

        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        assertTrue(!manager.isAwaitingReconnect(cfg.id))
    }

    @Test
    fun `a held tunnel can be started again`() {
        val cfg = config()
        manager.markStarting(cfg)
        manager.markStartCancelled(cfg, TUNNEL_START_CANCELLED_MESSAGE, hold = true)

        manager.markStarting(cfg)
        manager.markError(cfg, "failed on the network")

        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a cancelled start does not touch the STARTING of a start the user made meanwhile`() {
        // The first start is cancelled; the user stops the card and starts it again (another row
        // instance, equal by value) before the cancelled coroutine gets to its catch.
        val cancelled = config()
        val restartedByUser = cancelled.copy()
        manager.markStarting(restartedByUser)

        manager.markStartCancelled(cancelled, TUNNEL_START_CANCELLED_MESSAGE, hold = true)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cancelled.id)?.status, "the user's start goes on")
        assertNull(manager.getTunnelState(cancelled.id)?.errorMessage)
        // And it was not held either: when it fails on the network it is retried.
        manager.markError(restartedByUser, "failed on the network")
        assertEquals(listOf(cancelled.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a cancelled start does not take the STARTING of a more recent start whose row is equal by value`() {
        // Two starts in a row on a tunnel that is still STARTING: the second row equals the first, so
        // the state keeps the first instance (a StateFlow does not replace an equal value). The
        // retained start, not the state, says whose card it is.
        val older = config()
        val newer = older.copy()
        manager.markStarting(older)
        manager.markStarting(newer)

        manager.markStartCancelled(older, TUNNEL_START_CANCELLED_MESSAGE, hold = true)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(older.id)?.status, "the more recent start goes on")
        assertNull(manager.getTunnelState(older.id)?.errorMessage)
        assertTrue(!manager.isReconnectHeld(older.id), "and nothing was held")
        // The same for the reconnection that was started first.
        manager.markReconnectInterrupted(older.id, older, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(older.id)?.status)
    }

    @Test
    fun `two starts of equal rows both cancelled put the card in error, whichever order they end in`() {
        // The auto-start reserves the row A, the shortcut reads the row B (equal by value) and marks
        // it STARTING, then the auto-start marks A STARTING: A is the retained start, but the state
        // keeps the instance B (a StateFlow does not replace an equal value). Comparing the state to
        // the row of the cancelled start closed the card to both, and left it STARTING for ever.
        for (retainedFirst in listOf(false, true)) {
            val m = SshTunnelManager(sessionManager = mockk(relaxed = true))
            val a = config()
            val b = a.copy()
            assertTrue(m.reserveAutoStart(a))
            m.markStarting(b)
            m.markStarting(a)
            assertTrue(m.getTunnelState(a.id)?.config === b, "the premise: the state kept the instance of B")

            if (retainedFirst) {
                m.markStartCancelled(a, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
                assertEquals(TunnelStatus.ERROR, m.getTunnelState(a.id)?.status, "the retained start owns the card")
                m.markStartCancelled(b, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
            } else {
                m.markStartCancelled(b, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
                assertEquals(TunnelStatus.STARTING, m.getTunnelState(a.id)?.status, "not the retained one: not its card")
                m.markStartCancelled(a, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
            }

            assertEquals(TunnelStatus.ERROR, m.getTunnelState(a.id)?.status, "retainedFirst=$retainedFirst")
            assertEquals(TUNNEL_START_CANCELLED_MESSAGE, m.getTunnelState(a.id)?.errorMessage)
            assertTrue(m.isReconnectHeld(a.id))
        }
    }

    @Test
    fun `two attempts of equal rows both interrupted put the card in error, whichever order`() {
        for (retainedFirst in listOf(false, true)) {
            val m = SshTunnelManager(sessionManager = mockk(relaxed = true))
            val first = config()
            m.markStarting(first)
            m.markError(first, "Connexion SSH perdue")
            val attempt = first.copy() // the row of the reconnection
            val byUser = attempt.copy() // the row the user's start read
            assertTrue(m.reserveReconnectStart(attempt))
            m.markStarting(byUser)
            m.markStarting(attempt)
            assertTrue(m.getTunnelState(first.id)?.config === byUser, "the premise: the state kept the instance of the user")

            if (retainedFirst) {
                m.markReconnectInterrupted(first.id, attempt, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
                assertEquals(TunnelStatus.ERROR, m.getTunnelState(first.id)?.status, "the retained attempt owns the card")
                m.markReconnectInterrupted(first.id, byUser, "other")
            } else {
                m.markReconnectInterrupted(first.id, byUser, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
                assertEquals(TunnelStatus.STARTING, m.getTunnelState(first.id)?.status, "not the retained one: not its card")
                m.markReconnectInterrupted(first.id, attempt, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
            }

            assertEquals(TunnelStatus.ERROR, m.getTunnelState(first.id)?.status, "retainedFirst=$retainedFirst")
            assertEquals(TUNNEL_RECONNECT_INTERRUPTED_MESSAGE, m.getTunnelState(first.id)?.errorMessage)
        }
    }

    @Test
    fun `a cancelled start leaves alone a tunnel that is active, in error, stopped or unknown`() {
        val active = config("active")
        manager.markStarting(active)
        manager.injectActiveForTest(active, sessionId = "s1")
        val failed = config("failed")
        manager.markStarting(failed)
        manager.markError(failed, "refused")
        val unknown = config("unknown")

        manager.markStartCancelled(active, "x", hold = true)
        manager.markStartCancelled(failed, "x", hold = true)
        manager.markStartCancelled(unknown, "x", hold = true)

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(active.id)?.status)
        assertNull(manager.getTunnelState(active.id)?.errorMessage)
        assertEquals("refused", manager.getTunnelState(failed.id)?.errorMessage, "an error already there is kept")
        assertNull(manager.getTunnelState(unknown.id))
        // The error that was there is still retried: nothing was held.
        assertEquals(listOf(failed.id), manager.getTunnelsForReconnect().map { it.id })
    }

    // ── A reconnection cut short ──────────────────────────────────────────────

    @Test
    fun `a reconnection cut short goes back to error and stays a candidate`() {
        val cfg = config()
        manager.markStarting(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.RECONNECTING)

        manager.markReconnectInterrupted(cfg.id, attempt = null, message = TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_RECONNECT_INTERRUPTED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `the STARTING of the attempt in flight is the reconnection's own`() {
        val attempt = config()
        manager.markStarting(attempt) // what the shared start does with the row it is given

        manager.markReconnectInterrupted(attempt.id, attempt, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(attempt.id)?.status)
        assertEquals(listOf(attempt.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a reconnection cut short never touches a STARTING the user started meanwhile`() {
        val attempt = config()
        val startedByUser = attempt.copy()
        manager.markStarting(startedByUser)

        manager.markReconnectInterrupted(attempt.id, attempt, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
        manager.markReconnectInterrupted(attempt.id, attempt = null, message = TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(attempt.id)?.status)
        assertNull(manager.getTunnelState(attempt.id)?.errorMessage)
    }

    @Test
    fun `a reconnection cut short leaves alone a tunnel that is active, stopped or already in error`() {
        val active = config("active")
        manager.markStarting(active)
        manager.injectActiveForTest(active, sessionId = "s1")
        val failed = config("failed")
        manager.markStarting(failed)
        manager.markError(failed, "refused")

        manager.markReconnectInterrupted(active.id, active, "x")
        manager.markReconnectInterrupted(failed.id, failed, "x")
        manager.markReconnectInterrupted("unknown", null, "x")

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(active.id)?.status)
        assertEquals("refused", manager.getTunnelState(failed.id)?.errorMessage)
        assertNull(manager.getTunnelState("unknown"))
    }
}
