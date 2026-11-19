// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import fr.techtical.nextsh.domain.model.SshErrorCode
import io.mockk.every
import io.mockk.mockk
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.concurrent.TimeoutException

/**
 * SSHJ throws [UserAuthException] when the server says no, and also when the transport dies or
 * the wait for the answer runs out during the authentication (it wraps the cause). Only the first
 * is an authentication failure that the automatic reconnection must give up on.
 *
 * The chains below are the shapes SSHJ builds: `Promise.retrieve` chains a `TimeoutException`
 * through `UserAuthException.chainer`, `SSHClient.auth` wraps the last attempt in an "Exhausted
 * available authentication methods" exception, a transport that dies delivers its exception to the
 * waiting promise.
 */
class UserAuthFailureTest {

    private fun refusal() = UserAuthException("Exhausted available authentication methods")

    // ── A genuine refusal ─────────────────────────────────────────────────────

    @Test
    fun `a bare refusal is a refusal`() {
        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(refusal()))
    }

    @Test
    fun `the attempts SSHJ nests one per method tried are still a refusal`() {
        val attempt = UserAuthException("Auth failed")
        val older = UserAuthException("publickey auth failed", attempt)
        val exhausted = UserAuthException("Exhausted available authentication methods", older)

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(exhausted))
    }

    @Test
    fun `the security key prompt that was cancelled is not a transport problem`() {
        // SkAuthMethod: UserAuthException("FIDO2 challenge cancelled by user"), then "signing failed"
        // around the exception of the handler (a cancellation here).
        assertEquals(
            UserAuthFailure.REFUSED,
            classifyUserAuthFailure(UserAuthException("FIDO2 signing failed", IllegalStateException("cancelled"))),
        )
    }

    @Test
    fun `a server that hangs up after too many failed attempts is a refusal, not a lost connection`() {
        val hangup = TransportException(DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE, "Too many authentication failures")

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(UserAuthException(hangup)))
        assertEquals(
            UserAuthFailure.REFUSED,
            classifyUserAuthFailure(UserAuthException("Exhausted available authentication methods", UserAuthException(hangup))),
        )
    }

    @Test
    fun `OpenSSH too many authentication failures is a refusal, not a lost connection`() {
        // MaxAuthTries: SSH_MSG_DISCONNECT with PROTOCOL_ERROR, which SSHJ throws as a TransportException.
        val tooMany = TransportException(DisconnectReason.PROTOCOL_ERROR, "Too many authentication failures")

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(UserAuthException(tooMany)))
        assertEquals(
            UserAuthFailure.REFUSED,
            classifyUserAuthFailure(UserAuthException("Exhausted available authentication methods", UserAuthException(tooMany))),
        )
    }

    @Test
    fun `every refusal reason of a hang-up is a refusal`() {
        for (reason in listOf(
            DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE,
            DisconnectReason.ILLEGAL_USER_NAME,
            DisconnectReason.PROTOCOL_ERROR,
            DisconnectReason.AUTH_CANCELLED_BY_USER,
            DisconnectReason.HOST_NOT_ALLOWED_TO_CONNECT,
        )) {
            val e = UserAuthException(TransportException(reason, "server hung up"))
            assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(e), "$reason")
        }
    }

    @Test
    fun `the other hang-ups are transport failures`() {
        for (reason in listOf(
            DisconnectReason.CONNECTION_LOST,
            DisconnectReason.BY_APPLICATION,
            DisconnectReason.TOO_MANY_CONNECTIONS,
            DisconnectReason.MAC_ERROR,
            DisconnectReason.KEY_EXCHANGE_FAILED,
            DisconnectReason.SERVICE_NOT_AVAILABLE,
            DisconnectReason.COMPRESSION_ERROR,
            DisconnectReason.UNKNOWN,
        )) {
            val e = UserAuthException(TransportException(reason, "server hung up"))
            assertEquals(UserAuthFailure.TRANSPORT, classifyUserAuthFailure(e), "$reason")
        }
    }

    @Test
    fun `a host key refused during the authentication is a host key failure`() {
        // A re-key inside the authentication whose key the verifier refuses.
        val rekey = TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "Could not verify host key")

        val kind = classifyUserAuthFailure(UserAuthException(rekey))

        assertEquals(UserAuthFailure.HOST_KEY, kind)
        assertEquals(SshErrorCode.HOST_KEY_MISMATCH, userAuthFailureCode(kind))
        assertTrue(userAuthFailureCode(kind).isNonRetryable(), "a security event: never replayed automatically")
    }

    @Test
    fun `a refused user name is a refusal`() {
        val hangup = TransportException(DisconnectReason.ILLEGAL_USER_NAME, "no such user")

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(UserAuthException(hangup)))
    }

    @Test
    fun `the disconnect reason on the exception itself counts`() {
        val e = UserAuthException(DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE, "Too many authentication failures")

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(e))
    }

    // ── A transport that broke ────────────────────────────────────────────────

    @Test
    fun `a transport exception in the chain is a transport failure`() {
        val lost = TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")

        assertEquals(UserAuthFailure.TRANSPORT, classifyUserAuthFailure(UserAuthException(lost)))
    }

    @Test
    fun `a connection exception in the chain is a transport failure`() {
        assertEquals(
            UserAuthFailure.TRANSPORT,
            classifyUserAuthFailure(UserAuthException(ConnectionException("channel closed"))),
        )
    }

    @Test
    fun `socket errors and an end of stream in the chain are transport failures`() {
        val shapes = listOf<IOException>(
            EOFException("EOF while reading packet"),
            SocketException("Connection reset"),
            ConnectException("Connection refused"),
            SocketException("sendto failed: EPIPE (Broken pipe)"),
        )
        for (io in shapes) {
            assertEquals(UserAuthFailure.TRANSPORT, classifyUserAuthFailure(UserAuthException("auth", io)), io.javaClass.simpleName)
        }
    }

    @Test
    fun `a bare IO exception is not taken for a lost connection`() {
        // Neither a transport exception nor a socket error: not retried (the safe side).
        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(UserAuthException("auth", IOException("something"))))
    }

    // ── A key that cannot be read here ────────────────────────────────────────

    @Test
    fun `a wrong or missing passphrase is a local key failure, not a lost connection`() {
        // KeyedAuthMethod.putSig: UserAuthException("Problem getting private key from ...", ioe),
        // with the KeyDecryptionFailedException of the key file as the IOException.
        val wrongPassphrase = UserAuthException(
            "Problem getting private key from FileKeyProvider",
            KeyDecryptionFailedException(),
        )

        assertEquals(UserAuthFailure.KEY_UNREADABLE, classifyUserAuthFailure(wrongPassphrase))
    }

    @Test
    fun `a corrupt key file is a local key failure`() {
        val corrupt = UserAuthException(
            "Problem getting public key from FileKeyProvider",
            IOException("Invalid key format: not a PEM block"),
        )

        assertEquals(UserAuthFailure.KEY_UNREADABLE, classifyUserAuthFailure(corrupt))
    }

    @Test
    fun `whatever the key provider throws under that wrapper is local`() {
        // Reading a key file cannot be a network error: even a socket error under the wrapper is the key.
        for (cause in listOf<Throwable>(
            SocketException("not from the network"),
            EOFException("truncated key file"),
            IOException("bad key"),
            GeneralSecurityException("unsupported algorithm"),
        )) {
            val e = UserAuthException("Problem getting private key from FileKeyProvider", cause)
            assertEquals(UserAuthFailure.KEY_UNREADABLE, classifyUserAuthFailure(e), cause.javaClass.simpleName)
        }
    }

    @Test
    fun `the key failure is found under the attempts of the authentication`() {
        val wrapper = UserAuthException("Problem getting private key from FileKeyProvider", KeyDecryptionFailedException())
        val exhausted = UserAuthException("Exhausted available authentication methods", wrapper)

        assertEquals(UserAuthFailure.KEY_UNREADABLE, classifyUserAuthFailure(exhausted))
    }

    @Test
    fun `a decryption failure or a security exception without the wrapper is a key failure too`() {
        assertEquals(UserAuthFailure.KEY_UNREADABLE, classifyUserAuthFailure(UserAuthException(KeyDecryptionFailedException())))
        assertEquals(
            UserAuthFailure.KEY_UNREADABLE,
            classifyUserAuthFailure(UserAuthException("signing", GeneralSecurityException("InvalidKeyException"))),
        )
    }

    @Test
    fun `a cipher or MAC failure of the transport is a transport failure, not a key problem`() {
        // A GeneralSecurityException under a TransportException is the transport's cipher or MAC.
        val mac = TransportException(DisconnectReason.MAC_ERROR, "bad mac", GeneralSecurityException("Mac check failed"))

        assertEquals(UserAuthFailure.TRANSPORT, classifyUserAuthFailure(UserAuthException(mac)))
        assertEquals(
            UserAuthFailure.TRANSPORT,
            classifyUserAuthFailure(UserAuthException("Exhausted available authentication methods", UserAuthException("attempt", mac))),
        )
    }

    @Test
    fun `a refusal still wins over a key problem in the same chain`() {
        val e = UserAuthException(
            TransportException(DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE, "Too many authentication failures", GeneralSecurityException("x")),
        )

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(e))
    }

    @Test
    fun `the Keystore exceptions of a biometric key are told apart`() {
        val windowOver = mockk<UserNotAuthenticatedException>(relaxed = true).also { every { it.cause } returns null }
        val invalidated = mockk<KeyPermanentlyInvalidatedException>(relaxed = true).also { every { it.cause } returns null }

        val expired = classifyUserAuthFailure(UserAuthException("sign", SSHRuntimeException(windowOver)))
        val invalid = classifyUserAuthFailure(UserAuthException("sign", SSHRuntimeException(invalidated)))

        assertEquals(UserAuthFailure.BIOMETRIC_AUTH_EXPIRED, expired)
        assertEquals(UserAuthFailure.BIOMETRIC_KEY_INVALID, invalid)
        assertEquals(SshErrorCode.AUTH_EXPIRED, userAuthFailureCode(expired))
        assertEquals(SshErrorCode.AUTH_FAILED, userAuthFailureCode(invalid))
        assertFalse(userAuthFailureCode(expired).isNonRetryable(), "the user renews it with the fingerprint")
        assertTrue(userAuthFailureCode(invalid).isNonRetryable())
    }

    @Test
    fun `a wrong passphrase is an authentication failure that is not retried`() {
        val code = userAuthFailureCode(
            classifyUserAuthFailure(
                UserAuthException("Problem getting private key from FileKeyProvider", KeyDecryptionFailedException()),
            ),
        )

        assertEquals(SshErrorCode.AUTH_FAILED, code)
        assertTrue(code.isNonRetryable())
    }

    @Test
    fun `a transport exception under the attempts of the authentication is found`() {
        val lost = TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")
        val exhausted = UserAuthException("Exhausted available authentication methods", UserAuthException("attempt", lost))

        assertEquals(UserAuthFailure.TRANSPORT, classifyUserAuthFailure(exhausted))
    }

    @Test
    fun `an interrupted wait is a transport failure`() {
        assertEquals(
            UserAuthFailure.TRANSPORT,
            classifyUserAuthFailure(UserAuthException(InterruptedException())),
        )
    }

    // ── A wait that ran out ───────────────────────────────────────────────────

    @Test
    fun `the timeout SSHJ chains when the answer is late is a timeout`() {
        // Promise.retrieve: chainer.chain(new TimeoutException("Timeout expired: 30000 MILLISECONDS"))
        val timeout = UserAuthException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))

        assertEquals(UserAuthFailure.TIMED_OUT, classifyUserAuthFailure(timeout))
    }

    @Test
    fun `a socket timeout in the chain is a timeout`() {
        assertEquals(
            UserAuthFailure.TIMED_OUT,
            classifyUserAuthFailure(UserAuthException("auth", SocketTimeoutException("Read timed out"))),
        )
    }

    @Test
    fun `a timeout under the attempts of the authentication is found`() {
        val exhausted = UserAuthException(
            "Exhausted available authentication methods",
            UserAuthException("attempt", TimeoutException("Timeout expired: 30000 MILLISECONDS")),
        )

        assertEquals(UserAuthFailure.TIMED_OUT, classifyUserAuthFailure(exhausted))
    }

    @Test
    fun `a timeout wins over the transport exception that follows it`() {
        val e = UserAuthException(
            "auth",
            TransportException("closed", TimeoutException("Timeout expired: 30000 MILLISECONDS")),
        )

        assertEquals(UserAuthFailure.TIMED_OUT, classifyUserAuthFailure(e))
    }

    // ── Robustness ────────────────────────────────────────────────────────────

    @Test
    fun `a cause cycle ends`() {
        val a = UserAuthException("a")
        val b = UserAuthException("b", a)
        a.initCause(b)

        assertEquals(UserAuthFailure.REFUSED, classifyUserAuthFailure(a))
    }

    // ── What each kind becomes ────────────────────────────────────────────────

    @Test
    fun `a refusal and an unreadable key are authentication failures, a late or broken exchange is not`() {
        assertEquals(SshErrorCode.AUTH_FAILED, userAuthFailureCode(UserAuthFailure.REFUSED))
        assertEquals(SshErrorCode.AUTH_FAILED, userAuthFailureCode(UserAuthFailure.KEY_UNREADABLE))
        assertEquals(SshErrorCode.AUTH_FAILED, userAuthFailureCode(UserAuthFailure.BIOMETRIC_KEY_INVALID))
        assertEquals(SshErrorCode.AUTH_EXPIRED, userAuthFailureCode(UserAuthFailure.BIOMETRIC_AUTH_EXPIRED))
        assertEquals(SshErrorCode.TIMEOUT, userAuthFailureCode(UserAuthFailure.TIMED_OUT))
        assertEquals(SshErrorCode.HOST_UNREACHABLE, userAuthFailureCode(UserAuthFailure.TRANSPORT))

        assertTrue(userAuthFailureCode(UserAuthFailure.REFUSED).isNonRetryable())
        assertTrue(userAuthFailureCode(UserAuthFailure.KEY_UNREADABLE).isNonRetryable())
        assertFalse(userAuthFailureCode(UserAuthFailure.TIMED_OUT).isNonRetryable())
        assertFalse(userAuthFailureCode(UserAuthFailure.TRANSPORT).isNonRetryable())
    }
}
