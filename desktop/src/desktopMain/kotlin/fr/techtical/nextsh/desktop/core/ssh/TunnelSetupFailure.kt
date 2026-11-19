// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.connect_error_generic
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
import fr.techtical.nextsh.shared.core.ssh.SshConnectErrorClassification
import fr.techtical.nextsh.shared.core.ssh.classifySshConnectError
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/*
 * Texts of the failures of a tunnel start, the part that does not depend on the live manager :
 *  - why the forwarder setup failed (the local bind, the remote forward request, the SOCKS5 server),
 *    classified from the exception and turned into a translated text ;
 *  - which credential the vault does not hold, and why a host cannot be used for a tunnel ;
 *  - what a failed connection or authentication says.
 *
 * The message of an exception is never shown nor logged. It comes from SSHJ or the JDK, in English,
 * and can quote a host, an address or a request name ("Global request [tcpip-forward] failed",
 * "Address already in use: bind"). Only the category of the failure is kept, and the text the user
 * reads is a string resource. The one place where a host label reaches a text is the "credential
 * missing" one, which goes to the card and the error dialog, never to a log.
 */

/** Longest cause chain walked by [anyInCauseChain] : a chain of causes can loop. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * Whether [predicate] holds for this throwable or one of its causes. Bounded to [MAX_CAUSE_DEPTH]
 * levels, so that a chain of causes that loops ends anyway.
 */
internal fun Throwable.anyInCauseChain(predicate: (Throwable) -> Boolean): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < MAX_CAUSE_DEPTH) {
        if (predicate(current)) return true
        current = current.cause
    }
    return false
}

/** The server stayed silent : a `TimeoutException` or a `SocketTimeoutException` in the causes. */
private fun Throwable.hasTimeout(): Boolean = anyInCauseChain { it is TimeoutException || it is SocketTimeoutException }

/** The wording of the JDK (Linux, macOS, Windows, with or without the `: connect` suffix) for a network that is gone. */
private val NETWORK_GONE_MESSAGES = listOf("network is unreachable", "no route to host", "network is down")

/**
 * Is this failure, or one of its causes, a network-level one: `NoRouteToHostException`, or a
 * `SocketException` (a `ConnectException` included) saying the network is unreachable, down or that
 * there is no route to the host ? The chain is read, so an SSHJ exception wrapping it counts.
 *
 * Never a timeout: a server that is down gives a timeout too, and that says nothing about the
 * network. A refused connection is not network-level either.
 */
internal fun Throwable.isNetworkLevelFailure(): Boolean {
    if (hasTimeout()) return false
    return anyInCauseChain { cause ->
        cause is NoRouteToHostException ||
            (cause is SocketException && NETWORK_GONE_MESSAGES.any { cause.message.orEmpty().contains(it, ignoreCase = true) })
    }
}

// ── Local port ──────────────────────────────────────────────────────────────

/** The first local port a process without administrator rights may listen on (Linux and macOS). */
private const val FIRST_UNPRIVILEGED_PORT = 1024

/** How long [isLocalPortListening] waits for a connection : a local connect answers at once or never. */
private const val LISTENER_PROBE_TIMEOUT_MS = 200

/**
 * Is a bind failure on [port] the permission error of a port the system keeps for administrators,
 * rather than a port taken by another application ? Linux and macOS reserve the ports below 1024 for
 * administrators, and the JDK reports that permission error as the same `BindException` as a port
 * already in use. Windows has no such rule. Port 0 asks for any free port and is not reserved.
 * Android applies the same cut to every local port (its own `TunnelPortRules`).
 *
 * The rule alone would be wrong for a port below 1024 that a service really holds : a process run as
 * root, with `CAP_NET_BIND_SERVICE`, or a lowered `net.ipv4.ip_unprivileged_port_start` (the bind then
 * fails because the port is taken, not because it is forbidden). So the port counts as reserved only
 * when [someoneListens] finds nobody on it. It is asked last, and only for a port below 1024 on a
 * system that has the rule : nothing is connected to otherwise.
 *
 * Known gap : since Mojave, macOS lets a process open a port below 1024 on the wildcard address, so
 * the first check of [localPortFailure] can pass there and only the bind on `127.0.0.1` fails. The
 * macOS rule is to be reviewed with the macOS application (phase 4).
 *
 * Not tied to the real system, so that the tests do not depend on the machine : [osName] is
 * `os.name`.
 *
 * @param someoneListens whether a TCP connection to the port on the loopback address succeeds ;
 *   replaceable by the tests.
 */
