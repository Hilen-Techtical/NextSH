// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.connect_error_generic
import fr.techtical.nextsh.desktop.generated.resources.connect_error_host_not_found
import fr.techtical.nextsh.desktop.generated.resources.connect_error_refused
import fr.techtical.nextsh.desktop.generated.resources.connect_error_timeout
import fr.techtical.nextsh.desktop.generated.resources.session_error_biometric_unsupported
import fr.techtical.nextsh.desktop.generated.resources.session_error_key_or_certificate_missing
import fr.techtical.nextsh.desktop.generated.resources.session_error_ssh_key_missing
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_auth_failed
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_fido2_unsupported
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_host_key_rejected
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_key_unreadable
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_local_port_reserved
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_password_missing
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_port_in_use
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_remote_forward_refused
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_setup_timeout
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_start_failed
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of the texts of the failures of a tunnel start (`TunnelSetupFailure.kt`) : the classification
 * of the exception of a failed forwarder setup, the string it resolves to, the credential the vault
 * does not hold, and the error of a failed connection.
 *
 * The exceptions are built by hand with the constructors of SSHJ 0.38 (the shapes are the ones its
 * bytecode produces, see [classifyTunnelSetupFailure]). The texts depend on the machine language, so
 * they are compared with the string resource, never with a literal. What must never reach the user
 * is checked on fragments of what SSHJ and the JDK say.
 */
class TunnelSetupFailureTest {

    private val localPort = 18080
    private val remotePort = 9443

    private fun configOf(type: TunnelType) = TunnelConfig(
        id = "tun-setup", label = "setup", hostId = "host-1", type = type,
        localPort = localPort, remoteHost = "127.0.0.1", remotePort = remotePort,
    )

    private fun classify(e: Throwable, type: TunnelType, connected: Boolean = true, reserved: Boolean = false) =
        classifyTunnelSetupFailure(e, type, localPortReserved = { reserved }) { connected }

    /** What SSHJ 0.38 hands a remote forward request that the server answered with a failure. */
    private fun refusal(text: String = "Global request [tcpip-forward] failed") = ConnectionException(text)

    // ── Classification : the local port ────────────────────────────────────

    @Test
    fun `a BindException on a LOCAL_FORWARD is a port in use`() {
        assertEquals(
            TunnelSetupFailure.PORT_IN_USE,
            classify(BindException("Address already in use: bind"), TunnelType.LOCAL_FORWARD),
        )
    }

    @Test
    fun `an IOException caused by a BindException on a SOCKS5 server is a port in use`() {
        assertEquals(
            TunnelSetupFailure.PORT_IN_USE,
            classify(IOException("cannot start", BindException("Address already in use")), TunnelType.DYNAMIC_SOCKS5),
        )
    }

