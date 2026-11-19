// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.network.NetworkEvent
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.concurrent.Promise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The liveness watchdog of the tunnel sessions: the decision on its own ([LivenessPolicy]) and the
 * loops around it, in virtual time. The cadence is the keepalive of the host: 30 s by default, so a
 * probe after 30 s of silence and the death after 60 s of silence with the probe unanswered for a
 * whole tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelLivenessWatchdogTest {

    private class Harness(private val scope: TestScope, cadence: Int? = 30) {
        val ids = MutableStateFlow(setOf("s1"))
        val events = MutableSharedFlow<NetworkEvent>()
        val client = newClient()
        val clients = mutableMapOf<String, SSHClient>("s1" to client)
        val cadences = mutableMapOf<String, Int?>("s1" to cadence)
        val inbound = mutableMapOf<SSHClient, Long?>()
        val declared = mutableListOf<Pair<String, SSHClient>>()
        val probes = AtomicInteger()

        /** How many times the loops read the clock: a loop that spins would read it thousands of times. */
        var clockCalls = 0

        /** Added to the virtual time: a device that slept without the coroutine delays running. */
        var skewMs = 0L

        var probeBody: suspend (SSHClient) -> Boolean = { awaitCancellation() }

        /** The write of the probe never returns (a full send buffer on a dead link). */
        var writeBlocked = false

        /** When each probe was sent, in virtual milliseconds. */
        val probeTimes = mutableListOf<Long>()

        val watchdog = TunnelLivenessWatchdog(
            activeSessionIds = ids,
            networkEvents = events,
            clientOf = { clients[it] },
            cadenceSecondsOf = { cadences[it] },
            declareUnresponsive = { id, c -> declared += id to c },
            probe = { c, written ->
                probes.incrementAndGet()
                probeTimes += scope.currentTime
                if (!writeBlocked) written()
                probeBody(c)
            },
            inboundAt = { inbound[it] },
            clock = { clockCalls++; scope.currentTime + skewMs },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        fun start(): Job = scope.launch { watchdog.observe() }

        fun at(ms: Long) {
            scope.advanceTimeBy(ms - scope.currentTime)
            scope.runCurrent()
        }

        companion object {
            fun newClient(): SSHClient = mockk<SSHClient>(relaxed = true).also {
                every { it.isConnected } returns true
            }
        }
    }

    // ── Policy on its own ─────────────────────────────────────────────────────

    @Test
    fun `the cadence is kept between 15 and 60 seconds`() {
        // 5 s is read as 15 s: a probe at 15 s of silence.
        val low = LivenessPolicy(5, armedAt = 0)
        assertEquals(LivenessAction.NONE, low.decide(14_999, null, Long.MIN_VALUE, null))
        assertEquals(LivenessAction.PROBE, low.decide(15_000, null, Long.MIN_VALUE, null))
        // 600 s is read as 60 s.
        val high = LivenessPolicy(600, armedAt = 0)
        assertEquals(LivenessAction.NONE, high.decide(59_999, null, Long.MIN_VALUE, null))
        assertEquals(LivenessAction.PROBE, high.decide(60_000, null, Long.MIN_VALUE, null))
    }

    @Test
    fun `a probe is never stacked on one that waits for its answer or is still being written`() {
        val policy = LivenessPolicy(30, armedAt = 0)
        assertEquals(LivenessAction.PROBE, policy.decide(30_000, null, Long.MIN_VALUE, null))
        assertEquals(LivenessAction.NONE, policy.decide(45_000, null, Long.MIN_VALUE, null))
        val busy = LivenessPolicy(30, armedAt = 0)
        assertEquals(LivenessAction.NONE, busy.decide(30_000, null, Long.MIN_VALUE, writingSince = 0L))
    }

    @Test
    fun `a byte received after the probe clears it`() {
        val policy = LivenessPolicy(30, armedAt = 0)
        assertEquals(LivenessAction.PROBE, policy.decide(30_000, null, Long.MIN_VALUE, null))
        assertEquals(LivenessAction.NONE, policy.decide(60_000, 50_000L, Long.MIN_VALUE, null))
        assertFalse(policy.probeInFlight)
        // 30 s of silence after that byte: probed again.
        assertEquals(LivenessAction.PROBE, policy.decide(80_000, 50_000L, Long.MIN_VALUE, null))
    }

    @Test
    fun `a probe in flight for less than a tick is not declared dead, even after a network event`() {
        val policy = LivenessPolicy(30, armedAt = 0)
        assertEquals(LivenessAction.PROBE, policy.decide(100_000, null, Long.MIN_VALUE, null))
        policy.nudge()
        assertEquals(LivenessAction.NONE, policy.decide(105_000, null, Long.MIN_VALUE, null))
        assertEquals(LivenessAction.NONE, policy.decide(113_999, null, Long.MIN_VALUE, null))
        assertEquals(LivenessAction.DECLARE_DEAD, policy.decide(114_000, null, Long.MIN_VALUE, null))
    }

    @Test
    fun `a write stuck on the link counts as a probe in flight since its start`() {
        val policy = LivenessPolicy(30, armedAt = 0)
        // A byte came at 31 s, but the write begun at 30 s is still stuck: the silence rule goes on.
        assertEquals(LivenessAction.NONE, policy.decide(45_000, 31_000L, Long.MIN_VALUE, writingSince = 30_000))
        assertEquals(LivenessAction.NONE, policy.decide(90_000, 31_000L, Long.MIN_VALUE, writingSince = 30_000))
        assertEquals(LivenessAction.DECLARE_DEAD, policy.decide(91_000, 31_000L, Long.MIN_VALUE, writingSince = 30_000))
    }

    // ── The loops ─────────────────────────────────────────────────────────────

    @Test
    fun `traffic at every tick means no probe and no declaration over ten minutes`() = runTest {
        val h = Harness(this)
        val job = h.start()
        val traffic = launch {
            while (true) {
                h.inbound[h.client] = currentTime + h.skewMs
                delay(5_000)
            }
        }
        h.at(600_000)

        assertEquals(0, h.probes.get())
        assertTrue(h.declared.isEmpty())
        traffic.cancel()
        job.cancel()
    }

    @Test
    fun `an idle session whose server answers is probed once per silence and never declared`() = runTest {
        val h = Harness(this)
        h.probeBody = { true }
        val job = h.start()

        h.at(300_000)

        assertEquals(10, h.probes.get(), "one probe per 30 s of silence, at 30 s, 60 s ... 300 s")
        assertEquals(30_000L, h.probeTimes.first())
        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `a mute server gets one probe and is declared once, with the same client, at 60 s`() = runTest {
        val h = Harness(this)
        val job = h.start()

        h.at(59_000)
        assertTrue(h.declared.isEmpty(), "not before the silence reaches 60 s")
        h.at(60_000)
        assertEquals(1, h.declared.size)
        assertEquals("s1", h.declared[0].first)
        assertSame(h.client, h.declared[0].second)
        h.at(300_000)

        assertEquals(1, h.probes.get(), "one probe only")
        assertEquals(1, h.declared.size, "declared exactly once")
        assertTrue(job.isActive, "observe keeps running for the other sessions")
        job.cancel()
    }

    @Test
    fun `a probe whose sending never returns is not stacked and the death comes at the same time`() = runTest {
        val h = Harness(this)
        h.probeBody = { awaitCancellation() }
        val job = h.start()

        h.at(60_000)

        assertEquals(1, h.probes.get())
        assertEquals(1, h.declared.size)
        job.cancel()
    }

    @Test
    fun `a probe that throws counts as no answer and does not crash the loop`() = runTest {
        val h = Harness(this)
        h.probeBody = { throw TransportException("down") }
        val job = h.start()

        h.at(120_000)

        assertEquals(1, h.declared.size)
        job.cancel()
    }

    @Test
    fun `a client that is not connected any more ends the loop without a declaration`() = runTest {
        val h = Harness(this)
        every { h.client.isConnected } returns false
        val job = h.start()

        h.at(300_000)

        assertEquals(0, h.probes.get())
        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `a client replaced under the same id ends the loop without a declaration`() = runTest {
        val h = Harness(this)
        val job = h.start()
        h.at(20_000)
        h.clients["s1"] = Harness.newClient()

        h.at(300_000)

        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `when the last tunnel of the session goes the loop stops and nothing is probed`() = runTest {
        val h = Harness(this)
        val job = h.start()
        h.at(20_000)

        h.ids.value = emptySet()
        h.at(300_000)

        assertEquals(0, h.probes.get())
        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `tunnels that share a session are one watched session, probed once`() = runTest {
        val config = TunnelConfig(
            id = "t",
            label = "t",
            hostId = "h",
            type = TunnelType.LOCAL_FORWARD,
            localPort = 8080,
            remoteHost = "127.0.0.1",
            remotePort = 80,
        )
        val tunnels = mapOf(
            "t1" to ActiveTunnel(config = config.copy(id = "t1"), sessionId = "s1"),
            "t2" to ActiveTunnel(config = config.copy(id = "t2"), sessionId = "s1"),
            "t3" to ActiveTunnel(config = config.copy(id = "t3"), sessionId = ""),
        )
        assertEquals(setOf("s1"), watchedSessionIds(MutableStateFlow(tunnels)).first())

        val h = Harness(this)
        val job = h.start()
        h.at(50_000)

        assertEquals(1, h.probes.get())
        job.cancel()
    }

    @Test
    fun `a reconnection on a new session starts a new loop and the old one is stopped`() = runTest {
        val h = Harness(this)
        val job = h.start()
        h.at(20_000)

        val next = Harness.newClient()
        h.clients["s2"] = next
        h.cadences["s2"] = 30
        h.ids.value = setOf("s2")
        h.at(49_999)
        assertEquals(0, h.probes.get(), "the old session was dropped at 20 s: no probe for it at 30 s")
        h.at(50_000)

        assertEquals(1, h.probes.get(), "one probe for the new one at 50 s")
        assertEquals(listOf(50_000L), h.probeTimes)
        h.at(80_000)
        assertEquals(1, h.declared.size)
        assertEquals("s2", h.declared[0].first)
        assertSame(next, h.declared[0].second)
        job.cancel()
    }

    @Test
    fun `a long sleep gives a probe at the next tick and the death at the one after if mute`() = runTest {
        val h = Harness(this)
        val job = h.start()
        h.at(10_000)

        h.skewMs = 3_600_000
        h.at(14_000)
        assertEquals(0, h.probes.get(), "the loop only wakes at its tick")
        h.at(15_000)
        assertEquals(1, h.probes.get(), "probe at the first tick after the sleep")
        assertTrue(h.declared.isEmpty())
        h.at(30_000)
        assertEquals(1, h.declared.size, "dead at the tick after the probe")
        assertEquals(1, h.probes.get())
        job.cancel()
    }

    @Test
    fun `a long sleep followed by an answer declares nothing`() = runTest {
        val h = Harness(this)
        h.probeBody = { true }
        val job = h.start()
        h.at(10_000)

        h.skewMs = 3_600_000
        h.at(120_000)

        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `a network event probes at once, and a mute server is declared after 10 s`() = runTest {
        val h = Harness(this)
        val job = h.start()
        h.at(1_000)

        h.events.emit(NetworkEvent.Lost)
        h.at(1_001)
        assertEquals(1, h.probes.get())
        h.at(10_500)
        assertTrue(h.declared.isEmpty())
        h.at(11_100)
        assertEquals(1, h.declared.size)
        job.cancel()
    }

    @Test
    fun `a network event whose probe is answered declares nothing`() = runTest {
        val h = Harness(this)
        h.probeBody = { true }
        val job = h.start()
        h.at(1_000)

        h.events.emit(NetworkEvent.Connected)
        h.at(1_001)
        assertEquals(1, h.probes.get(), "the event probes at once, not at 30 s")
        assertEquals(listOf(1_000L), h.probeTimes)
        h.at(40_000)

        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `a network event 3 s after a probe sent after a sleep does not kill the session at once`() = runTest {
        val h = Harness(this)
        val job = h.start()
        h.at(10_000)
        h.skewMs = 3_600_000
        h.at(15_000)
        assertEquals(1, h.probes.get())

        h.at(18_000)
        h.events.emit(NetworkEvent.Connected)
        h.at(18_100)

        assertTrue(h.declared.isEmpty(), "the probe is 3 s old: not a whole tick")
        h.at(34_000)
        assertEquals(1, h.declared.size)
        job.cancel()
    }

    @Test
    fun `bytes that clear a probe do not leave the session unwatched when the link then goes mute`() = runTest {
        val h = Harness(this)
        // The server never answers global requests: the wait for the promise has no end.
        h.probeBody = { awaitCancellation() }
        val job = h.start()

        h.at(30_000)
        assertEquals(1, h.probes.get())
        h.inbound[h.client] = 31_000L
        // Then nothing: probed again once 30 s have passed since the byte, dead 30 s later.
        h.at(104_000)
        assertTrue(h.declared.isEmpty())
        h.at(105_000)

        assertEquals(2, h.probes.get())
        assertEquals(1, h.declared.size)
        job.cancel()
    }

    @Test
    fun `a wake-up probe whose write is stuck is declared dead after 10 s`() = runTest {
        val h = Harness(this)
        h.writeBlocked = true
        val job = h.start()
        h.at(1_000)

        h.events.emit(NetworkEvent.Lost)
        h.at(10_900)
        assertTrue(h.declared.isEmpty())
        h.at(11_100)

        assertEquals(1, h.declared.size)
        assertEquals(1, h.probes.get())
        job.cancel()
    }

    @Test
    fun `a stuck wake-up write with bytes still arriving does not spin the loop`() = runTest {
        val h = Harness(this)
        h.writeBlocked = true
        val job = h.start()
        h.at(1_000)
        h.events.emit(NetworkEvent.Lost)
        val traffic = launch {
            while (true) {
                delay(2_000)
                h.inbound[h.client] = currentTime
            }
        }

        h.at(40_000)

        assertTrue(h.declared.isEmpty(), "bytes arrive: the link lives")
        assertTrue(h.clockCalls < 100, "a few turns of the loop, was ${h.clockCalls}")
        traffic.cancel()
        job.cancel()
    }

    @Test
    fun `a probe that clears another one cancels the wait of the old one`() = runTest {
        val h = Harness(this)
        val cancelled = AtomicInteger()
        h.probeBody = {
            try {
                awaitCancellation()
            } finally {
                cancelled.incrementAndGet()
            }
        }
        val job = h.start()
        h.at(30_000)
        assertEquals(1, h.probes.get())
        h.inbound[h.client] = 31_000L

        h.at(40_000)
        h.events.emit(NetworkEvent.Connected)
        h.at(40_100)

        assertEquals(2, h.probes.get())
        assertEquals(1, cancelled.get(), "the wait of the first probe ended")
        job.cancel()
    }

    @Test
    fun `a write stuck on the link is declared dead although bytes came after it began`() = runTest {
        val h = Harness(this)
        h.writeBlocked = true
        val job = h.start()

        h.at(30_000)
        assertEquals(1, h.probes.get())
        h.inbound[h.client] = 31_000L
        h.at(300_000)

        assertEquals(1, h.probes.get(), "no second write is stacked on the stuck one")
        assertEquals(1, h.declared.size)
        job.cancel()
    }

    @Test
    fun `a session whose host keepalive is 0 is not watched, nor woken by the network`() = runTest {
        val h = Harness(this, cadence = 0)
        val job = h.start()
        h.at(1_000)

        h.events.emit(NetworkEvent.Connected)
        h.at(300_000)

        assertEquals(0, h.probes.get())
        assertTrue(h.declared.isEmpty())
        job.cancel()
    }

    @Test
    fun `a shorter cadence of the host is followed`() = runTest {
        val h = Harness(this, cadence = 15)
        val job = h.start()

        h.at(44_000)
        assertTrue(h.declared.isEmpty())
        h.at(45_000)

        assertEquals(1, h.declared.size, "15 s: probe at 15 s, death at 45 s")
        job.cancel()
    }

    @Test
    fun `cancelling observe stops every loop`() = runTest {
        val h = Harness(this)
        h.clients["s2"] = Harness.newClient()
        h.cadences["s2"] = 30
        h.ids.value = setOf("s1", "s2")
        val job = h.start()
        h.at(20_000)

        job.cancel()
        h.at(300_000)

        assertEquals(0, h.probes.get())
        assertTrue(h.declared.isEmpty())
    }

    // ── The default probe ─────────────────────────────────────────────────────

    private fun promise() =
        Promise<SSHPacket, ConnectionException>("probe", ConnectionException.chainer, LoggerFactory.DEFAULT)

    private fun clientWith(promise: Promise<SSHPacket, ConnectionException>): SSHClient {
        val connection = mockk<Connection>()
        every { connection.sendGlobalRequest(any(), any(), any()) } returns promise
        val client = mockk<SSHClient>()
        every { client.connection } returns connection
        return client
    }

    @Test
    fun `a request failure with no cause is an answer, so the server lives`() = runTest {
        val p = promise().also { it.deliverError(ConnectionException("Global request failed")) }

        assertTrue(sendKeepAliveProbe(clientWith(p)))
    }

    @Test
    fun `a success is an answer`() = runTest {
        val p = promise().also { it.deliver(SSHPacket()) }

        assertTrue(sendKeepAliveProbe(clientWith(p)))
    }

    @Test
    fun `an error with a cause, the transport that fell, is not an answer`() = runTest {
        val p = promise().also { it.deliverError(ConnectionException(TransportException("down"))) }

        assertFalse(sendKeepAliveProbe(clientWith(p)))
    }

    @Test
    fun `a probe that cannot be sent is not an answer`() = runTest {
        val connection = mockk<Connection>()
        every { connection.sendGlobalRequest(any(), any(), any()) } throws TransportException("down")
        val client = mockk<SSHClient>()
        every { client.connection } returns connection

        assertFalse(sendKeepAliveProbe(client))
    }

    @Test
    fun `the default probe waits for its promise by looking, not by blocking`() = runTest {
        val p = promise()
        val result = launch {
            assertTrue(sendKeepAliveProbe(clientWith(p)))
        }
        runCurrent()
        assertTrue(result.isActive)
        p.deliver(SSHPacket())
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(result.isActive)
    }
}