internal fun isReservedLocalPort(
    port: Int,
    osName: String,
    someoneListens: (Int) -> Boolean = ::isLocalPortListening,
): Boolean =
    port in 1 until FIRST_UNPRIVILEGED_PORT &&
        !osName.contains("windows", ignoreCase = true) &&
        !someoneListens(port)

/**
 * Does a TCP connection to [port] on `127.0.0.1` succeed ? That is the proof that something listens
 * there. The connection is closed at once and nothing is sent. False on any refusal, on a timeout
 * ([LISTENER_PROBE_TIMEOUT_MS]) and on any other `IOException`.
 */
internal fun isLocalPortListening(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), LISTENER_PROBE_TIMEOUT_MS) }
    true
} catch (_: IOException) {
    false
}

/**
 * The check done before the SSH connection opens, so that a local port that cannot be listened on
 * does not cost an authentication : null when [probe] opens and closes [port], otherwise
 * [TunnelSetupFailure.PORT_RESERVED] when the system keeps the port for administrators and nobody
 * listens on it (see [isReservedLocalPort]), [TunnelSetupFailure.PORT_IN_USE] for any other
 * `BindException`. Any other `IOException` of the probe (descriptors exhausted...) says nothing about
 * the port : [TunnelSetupFailure.GENERIC].
 *
 * @param probe opens then closes a server socket on the port ; replaceable by the tests.
 * @param someoneListens see [isReservedLocalPort] ; replaceable by the tests.
 */
internal fun localPortFailure(
    port: Int,
    osName: String,
    probe: (Int) -> Unit = { ServerSocket(it).close() },
    someoneListens: (Int) -> Boolean = ::isLocalPortListening,
): TunnelSetupFailure? = try {
    probe(port)
    null
} catch (_: BindException) {
    if (isReservedLocalPort(port, osName, someoneListens)) {
        TunnelSetupFailure.PORT_RESERVED
    } else {
        TunnelSetupFailure.PORT_IN_USE
    }
} catch (_: IOException) {
    // Not a refusal of the port (descriptors exhausted, a failing network stack...) : saying "port
    // already in use" would send the user looking for the wrong cause.
    TunnelSetupFailure.GENERIC
}

// ── Failure of the forwarder setup ──────────────────────────────────────────

/**
 * Why the setup of a tunnel failed once the SSH client was open, for the cases that have a text of
 * their own. Never carries the message of the exception, a host or a path.
 */
internal enum class TunnelSetupFailure {
    /** The local port is taken by another application (LOCAL_FORWARD and DYNAMIC_SOCKS5). */
    PORT_IN_USE,

    /**
     * The local port is one the system keeps for administrators and that nobody holds
     * (LOCAL_FORWARD and DYNAMIC_SOCKS5 below 1024 on Linux and macOS, see [isReservedLocalPort]).
     */
    PORT_RESERVED,

    /** The server refused the `tcpip-forward` request of a remote forward. */
    REMOTE_FORWARD_REFUSED,

    /** The server did not answer in time. */
    TIMEOUT,

    /** The SSH transport went away during the setup. */
    CONNECTION_LOST,

    /** Nothing more precise to say : "the tunnel could not start". */
    GENERIC,
}

