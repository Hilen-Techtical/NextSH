// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reason of a reconnection on the card: [SshTunnelManager.markIfAwaitingReconnect] writes it only
 * while the tunnel still waits for a reconnection (a stop or a restart by hand always wins), and the
 * start the reconnection reserved carries it into STARTING, which no other start does.
 */
class SshTunnelManagerReconnectReasonTest {

    private fun manager() = SshTunnelManager(sessionManager = mockk(relaxed = true))

    private fun config(id: String = "t1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    /** A tunnel that was running and whose session ended: ERROR with [reason], eligible to reconnect. */
    private fun SshTunnelManager.cut(cfg: TunnelConfig, reason: String = TUNNEL_SESSION_LOST_MESSAGE) {
        markStarting(cfg)
        injectActiveForTest(cfg, sessionId = "s1")
        simulateUnexpectedStopForTest(cfg.id, reason)
    }

    private fun SshTunnelManager.stopNow(id: String) = runBlocking { stopTunnel(id) }

    // ── markIfAwaitingReconnect ───────────────────────────────────────────────

    @Test
    fun `a tunnel in error waiting for a reconnection takes the status and the reason`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)

        assertTrue(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, TUNNEL_NO_ANSWER_MESSAGE))

        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a tunnel already reconnecting takes the next status, and a null reason clears the old one`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        assertTrue(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, TUNNEL_NETWORK_LOST_MESSAGE))

        assertTrue(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.ERROR, "Reconnexion échouée"))
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Reconnexion échouée", manager.getTunnelState(cfg.id)?.errorMessage)

        assertTrue(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, null))
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `after a stop by hand nothing is written and the card stays closed`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        manager.stopNow(cfg.id)

        assertFalse(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, TUNNEL_NO_ANSWER_MESSAGE))

        assertNull(manager.getTunnelState(cfg.id), "no ghost card brought back by the reason")
        assertFalse(manager.isAwaitingReconnect(cfg.id))
    }

    @Test
    fun `after a restart by hand the starting card is not overwritten`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        manager.markStarting(cfg.copy())

        assertFalse(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, TUNNEL_NO_ANSWER_MESSAGE))
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)

        manager.injectActiveForTest(cfg, sessionId = "s2")
        assertFalse(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.ERROR, "Reconnexion échouée"))
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "an active tunnel is never turned to error")
    }

    @Test
    fun `a tunnel held out of the reconnection is not written either`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        assertTrue(manager.holdReconnect(cfg.id, TUNNEL_AUTH_REQUIRED_MESSAGE))

        assertFalse(manager.markIfAwaitingReconnect(cfg.id, TunnelStatus.RECONNECTING, TUNNEL_NO_ANSWER_MESSAGE))

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_AUTH_REQUIRED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage, "the hold keeps its message")
    }

    @Test
    fun `a tunnel the manager does not know is not created`() {
        val manager = manager()

        assertFalse(manager.markIfAwaitingReconnect("ghost", TunnelStatus.RECONNECTING, TUNNEL_NO_ANSWER_MESSAGE))

        assertTrue(manager.tunnelStates.value.isEmpty())
    }

    // ── The reason rides on the reserved start ────────────────────────────────

    @Test
    fun `the start the reconnection reserved shows the reason while it connects`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        val attempt = cfg.copy()

        assertTrue(manager.reserveReconnectStart(attempt, TUNNEL_NO_ANSWER_MESSAGE))
        manager.markStarting(attempt)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a reserved start without a reason shows none`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        val attempt = cfg.copy()

        assertTrue(manager.reserveReconnectStart(attempt))
        manager.markStarting(attempt)

        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a start of the user never shows the reason of a reservation it did not make`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        val reserved = cfg.copy()
        assertTrue(manager.reserveReconnectStart(reserved, TUNNEL_NO_ANSWER_MESSAGE))

        // The user starts the tunnel by hand with another row: the reservation is not theirs.
        manager.markStarting(cfg.copy())

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `an auto-start reservation carries no reason`() {
        val manager = manager()
        val cfg = config()
        val attempt = cfg.copy()

        assertTrue(manager.reserveAutoStart(attempt))
        manager.markStarting(attempt)

        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a reserved start caught by a stop shows nothing, reason included`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        val attempt = cfg.copy()
        assertTrue(manager.reserveReconnectStart(attempt, TUNNEL_NO_ANSWER_MESSAGE))
        manager.stopNow(cfg.id)

        manager.markStarting(attempt)

        assertNull(manager.getTunnelState(cfg.id), "the stop sticks: no STARTING, so no reason either")
    }

    @Test
    fun `nothing is reserved for a tunnel that no longer waits`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        manager.stopNow(cfg.id)

        assertFalse(manager.reserveReconnectStart(cfg.copy(), TUNNEL_NO_ANSWER_MESSAGE))
    }

    @Test
    fun `the reason is gone once the attempt succeeds or fails`() {
        val manager = manager()
        val cfg = config()
        manager.cut(cfg)
        val attempt = cfg.copy()
        assertTrue(manager.reserveReconnectStart(attempt, TUNNEL_NO_ANSWER_MESSAGE))
        manager.markStarting(attempt)

        manager.markError(attempt, "Hôte injoignable")
        assertEquals("Hôte injoignable", manager.getTunnelState(cfg.id)?.errorMessage, "the failure replaces the reason")

        val second = cfg.copy()
        assertTrue(manager.reserveReconnectStart(second, "Hôte injoignable"))
        manager.markStarting(second)
        manager.injectActiveForTest(second, sessionId = "s2")
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage, "an active tunnel carries no reason")
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
