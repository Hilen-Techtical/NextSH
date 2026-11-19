// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException

/** Why a [UserAuthException] was thrown: the server said no, the key could not be read, or the exchange did not complete. */
internal enum class UserAuthFailure {
    /** The server refused the credential (or no method was left to try). Trying again cannot fix it. */
    REFUSED,

    /**
     * The private key could not be read or decrypted here, before the server was asked anything:
     * wrong or missing passphrase, corrupt key. Local, so trying again cannot fix it either.
     */
    KEY_UNREADABLE,

    /** The Android Keystore key was invalidated or deleted: it must be regenerated. */
    BIOMETRIC_KEY_INVALID,

    /** The Android Keystore key needs a fresh biometric authentication (`AUTH_EXPIRED`). */
    BIOMETRIC_AUTH_EXPIRED,

    /**
     * The host key was refused in the middle of the authentication (a re-key the verifier did not
     * accept): `HOST_KEY_MISMATCH`, as when it is refused while connecting.
     */
    HOST_KEY,

    /** The wait for the server's answer ran out. The credential was never judged. */
    TIMED_OUT,

    /** The transport broke during the authentication (connection lost, server gone, interrupted). */
    TRANSPORT,
}

/**
 * Disconnect reasons with which a server says no to the credential, or to this client, by closing
 * the connection. SSHJ turns the server's SSH_MSG_DISCONNECT into a `TransportException` carrying
 * the reason, and the authentication wraps it in a [UserAuthException].
 *
 * `PROTOCOL_ERROR` is what OpenSSH sends after too many failed attempts ("Too many authentication
 * failures": MaxAuthTries), so a wrong key is retried 5 times and then at every network event
 * unless it is a refusal (and fail2ban counts them). A genuine protocol error is the same reason,
 * and is not worth retrying either.
 */
private val REFUSAL_REASONS = setOf(
    DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE,
    DisconnectReason.ILLEGAL_USER_NAME,
    DisconnectReason.PROTOCOL_ERROR,
    DisconnectReason.AUTH_CANCELLED_BY_USER,
    DisconnectReason.HOST_NOT_ALLOWED_TO_CONNECT,
)

/** Chains are short; this only guards against a cause cycle. */
private const val MAX_CAUSE_DEPTH = 32

/**
 * Tells a refusal from a key that cannot be read, from a broken exchange.
 *
 * SSHJ throws [UserAuthException] for all of these. `SSHClient.auth` waits for the server's answer
 * on a promise, and when the wait times out or the transport dies it wraps the cause (a
 * [TimeoutException], a [TransportException], a [ConnectionException], a socket error) in a
 * [UserAuthException]; and `KeyedAuthMethod` wraps in the same exception the `IOException` of a
 * `KeyProvider` that cannot read or decrypt the key. The connect functions used to report them all
 * as `AUTH_FAILED`, and a tunnel that lost its network during the authentication was given up as if
 * its key had been refused.
 *
 * The cause chain decides. [UserAuthException]s inside the chain are the attempts of the
 * authentication itself (SSHJ nests one per method tried) and say nothing about the exchange:
 * the walk goes through them. A genuine refusal has no other cause.
 *
 * - Only transport-level causes make a transport failure: [TransportException],
 *   [ConnectionException], [SocketException], [EOFException], [InterruptedException]. A bare
 *   `IOException` is not one of them: when SSHJ reads the key the `IOException` comes from the
 *   key, not from the network, and one that is neither is left as a refusal rather than retried.
 * - A key problem of this device ([classifyLocalKeyFailure]: a key that cannot be read or decrypted,
 *   an Android Keystore key that is invalid or needs a fresh biometric authentication) is local: no
 *   server can fix a wrong passphrase. A security exception that sits under a transport exception
 *   is the transport's cipher or MAC, not the key.
 * - A server that refuses by hanging up (`PROTOCOL_ERROR` as OpenSSH sends it after too many failed
 *   attempts, `NO_MORE_AUTH_METHODS_AVAILABLE`, `ILLEGAL_USER_NAME`, `AUTH_CANCELLED_BY_USER`,
 *   `HOST_NOT_ALLOWED_TO_CONNECT`) is a refusal, although it reaches us as a transport exception.
 * - A host key the verifier refuses in the middle of the authentication (`HOST_KEY_NOT_VERIFIABLE`,
 *   a re-key) is `HOST_KEY_MISMATCH`, as when it is refused while connecting.
 */
internal fun classifyUserAuthFailure(e: UserAuthException): UserAuthFailure {
    var timedOut = false
    var transport = false
    var refused = false
    var hostKey = false

    val seen = IdentityHashMap<Throwable, Unit>()
    var cause: Throwable? = e
    while (cause != null && seen.size < MAX_CAUSE_DEPTH && seen.put(cause, Unit) == null) {
        if (cause is SSHException && cause.disconnectReason in REFUSAL_REASONS) refused = true
        // A re-key inside the authentication whose host key the verifier refuses.
        if (cause is SSHException && cause.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) hostKey = true
        // The exception itself is a UserAuthException by definition: only its causes tell more.
        if (cause !== e) {
            when (cause) {
                is TimeoutException, is SocketTimeoutException -> timedOut = true
                is TransportException, is ConnectionException, is SocketException, is EOFException,
                is InterruptedException,
                -> transport = true
            }
        }
        cause = cause.cause
    }
    return when {
        refused -> UserAuthFailure.REFUSED
        else -> when (classifyLocalKeyFailure(e)) {
            LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED -> UserAuthFailure.BIOMETRIC_AUTH_EXPIRED
            LocalKeyFailure.BIOMETRIC_KEY_INVALID -> UserAuthFailure.BIOMETRIC_KEY_INVALID
            LocalKeyFailure.UNREADABLE -> UserAuthFailure.KEY_UNREADABLE
            null -> when {
                hostKey -> UserAuthFailure.HOST_KEY
                timedOut -> UserAuthFailure.TIMED_OUT
                transport -> UserAuthFailure.TRANSPORT
                else -> UserAuthFailure.REFUSED
            }
        }
    }
}

/**
 * The error code of a connection that failed with [failure]. A refusal, an unreadable key and an
 * invalid Keystore key are authentication failures (`AUTH_FAILED`, not retried by the automatic
 * reconnection); a Keystore key whose biometric window ran out is `AUTH_EXPIRED`; a late answer and
 * a broken transport are what the reconnection is for.
 */
internal fun userAuthFailureCode(failure: UserAuthFailure): SshErrorCode = when (failure) {
    UserAuthFailure.REFUSED -> SshErrorCode.AUTH_FAILED
    UserAuthFailure.KEY_UNREADABLE -> SshErrorCode.AUTH_FAILED
    UserAuthFailure.BIOMETRIC_KEY_INVALID -> SshErrorCode.AUTH_FAILED
    UserAuthFailure.BIOMETRIC_AUTH_EXPIRED -> SshErrorCode.AUTH_EXPIRED
    UserAuthFailure.HOST_KEY -> SshErrorCode.HOST_KEY_MISMATCH
    UserAuthFailure.TIMED_OUT -> SshErrorCode.TIMEOUT
    UserAuthFailure.TRANSPORT -> SshErrorCode.HOST_UNREACHABLE
}