/**
 * Sorts the exception that made the setup of a tunnel of [type] fail into a [TunnelSetupFailure].
 * Pure : no SSHJ round trip, no resource, the tests feed it exceptions built by hand. Checked in
 * this order :
 *
 *  1. LOCAL_FORWARD and DYNAMIC_SOCKS5 : a `BindException`, in the exception or its causes, is the
 *     local port taken between the check that precedes the connection and the bind, or, when
 *     [localPortReserved], the permission error the system answers for a port it keeps for
 *     administrators (the JDK uses the same exception for both).
 *  2. REMOTE_FORWARD : the refusal of the server, see [isRemoteForwardRefusal].
 *  3. A `TimeoutException` or a `SocketTimeoutException` in the causes : the server stayed silent.
 *  4. A `TransportException` in the causes, or a `ConnectionException` that carries a cause or a
 *     reason of its own : the transport went away.
 *  5. Anything else : [TunnelSetupFailure.GENERIC].
 *
 * What SSHJ 0.38 throws for each case was read on its bytecode :
 *  - a remote forward refused : `ConnectionImpl.gotGlobalReqResponse` hands the request a
 *    `ConnectionException(String)`, the class `ConnectionException` itself, with no cause and the
 *    reason `UNKNOWN`. The connection is alive ;
 *  - a remote forward request left unanswered : `Promise.retrieve` raises a `TimeoutException`, which
 *    the chainer of `ConnectionException` wraps (the cause is not null) ;
 *  - a transport lost during the request : a `TransportException`, or a `ConnectionException` that
 *    carries its own reason (`PROTOCOL_ERROR`, not `UNKNOWN`) or wraps the error of the transport ;
 *  - `OpenFailException` extends `ConnectionException` and is a refused channel, not a refused
 *    forward : the exact class test leaves it out.
 *
 * @param localPortReserved whether the local port of the tunnel is one the system keeps for
 *   administrators and nobody listens on ([isReservedLocalPort]), so that a bind failure on it is
 *   [TunnelSetupFailure.PORT_RESERVED]. A function and not a value : it may connect to the port, and
 *   is asked only for a bind failure of a local listener.
 * @param clientConnected whether the SSH client is still connected, read at the moment of the
 *   classification and only when the shape of the exception needs it. "Not connected" alone never
 *   gives [TunnelSetupFailure.CONNECTION_LOST] (a relaxed mock answers false to everything).
 */
internal fun classifyTunnelSetupFailure(
    e: Throwable,
    type: TunnelType,
    localPortReserved: () -> Boolean = { false },
    clientConnected: () -> Boolean,
): TunnelSetupFailure = when {
    (type == TunnelType.LOCAL_FORWARD || type == TunnelType.DYNAMIC_SOCKS5) &&
        e.anyInCauseChain { it is BindException } ->
        if (localPortReserved()) TunnelSetupFailure.PORT_RESERVED else TunnelSetupFailure.PORT_IN_USE
    type == TunnelType.REMOTE_FORWARD && isRemoteForwardRefusal(e, clientConnected) ->
        TunnelSetupFailure.REMOTE_FORWARD_REFUSED
    e.hasTimeout() -> TunnelSetupFailure.TIMEOUT
    e.hasTransportFailure() || isConnectionLost(e) -> TunnelSetupFailure.CONNECTION_LOST
    else -> TunnelSetupFailure.GENERIC
}

/** A `TransportException` in the causes : the SSH transport went away. */
private fun Throwable.hasTransportFailure(): Boolean = anyInCauseChain { it is TransportException }

/**
 * Is [e] the refusal, by the server, of the `tcpip-forward` request of a remote forward ?
 *
 * The protocol gives no reason for it (SSH_MSG_REQUEST_FAILURE carries no text). The refusal is
 * recognised by its shape, see [classifyTunnelSetupFailure] : the exact class `ConnectionException`,
 * no cause, the reason `UNKNOWN`, and a connection that is still established (a refusal followed by a
 * drop looks like a drop : the generic text, which is the safe one). The text of the message is NOT
 * read : it comes from SSHJ and would break at the first rewording of the library.
 */
private fun isRemoteForwardRefusal(e: Throwable, clientConnected: () -> Boolean): Boolean =
    e is ConnectionException &&
        e.javaClass == ConnectionException::class.java &&
        e.cause == null &&
        e.disconnectReason == DisconnectReason.UNKNOWN &&
        clientConnected()

