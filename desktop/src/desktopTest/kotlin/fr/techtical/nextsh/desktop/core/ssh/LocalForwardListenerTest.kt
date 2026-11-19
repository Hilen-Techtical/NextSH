// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.transport.TransportException
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The accept loop of a local forward. SSHJ's `LocalPortForwarder.listen()` ends with an exception when
 * the channel of ONE client connection cannot be opened: the server refuses it (the target refuses,
 * `AllowTcpForwarding no`, `PermitOpen`) or does not answer within the delay of SSHJ (a target behind
 * a firewall that drops the packets). That used to end the whole tunnel: a client that retries then
 * killed it again and again, each time with a full SSH reconnection. The listener goes on after such a
 * failure while the SSH connection is alive, and stays fatal for what really breaks the tunnel. The
 * function logs nothing: the caller does, through `onClientFailed`.
 */
class LocalForwardListenerTest {

    private fun refused(reason: OpenFailException.Reason = OpenFailException.Reason.CONNECT_FAILED) =
        OpenFailException("direct-tcpip", reason, "Connection refused")

    private val openSocket = ServerSocket()
    private val closedSocket = ServerSocket().also { it.close() }

    @AfterTest
    fun tearDown() {
        openSocket.close()
        // The flag belongs to the worker thread of the tests: a test that set it must not leak it.
        Thread.interrupted()
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

    @Test
    fun `a client connection the server refuses does not end the tunnel`() {
        val script = Script(refused(), refused(), refused())

        val failure = runLocalForwardListener(script::listen, openSocket, clientConnected = { true })

        assertNull(failure, "the listener ended only because listen() returned: the tunnel was closed")
        assertEquals(4, script.calls, "three refusals, then it listened on and came back normally")
    }

    @Test
    fun `every kind of refusal of the server is survived`() {
        for (reason in OpenFailException.Reason.entries) {
            val script = Script(refused(reason))

            assertNull(runLocalForwardListener(script::listen, openSocket, clientConnected = { true }), "$reason")
            assertEquals(2, script.calls, "$reason")
        }
    }

    @Test
    fun `a refusal wrapped in another exception is still a refusal`() {
        val script = Script(ConnectionException("open failed", refused()))

        assertNull(runLocalForwardListener(script::listen, openSocket, clientConnected = { true }))
        assertEquals(2, script.calls)
    }

    @Test
    fun `a refusal on a connection that is gone ends the loop with that failure`() {
        val refusal = refused()
        val script = Script(refusal)

        val failure = runLocalForwardListener(script::listen, openSocket, clientConnected = { false })

        assertSame(refusal, failure, "the failure is handed to the caller as it is")
        assertEquals(1, script.calls, "no point in listening on a dead connection")
    }

    @Test
    fun `a refusal after the tunnel was closed is the end of the tunnel, not a failure`() {
        val script = Script(refused())

        assertNull(runLocalForwardListener(script::listen, closedSocket, clientConnected = { true }))
        assertEquals(1, script.calls)
    }

    @Test
    fun `a refusal racing an interrupt ends the listener, listen is not entered again`() {
        // The stopper cancels the job (an interrupt of the thread), then closes the socket: a refusal
        // that lands in between must not send the loop back into listen(), the call the interrupt is
        // there to end.
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
                serverSocket = openSocket,
                clientConnected = { true },
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
                serverSocket = openSocket,
                clientConnected = { true },
            )
        } finally {
            Thread.interrupted()
        }

