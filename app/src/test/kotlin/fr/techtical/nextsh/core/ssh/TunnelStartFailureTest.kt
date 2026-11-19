// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelPortProblem
import fr.techtical.nextsh.domain.model.TunnelType
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.transport.TransportException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.BindException
import java.net.SocketException
import java.util.concurrent.TimeoutException

/**
 * How the exception of a failed start becomes a reason, with exceptions built by hand: no SSH
 * client, no socket.
 *
 * The three shapes a remote forward can fail with are the ones SSHJ 0.38.0 produces, read on its
 * bytecode (see [isRemoteForwardRefusal]):
 * - the server answers SSH_MSG_REQUEST_FAILURE: `ConnectionException("Global request [...] failed")`,
 *   no cause, reason UNKNOWN, the transport alive;
 * - nothing answers in time: `Promise.retrieve` chains a `TimeoutException` into a
 *   `ConnectionException` (cause set);
 * - the transport dies while the request waits: the error of the transport is delivered to the
 *   request, wrapped in a `ConnectionException` (cause set) unless it already is one (then it carries
 *   its own reason), and the transport is gone.
 */
class TunnelStartFailureTest {

    private val connected = { true }
    private val gone = { false }

    private fun classify(e: Throwable, type: TunnelType, clientConnected: () -> Boolean = connected) =
        classifyStartFailure(e, type, clientConnected)

    // ── The refusal of a remote forward ───────────────────────────────────────

    /** What `RemotePortForwarder.bind` throws when the server answers REQUEST_FAILURE. */
    private fun refusalOfTheServer() = ConnectionException("Global request [global req for tcpip-forward] failed")

    @Test
    fun `the refusal of the server is told, whatever the text of the message`() {
        assertEquals(StartFailure.REMOTE_FORWARD_REFUSED, classify(refusalOfTheServer(), TunnelType.REMOTE_FORWARD))
        // The text is not what is read: a library that rewords it still gives the same answer.
        assertEquals(
            StartFailure.REMOTE_FORWARD_REFUSED,
            classify(ConnectionException("a completely different wording"), TunnelType.REMOTE_FORWARD),
        )
        assertEquals(
            StartFailure.REMOTE_FORWARD_REFUSED,
            classify(ConnectionException(DisconnectReason.UNKNOWN), TunnelType.REMOTE_FORWARD),
        )
    }

