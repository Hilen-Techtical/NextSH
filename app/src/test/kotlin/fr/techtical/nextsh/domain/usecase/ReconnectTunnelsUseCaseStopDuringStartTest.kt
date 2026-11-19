// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.ssh.DeletedTunnelStopper
import fr.techtical.nextsh.core.ssh.LiveSessionCheck
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_LOCAL_PORT_RESERVED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_STOPPED_MESSAGE
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The reconnection against a stop by hand that lands while one of its attempts is on: the tunnel
 * the user just closed must not come back, nothing is written for the attempt, and the loop ends.
 * Same set-up as [ReconnectTunnelsUseCaseTest]: the real [SshTunnelManager] state machine, the start
 * use case mocked. The mocked start does what the shared one does (`markStarting`, then the
 * connection), and the stop lands in it where the test says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTunnelsUseCaseStopDuringStartTest {

    private lateinit var manager: SshTunnelManager
    private lateinit var startTunnel: StartTunnelUseCase
    private lateinit var liveTunnels: LiveTunnelLookup
    private lateinit var useCase: ReconnectTunnelsUseCase

    @BeforeEach
    fun setUp() {
        manager = SshTunnelManager(sessionManager = mockk(relaxed = true))
        val networkMonitor = mockk<NetworkMonitor>()
        every { networkMonitor.networkEvents } returns MutableSharedFlow()
        every { networkMonitor.hasUsableNetwork() } returns true
        startTunnel = mockk()
        liveTunnels = mockk()
        useCase = ReconnectTunnelsUseCase(
            networkMonitor = networkMonitor,
            tunnelManager = manager,
            startTunnel = startTunnel,
            liveTunnels = liveTunnels,
            deletedTunnelStopper = DeletedTunnelStopper(manager, mockk(relaxed = true)),
            appVisibility = AppVisibility { true },
            authRequiredNotifier = mockk(relaxed = true),
            liveSessions = LiveSessionCheck { false },
            settings = reconnectTestSettings(),
            context = reconnectTestContext(),
        )
    }

    private fun config(id: String = "t1", localPort: Int = 8080) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    /** A tunnel that was running and whose SSH transport dropped: ERROR, eligible to reconnect. */
    private fun inError(cfg: TunnelConfig) {
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
    }

    /** runBlocking keeps the test thread, so the stop is done when this returns (see ReconnectTunnelsUseCaseTest). */
    private fun stopByHand(id: String) = runBlocking { manager.stopTunnel(id) }

    @Test
    fun `a stop by hand between the reservation and markStarting does not bring the tunnel back`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val attempts = mutableListOf<TunnelConfig>()
        coEvery { startTunnel(any()) } coAnswers {
            val attempt = firstArg<TunnelConfig>()
            attempts += attempt
            // The shared start reads the host: the user closes the card meanwhile. Then it marks the
            // tunnel STARTING, and the connection fails.
            stopByHand(attempt.id)
            manager.markStarting(attempt)
            manager.markError(attempt, "Connexion refusée")
            SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Connexion refusée")
        }

        useCase.reconnectErrorTunnels()
        advanceUntilIdle()

        assertEquals(1, attempts.size, "the loop ends with the attempt the stop caught")
        assertNull(manager.getTunnelState(cfg.id), "no STARTING, no ERROR: the card stays closed")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "and the stop still holds")
        assertFalse(manager.isAwaitingReconnect(cfg.id))
    }

    @Test
    fun `a stop by hand during the connection of an attempt ends the loop without a word`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var attempts = 0
        coEvery { startTunnel(any()) } coAnswers {
            attempts++
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            stopByHand(attempt.id) // the user presses Stop on the DEMARRAGE card
            // What the manager's start answers once it finds itself stopped: nothing to tell.
            SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_START_STOPPED_MESSAGE)
        }

        useCase.reconnectErrorTunnels()
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertNull(manager.getTunnelState(cfg.id), "not ERROR, and no 'reconnection failed' message either")
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        assertFalse(manager.isReconnectHeld(cfg.id), "a stop is not a hold")
    }

    @Test
    fun `a refused authentication of an attempt the user stopped is not held nor retried`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var attempts = 0
        coEvery { startTunnel(any()) } coAnswers {
            attempts++
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            stopByHand(attempt.id)
            // The connection, which the stop did not interrupt, ends on a non-retryable failure.
            manager.markError(attempt, "Authentication failed")
            SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentication failed")
        }

        useCase.reconnectErrorTunnels()
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertNull(manager.getTunnelState(cfg.id))
        assertFalse(manager.isReconnectHeld(cfg.id))
    }

    @Test
    fun `an attempt that the user replaced by a start of their own steps aside`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val byUser = cfg.copy()
        var attempts = 0
        coEvery { startTunnel(any()) } coAnswers {
            attempts++
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            manager.markStarting(byUser) // the user starts it too, and is the most recent
            manager.markError(attempt, "Connexion refusée") // the attempt fails late: not its card any more
            SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Connexion refusée")
        }

        useCase.reconnectErrorTunnels()
        advanceUntilIdle()

        assertEquals(1, attempts, "no other attempt follows")
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status, "the start of the user goes on")
        assertTrue(manager.isStartAbandoned(cfg), "the attempt knows it is not the start of the tunnel any more")
        assertFalse(manager.isStartAbandoned(byUser))
    }

    @Test
    fun `the result of a start that faded out ends the loop even when its replacement has already finished`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val byUser = cfg.copy()
        var attempts = 0
        coEvery { startTunnel(any()) } coAnswers {
            attempts++
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            // The user started the tunnel again and that start has already finished: the manager can
            // no longer tell which start is the more recent, but this one says it faded out.
            manager.markStarting(byUser)
            manager.injectActiveForTest(byUser, sessionId = "s2")
            SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_START_STOPPED_MESSAGE)
        }

        useCase.reconnectErrorTunnels()

        assertEquals(1, attempts)
        assertFalse(manager.isStartAbandoned(cfg), "the premise: the manager does not know")
        // Only the wait before the first attempt: no second wait before a verdict that was not needed.
        assertEquals(1_000L, currentTime)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
    }

    @Test
    fun `a tunnel with a port that cannot be used is not reconnected, and is held`() = runTest {
        val cfg = config(localPort = 80)
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)

        useCase.reconnectErrorTunnels()
        advanceUntilIdle()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertTrue(manager.isReconnectHeld(cfg.id), "a port does not mend by itself")
        assertTrue(manager.getTunnelsForReconnect().isEmpty())

        // The next network connection does not try it again either.
        useCase.reconnectErrorTunnels()
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `starting a refused tunnel by hand lifts the hold, as for any other`() = runTest {
        val cfg = config(localPort = 80)
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        useCase.reconnectErrorTunnels()
        advanceUntilIdle()
        assertTrue(manager.isReconnectHeld(cfg.id))

        // The user fixes the port and starts it: the shared start marks it STARTING.
        manager.markStarting(cfg.copy(localPort = 8080))

        assertFalse(manager.isReconnectHeld(cfg.id))
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
    }

    // ── The wait between two attempts is RECONNECTING, stopped with Stop ─────

    @Test
    fun `stopping a tunnel that waits between two attempts ends the reconnection without another attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var attempts = 0
        coEvery { startTunnel(any()) } coAnswers {
            attempts++
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            manager.markError(attempt, "Connexion refusée") // ERROR for an instant, then the loop says RECONNECTING
            SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Connexion refusée")
        }
        val reconnection = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        advanceTimeBy(1_001) // the first attempt (after 1 s) has failed, the wait of 2 s before the second begins
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "amber, not red, between attempts")
        assertEquals("Connexion refusée", manager.getTunnelState(cfg.id)?.errorMessage, "with the reason of the failure")
        assertFalse(reconnection.isCompleted, "the reconnection is in its wait")

        // No cross in this state: the error cannot be cleared. The user presses Stop instead.
        assertFalse(manager.dismissError(cfg.id))
        stopByHand(cfg.id)
        advanceUntilIdle()

        assertEquals(1, attempts, "no other attempt")
        assertNull(manager.getTunnelState(cfg.id), "the card stays stopped")
        assertTrue(reconnection.isCompleted, "the loop is out")
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        coVerify(exactly = 1) { startTunnel(any()) }
    }
}