        assertNull(failure)
        assertEquals(1, calls)
    }

    @Test
    fun `a refusal once the stop is requested ends the listener without an interrupt`() {
        // The second signal: the job is cancelled, while nothing raised the interrupt flag of this
        // thread (a piece of code in between swallowed it). The loop must not depend on the flag.
        val script = Script(refused(), refused(), refused())

        val failure = runLocalForwardListener(script::listen, openSocket, clientConnected = { true }, stopRequested = { true })

        assertFalse(Thread.currentThread().isInterrupted, "the flag was not raised: only stopRequested speaks")
        assertNull(failure, "a requested stop is not a failure")
        assertEquals(1, script.calls, "listen is not entered again on a tunnel that is being stopped")
    }

    @Test
    fun `a stop requested between two refusals ends the listener at the next one`() {
        var stop = false
        val script = Script(refused(), refused(), refused())
        var refusals = 0

        val failure = runLocalForwardListener(
            script::listen,
            openSocket,
            clientConnected = { true },
            stopRequested = { stop },
            onClientFailed = {
                refusals++
                stop = true
            },
        )

        assertNull(failure)
        assertEquals(1, refusals, "the first refusal was survived and reported")
        assertEquals(2, script.calls, "the second refusal came after the stop request: it ended the loop")
    }

    @Test
    fun `a requested stop wins over any failure`() {
        val script = Script(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"))

        val failure = runLocalForwardListener(script::listen, openSocket, clientConnected = { true }, stopRequested = { true })

        assertNull(failure, "the stopper owns the state: a transport torn down by the stop is not a loss")
        assertEquals(1, script.calls)
    }

    @Test
    fun `a lost transport stays fatal`() {
        val loss = TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")
        val script = Script(loss)

        val failure = runLocalForwardListener(script::listen, openSocket, clientConnected = { true })

        assertSame(loss, failure)
        assertEquals(1, script.calls)
    }

    @Test
    fun `a channel that did not open in time is survived while the connection is alive`() {
        // sshd answers a direct-tcpip only once its own connection to the target is done or failed:
        // behind a firewall that drops the packets it waits about two minutes, SSHJ gives up at 30 s
        // on a healthy transport. Ending the tunnel there would kill it on every client attempt.
        // Both shapes of SSHJ's exception: with a message and without.
        val shapes = listOf(
            ConnectionException("Timeout expired", TimeoutException("Timeout expired: 30000 MILLISECONDS")),
            ConnectionException(TimeoutException("Timeout expired: 30000 MILLISECONDS")),
        )
        for (timeout in shapes) {
            val script = Script(timeout)
            val seen = mutableListOf<IOException>()

            val failure = runLocalForwardListener(
                script::listen,
                openSocket,
                clientConnected = { true },
                onClientFailed = { seen += it },
            )

            assertNull(failure, "the listener ended only because listen() returned: the tunnel was closed")
            assertEquals(2, script.calls, "the timeout, then it listened on and came back normally")
            assertEquals(listOf<IOException>(timeout), seen, "the timeout was reported to the caller")
        }
    }

    @Test
    fun `a timeout wrapped in another exception is still a channel open timeout`() {
        val script = Script(ConnectionException("open failed", ConnectionException(TimeoutException("Timeout expired"))))

        assertNull(runLocalForwardListener(script::listen, openSocket, clientConnected = { true }))
        assertEquals(2, script.calls)
    }

    @Test
    fun `a channel that did not open in time on a connection that is gone stays fatal`() {
        // The connection is torn down: nothing to listen for, the tunnel has to be reconnected.
        val timeout = ConnectionException("Timeout expired", TimeoutException("Timeout expired: 30000 MILLISECONDS"))
        val script = Script(timeout)

        val failure = runLocalForwardListener(script::listen, openSocket, clientConnected = { false })

        assertSame(timeout, failure)
        assertEquals(1, script.calls, "no point in listening on a dead connection")
    }

    @Test
    fun `a channel open timeout after the tunnel was closed is the end of the tunnel, not a failure`() {
        val script = Script(ConnectionException(TimeoutException("Timeout expired")))

        assertNull(runLocalForwardListener(script::listen, closedSocket, clientConnected = { true }))
        assertEquals(1, script.calls)
    }

    @Test
    fun `a timeout that is not the one of a channel open stays fatal`() {
        // A timeout of the transport, or of a socket, is not a client connection that got no answer.
        val transportTimeout = TransportException(TimeoutException("Timeout expired"))
        val socketTimeout = SocketTimeoutException("Read timed out")

        for (failure in listOf<IOException>(transportTimeout, socketTimeout)) {
            val script = Script(failure)

            assertSame(failure, runLocalForwardListener(script::listen, openSocket, clientConnected = { true }), "$failure")
            assertEquals(1, script.calls, "$failure")
        }
    }

    @Test
    fun `a connection exception that is neither a refusal nor a timeout stays fatal`() {
        val other = ConnectionException("Channel closed")
        val script = Script(other)

        assertSame(other, runLocalForwardListener(script::listen, openSocket, clientConnected = { true }))
        assertEquals(1, script.calls)
    }

    @Test
    fun `a broken listening socket stays fatal`() {
        val broken = SocketException("Socket closed by the system")
        val script = Script(broken)

        assertSame(broken, runLocalForwardListener(script::listen, openSocket, clientConnected = { true }))
        assertEquals(1, script.calls)
    }

    @Test
    fun `any other IO failure stays fatal`() {
        val failure = IOException()

        assertSame(failure, runLocalForwardListener(Script(failure)::listen, openSocket, clientConnected = { true }))
    }

    @Test
    fun `a listen that returns right away is the end of a closed tunnel`() {
        val script = Script()

        assertNull(runLocalForwardListener(script::listen, closedSocket, clientConnected = { true }))
        assertEquals(1, script.calls)
    }

    @Test
    fun `onClientFailed sees every survived failure and nothing else`() {
        val first = refused()
        val second = ConnectionException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))
        val third = refused(OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED)
        val loss = TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")
        val seen = mutableListOf<IOException>()

        val failure = runLocalForwardListener(
            Script(first, second, third, loss)::listen,
            openSocket,
            clientConnected = { true },
            onClientFailed = { seen += it },
        )

        assertSame(loss, failure)
        assertEquals(listOf<IOException>(first, second, third), seen, "the refusals and the timeout, in order, and not the loss")
    }

    private companion object {
        /** More listen() calls than a stop can need: the scripts that must be left once end instead of looping. */
        const val MAX_CALLS = 5
    }
}
