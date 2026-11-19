// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.transport.TransportException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * The accept loop of a local forward. SSHJ's `LocalPortForwarder.listen()` ends with an exception when
 * the server refuses the channel of ONE client connection (the target refuses, `AllowTcpForwarding no`,
 * `PermitOpen`), which used to end the whole tunnel: a client that retries then killed it again and
 * again. The listener goes on after such a refusal, and stays fatal for what really breaks the tunnel.
 *
 * A channel open that times out (SSHJ gives up at 30 s, while sshd waits for the SYN timeout of its
 * own connect to the target, about 127 s, behind a firewall that drops packets) is not proof of a
 * dead transport, but not proof of a live one either (a silent link looks the same, and nothing on
 * Android watches the liveness of a tunnel). The listener goes on only when a probe, a global request
 * that asks for an answer, proves that the server still answers; the probe is never sent for a
 * refusal, which already is an answer.
 */
class LocalForwardListenerTest {

    private fun refused(reason: OpenFailException.Reason = OpenFailException.Reason.CONNECT_FAILED) =
        OpenFailException("direct-tcpip", reason, "Connection refused")

    /** What SSHJ 0.38 throws when the channel of a client connection is not confirmed in `getTimeoutMs()`. */
    private fun openTimedOut(message: String = "Timeout expired") =
        ConnectionException(message, TimeoutException("Timeout expired: 30000 MILLISECONDS"))

    /** What SSHJ throws for the REQUEST_FAILURE OpenSSH answers to a global request it does not know. */
    private fun requestFailure() = ConnectionException("Global request [keepalive@openssh.com] failed")

    private val open = ServerSocket()
    private val closed = ServerSocket().also { it.close() }

    /** A probe that must not be called: only a channel open timeout asks for one. */
    private val noProbe: () -> Boolean = { throw AssertionError("the transport probe is only for a channel open timeout") }

    /** A probe that counts its calls and gives the same answer every time. */
    private class Probe(private val answer: Boolean) {
        var calls = 0

        fun answers(): Boolean {
            calls++
            return answer
        }
    }

    /** A `listen` that throws what it is given, one per call, and returns normally once they are used. */
    private class Script(vararg outcomes: Throwable?) {
        private val remaining = outcomes.toMutableList()
        var calls = 0

        fun listen() {
            calls++
            val next = remaining.removeFirstOrNull() ?: return
            throw next
        }
    }

    private fun runListener(
        script: Script,
        socket: ServerSocket = open,
        connected: Boolean = true,
        probe: () -> Boolean = noProbe,
    ): String? = runLocalForwardListener(script::listen, socket, clientConnected = { connected }, transportAnswers = probe)

    // ── Refusals of the server ───────────────────────────────────────────────────

    @Test
    fun `a client connection the server refuses does not end the tunnel`() {
        val script = Script(refused(), refused(), refused())

        val failure = runListener(script)

        assertNull(failure, "the listener ended only because listen() returned: the tunnel was closed")
        assertEquals(4, script.calls, "three refusals, then it listened on and came back normally")
    }

    @Test
    fun `every kind of refusal of the server is survived`() {
        for (reason in OpenFailException.Reason.entries) {
            val script = Script(refused(reason))

            assertNull(runListener(script), "$reason")
            assertEquals(2, script.calls, "$reason")
        }
    }

    @Test
    fun `a refusal wrapped in another exception is still a refusal`() {
        val script = Script(ConnectionException("open failed", refused()))

        assertNull(runListener(script))
        assertEquals(2, script.calls)
    }

    @Test
    fun `a refusal is an answer of the server, the probe is not sent`() {
        val probe = Probe(answer = false)
        val script = Script(refused(), refused())

        assertNull(runListener(script, probe = probe::answers))

        assertEquals(3, script.calls)
        assertEquals(0, probe.calls, "a refusal proves the transport answers: nothing to probe")
    }