    @Test
    fun `a timeout is not the refusal of the server`() {
        // Promise.retrieve: a TimeoutException chained by the ConnectionException chainer.
        val timeout = ConnectionException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))

        assertEquals(StartFailure.GENERIC, classify(timeout, TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `a transport lost during the request is not the refusal of the server`() {
        // Wrapped by the chainer: a cause.
        val wrapped = ConnectionException(TransportException(DisconnectReason.CONNECTION_LOST))
        // A connection error of the protocol, delivered as it is: it carries its own reason.
        val protocolError = ConnectionException(DisconnectReason.PROTOCOL_ERROR, "Got a global request response when none was requested")
        // Writing on a transport that is already dead.
        val deadOnWrite = TransportException(DisconnectReason.CONNECTION_LOST, "Socket closed")

        for (failure in listOf(wrapped, protocolError, deadOnWrite)) {
            assertEquals(StartFailure.GENERIC, classify(failure, TunnelType.REMOTE_FORWARD), "$failure")
        }
    }

    @Test
    fun `the shape of a refusal is not enough when the connection is gone`() {
        // A refusal followed at once by a cut cannot be told from a cut: the generic text is the safe one.
        assertEquals(StartFailure.GENERIC, classify(refusalOfTheServer(), TunnelType.REMOTE_FORWARD, gone))
    }

    @Test
    fun `a refusal to open a channel is not the refusal of a remote forward`() {
        // OpenFailException extends ConnectionException but is the refusal of a channel, not of a request.
        val channelRefusal = OpenFailException("direct-tcpip", OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED, "no")

        assertEquals(StartFailure.GENERIC, classify(channelRefusal, TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `the refusal text is for a remote forward only`() {
        assertEquals(StartFailure.GENERIC, classify(refusalOfTheServer(), TunnelType.LOCAL_FORWARD))
        assertEquals(StartFailure.GENERIC, classify(refusalOfTheServer(), TunnelType.DYNAMIC_SOCKS5))
    }

    @Test
    fun `the refusal is retryable, it is not one of the failures that stop the reconnection`() {
        assertFalse(StartFailure.REMOTE_FORWARD_REFUSED.code.isNonRetryable())
        assertEquals(TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE, StartFailure.REMOTE_FORWARD_REFUSED.message)
    }

    // ── A local port taken at the last moment ─────────────────────────────────

    @Test
    fun `a bind exception is the port in use for the types that listen on a local port`() {
        val bind = BindException("Address already in use")

        assertEquals(StartFailure.PORT_IN_USE, classify(bind, TunnelType.LOCAL_FORWARD))
        assertEquals(StartFailure.PORT_IN_USE, classify(bind, TunnelType.DYNAMIC_SOCKS5))
        assertEquals(SshErrorCode.PORT_IN_USE, StartFailure.PORT_IN_USE.code)
        assertEquals(TUNNEL_PORT_IN_USE_MESSAGE, StartFailure.PORT_IN_USE.message)
        // A remote forward binds nothing on the phone.
        assertEquals(StartFailure.GENERIC, classify(bind, TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `a bind exception is found among the causes, and a plain socket error is not one`() {
        val wrapped = IOException("start failed", BindException("Address already in use"))

        assertEquals(StartFailure.PORT_IN_USE, classify(wrapped, TunnelType.LOCAL_FORWARD))
        assertEquals(StartFailure.GENERIC, classify(SocketException("Connection reset"), TunnelType.LOCAL_FORWARD))
        assertEquals(StartFailure.GENERIC, classify(IllegalStateException("boom"), TunnelType.DYNAMIC_SOCKS5))
    }

    @Test
    fun `a chain of causes that loops does not loop the classification`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)

        assertEquals(StartFailure.GENERIC, classify(a, TunnelType.LOCAL_FORWARD))
    }

    @Test
    fun `the port in use stays retryable, the other application may free the port`() {
        assertFalse(StartFailure.PORT_IN_USE.code.isNonRetryable())
    }

    // ── The probe of a local port ─────────────────────────────────────────────

    private fun taken(failure: Throwable?): Boolean =
        isLocalPortTaken(8080) { if (failure != null) throw failure }

    @Test
    fun `only a bind failure says that the port is taken`() {
        assertFalse(taken(null))
        assertTrue(taken(BindException("Address already in use")))
        assertTrue(taken(IOException("probe failed", BindException("Address already in use"))))
    }

    @Test
    fun `another failure of the probe does not say that the port is taken`() {
        // The real bind that follows fails on its own, and is classified (classifyStartFailure).
        assertFalse(taken(IOException("Network is unreachable")))
        assertFalse(taken(SocketException("Permission denied")))
        assertFalse(taken(SecurityException("denied by the policy")))
        assertFalse(taken(IllegalArgumentException("Port value out of range")))
    }

    @Test
    fun `the real probe sees a port that is held and one that is free`() {
        val held = java.net.ServerSocket(0)
        try {
            assertTrue(isLocalPortTaken(held.localPort))
        } finally {
            held.close()
        }
        assertFalse(isLocalPortTaken(held.localPort))
    }

    // ── The texts ─────────────────────────────────────────────────────────────

    @Test
    fun `each problem of a port has its own text, and each text has a translation`() {
        val messages = TunnelPortProblem.values().map { it.tunnelMessage() }

        assertEquals(TunnelPortProblem.values().size, messages.toSet().size)
        messages.forEach { assertNotNull(tunnelMessageRes(it), "$it has no string resource") }
        assertEquals(TUNNEL_LOCAL_PORT_INVALID_MESSAGE, TunnelPortProblem.LOCAL_OUT_OF_RANGE.tunnelMessage())
        assertEquals(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, TunnelPortProblem.LOCAL_RESERVED.tunnelMessage())
        assertEquals(TUNNEL_REMOTE_PORT_INVALID_MESSAGE, TunnelPortProblem.REMOTE_OUT_OF_RANGE.tunnelMessage())
    }

    @Test
    fun `no reason carries the message of an exception or a port`() {
        val reasons = listOf(StartFailure.GENERIC, StartFailure.PORT_IN_USE, StartFailure.REMOTE_FORWARD_REFUSED)
            .map { it.message } + TunnelPortProblem.values().map { it.tunnelMessage() }

        reasons.forEach { assertTrue(it.none { c -> c.isDigit() }, it) }
    }
}