/**
 * A `ConnectionException` that is not the plain refusal of a request : it wraps another error or
 * carries a reason of its own. `OpenFailException` is a refused channel and does not count.
 */
private fun isConnectionLost(e: Throwable): Boolean =
    e is ConnectionException &&
        e !is OpenFailException &&
        (e.disconnectReason != DisconnectReason.UNKNOWN || e.cause != null)

/**
 * The error a failed start returns and writes on the tunnel. `suspend` because the text is a string
 * resource : the call sits in the `catch` of the start, where a cancellation must go up, so it is
 * never wrapped in `runCatching` or in a `catch (Exception)`.
 *
 * Only [TunnelSetupFailure.PORT_IN_USE], [TunnelSetupFailure.PORT_RESERVED] and
 * [TunnelSetupFailure.REMOTE_FORWARD_REFUSED] name a port, and it is the one of the tunnel, never a
 * value taken from the exception.
 *
 * @param startFailedMessage the translated "the tunnel could not start", for [TunnelSetupFailure.GENERIC].
 */
internal suspend fun TunnelSetupFailure.toStartError(config: TunnelConfig, startFailedMessage: String): SshResult.Error =
    when (this) {
        TunnelSetupFailure.PORT_IN_USE ->
            SshResult.Error(
                SshErrorCode.PORT_IN_USE,
                getString(Res.string.tunnels_error_port_in_use, config.localPort),
            )
        // Not PORT_IN_USE : nobody holds the port. UNKNOWN, like the reserved port of the Android app.
        TunnelSetupFailure.PORT_RESERVED ->
            SshResult.Error(
                SshErrorCode.UNKNOWN,
                getString(Res.string.tunnels_error_local_port_reserved, config.localPort),
            )
        TunnelSetupFailure.REMOTE_FORWARD_REFUSED ->
            SshResult.Error(
                SshErrorCode.UNKNOWN,
                getString(Res.string.tunnels_error_remote_forward_refused, config.remotePort),
            )
        TunnelSetupFailure.TIMEOUT ->
            SshResult.Error(SshErrorCode.TIMEOUT, getString(Res.string.tunnels_error_setup_timeout))
        TunnelSetupFailure.CONNECTION_LOST ->
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_connection_lost))
        TunnelSetupFailure.GENERIC ->
            SshResult.Error(SshErrorCode.UNKNOWN, startFailedMessage)
    }

// ── Credential the vault does not hold ──────────────────────────────────────

/**
 * What stops [VaultSshClientFactory] from authenticating a host : a credential the vault does not
 * hold, or an authentication a tunnel cannot use on Desktop.
 */
internal enum class TunnelCredentialProblem {
    PASSWORD_MISSING,
    SSH_KEY_MISSING,
    CERTIFICATE_MISSING,
    FIDO2_UNSUPPORTED,
    BIOMETRIC_UNSUPPORTED,
}

/**
 * What stops a tunnel from authenticating a host of [authType], or null when nothing does. Pure :
 * the vault is read by the caller, which says what it found. Only what the type needs counts : a
 * password for [AuthType.PASSWORD], the private key for [AuthType.SSH_KEY], the private key and the
 * certificate for [AuthType.CERTIFICATE]. [AuthType.FIDO2] and [AuthType.BIOMETRIC_KEY] have no
 * tunnel support on Desktop whatever the vault holds.
 *
 * The type of a host is synchronised with the other devices, so the answer only says what this
 * program cannot do : nothing here tells the user to change the type of the host.
 */
