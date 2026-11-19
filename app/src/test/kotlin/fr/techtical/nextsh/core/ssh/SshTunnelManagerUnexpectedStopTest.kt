// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SshTunnelManager.unexpectedStops] is what starts the automatic reconnection of a tunnel the
 * moment it is cut: it must say so for a tunnel that stopped by itself, and for nothing else (a stop
 * by hand, the stop of all, a start that failed, a tunnel deleted elsewhere), or the reconnection
 * would bring back what the user closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshTunnelManagerUnexpectedStopTest {

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

    /** What the collector saw, and the state of the tunnel at the moment it was told. */
    private class Heard(
        val id: String,
        val stateThen: TunnelStatus?,
        val stillActive: Boolean,
        val stop: UnexpectedStop,
    )

    private fun TestScope.listen(manager: SshTunnelManager): List<Heard> {
        val heard = mutableListOf<Heard>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.unexpectedStops.collect { stop ->
                val id = stop.tunnelId
                heard += Heard(id, manager.getTunnelState(id)?.status, manager.activeTunnels.value.containsKey(id), stop)
            }
        }
        return heard
    }

    private fun run(manager: SshTunnelManager, cfg: TunnelConfig, sessionId: String = "s1") {
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = sessionId)
    }

    @Test
    fun `a tunnel that stops by itself is announced once, already in error and out of the active tunnels`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val cfg = config()
        run(manager, cfg)

        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
        // The listener thread wakes up again after the fact: nothing more to say.
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(1, heard.size)
        assertEquals(cfg.id, heard[0].id)
        assertEquals(TunnelStatus.RECONNECTING, heard[0].stateThen, "the reconnection finds it already in amber")
        assertEquals(false, heard[0].stillActive, "and no longer among the active tunnels")
    }

    @Test
    fun `the end of a session announces each tunnel it carried, not the others`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val heard = listen(manager)
        val a = config("a")
        val b = config("b")
        val elsewhere = config("c")
        run(manager, a, "s1")
        run(manager, b, "s1")
        run(manager, elsewhere, "s2")

        ends.captured.onSessionEnded("s1", SessionEnd.Lost("CONNECTION_LOST"))

        assertEquals(listOf("a", "b"), heard.map { it.id }.sorted())
    }

    @Test
    fun `a session closed by the app announces nothing, the tunnels still fall in error`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val heard = listen(manager)
        val a = config("a")
        val b = config("b")
        run(manager, a, "s1")
        run(manager, b, "s1")

        // The last reference was released, or the session was forced shut: the app's own decision.
        ends.captured.onSessionEnded("s1", SessionEnd.Closed)

        assertTrue(heard.isEmpty(), "no reconnection answers a close the app asked for")
        for (cfg in listOf(a, b)) {
            assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value[cfg.id]?.status)
            assertNull(manager.activeTunnels.value[cfg.id])
        }
        assertEquals(listOf("a", "b"), manager.getTunnelsForReconnect().map { it.id }.sorted(), "the next network event still finds them")
    }

    @Test
    fun `a session lost announces its tunnels even when the reason is any of the SSHJ ones`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val heard = listen(manager)
        val cfg = config()
        run(manager, cfg, "s1")

        ends.captured.onSessionEnded("s1", SessionEnd.Lost("CONNECTION_LOST"))

        assertEquals(listOf(cfg.id), heard.map { it.id })
    }

    @Test
    fun `a session declared unresponsive announces its tunnels, with the reason that the server stopped answering`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val heard = listen(manager)
        val a = config("a")
        val b = config("b")
        val elsewhere = config("c")
        run(manager, a, "s1")
        run(manager, b, "s1")
        run(manager, elsewhere, "s2")

        ends.captured.onSessionEnded("s1", SessionEnd.Unresponsive)

        assertEquals(listOf("a", "b"), heard.map { it.id }.sorted(), "announced, so the reconnection starts at once")
        for (id in listOf("a", "b")) {
            assertEquals(TunnelStatus.RECONNECTING, manager.tunnelStates.value[id]?.status)
            assertEquals(TUNNEL_NO_ANSWER_MESSAGE, manager.tunnelStates.value[id]?.errorMessage)
        }
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value["c"]?.status, "another session is not touched")
    }

    @Test
    fun `a cut goes straight to amber, no state in between is ever an error`() = runTest {
        val manager = manager()
        val seen = mutableListOf<TunnelStatus?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.tunnelStates.collect { seen += it["t1"]?.status }
        }
        listen(manager)
        val cfg = config()
        run(manager, cfg)

        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)

        assertEquals(TunnelStatus.RECONNECTING, seen.last())
        assertTrue(TunnelStatus.ERROR !in seen, "no red flash before the reconnection takes over: $seen")
        assertEquals(TUNNEL_SESSION_LOST_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
    }

    @Test
    fun `without anyone listening the cut is an error, which the next network event picks up`() = runTest {
        val manager = manager()
        val cfg = config()
        run(manager, cfg)

        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)

        assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value[cfg.id]?.status)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `an orphan reconnecting card is a candidate again, and a cut candidate whichever of the two states it has`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val cfg = config()
        run(manager, cfg)
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
        assertEquals(TunnelStatus.RECONNECTING, manager.tunnelStates.value[cfg.id]?.status)

        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
        assertEquals(cfg.id, manager.getTunnelForCut(cfg.id)?.id)
        assertEquals(1, heard.size)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.ERROR, "x")
        assertEquals(cfg.id, manager.getTunnelForCut(cfg.id)?.id)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.ACTIVE)
        assertNull(manager.getTunnelForCut(cfg.id))
    }

    @Test
    fun `a stop by hand during the cut leaves no state at all`() = runTest {
        val manager = manager()
        listen(manager)
        val cfg = config()
        run(manager, cfg)

        manager.stopTunnel(cfg.id)
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)

        assertNull(manager.tunnelStates.value[cfg.id])
        assertNull(manager.getTunnelForCut(cfg.id))
    }

    @Test
    fun `a session lost or closed keeps the connection lost reason`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val lost = config("lost")
        val closed = config("closed")
        run(manager, lost, "s1")
        run(manager, closed, "s2")

        ends.captured.onSessionEnded("s1", SessionEnd.Lost("CONNECTION_LOST"))
        ends.captured.onSessionEnded("s2", SessionEnd.Closed)

        assertEquals(TUNNEL_SESSION_LOST_MESSAGE, manager.tunnelStates.value["lost"]?.errorMessage)
        assertEquals(TUNNEL_SESSION_LOST_MESSAGE, manager.tunnelStates.value["closed"]?.errorMessage)
    }

    @Test
    fun `a tunnel stopped by hand is not touched by the unresponsive end of its session`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val heard = listen(manager)
        val cfg = config()
        run(manager, cfg)

        manager.stopTunnel(cfg.id)
        ends.captured.onSessionEnded("s1", SessionEnd.Unresponsive)

        assertTrue(heard.isEmpty())
        assertNull(manager.tunnelStates.value[cfg.id], "the stop sticks")
    }

    @Test
    fun `a stop by hand is not announced, nor the late wake-up of its listener`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val cfg = config()
        run(manager, cfg)

        manager.stopTunnel(cfg.id)
        manager.simulateUnexpectedStopForTest(cfg.id, "socket closed")

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `the stop of all the tunnels is not announced`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val a = config("a")
        val b = config("b")
        run(manager, a)
        run(manager, b)

        manager.stopAllTunnels()
        manager.simulateUnexpectedStopForTest(a.id, "socket closed")
        manager.simulateUnexpectedStopForTest(b.id, "socket closed")

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `removing a tunnel that is in error, as for a tunnel deleted elsewhere, is not announced`() = runTest {
        val manager = manager()
        val cfg = config()
        run(manager, cfg)
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
        val heard = listen(manager)

        manager.stopTunnel(cfg.id)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a start that fails is not a stop, whatever the error it leaves`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val cfg = config()

        manager.markStarting(cfg)
        manager.markError(cfg, "Délai de connexion dépassé (10 s)")

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `the thread of a replaced instance does not announce the tunnel that took its place`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val cfg = config()
        manager.markStarting(cfg)
        val old = manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.stopTunnel(cfg.id)
        run(manager, cfg)

        manager.simulateUnexpectedStopForTest(cfg.id, "socket closed", instanceId = old)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a stop nobody listens to is not kept for a later listener`() = runTest {
        val manager = manager()
        val cfg = config()
        run(manager, cfg)
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        val late = mutableListOf<UnexpectedStop>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.unexpectedStops.toList(late) }

        assertTrue(late.isEmpty(), "the network event picks that tunnel up, it is in error")
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    // ── How long the tunnel held, and which run it was ────────────────────────

    @Test
    fun `the stop says how long the tunnel held, from the moment it became active`() = runTest {
        var now = 1_000L
        val manager = SshTunnelManager(sessionManager = mockk(relaxed = true), clock = { now })
        val heard = listen(manager)
        val cfg = config()
        manager.markStarting(cfg)
        now = 5_000L
        manager.injectActiveForTest(cfg, sessionId = "s1")
        now = 47_500L

        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(42_500L, heard.single().stop.uptimeMs, "counted from 5 000, not from the start of the start")
    }

    @Test
    fun `the stop names the run that was cut, so a restart by hand can be told from a restoration`() = runTest {
        val manager = manager()
        val heard = listen(manager)
        val cfg = config()
        manager.markStarting(cfg)
        val first = manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
        manager.markStarting(cfg)
        val second = manager.injectActiveForTest(cfg, sessionId = "s2")
        assertEquals(second, manager.lastInstanceId(cfg.id))
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(listOf(first, second), heard.map { it.stop.instanceId })
        assertEquals(second, manager.lastInstanceId(cfg.id), "still known once it is cut: the reconnection reads it after the start")
        assertTrue(manager.activeTunnels.value.isEmpty(), "while nothing runs any more")
    }

    @Test
    fun `the last run of a tunnel is known even if it was cut before anyone asked, and a tunnel never run has none`() = runTest {
        val manager = manager()
        val cfg = config()
        assertNull(manager.lastInstanceId(cfg.id))
        manager.markStarting(cfg)
        val run = manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(run, manager.lastInstanceId(cfg.id))
        assertNull(manager.lastInstanceId("another"), "per tunnel")
    }

    @Test
    fun `a clock that went backwards gives an uptime of zero, never a negative one`() = runTest {
        var now = 10_000L
        val manager = SshTunnelManager(sessionManager = mockk(relaxed = true), clock = { now })
        val heard = listen(manager)
        val cfg = config()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        now = 9_000L

        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(0L, heard.single().stop.uptimeMs)
    }

    // ── The one tunnel a stop is about ────────────────────────────────────────

    @Test
    fun `a tunnel in error is a candidate for its own reconnection`() = runTest {
        val manager = manager()
        val cfg = config()
        run(manager, cfg)
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(cfg.id, manager.getTunnelForReconnect(cfg.id)?.id)
    }

    @Test
    fun `an active, held, stopped or unknown tunnel is not a candidate`() = runTest {
        val manager = manager()
        val active = config("active")
        val held = config("held")
        val stopped = config("stopped")
        run(manager, active)
        run(manager, held)
        manager.simulateUnexpectedStopForTest(held.id, "Connexion SSH perdue")
        manager.holdReconnect(held.id, "Authentification échouée")
        run(manager, stopped)
        manager.stopTunnel(stopped.id)

        assertNull(manager.getTunnelForReconnect(active.id))
        assertNull(manager.getTunnelForReconnect(held.id))
        assertNull(manager.getTunnelForReconnect(stopped.id))
        assertNull(manager.getTunnelForReconnect("unknown"))
    }
}