    @Test
    fun `a BindException on a REMOTE_FORWARD is not a port in use`() {
        // A remote forward binds nothing on this computer : the local port is only the target.
        assertEquals(TunnelSetupFailure.GENERIC, classify(BindException("Address already in use"), TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `a BindException deeper than the bound of the chain is not looked for`() {
        var cause: Throwable = BindException("Address already in use")
        repeat(10) { cause = IOException("level $it", cause) }

        assertEquals(TunnelSetupFailure.GENERIC, classify(cause, TunnelType.LOCAL_FORWARD))
    }

    @Test
    fun `a BindException on a reserved local port is a reserved port, not a port in use`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            assertEquals(
                TunnelSetupFailure.PORT_RESERVED,
                classify(BindException("Permission denied"), type, reserved = true),
                "type $type",
            )
            assertEquals(
                TunnelSetupFailure.PORT_RESERVED,
                classify(IOException("cannot start", BindException("Permission denied")), type, reserved = true),
                "type $type, cause",
            )
        }
    }

    @Test
    fun `a reserved local port changes nothing for what is not a bind failure or a local listener`() {
        assertEquals(TunnelSetupFailure.GENERIC, classify(IOException("boom"), TunnelType.LOCAL_FORWARD, reserved = true))
        assertEquals(TunnelSetupFailure.GENERIC, classify(BindException("x"), TunnelType.REMOTE_FORWARD, reserved = true))
        assertEquals(
            TunnelSetupFailure.REMOTE_FORWARD_REFUSED,
            classify(refusal(), TunnelType.REMOTE_FORWARD, reserved = true),
        )
    }

    @Test
    fun `the reserved port is asked only for a bind failure of a local listener`() {
        var asked = 0
        val counting = { asked++; true }

        classifyTunnelSetupFailure(IOException("boom"), TunnelType.LOCAL_FORWARD, localPortReserved = counting) { true }
        classifyTunnelSetupFailure(BindException("x"), TunnelType.REMOTE_FORWARD, localPortReserved = counting) { true }
        classifyTunnelSetupFailure(refusal(), TunnelType.REMOTE_FORWARD, localPortReserved = counting) { true }
        assertEquals(0, asked, "nothing to connect to unless a local listener failed to bind")

        classifyTunnelSetupFailure(BindException("x"), TunnelType.LOCAL_FORWARD, localPortReserved = counting) { true }
        assertEquals(1, asked)
    }

    // ── The local port : what the system keeps for administrators ───────────

    /** A system where nobody listens on any port, or where somebody listens on all of them. */
    private val nobodyListens: (Int) -> Boolean = { false }
    private val everybodyListens: (Int) -> Boolean = { true }

    @Test
    fun `ports below 1024 are reserved on Linux and macOS when nobody listens, not on Windows`() {
        for (osName in listOf("Linux", "Mac OS X")) {
            assertTrue(isReservedLocalPort(1, osName, nobodyListens), "1 on $osName")
            assertTrue(isReservedLocalPort(80, osName, nobodyListens), "80 on $osName")
            assertTrue(isReservedLocalPort(1023, osName, nobodyListens), "1023 on $osName")
            assertFalse(isReservedLocalPort(1024, osName, nobodyListens), "1024 on $osName")
            assertFalse(isReservedLocalPort(8080, osName, nobodyListens), "8080 on $osName")
            assertFalse(isReservedLocalPort(0, osName, nobodyListens), "port 0 asks for any free port on $osName")
        }
        for (osName in listOf("Windows 11", "windows 10", "WINDOWS SERVER 2022")) {
            assertFalse(isReservedLocalPort(80, osName, nobodyListens), "80 on $osName")
            assertFalse(isReservedLocalPort(1023, osName, nobodyListens), "1023 on $osName")
        }
    }

    @Test
    fun `a port below 1024 that a service holds is not reserved, it is taken`() {
        // A process run as root, CAP_NET_BIND_SERVICE or a lowered ip_unprivileged_port_start : the bind
        // fails because the port is held, not because it is forbidden.
        for (osName in listOf("Linux", "Mac OS X")) {
            assertFalse(isReservedLocalPort(80, osName, everybodyListens), "80 on $osName")
            assertFalse(isReservedLocalPort(1023, osName, everybodyListens), "1023 on $osName")
        }
    }

    @Test
    fun `nobody is connected to unless the port is below 1024 on a system that keeps such ports`() {
        val asked = mutableListOf<Int>()
        val counting: (Int) -> Boolean = { asked += it; false }

        isReservedLocalPort(8080, "Linux", counting)
        isReservedLocalPort(1024, "Mac OS X", counting)
        isReservedLocalPort(0, "Linux", counting)
        isReservedLocalPort(80, "Windows 11", counting)
        assertEquals(emptyList(), asked, "no connection for these")

        isReservedLocalPort(80, "Linux", counting)
        assertEquals(listOf(80), asked)
    }

    @Test
    fun `the check before the connection answers for the system it runs on`() {
        val denied: (Int) -> Unit = { throw BindException("Permission denied") }
        val free: (Int) -> Unit = { }

        assertNull(localPortFailure(80, "Linux", free, nobodyListens))
        assertNull(localPortFailure(8080, "Windows 11", free, nobodyListens))
        assertEquals(TunnelSetupFailure.PORT_RESERVED, localPortFailure(80, "Linux", denied, nobodyListens))
        assertEquals(TunnelSetupFailure.PORT_RESERVED, localPortFailure(1023, "Mac OS X", denied, nobodyListens))
        // Windows keeps no port for administrators : a failure there is a port taken.
        assertEquals(TunnelSetupFailure.PORT_IN_USE, localPortFailure(80, "Windows 11", denied, nobodyListens))
        // From 1024 on, a failure is a port taken, whatever the system.
        assertEquals(TunnelSetupFailure.PORT_IN_USE, localPortFailure(1024, "Linux", denied, nobodyListens))
        assertEquals(TunnelSetupFailure.PORT_IN_USE, localPortFailure(8080, "Linux", denied, nobodyListens))
    }

    @Test
    fun `a failure of the check on a port below 1024 that a service holds is a port in use`() {
        val denied: (Int) -> Unit = { throw BindException("Address already in use") }

        for (osName in listOf("Linux", "Mac OS X")) {
            assertEquals(TunnelSetupFailure.PORT_IN_USE, localPortFailure(80, osName, denied, everybodyListens), osName)
        }
    }

    @Test
    fun `the check before the connection hands the port to the probe and treats a BindException as a port in use`() {
        val probed = mutableListOf<Int>()

        assertEquals(
            TunnelSetupFailure.PORT_IN_USE,
            localPortFailure(8080, "Linux", probe = { port -> probed += port; throw BindException("boom") }, someoneListens = nobodyListens),
        )
        assertEquals(listOf(8080), probed)
    }

    @Test
    fun `an IOException of the check that is not a BindException says nothing about the port`() {
        for (osName in listOf("Linux", "Windows 11")) {
            assertEquals(
                TunnelSetupFailure.GENERIC,
                localPortFailure(80, osName, probe = { throw java.net.SocketException("Too many open files") }, someoneListens = everybodyListens),
                osName,
            )
        }
        assertEquals(
            TunnelSetupFailure.GENERIC,
            localPortFailure(8080, "Linux", probe = { throw IOException("boom") }, someoneListens = nobodyListens),
        )
    }

    // ── Network-level failure of an open ────────────────────────────────────

    @Test
    fun `a no route or network unreachable failure is a network-level one, however it is worded or wrapped`() {
        assertTrue(java.net.NoRouteToHostException("No route to host: connect").isNetworkLevelFailure())
        assertTrue(java.net.SocketException("Network is unreachable: connect").isNetworkLevelFailure())
        assertTrue(java.net.SocketException("No route to host").isNetworkLevelFailure())
        assertTrue(ConnectException("Network is unreachable (connect failed)").isNetworkLevelFailure())
        assertTrue(java.net.SocketException("NETWORK IS DOWN").isNetworkLevelFailure())
        // Wrapped by SSHJ, or by another exception.
        assertTrue(TransportException("failed", java.net.NoRouteToHostException("No route to host")).isNetworkLevelFailure())
        assertTrue(IOException("outer", ConnectException("Network is unreachable: connect")).isNetworkLevelFailure())
    }

    @Test
    fun `a timeout, a refusal or anything else is not a network-level failure`() {
        assertFalse(SocketTimeoutException("connect timed out").isNetworkLevelFailure())
        assertFalse(TimeoutException("no answer").isNetworkLevelFailure())
        assertFalse(ConnectException("Connection refused: connect").isNetworkLevelFailure())
        assertFalse(UnknownHostException("host.example").isNetworkLevelFailure())
        assertFalse(IOException("boom").isNetworkLevelFailure())
        // A timeout wins over a network wording in the same chain.
        assertFalse(
            IOException("outer", SocketTimeoutException("Network is unreachable but timed out")).isNetworkLevelFailure(),
        )
    }

    // ── The proof that something listens ────────────────────────────────────

    @Test
    fun `a connection to a port that something listens on succeeds, and to a free one does not`() {
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { listener ->
            assertTrue(isLocalPortListening(listener.localPort), "a listener is on ${listener.localPort}")
            listener.localPort
        }

        // The listener is closed : the port is free again, a connection is refused at once.
        assertFalse(isLocalPortListening(port), "nobody listens on $port any more")
    }

    // ── Classification : the refusal of a remote forward ───────────────────

    @Test
    fun `the refusal of a remote forward on a live connection is recognised`() {
        assertEquals(TunnelSetupFailure.REMOTE_FORWARD_REFUSED, classify(refusal(), TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `the refusal is recognised by its shape, not by its text`() {
        assertEquals(
            TunnelSetupFailure.REMOTE_FORWARD_REFUSED,
            classify(refusal("the library may word this differently one day"), TunnelType.REMOTE_FORWARD),
        )
    }

    @Test
    fun `the same exception on a connection that is gone is the generic failure`() {
        // A refusal followed by a drop looks like a drop : the generic text is the safe one.
        assertEquals(TunnelSetupFailure.GENERIC, classify(refusal(), TunnelType.REMOTE_FORWARD, connected = false))
    }

    @Test
    fun `the same exception on a local forward or a SOCKS5 server is the generic failure`() {
        assertEquals(TunnelSetupFailure.GENERIC, classify(refusal(), TunnelType.LOCAL_FORWARD))
        assertEquals(TunnelSetupFailure.GENERIC, classify(refusal(), TunnelType.DYNAMIC_SOCKS5))
    }

    @Test
    fun `a refused channel is not the refusal of a remote forward`() {
        val refusedChannel = OpenFailException("direct-tcpip", OpenFailException.Reason.CONNECT_FAILED, "Connection refused")

        assertEquals(TunnelSetupFailure.GENERIC, classify(refusedChannel, TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `the connection is asked only when the shape of the exception needs it`() {
        var asked = 0
        val counting = { asked++; true }

        classifyTunnelSetupFailure(BindException("x"), TunnelType.LOCAL_FORWARD, clientConnected = counting)
        classifyTunnelSetupFailure(IOException("boom"), TunnelType.REMOTE_FORWARD, clientConnected = counting)
        assertEquals(0, asked, "a bind failure or a plain IOException does not need the connection")

        classifyTunnelSetupFailure(refusal(), TunnelType.REMOTE_FORWARD, clientConnected = counting)
        assertEquals(1, asked)
    }

    // ── Classification : timeout, lost connection, the rest ─────────────────

    @Test
    fun `a timed out request is a timeout whatever the type`() {
        val timedOut = ConnectionException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))

        for (type in TunnelType.entries) {
            assertEquals(TunnelSetupFailure.TIMEOUT, classify(timedOut, type), "type $type")
        }
    }

    @Test
    fun `a SocketTimeoutException is a timeout`() {
        assertEquals(TunnelSetupFailure.TIMEOUT, classify(SocketTimeoutException("Read timed out"), TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `a transport that went away is a lost connection`() {
        assertEquals(TunnelSetupFailure.CONNECTION_LOST, classify(TransportException("Broken transport"), TunnelType.REMOTE_FORWARD))
        assertEquals(
            TunnelSetupFailure.CONNECTION_LOST,
            classify(ConnectionException(TransportException("Broken transport")), TunnelType.REMOTE_FORWARD),
        )
    }

    @Test
    fun `a ConnectionException with a reason of its own is a lost connection`() {
        assertEquals(
            TunnelSetupFailure.CONNECTION_LOST,
            classify(ConnectionException(DisconnectReason.PROTOCOL_ERROR, "x"), TunnelType.REMOTE_FORWARD),
        )
    }

    @Test
    fun `an unknown exception is the generic failure`() {
        for (type in TunnelType.entries) {
            assertEquals(TunnelSetupFailure.GENERIC, classify(IOException("boom"), type), "IOException on $type")
            assertEquals(TunnelSetupFailure.GENERIC, classify(IllegalStateException("boom"), type), "IllegalStateException on $type")
        }
    }

    @Test
    fun `a chain of causes that loops ends on the generic failure`() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)

        for (type in TunnelType.entries) {
            assertEquals(TunnelSetupFailure.GENERIC, classify(first, type), "type $type")
        }
    }

    // ── Resolution : one text per category ──────────────────────────────────

    private val startFailed = "start failed (test text)"

    private suspend fun resolve(failure: TunnelSetupFailure) =
        failure.toStartError(configOf(TunnelType.REMOTE_FORWARD), startFailed)

    @Test
    fun `every category resolves to its code and its text`() = runTest {
        val config = configOf(TunnelType.REMOTE_FORWARD)

        val portInUse = resolve(TunnelSetupFailure.PORT_IN_USE)
        assertEquals(SshErrorCode.PORT_IN_USE, portInUse.code)
        assertEquals(getString(Res.string.tunnels_error_port_in_use, config.localPort), portInUse.message)

        val reserved = resolve(TunnelSetupFailure.PORT_RESERVED)
        assertEquals(SshErrorCode.UNKNOWN, reserved.code, "nobody holds a reserved port : not PORT_IN_USE")
        assertEquals(getString(Res.string.tunnels_error_local_port_reserved, config.localPort), reserved.message)

        val refused = resolve(TunnelSetupFailure.REMOTE_FORWARD_REFUSED)
        assertEquals(SshErrorCode.UNKNOWN, refused.code)
        assertEquals(getString(Res.string.tunnels_error_remote_forward_refused, config.remotePort), refused.message)

        val timeout = resolve(TunnelSetupFailure.TIMEOUT)
        assertEquals(SshErrorCode.TIMEOUT, timeout.code)
        assertEquals(getString(Res.string.tunnels_error_setup_timeout), timeout.message)

        val lost = resolve(TunnelSetupFailure.CONNECTION_LOST)
        assertEquals(SshErrorCode.UNKNOWN, lost.code)
        assertEquals(getString(Res.string.tunnels_error_connection_lost), lost.message)
    }

    @Test
    fun `the generic category answers exactly the text it is given`() = runTest {
        val generic = resolve(TunnelSetupFailure.GENERIC)

        assertEquals(SshErrorCode.UNKNOWN, generic.code)
        assertEquals(startFailed, generic.message)
    }

    @Test
    fun `the port texts name their own port and keep no format token`() = runTest {
        val portInUse = resolve(TunnelSetupFailure.PORT_IN_USE).message
        val reserved = resolve(TunnelSetupFailure.PORT_RESERVED).message
        val refused = resolve(TunnelSetupFailure.REMOTE_FORWARD_REFUSED).message

        assertTrue(portInUse.contains(localPort.toString()), "the local port must be named, got $portInUse")
        assertFalse(portInUse.contains(remotePort.toString()), "the remote port is not the one in use here, got $portInUse")
        assertTrue(reserved.contains(localPort.toString()), "the local port must be named, got $reserved")
        assertFalse(reserved.contains(remotePort.toString()), "the remote port is not the reserved one, got $reserved")
        assertTrue(refused.contains(remotePort.toString()), "the remote port must be named, got $refused")
        assertFalse(refused.contains(localPort.toString()), "the local port is not the refused one, got $refused")
        for (message in listOf(portInUse, reserved, refused)) {
            assertFalse(message.contains("%"), "a format specifier is left in $message")
        }
    }

    @Test
    fun `no text quotes what SSHJ or the JDK say`() = runTest {
        val forbidden = listOf("tcpip-forward", "Global request", "Address already in use", "Timeout expired", "Exception")

        for (failure in TunnelSetupFailure.entries) {
            val message = resolve(failure).message
            assertTrue(message.isNotBlank(), "$failure reads blank")
            assertTrue(forbidden.none { message.contains(it) }, "$failure leaks a library text: $message")
        }
    }

    @Test
    fun `the categories read differently from each other`() = runTest {
        val messages = TunnelSetupFailure.entries.map { resolve(it).message }

        assertEquals(messages.size, messages.toSet().size, "two categories read the same: $messages")
    }

    // ── The credential the vault does not hold ──────────────────────────────

    private val label = "gw-prod-01"

    @Test
    fun `a missing password names the host and says where to add it`() = runTest {
        val error = TunnelCredentialProblem.PASSWORD_MISSING.toAuthError(label)

        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(getString(Res.string.tunnels_error_password_missing, label), error.message)
        assertTrue(error.message.contains(label), error.message)
        assertFalse(error.message.contains("%"), error.message)
    }

    @Test
    fun `a missing key and a missing certificate use the session texts`() = runTest {
        val key = TunnelCredentialProblem.SSH_KEY_MISSING.toAuthError(label)
        val certificate = TunnelCredentialProblem.CERTIFICATE_MISSING.toAuthError(label)

        assertEquals(SshErrorCode.AUTH_FAILED, key.code)
        assertEquals(getString(Res.string.session_error_ssh_key_missing, label), key.message)
        assertEquals(SshErrorCode.AUTH_FAILED, certificate.code)
        assertEquals(getString(Res.string.session_error_key_or_certificate_missing, label), certificate.message)
        assertTrue(key.message.contains(label) && certificate.message.contains(label))
    }

    @Test
    fun `FIDO2 and the biometric key each have their own text and name no host`() = runTest {
        val fido2 = TunnelCredentialProblem.FIDO2_UNSUPPORTED.toAuthError(label)
        val biometric = TunnelCredentialProblem.BIOMETRIC_UNSUPPORTED.toAuthError(label)

        assertEquals(SshErrorCode.AUTH_FAILED, fido2.code)
        assertEquals(getString(Res.string.tunnels_error_fido2_unsupported), fido2.message)
        assertEquals(SshErrorCode.AUTH_FAILED, biometric.code)
        assertEquals(getString(Res.string.session_error_biometric_unsupported), biometric.message)
        assertNotEquals(fido2.message, biometric.message, "FIDO2 and the biometric key must not share a text")
        assertFalse(fido2.message.contains(label), "nothing to fix on the host itself: ${fido2.message}")
        assertFalse(biometric.message.contains(label), "nothing to fix on the host itself: ${biometric.message}")
    }

    @Test
    fun `no credential text is the old French literal and every one differs`() = runTest {
        val messages = TunnelCredentialProblem.entries.map { it.toAuthError(label).message }

        assertEquals(messages.size, messages.toSet().size, "two problems read the same: $messages")
        for (message in messages) {
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("absent du vault"), message)
            assertFalse(message.contains("Wave 5"), message)
        }
    }

    // ── The error of a failed connection ────────────────────────────────────

    private val hostKeyText =
        "Could not verify `ssh-ed25519` host key with fingerprint `ab:cd:ef:01:23:45` for `gw-prod-01` on port 22"

    @Test
    fun `a host key the verifier refused says where to look and quotes nothing`() = runTest {
        val refused = TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, hostKeyText)

        val error = tunnelConnectError(refused, 10_000)

        assertEquals(SshErrorCode.HOST_KEY_MISMATCH, error.code)
        assertEquals(getString(Res.string.tunnels_error_host_key_rejected), error.message)
        for (leaked in listOf("gw-prod-01", "fingerprint", "ssh-ed25519", "ab:cd")) {
            assertFalse(error.message.contains(leaked), "the key text leaked ($leaked): ${error.message}")
        }
    }

    @Test
    fun `a refused host key wrapped in another IOException is recognised too`() = runTest {
        val wrapped = IOException("connect failed", TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, hostKeyText))

        val error = tunnelConnectError(wrapped, 10_000)

        assertEquals(SshErrorCode.HOST_KEY_MISMATCH, error.code)
        assertEquals(getString(Res.string.tunnels_error_host_key_rejected), error.message)
    }

    @Test
    fun `another transport failure is a plain connection failure with no detail`() = runTest {
        val error = tunnelConnectError(TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "no matching kex at gw-prod-01"), 10_000)

        assertEquals(SshErrorCode.HOST_UNREACHABLE, error.code)
        assertEquals(getString(Res.string.connect_error_generic), error.message)
        assertFalse(error.message.contains("gw-prod-01"), error.message)
    }

    @Test
    fun `an unclassified IOException is a plain connection failure with no detail`() = runTest {
        val error = tunnelConnectError(IOException("boom at gw-prod-01"), 10_000)

        assertEquals(SshErrorCode.HOST_UNREACHABLE, error.code)
        assertEquals(getString(Res.string.connect_error_generic), error.message)
        assertFalse(error.message.contains("gw-prod-01"), error.message)
    }

    @Test
    fun `a timeout, a refused connection and an unknown host keep the texts of the shared mapper`() = runTest {
        val timeout = tunnelConnectError(SocketTimeoutException("connect timed out"), 10_000)
        assertEquals(SshErrorCode.TIMEOUT, timeout.code)
        assertEquals(getString(Res.string.connect_error_timeout, 10), timeout.message)

        val refused = tunnelConnectError(ConnectException("Connection refused: gw-prod-01"), 10_000)
        assertEquals(SshErrorCode.HOST_UNREACHABLE, refused.code)
        assertEquals(getString(Res.string.connect_error_refused), refused.message)

        val unknown = tunnelConnectError(UnknownHostException("gw-prod-01"), 10_000)
        assertEquals(SshErrorCode.UNKNOWN, unknown.code)
        assertEquals(getString(Res.string.connect_error_host_not_found), unknown.message)
        assertFalse(unknown.message.contains("gw-prod-01"), unknown.message)
    }

    // ── Which problem for which authentication type ─────────────────────────

    private fun problemOf(
        authType: AuthType,
        password: Boolean = false,
        key: Boolean = false,
        certificate: Boolean = false,
    ) = tunnelCredentialProblem(authType, hasPassword = password, hasPrivateKey = key, hasCertificate = certificate)

    @Test
    fun `a password host needs the password and nothing else`() {
        assertEquals(TunnelCredentialProblem.PASSWORD_MISSING, problemOf(AuthType.PASSWORD))
        // A key or a certificate in the vault does not stand in for the password.
        assertEquals(TunnelCredentialProblem.PASSWORD_MISSING, problemOf(AuthType.PASSWORD, key = true, certificate = true))
        assertNull(problemOf(AuthType.PASSWORD, password = true))
    }

    @Test
    fun `a key host needs the private key and nothing else`() {
        assertEquals(TunnelCredentialProblem.SSH_KEY_MISSING, problemOf(AuthType.SSH_KEY))
        assertEquals(TunnelCredentialProblem.SSH_KEY_MISSING, problemOf(AuthType.SSH_KEY, password = true, certificate = true))
        assertNull(problemOf(AuthType.SSH_KEY, key = true))
    }

    @Test
    fun `a certificate host needs the private key and the certificate`() {
        assertEquals(TunnelCredentialProblem.CERTIFICATE_MISSING, problemOf(AuthType.CERTIFICATE))
        assertEquals(TunnelCredentialProblem.CERTIFICATE_MISSING, problemOf(AuthType.CERTIFICATE, key = true))
        assertEquals(TunnelCredentialProblem.CERTIFICATE_MISSING, problemOf(AuthType.CERTIFICATE, certificate = true))
        assertNull(problemOf(AuthType.CERTIFICATE, key = true, certificate = true))
    }

    @Test
    fun `FIDO2 and the biometric key are refused whatever the vault holds`() {
        for (everything in listOf(false, true)) {
            assertEquals(
                TunnelCredentialProblem.FIDO2_UNSUPPORTED,
                problemOf(AuthType.FIDO2, everything, everything, everything),
                "FIDO2, vault full: $everything",
            )
            assertEquals(
                TunnelCredentialProblem.BIOMETRIC_UNSUPPORTED,
                problemOf(AuthType.BIOMETRIC_KEY, everything, everything, everything),
                "biometric key, vault full: $everything",
            )
        }
    }

    @Test
    fun `every authentication type has an answer, and only the refused ones answer when the vault is full`() {
        for (authType in AuthType.entries) {
            val full = problemOf(authType, password = true, key = true, certificate = true)

            if (authType == AuthType.FIDO2 || authType == AuthType.BIOMETRIC_KEY) {
                assertTrue(full != null, "$authType has no tunnel support")
            } else {
                assertNull(full, "$authType with a full vault")
            }
        }
    }

    // ── The error of a failed authentication ────────────────────────────────

    private fun authFailure(cause: Throwable? = null) =
        UserAuthException("Exhausted available authentication methods", cause)

    @Test
    fun `a refused authentication reads as the neutral authentication text and quotes nothing`() = runTest {
        val error = tunnelAuthError(UserAuthException("Exhausted available authentication methods for gw-prod-01"))

        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(getString(Res.string.tunnels_error_auth_failed), error.message)
        assertFalse(error.message.contains("gw-prod-01") || error.message.contains("Exhausted"), error.message)
    }

    @Test
    fun `an authentication that failed on a local key problem is the neutral authentication text too`() = runTest {
        // A key that cannot be read (a wrong passphrase) reaches SSHJ as a cause of the same exception.
        val error = tunnelAuthError(authFailure(IOException("cannot decrypt /home/alice/.ssh/id_ed25519")))

        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(getString(Res.string.tunnels_error_auth_failed), error.message)
        assertFalse(error.message.contains("alice"), error.message)
    }

    @Test
    fun `an authentication that wraps a timeout is a timeout, not a refusal`() = runTest {
        for (timeout in listOf<Throwable>(TimeoutException("Timeout expired: 30000 MILLISECONDS"), SocketTimeoutException("Read timed out"))) {
            val error = tunnelAuthError(authFailure(ConnectionException(timeout)))

            assertEquals(SshErrorCode.TIMEOUT, error.code, "$timeout")
            assertEquals(getString(Res.string.tunnels_error_setup_timeout), error.message)
        }
    }

    @Test
    fun `an authentication that wraps a lost transport is a lost connection, not a refusal`() = runTest {
        val error = tunnelAuthError(authFailure(TransportException(DisconnectReason.CONNECTION_LOST, "Broken transport")))

        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals(getString(Res.string.tunnels_error_connection_lost), error.message)
    }

    @Test
    fun `a server that hangs up because of the authentication itself is a refusal`() = runTest {
        for (reason in listOf(
            DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE,
            DisconnectReason.ILLEGAL_USER_NAME,
            DisconnectReason.AUTH_CANCELLED_BY_USER,
        )) {
            val error = tunnelAuthError(authFailure(TransportException(reason, "Too many authentication failures")))

            assertEquals(SshErrorCode.AUTH_FAILED, error.code, "$reason")
            assertEquals(getString(Res.string.tunnels_error_auth_failed), error.message, "$reason")
        }
    }

    // ── One decision for every exception of the opening ─────────────────────

    @Test
    fun `a UserAuthException is an authentication error even though it is an IOException`() = runTest {
        val exception: Exception = authFailure()
        assertTrue(exception is IOException, "the premise : SSHJ's UserAuthException is an IOException")

        val error = tunnelOpenError(exception, 10_000)

        // Not "connection failed" : whatever the order of the clauses around it, the answer is this one.
        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(getString(Res.string.tunnels_error_auth_failed), error.message)
        assertNotEquals(getString(Res.string.connect_error_generic), error.message)
    }

    @Test
    fun `an IOException that is not an authentication goes to the connection texts`() = runTest {
        val generic = tunnelOpenError(IOException("boom at gw-prod-01"), 10_000)
        assertEquals(SshErrorCode.HOST_UNREACHABLE, generic.code)
        assertEquals(getString(Res.string.connect_error_generic), generic.message)
        assertFalse(generic.message.contains("gw-prod-01"), generic.message)

        val hostKey = tunnelOpenError(TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, hostKeyText), 10_000)
        assertEquals(SshErrorCode.HOST_KEY_MISMATCH, hostKey.code)
        assertEquals(getString(Res.string.tunnels_error_host_key_rejected), hostKey.message)

        val timeout = tunnelOpenError(SocketTimeoutException("connect timed out"), 10_000)
        assertEquals(SshErrorCode.TIMEOUT, timeout.code)
        assertEquals(getString(Res.string.connect_error_timeout, 10), timeout.message)
    }

    @Test
    fun `any other exception reads as the tunnel could not start and quotes nothing`() = runTest {
        val error = tunnelOpenError(IllegalStateException("vault locked for alice"), 10_000)

        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals(getString(Res.string.tunnels_error_start_failed), error.message)
        assertFalse(error.message.contains("alice"), error.message)
    }

    @Test
    fun `a cancellation is rethrown, never turned into an error`() = runTest {
        val cancellation = CancellationException("cancelled during the handshake")

        val thrown = assertFailsWith<CancellationException> { tunnelOpenError(cancellation, 10_000) }

        assertTrue(thrown === cancellation, "the very same cancellation must go up")
    }

    // ── A key or a certificate that cannot be read ──────────────────────────

    private val certificateHeader = "ssh-ed25519-cert-v01@openssh.com"

    private suspend fun readableKey(): String =
        DesktopSshKeyManager().generateKeyPair(SshKeyType.ECDSA_256, "tunnel-load").first

    @Test
    fun `a readable key loads without a certificate`() = runTest {
        val provider = loadTunnelKeyProvider(readableKey(), certificate = null, passphrase = null)

        assertNotNull(provider.public)
    }

    @Test
    fun `a key in an unknown format is unreadable`() {
        val thrown = assertFailsWith<TunnelKeyUnreadableException> {
            loadTunnelKeyProvider("this is not a PEM file at all", certificate = null, passphrase = null)
        }

        assertTrue(thrown.cause is IllegalArgumentException, "the cause is kept for the log: ${thrown.cause}")
    }

    @Test
    fun `a truncated certificate is unreadable, though its parser raises an IOException`() = runTest {
        // "AAAA" decodes to three zero bytes : the buffer of the certificate underflows.
        val thrown = assertFailsWith<TunnelKeyUnreadableException> {
            loadTunnelKeyProvider(readableKey(), certificate = "$certificateHeader AAAA comment", passphrase = null)
        }

        assertNotNull(thrown.cause, "the cause is kept for the log")
        assertFalse(thrown.message.orEmpty().contains("AAAA"), "the certificate must not be quoted: ${thrown.message}")
    }

    @Test
    fun `an empty certificate, a one-word one and a body that is not Base64 are unreadable`() = runTest {
        val key = readableKey()

        for (certificate in listOf("", "   \n", "justoneword", "$certificateHeader !!!not-base64!!!")) {
            assertFailsWith<TunnelKeyUnreadableException>("certificate: '$certificate'") {
                loadTunnelKeyProvider(key, certificate = certificate, passphrase = null)
            }
        }
    }

    @Test
    fun `a key that cannot be read is an identity error, not a connection failure`() = runTest {
        // The loader's own exception for a truncated certificate is an IOException : this is the case
        // that used to read as "connection failed" after a connection that had succeeded.
        val error = tunnelOpenError(TunnelKeyUnreadableException(IOException("Underflow")), 10_000)

        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(getString(Res.string.tunnels_error_key_unreadable), error.message)
        assertNotEquals(getString(Res.string.connect_error_generic), error.message)
    }
}