internal fun tunnelCredentialProblem(
    authType: AuthType,
    hasPassword: Boolean,
    hasPrivateKey: Boolean,
    hasCertificate: Boolean,
): TunnelCredentialProblem? = when (authType) {
    AuthType.PASSWORD -> if (hasPassword) null else TunnelCredentialProblem.PASSWORD_MISSING
    AuthType.SSH_KEY -> if (hasPrivateKey) null else TunnelCredentialProblem.SSH_KEY_MISSING
    AuthType.CERTIFICATE -> if (hasPrivateKey && hasCertificate) null else TunnelCredentialProblem.CERTIFICATE_MISSING
    AuthType.FIDO2 -> TunnelCredentialProblem.FIDO2_UNSUPPORTED
    AuthType.BIOMETRIC_KEY -> TunnelCredentialProblem.BIOMETRIC_UNSUPPORTED
}

/**
 * The `AUTH_FAILED` error for [this] problem. [hostLabel] reaches the text so that the user knows
 * which host to fix, and goes nowhere else : it is never logged. FIDO2 and the biometric key name no
 * host, there is nothing to fix on the host itself.
 */
internal suspend fun TunnelCredentialProblem.toAuthError(hostLabel: String): SshResult.Error =
    SshResult.Error(
        SshErrorCode.AUTH_FAILED,
        when (this) {
            TunnelCredentialProblem.PASSWORD_MISSING ->
                getString(Res.string.tunnels_error_password_missing, hostLabel)
            TunnelCredentialProblem.SSH_KEY_MISSING ->
                getString(Res.string.session_error_ssh_key_missing, hostLabel)
            TunnelCredentialProblem.CERTIFICATE_MISSING ->
                getString(Res.string.session_error_key_or_certificate_missing, hostLabel)
            TunnelCredentialProblem.FIDO2_UNSUPPORTED ->
                getString(Res.string.tunnels_error_fido2_unsupported)
            TunnelCredentialProblem.BIOMETRIC_UNSUPPORTED ->
                getString(Res.string.session_error_biometric_unsupported)
        },
    )

// ── Key or certificate that cannot be read ──────────────────────────────────

/**
 * The key or the certificate of a host could not be loaded. Raised by [loadTunnelKeyProvider] and
 * turned into an identity error by [tunnelOpenError]. Not an `IOException` on purpose : the loader's
 * `BufferException` (a truncated certificate) is one, and would otherwise read as "connection failed"
 * after a connection that succeeded. The cause stays for the log (its class only), the message of
 * the cause is not repeated here.
 */
internal class TunnelKeyUnreadableException(cause: Throwable) : Exception("key or certificate unreadable", cause)

/**
 * Loads the key provider of a tunnel : the private key alone, or the private key with its
 * [certificate] when there is one. This is the dedicated `try` around the loading, and only around
 * it : whatever the loader throws (an unknown format, an empty or truncated certificate, a bad
 * Base64 body, a malformed key) is a [TunnelKeyUnreadableException], while an error of the
 * authentication that follows is not caught here.
 *
 * The loader does not suspend, so no cancellation can be swallowed here ; one that comes anyway is
 * rethrown as it is.
 *
 * @param passphrase wiped by the caller.
 * @throws TunnelKeyUnreadableException when the key or the certificate cannot be loaded.
 */