    @Test
    fun `a refusal on a connection that is gone ends the tunnel with the failure`() {
        val script = Script(refused())

        val failure = runListener(script, connected = false)

        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, failure)
        assertEquals(1, script.calls, "no point in listening on a dead connection")
    }

    @Test
    fun `a refusal after the tunnel was closed is the end of the tunnel, not a failure`() {
        val script = Script(refused())

        assertNull(runListener(script, socket = closed))
        assertEquals(1, script.calls)
    }

    @Test
    fun `a refusal racing an interrupt ends the listener, listen is not entered again`() {
        // The stopper interrupts the thread, then closes the socket: a refusal that lands in between
        // must not send the loop back into listen(), the call the interrupt is there to end.
        var calls = 0
        val failure = try {
            runLocalForwardListener(
                listen = {
                    calls++
                    // Bounded: a loop that came back would otherwise never end, and hang the test.
                    if (calls > MAX_CALLS) return@runLocalForwardListener
                    Thread.currentThread().interrupt()
                    throw refused()
                },
                serverSocket = open,
                clientConnected = { true },
                transportAnswers = noProbe,
            )
        } finally {
            // The flag belongs to the thread of the test: leave it as it was found.
            Thread.interrupted()
        }

        assertNull(failure, "interrupted is a stop, not a failure: the stopper owns the state")
        assertEquals(1, calls, "exactly one listen()")
    }

    @Test
    fun `any exception that comes out of an interrupted listen is a stop, not a failure`() {
        var calls = 0
        val failure = try {
            runLocalForwardListener(
                listen = {
                    calls++
                    if (calls > MAX_CALLS) return@runLocalForwardListener
                    Thread.currentThread().interrupt()
                    throw IOException("woken up by the interrupt")
                },
                serverSocket = open,
                clientConnected = { true },
                transportAnswers = noProbe,
            )
        } finally {
            Thread.interrupted()
        }

        assertNull(failure)
        assertEquals(1, calls)
    }

    // ── Timeout of the channel open, with the probe of the transport ─────────────

    @Test
    fun `a channel that did not open in time does not end the tunnel when the transport answers`() {
        // sshd answers a direct-tcpip only after its own connect to the target: behind a firewall that
        // drops packets it waits for the kernel SYN timeout (about 127 s), SSHJ gives up at 30 s. The
        // probe shows the transport itself is fine: not a reason to kill the tunnel.
        val probe = Probe(answer = true)
        val script = Script(openTimedOut(), openTimedOut(), openTimedOut())

        val failure = runListener(script, probe = probe::answers)

        assertNull(failure, "the listener ended only because listen() returned: the tunnel was closed")
        assertEquals(4, script.calls, "three timeouts, then it listened on and came back normally")
        assertEquals(3, probe.calls, "one probe per timeout")
    }

    @Test
    fun `a channel open timeout whose probe gets a request failure is survived`() {
        // OpenSSH does not know keepalive@openssh.com as a global request and answers REQUEST_FAILURE:
        // that is an answer, the transport is alive.
        val script = Script(openTimedOut())

        val failure = runListener(script, probe = { transportAnswered { throw requestFailure() } })

        assertNull(failure)
        assertEquals(2, script.calls)
    }

    @Test
    fun `a channel open timeout whose probe gets no answer ends the tunnel with the no answer reason`() {
        // A silent link (NAT, firewall): isConnected stays true, only an answer tells it from a slow target.
        // The reason is that the server stopped answering, and the caller declares the session dead.
        val probe = Probe(answer = false)
        val script = Script(openTimedOut())

        val failure = runListener(script, probe = probe::answers)

        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, failure)
        assertEquals(1, script.calls, "no point in listening on a link that does not answer")
        assertEquals(1, probe.calls)
    }

    @Test
    fun `a channel open timeout whose probe times out too ends the tunnel with the no answer reason`() {
        val probeTimeout = ConnectionException("Timeout expired", TimeoutException("Timeout expired: 10000 MILLISECONDS"))
        val script = Script(openTimedOut())

        val failure = runListener(script, probe = { transportAnswered { throw probeTimeout } })

        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, failure)
        assertEquals(1, script.calls)
    }

    @Test
    fun `only an unanswered probe gives the no answer reason, every other fatal failure keeps the listener one`() {
        // A refusal on a gone connection and a lost transport are not a silent link.
        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, runListener(Script(refused()), connected = false))
        assertEquals(
            TUNNEL_LISTENER_STOPPED_MESSAGE,
            runListener(Script(openTimedOut()), connected = false, probe = Probe(answer = false)::answers),
        )
    }

    @Test
    fun `a refusal and a timeout can follow one another, the tunnel survives both`() {
        val probe = Probe(answer = true)
        val script = Script(refused(), openTimedOut(), refused())

        assertNull(runListener(script, probe = probe::answers))
        assertEquals(4, script.calls)
        assertEquals(1, probe.calls, "only the timeout is probed")
    }

    @Test
    fun `a channel open timeout wrapped in another exception is still a timeout`() {
        val probe = Probe(answer = true)
        val script = Script(IOException("wrapper", openTimedOut()))

        assertNull(runListener(script, probe = probe::answers))
        assertEquals(2, script.calls)
        assertEquals(1, probe.calls)
    }

    @Test
    fun `a channel open timeout on a connection that is gone ends the tunnel, without a probe`() {
        val probe = Probe(answer = true)
        val script = Script(openTimedOut())

        val failure = runListener(script, connected = false, probe = probe::answers)

        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, failure)
        assertEquals(1, script.calls, "no point in listening on a dead connection")
        assertEquals(0, probe.calls, "no point in probing a connection that is gone")
    }

    @Test
    fun `a channel open timeout after the tunnel was closed is the end of the tunnel, not a failure`() {
        val probe = Probe(answer = true)
        val script = Script(openTimedOut())

        assertNull(runListener(script, socket = closed, probe = probe::answers))
        assertEquals(1, script.calls)
        assertEquals(0, probe.calls)
    }

    @Test
    fun `a channel open timeout racing an interrupt ends the listener, no probe, listen is not entered again`() {
        var calls = 0
        val failure = try {
            runLocalForwardListener(
                listen = {
                    calls++
                    if (calls > MAX_CALLS) return@runLocalForwardListener
                    Thread.currentThread().interrupt()
                    throw openTimedOut()
                },
                serverSocket = open,
                clientConnected = { true },
                transportAnswers = noProbe,
            )
        } finally {
            Thread.interrupted()
        }

        assertNull(failure, "interrupted is a stop, not a failure: the stopper owns the state")
        assertEquals(1, calls, "exactly one listen()")
    }

    @Test
    fun `an interrupt during the probe ends the listener, whatever the probe answered`() {
        // The stop interrupts the thread while it waits for the probe: SSHJ puts the flag back and the
        // probe reports no answer, or the answer lands just before. Either way listen() is not entered
        // again and the stopper owns the state.
        for (answer in listOf(false, true)) {
            val script = Script(openTimedOut())
            val failure = try {
                runListener(
                    script,
                    probe = {
                        Thread.currentThread().interrupt()
                        answer
                    },
                )
            } finally {
                Thread.interrupted()
            }

            assertNull(failure, "answer=$answer")
            assertEquals(1, script.calls, "answer=$answer")
        }
    }

    @Test
    fun `a socket closed during the probe ends the listener, it is the end of the tunnel`() {
        val local = ServerSocket()
        try {
            val script = Script(openTimedOut())

            val failure = runListener(script, socket = local, probe = { local.close(); true })

            assertNull(failure)
            assertEquals(1, script.calls)
        } finally {
            local.close()
        }
    }

    @Test
    fun `a connection exception that is not a channel open timeout stays fatal, without a probe`() {
        val others = listOf(
            // Nothing behind it.
            ConnectionException("Connection closed"),
            // Another cause than a timeout: the transport went away while the channel was opening.
            ConnectionException("open failed", TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")),
            ConnectionException("open failed", IOException("Broken pipe")),
            // A timeout that is not the direct cause of a ConnectionException is not what SSHJ throws.
            TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost", TimeoutException("Timeout expired")),
        )
        for (other in others) {
            val script = Script(other)

            val failure = runListener(script)

            assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, failure, "$other")
            assertEquals(1, script.calls, "$other")
        }
    }

    @Test
    fun `a socket timeout stays fatal, it is not a channel open timeout`() {
        // java.net.SocketTimeoutException is an IOException of the socket layer, not the TimeoutException
        // SSHJ chains into a ConnectionException when a channel does not open.
        val script = Script(SocketTimeoutException("Read timed out"))

        val failure = runListener(script)

        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, failure)
        assertEquals(1, script.calls)
    }

    // ── The probe itself ─────────────────────────────────────────────────────────

    @Test
    fun `a probe that gets a success or a request failure shows the transport answers`() {
        assertTrue(transportAnswered { })
        assertTrue(transportAnswered { throw requestFailure() })
    }

    @Test
    fun `a probe that gets no answer shows nothing`() {
        val silent = listOf(
            // The timeout of the probe: Promise.retrieve chains a TimeoutException.
            ConnectionException(TimeoutException("Timeout expired: 10000 MILLISECONDS")),
            // The transport went away while waiting: SSHJ chains the TransportException.
            ConnectionException(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")),
            // Interrupted while waiting.
            ConnectionException(InterruptedException()),
            // The request could not even be sent.
            TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"),
            IOException("Broken pipe"),
            IllegalStateException("not usable"),
        )
        for (failure in silent) {
            assertFalse(transportAnswered { throw failure }, "$failure")
        }
    }

    @Test
    fun `a probe interrupted while it waits leaves the flag of the thread set`() {
        try {
            assertFalse(transportAnswered { throw InterruptedException() })
            assertTrue(Thread.currentThread().isInterrupted, "the listener loop reads the flag")
        } finally {
            Thread.interrupted()
        }
    }

    private fun clientAnswering(promise: Promise<SSHPacket, ConnectionException>): Pair<SSHClient, Connection> {
        val connection = mockk<Connection>()
        every { connection.sendGlobalRequest(any(), any(), any()) } returns promise
        val client = mockk<SSHClient>()
        every { client.connection } returns connection
        return client to connection
    }

    private fun promise() = Promise<SSHPacket, ConnectionException>("probe", ConnectionException.chainer, LoggerFactory.DEFAULT)

    @Test
    fun `the probe of the transport is a keepalive request that asks for an answer`() {
        val answered = promise().also { it.deliver(SSHPacket()) }
        val (client, connection) = clientAnswering(answered)

        assertTrue(probeTransport(client))

        // want_reply must be true, or the server never answers and the probe always times out.
        verify(exactly = 1) { connection.sendGlobalRequest("keepalive@openssh.com", true, any()) }
        assertEquals(10L, TRANSPORT_PROBE_TIMEOUT_SECONDS)
    }

    @Test
    fun `the probe of the transport counts the failure OpenSSH answers as an answer`() {
        val failed = promise().also { it.deliverError(ConnectionException("Global request [keepalive@openssh.com] failed")) }

        assertTrue(probeTransport(clientAnswering(failed).first))
    }

    @Test
    fun `the probe of the transport is not answered by a transport that went away`() {
        val lost = promise().also { it.deliverError(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")) }

        assertFalse(probeTransport(clientAnswering(lost).first))
    }

    @Test
    fun `the probe of the transport is not answered when the request cannot be sent`() {
        val connection = mockk<Connection>()
        every { connection.sendGlobalRequest(any(), any(), any()) } throws TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")
        val client = mockk<SSHClient>()
        every { client.connection } returns connection

        assertFalse(probeTransport(client))
    }

    // ── What stays fatal ─────────────────────────────────────────────────────────

    @Test
    fun `a lost transport stays fatal`() {
        val script = Script(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"))

        val failure = runListener(script)

        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, failure)
        assertEquals(1, script.calls)
    }

    @Test
    fun `a broken listening socket stays fatal`() {
        val script = Script(SocketException("Socket closed by the system"))

        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, runListener(script))
        assertEquals(1, script.calls)
    }

    @Test
    fun `any other IO failure stays fatal`() {
        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, runListener(Script(IOException())))
    }

    @Test
    fun `the message of the exception is never the failure the tunnel carries`() {
        // It comes from SSHJ, in English, and can name a host and a port: a fixed text is stored instead.
        val failure = runListener(Script(TransportException(DisconnectReason.CONNECTION_LOST, "db.interne.example:5432 lost")))

        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, failure)
        assertFalse(failure!!.contains("db.interne"))
    }

    @Test
    fun `a listen that returns right away is the end of a closed tunnel`() {
        val script = Script()

        assertNull(runListener(script, socket = closed))
        assertEquals(1, script.calls)
    }

    // ── Logs ─────────────────────────────────────────────────────────────────────

    /** The text the listener logs while [block] runs. */
    private fun logOf(block: () -> Unit): String {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return tree.entries.joinToString("\n") { it.message }
    }

    @Test
    fun `a refusal is logged by its class only, never the host or the port it concerned`() {
        val logged = logOf {
            val refusal = OpenFailException("direct-tcpip", OpenFailException.Reason.CONNECT_FAILED, "db.interne.example:5432 refused")
            runListener(Script(refusal))
        }

        assertFalse(logged.isBlank(), "the refusal is logged")
        assertFalse(logged.contains("db.interne"), logged)
        assertFalse(logged.contains("5432"), logged)
        assertFalse(logged.contains("direct-tcpip"), logged)
    }

    @Test
    fun `a channel open timeout is logged by its class only, never the host or the port it concerned`() {
        val logged = logOf {
            val timeout = ConnectionException(
                "db.interne.example:5432 Timeout expired",
                TimeoutException("db.interne.example:5432 Timeout expired: 30000 MILLISECONDS"),
            )
            runListener(Script(timeout), probe = { true })
        }

        assertFalse(logged.isBlank(), "the timeout is logged")
        assertTrue(logged.contains("ConnectionException"), logged)
        assertFalse(logged.contains("db.interne"), logged)
        assertFalse(logged.contains("5432"), logged)
        assertFalse(logged.contains("Timeout expired"), logged)
    }

    @Test
    fun `a channel open timeout whose probe got no answer is logged without the host or the port`() {
        val logged = logOf {
            val timeout = ConnectionException(
                "db.interne.example:5432 Timeout expired",
                TimeoutException("db.interne.example:5432 Timeout expired: 30000 MILLISECONDS"),
            )
            runListener(Script(timeout), probe = { false })
        }

        assertFalse(logged.isBlank(), "the end of the tunnel is logged")
        assertFalse(logged.contains("db.interne"), logged)
        assertFalse(logged.contains("5432"), logged)
        assertFalse(logged.contains("Timeout expired"), logged)
    }

    private companion object {
        /** More listen() calls than a stop can need: the scripts that must be left once end instead of looping. */
        const val MAX_CALLS = 5
    }
}
