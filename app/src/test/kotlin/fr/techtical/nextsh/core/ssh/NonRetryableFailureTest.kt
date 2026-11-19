// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Which start failures stop the automatic reconnection (authentication, an unreadable key, a host
 * key that no longer matches), and how a tunnel is held back until the user starts it again.
 */
class NonRetryableFailureTest {

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

    /** What a failed start leaves: STARTING then ERROR with the reason. */
    private fun failedStart(cfg: TunnelConfig, message: String = "reason") {
        manager.markStarting(cfg)
        manager.markError(cfg, message)
    }

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    fun `credential and key failures are not retried`() {
        for (code in listOf(
            SshErrorCode.AUTH_FAILED,
            SshErrorCode.FIDO2_NO_KEY,
            SshErrorCode.FIDO2_USER_CANCELLED,
            SshErrorCode.FIDO2_TIMEOUT,
        )) {
            assertTrue(code.isNonRetryable(), "$code")
        }
    }

    @Test
    fun `a host key that does not match is a security event, never retried`() {
        assertTrue(SshErrorCode.HOST_KEY_MISMATCH.isNonRetryable())
    }

    @Test
    fun `network, port and vault window failures are retried`() {
        for (code in listOf(
            SshErrorCode.HOST_UNREACHABLE,
            SshErrorCode.PORT_IN_USE,
            SshErrorCode.TIMEOUT,
            SshErrorCode.UNKNOWN,
            SshErrorCode.AUTH_EXPIRED,
        )) {
            assertFalse(code.isNonRetryable(), "$code")
        }
    }

    @Test
    fun `every error code is classified`() {
        // The classification is an exhaustive when: a new code does not compile until it is placed.
        // This only checks that the two lists above did not drop a code.
        val classified = setOf(
            SshErrorCode.AUTH_FAILED, SshErrorCode.FIDO2_NO_KEY, SshErrorCode.FIDO2_USER_CANCELLED,
            SshErrorCode.FIDO2_TIMEOUT, SshErrorCode.HOST_UNREACHABLE, SshErrorCode.HOST_KEY_MISMATCH,
            SshErrorCode.PORT_IN_USE, SshErrorCode.TIMEOUT, SshErrorCode.UNKNOWN, SshErrorCode.AUTH_EXPIRED,
        )
        assertEquals(SshErrorCode.entries.toSet(), classified)
    }

    // ── Holding a tunnel back ─────────────────────────────────────────────────

    @Test
    fun `an authentication failure holds the tunnel out of the reconnection, in error with its message`() {
        val cfg = config()
        failedStart(cfg)

        val held = manager.holdReconnectIfNonRetryable(cfg.id, SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée"))

        assertTrue(held)
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        assertFalse(manager.isAwaitingReconnect(cfg.id))
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Connexion annulée", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a host key that no longer matches holds the tunnel with its own message`() {
        val cfg = config()
        failedStart(cfg)
        val message = "Vérification de la clé hôte échouée : the key changed"

        val held = manager.holdReconnectIfNonRetryable(cfg.id, SshResult.Error(SshErrorCode.HOST_KEY_MISMATCH, message))

        assertTrue(held)
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "no automatic attempt to a server that is not the trusted one")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(message, manager.getTunnelState(cfg.id)?.errorMessage, "the existing message is kept")
    }

    @Test
    fun `starting the tunnel again after a host key mismatch makes it a candidate again`() {
        val cfg = config()
        failedStart(cfg)
        manager.holdReconnectIfNonRetryable(cfg.id, SshResult.Error(SshErrorCode.HOST_KEY_MISMATCH, "mismatch"))

        // The user looked at the message and starts it again; if it fails on the network, it is retried.
        manager.markStarting(cfg)
        manager.markError(cfg, "unreachable")

        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a failure that is retried holds nothing`() {
        val cfg = config()
        failedStart(cfg)

        val held = manager.holdReconnectIfNonRetryable(cfg.id, SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "x"))

        assertFalse(held)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a success holds nothing`() {
        val cfg = config()
        manager.markStarting(cfg)

        assertFalse(manager.holdReconnectIfNonRetryable(cfg.id, SshResult.Success(Unit)))
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
    }

    @Test
    fun `starting the tunnel again lifts the hold`() {
        val cfg = config()
        failedStart(cfg)
        manager.holdReconnectIfNonRetryable(cfg.id, SshResult.Error(SshErrorCode.AUTH_FAILED, "refused"))

        manager.markStarting(cfg)
        manager.markError(cfg, "failed again, on the network")

        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a hold racing a manual start always lets the start win`() {
        // The hold checks that the tunnel waits, excludes it and writes ERROR; the start writes
        // STARTING and lifts the exclusions. Under one lock either order ends with the start.
        val cfg = config()
        repeat(3_000) { round ->
            failedStart(cfg)
            val gate = CountDownLatch(1)
            // Bounded waits everywhere: a deadlock must fail this test, not hang the build.
            val hold = thread(isDaemon = true) {
                gate.await(10, TimeUnit.SECONDS)
                manager.holdReconnect(cfg.id, "refused")
            }
            val start = thread(isDaemon = true) {
                gate.await(10, TimeUnit.SECONDS)
                manager.markStarting(cfg)
            }
            gate.countDown()
            hold.join(10_000)
            start.join(10_000)
            assertFalse(hold.isAlive || start.isAlive, "round $round: the two calls must both return (deadlock?)")

            assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status, "round $round")
            assertNull(manager.getTunnelState(cfg.id)?.errorMessage, "round $round")
            // And the exclusion is gone: a later network failure is retried as usual.
            manager.markError(cfg, "later failure on the network")
            assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "round $round")
        }
    }

    @Test
    fun `holding a tunnel the user stopped or that is gone does nothing`() {
        // Not in the manager at all.
        assertFalse(manager.holdReconnect("unknown", "x"), "nothing was held")
        assertNull(manager.getTunnelState("unknown"))

        // Running: not waiting for a reconnection, so not touched.
        val cfg = config()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        assertFalse(manager.holdReconnect(cfg.id, "x"), "nothing was held")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
        assertFalse(manager.isReconnectHeld(cfg.id))
    }

    @Test
    fun `holding a tunnel that waits says so, and the hold is visible until it is started again`() {
        val cfg = config()
        failedStart(cfg)
        assertFalse(manager.isReconnectHeld(cfg.id))

        assertTrue(manager.holdReconnect(cfg.id, "refused"), "the hold really happened")

        assertTrue(manager.isReconnectHeld(cfg.id))
        manager.markStarting(cfg)
        assertFalse(manager.isReconnectHeld(cfg.id), "a start lifts it")
    }
}