internal fun loadTunnelKeyProvider(pem: String, certificate: String?, passphrase: CharArray?): KeyProvider =
    try {
        if (certificate == null) {
            DesktopSshKeyLoader.loadKeyProviderFromString(pem, passphrase)
        } else {
            DesktopSshKeyLoader.loadKeyProviderWithCertificate(pem, certificate, passphrase)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TunnelKeyUnreadableException(e)
    }

// ── Failed connection ───────────────────────────────────────────────────────

/**
 * The error for an `IOException` raised while a tunnel opens its SSH client (`connect()`, the
 * handshake, the host key check). Without the technical suffix the shared mapper appends :
 *
 *  - a host key the verifier refused is a `TransportException` with the reason
 *    `HOST_KEY_NOT_VERIFIABLE` (its message quotes the key type and the fingerprint) : the text says
 *    where to look, [SshErrorCode.HOST_KEY_MISMATCH] ;
 *  - a timeout, a refused connection and an unknown host keep the text of the shared mapper, which
 *    has no technical detail ;
 *  - any other `IOException` is "connection failed" with no detail.
 *
 * The shared mapper ([connectErrorResult]) stays as it is : the terminal uses it too.
 */
internal suspend fun tunnelConnectError(e: IOException, connectTimeoutMs: Int): SshResult.Error {
    if (e.anyInCauseChain { it is TransportException && it.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE }) {
        return SshResult.Error(SshErrorCode.HOST_KEY_MISMATCH, getString(Res.string.tunnels_error_host_key_rejected))
    }
    // HOST_UNREACHABLE is the fallback code of the shared mapper for this catch-all (kept private there).
    val classification = classifySshConnectError(e, connectTimeoutMs, SshErrorCode.HOST_UNREACHABLE)
    return if (classification is SshConnectErrorClassification.Generic) {
        SshResult.Error(classification.errorCode, getString(Res.string.connect_error_generic))
    } else {
        connectErrorResult(e, connectTimeoutMs)
    }
}

// ── Failed authentication ───────────────────────────────────────────────────

/**
 * The error for a [UserAuthException] raised while a tunnel authenticates. SSHJ raises it for more
 * than a refusal : "Exhausted available authentication methods" also wraps a connection that went
 * away during the exchange, a server that stayed silent, and a local key that could not be read (a
 * wrong passphrase). The causes tell them apart :
 *
 *  - a timeout in the causes : [SshErrorCode.TIMEOUT], the text of a silent server ;
 *  - a `TransportException` in the causes : the connection was lost, except when the server hung up
 *    on the authentication itself (`NO_MORE_AUTH_METHODS_AVAILABLE`, `ILLEGAL_USER_NAME`,
 *    `AUTH_CANCELLED_BY_USER`), which is a refusal ;
 *  - anything else : [SshErrorCode.AUTH_FAILED] with a neutral text that names the user, the
 *    password or key, and the passphrase of the key. Never the message of SSHJ.
 */
internal suspend fun tunnelAuthError(e: UserAuthException): SshResult.Error = when {
    e.hasTimeout() ->
        SshResult.Error(SshErrorCode.TIMEOUT, getString(Res.string.tunnels_error_setup_timeout))
    e.anyInCauseChain { it is TransportException && it.disconnectReason !in AUTH_DISCONNECT_REASONS } ->
        SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_connection_lost))
    else ->
        SshResult.Error(SshErrorCode.AUTH_FAILED, getString(Res.string.tunnels_error_auth_failed))
}

/** The reasons for which a server hangs up because of the authentication itself. */
private val AUTH_DISCONNECT_REASONS: Set<DisconnectReason> = setOf(
    DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE,
    DisconnectReason.ILLEGAL_USER_NAME,
    DisconnectReason.AUTH_CANCELLED_BY_USER,
)

/**
 * The error of an exception raised while a tunnel opens its SSH client (see
 * [VaultSshClientFactory.open]), decided in ONE place so that the order of the `catch` clauses of
 * the factory cannot change it : a [UserAuthException] is an `IOException`, and tested second it would
 * become "connection failed".
 *
 *  - [TunnelKeyUnreadableException] : the key or the certificate of the host cannot be read, an
 *    identity problem (`AUTH_FAILED`), not a network one ;
 *  - [UserAuthException] : [tunnelAuthError] ;
 *  - any other `IOException` : [tunnelConnectError] ;
 *  - anything else : "the tunnel could not start", never the message.
 *
 * A [CancellationException] is rethrown : it is not an error, and the caller decides what a
 * cancellation writes.
 */
internal suspend fun tunnelOpenError(e: Exception, connectTimeoutMs: Int): SshResult.Error {
    if (e is CancellationException) throw e
    return when (e) {
        is TunnelKeyUnreadableException ->
            SshResult.Error(SshErrorCode.AUTH_FAILED, getString(Res.string.tunnels_error_key_unreadable))
        is UserAuthException -> tunnelAuthError(e)
        is IOException -> tunnelConnectError(e, connectTimeoutMs)
        else -> SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.tunnels_error_start_failed))
    }
}
