// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import fr.techtical.nextsh.domain.model.SshErrorCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * What is wrong with the key itself, on this device, before the server judges anything: told from a
 * refusal and from a broken exchange, because it is neither retried nor explained the same way.
 *
 * The Android Keystore exceptions are mocks: the android.jar of a unit test has no working
 * constructors. The mocks are relaxed (SSHJ's wrapper reads their message), and their `cause` is
 * stubbed to null: left alone a relaxed mock answers with another mock, and the chain never ends.
 */
class LocalKeyFailureTest {

    private fun biometricWindowOver(): UserNotAuthenticatedException =
        mockk<UserNotAuthenticatedException>(relaxed = true).also { every { it.cause } returns null }

    private fun keyInvalidated(): KeyPermanentlyInvalidatedException =
        mockk<KeyPermanentlyInvalidatedException>(relaxed = true).also { every { it.cause } returns null }

    // ── A key file that cannot be read ────────────────────────────────────────

    @Test
    fun `a failed decryption is an unreadable key`() {
        assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(KeyDecryptionFailedException()))
    }

    @Test
    fun `the wrapper SSHJ gives a key provider failure is an unreadable key, whatever it wraps`() {
        for (cause in listOf<Throwable>(IOException("Invalid key format"), java.net.SocketException("not the network"))) {
            val e = UserAuthException("Problem getting public key from FileKeyProvider", cause)
            assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(e), cause.javaClass.simpleName)
        }
    }

    @Test
    fun `a security exception under a runtime exception of the signature is an unreadable key`() {
        // SSHJ turns the GeneralSecurityException of a signature (unsupported algorithm, bad key
        // spec, a Keystore key that cannot sign) into an SSHRuntimeException.
        val e = SSHRuntimeException(GeneralSecurityException("unsupported algorithm"))

        assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(e))
    }

    @Test
    fun `an exception that says nothing about a key is not a key failure`() {
        assertNull(classifyLocalKeyFailure(IllegalArgumentException("Format de cle non reconnu")))
        assertNull(classifyLocalKeyFailure(IOException("Connection reset")))
        assertNull(classifyLocalKeyFailure(UserAuthException("Exhausted available authentication methods")))
        assertNull(classifyLocalKeyFailure(SSHRuntimeException("something else")))
    }

    // ── The transport's own security exceptions are not the key ───────────────

    @Test
    fun `a cipher or MAC failure of the transport is not a key failure`() {
        // Decoder / Encoder: the GeneralSecurityException of the cipher or the MAC, under a TransportException.
        val mac = TransportException(DisconnectReason.MAC_ERROR, "bad mac", GeneralSecurityException("Mac check failed"))
        val cipher = TransportException(DisconnectReason.PROTOCOL_ERROR, "cipher", GeneralSecurityException("bad padding"))

        assertNull(classifyLocalKeyFailure(mac))
        assertNull(classifyLocalKeyFailure(cipher))
        assertNull(classifyLocalKeyFailure(UserAuthException(mac)))
        assertNull(classifyLocalKeyFailure(UserAuthException("Exhausted available authentication methods", UserAuthException("attempt", mac))))
    }

    @Test
    fun `a security exception under a connection exception is the connection's`() {
        val e = UserAuthException(ConnectionException("channel", GeneralSecurityException("bad padding")))

        assertNull(classifyLocalKeyFailure(e))
    }

    @Test
    fun `a security exception anywhere under a transport exception is the transport's`() {
        // The authentication failed because the transport died; the key was never the question.
        val e = UserAuthException(
            "auth",
            TransportException(DisconnectReason.CONNECTION_LOST, "lost", IOException("x", GeneralSecurityException("y"))),
        )

        assertNull(classifyLocalKeyFailure(e))
    }

    // ── The Android Keystore ──────────────────────────────────────────────────

    @Test
    fun `a biometric window that ran out is told apart`() {
        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(SSHRuntimeException(biometricWindowOver())))
        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(biometricWindowOver()))
    }

    @Test
    fun `a key invalidated by a change of biometrics is told apart`() {
        assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(SSHRuntimeException(keyInvalidated())))
    }

    @Test
    fun `the Keystore exceptions are found under the attempts of the authentication`() {
        val expired = UserAuthException("Exhausted", UserAuthException("attempt", SSHRuntimeException(biometricWindowOver())))
        val invalid = UserAuthException("Exhausted", UserAuthException("attempt", SSHRuntimeException(keyInvalidated())))

        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(expired))
        assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(invalid))
    }

    @Test
    fun `the Keystore exceptions are security exceptions, but are never reported as an unreadable key`() {
        // Both extend InvalidKeyException: the more specific reading wins over the generic one.
        assertTrue(GeneralSecurityException::class.java.isAssignableFrom(UserNotAuthenticatedException::class.java))
        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(SSHRuntimeException(biometricWindowOver())))
        assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(SSHRuntimeException(keyInvalidated())))
    }

    @Test
    fun `an expired window wins over a plain key problem in the same chain`() {
        val e = UserAuthException(
            "Problem getting private key from KeystoreKeyProvider",
            SSHRuntimeException(biometricWindowOver()),
        )

        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(e))
    }

    // ── What proves a Keystore key is gone, and what does not ─────────────────

    @Test
    fun `only the signs that prove a Keystore key is gone say to regenerate it`() {
        val gone = listOf<Throwable>(
            keyInvalidated(),
            java.security.UnrecoverableKeyException("deleted"),
            java.security.KeyStoreException(fr.techtical.nextsh.core.crypto.MISSING_BIOMETRIC_KEY_MESSAGE),
        )
        for (sign in gone) {
            assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(sign), sign.javaClass.simpleName)
            // Under the wrappers SSHJ and the app put around it, too.
            assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(SSHRuntimeException(sign)))
            assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(UserAuthException("signing", sign)))
        }
    }

    @Test
    fun `any other Keystore failure is not a proof, it is the catch-all of an unreadable key`() {
        val notProofs = listOf<Throwable>(
            java.security.KeyStoreException("Key not found"),
            java.security.KeyStoreException(fr.techtical.nextsh.core.crypto.NOT_A_BIOMETRIC_ALIAS_MESSAGE),
            java.security.InvalidKeyException("Keystore operation failed"),
            java.security.SignatureException("Keystore operation failed"),
            java.security.NoSuchAlgorithmException("missing"),
        )
        for (failure in notProofs) {
            assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(failure), failure.javaClass.simpleName)
        }
        // Runtime failures of a provider or of a restarted Keystore say nothing at all.
        for (failure in listOf<Throwable>(java.security.ProviderException("Keystore restarted"), IllegalStateException("x"), NullPointerException())) {
            assertNull(classifyLocalKeyFailure(failure), failure.javaClass.simpleName)
        }
    }

    @Test
    fun `a proof wins over a catch-all in the same chain`() {
        val e = java.security.SignatureException("failed", java.security.UnrecoverableKeyException("deleted"))

        assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(e))
    }

    // ── API 33: the Keystore's own "user authentication is required" ──────────

    private fun userAuthRequired(code: Int = 2): android.security.KeyStoreException =
        mockk<android.security.KeyStoreException>(relaxed = true).also {
            every { it.numericErrorCode } returns code
            every { it.cause } returns null
        }

    @Test
    fun `on API 33 a Keystore error that requires user authentication is the expired window`() {
        val required = userAuthRequired()

        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(required, sdkInt = 33))
        // Sometimes the cause of a provider or signature exception.
        assertEquals(
            LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED,
            classifyLocalKeyFailure(java.security.ProviderException("update failed", required), sdkInt = 34),
        )
        assertEquals(
            LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED,
            classifyLocalKeyFailure(UserAuthException("signing", SSHRuntimeException(java.security.SignatureException("x", required))), sdkInt = 36),
        )
    }

    @Test
    fun `on API 33 another Keystore error code is not the expired window`() {
        // 6 and 7 (does not exist, corrupted) are not this either: they are a lost key, tested below.
        for (code in listOf(1, 3, 4, 5, 10, 11, 15)) {
            assertNull(classifyLocalKeyFailure(userAuthRequired(code), sdkInt = 33), "code $code")
        }
    }

    @Test
    fun `before API 33 the new Keystore exception is never looked at`() {
        // The class does not exist there; the level is checked first, whatever the object says.
        assertNull(classifyLocalKeyFailure(userAuthRequired(), sdkInt = 32))
        assertNull(classifyLocalKeyFailure(userAuthRequired(), sdkInt = 29))
        assertNull(classifyLocalKeyFailure(userAuthRequired(), sdkInt = 0))
    }

    @Test
    fun `the expired window still wins over a key problem on API 33`() {
        val e = UserAuthException("Problem getting private key from KeystoreKeyProvider", userAuthRequired())

        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, classifyLocalKeyFailure(e, sdkInt = 33))
    }

    // ── UnrecoverableKeyException is not airtight proof ───────────────────────

    private fun keystoreError(code: Int, transient: Boolean = false, cause: Throwable? = null): android.security.KeyStoreException =
        mockk<android.security.KeyStoreException>(relaxed = true).also {
            every { it.numericErrorCode } returns code
            every { it.isTransientFailure } returns transient
            every { it.cause } returns cause
        }

    private fun unrecoverable(keystore: Throwable?) = java.security.UnrecoverableKeyException("lookup failed").also {
        if (keystore != null) it.initCause(keystore)
    }

    @Test
    fun `an unrecoverable key with nothing else to say reads as a lost key, below API 33 and after`() {
        // The trade-off, documented on classifyLocalKeyFailure: nothing tells the two apart.
        for (sdk in listOf(0, 29, 32, 33, 36)) {
            assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(unrecoverable(null), sdkInt = sdk), "sdk $sdk")
        }
    }

    @Test
    fun `below API 33 the Keystore error under an unrecoverable key is not looked at`() {
        val transient = keystoreError(code = 4, transient = true)

        assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(unrecoverable(transient), sdkInt = 32))
    }

    @Test
    fun `on API 33 an unrecoverable key whose Keystore error is transient is a retry, not a lost key`() {
        val transient = keystoreError(code = 4, transient = true)

        assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(unrecoverable(transient), sdkInt = 33))
        assertEquals(
            LocalKeyFailure.UNREADABLE,
            classifyLocalKeyFailure(UserAuthException("signing", SSHRuntimeException(unrecoverable(transient))), sdkInt = 34),
        )
    }

    @Test
    fun `on API 33 an unrecoverable key is a lost key when the Keystore says the key does not exist or is corrupted`() {
        for (code in listOf(
            android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST,
            android.security.KeyStoreException.ERROR_KEY_CORRUPTED,
        )) {
            assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(unrecoverable(keystoreError(code)), sdkInt = 33), "code $code")
        }
    }

    @Test
    fun `on API 33 an unrecoverable key whose Keystore error confirms nothing is a retry`() {
        for (code in listOf(1, 3, 4, 5, 10, 11, 13, 15)) {
            assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(unrecoverable(keystoreError(code)), sdkInt = 33), "code $code")
        }
    }

    @Test
    fun `on API 33 a transient failure wins over a code that would confirm the loss`() {
        val both = keystoreError(code = android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST, transient = true)

        assertEquals(LocalKeyFailure.UNREADABLE, classifyLocalKeyFailure(unrecoverable(both), sdkInt = 33))
        assertNull(classifyLocalKeyFailure(both, sdkInt = 33), "and alone it proves nothing")
    }

    @Test
    fun `on API 33 a Keystore error that says the key does not exist is enough by itself`() {
        for (code in listOf(
            android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST,
            android.security.KeyStoreException.ERROR_KEY_CORRUPTED,
        )) {
            val e = java.security.ProviderException("update failed", keystoreError(code))
            assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, classifyLocalKeyFailure(e, sdkInt = 33), "code $code")
            assertNull(classifyLocalKeyFailure(e, sdkInt = 32), "code $code: before API 33 the class is not read")
        }
    }

    @Test
    fun `the Keystore signs are read from the whole chain, and only from API 33`() {
        val root = IllegalStateException("root")
        val authRequired = keystoreError(code = 2)
        val chain = causeChain(java.security.SignatureException("outer", keystoreError(code = 6, cause = authRequired)))
        val found = KeystoreApi33.inspect(chain, sdkInt = 33)

        assertTrue(found.userAuthenticationRequired)
        assertTrue(found.confirmsKeyLoss)
        assertFalse(found.transient)
        assertEquals(6, found.numericCode, "the first Keystore error of the chain")
        assertTrue(found.present)
        assertSame(KeystoreSigns.NONE, KeystoreApi33.inspect(chain, sdkInt = 32))
        assertFalse(KeystoreSigns.NONE.present)
        assertNull(KeystoreApi33.inspect(causeChain(root), sdkInt = 36).numericCode)
    }

    // ── The log line says the class and the Keystore code, nothing the exception says ──

    @Test
    fun `the log description of a Keystore problem is the root class and, from API 33, the code`() {
        val root = IllegalStateException("alias-and-detail-that-must-not-leak")
        val problem = java.security.SignatureException("outer", keystoreError(code = 4, cause = root))

        assertEquals("IllegalStateException, code=4", keyProblemLogDescription(problem, sdkInt = 33))
        assertEquals("IllegalStateException", keyProblemLogDescription(problem, sdkInt = 32))
        assertEquals("IllegalStateException", keyProblemLogDescription(root, sdkInt = 36))
        assertFalse(keyProblemLogDescription(problem, sdkInt = 33).contains("alias-and-detail"))
    }

    // ── The chain helpers the log lines use ───────────────────────────────────

    @Test
    fun `the root cause is the innermost one, and a cycle ends`() {
        val inner = IllegalArgumentException("inner")
        val chain = UserAuthException("outer", IllegalStateException("middle", inner))

        assertSame(inner, rootCause(chain))
        assertSame(inner, rootCause(inner))
        assertEquals(3, causeChain(chain).size)

        val a = SSHRuntimeException("a")
        val b = SSHRuntimeException("b", a)
        a.initCause(b)
        assertEquals(2, causeChain(a).size)
    }

    @Test
    fun `a cause cycle ends`() {
        val a = SSHRuntimeException("a")
        val b = SSHRuntimeException("b", a)
        a.initCause(b)

        assertNull(classifyLocalKeyFailure(a))
    }

    // ── What each one becomes ─────────────────────────────────────────────────

    @Test
    fun `an unreadable or invalidated key is a failure that is not retried, an expired window is renewed by the user`() {
        assertEquals(SshErrorCode.AUTH_FAILED, localKeyFailureCode(LocalKeyFailure.UNREADABLE))
        assertEquals(SshErrorCode.AUTH_FAILED, localKeyFailureCode(LocalKeyFailure.BIOMETRIC_KEY_INVALID))
        assertEquals(SshErrorCode.AUTH_EXPIRED, localKeyFailureCode(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED))

        assertTrue(localKeyFailureCode(LocalKeyFailure.UNREADABLE).isNonRetryable())
        assertTrue(localKeyFailureCode(LocalKeyFailure.BIOMETRIC_KEY_INVALID).isNonRetryable())
        assertFalse(localKeyFailureCode(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED).isNonRetryable())
    }

    // ── The wrappers of the connect functions ─────────────────────────────────

    @Test
    fun `a key failure with a cause that says nothing is an unreadable key`() {
        // An unrecognized format, a bad certificate, an unsupported cipher: all about the key.
        for (cause in listOf<Throwable>(
            IllegalArgumentException("Format de cle non reconnu"),
            IllegalStateException("Unsupported cipher"),
            IOException("Invalid certificate"),
        )) {
            assertEquals(LocalKeyFailure.UNREADABLE, KeyFailure(cause).failure, cause.javaClass.simpleName)
        }
    }

    @Test
    fun `a key failure keeps what is known about its cause`() {
        assertEquals(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, KeyFailure(SSHRuntimeException(biometricWindowOver())).failure)
        assertEquals(LocalKeyFailure.BIOMETRIC_KEY_INVALID, KeyFailure(SSHRuntimeException(keyInvalidated())).failure)
    }

    @Test
    fun `a key failure does not repeat its cause in its message`() {
        // Nothing the cause says (a path, a parser message) goes anywhere through the wrapper.
        assertNull(KeyFailure(IllegalArgumentException("secret-looking detail")).message)
    }

    @Test
    fun `loading a key turns what it throws into a key failure`() {
        val cause = IllegalArgumentException("Format de cle non reconnu")

        val thrown = assertThrows(KeyFailure::class.java) { loadingKey<Unit> { throw cause } }

        assertSame(cause, thrown.cause)
        assertEquals("loaded", loadingKey { "loaded" })
    }

    @Test
    fun `loading a key lets a cancellation through`() {
        val cancelled = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) { loadingKey<Unit> { throw cancelled } }

        assertSame(cancelled, thrown)
    }

    private fun clientConnected(connected: Boolean): SSHClient =
        mockk<SSHClient>().also { every { it.isConnected } returns connected }

    @Test
    fun `authenticating with a key turns a runtime exception into a key failure, not an exchange failure`() {
        val cause = SSHRuntimeException(biometricWindowOver())
        val client = clientConnected(true)

        val thrown = assertThrows(KeyFailure::class.java) { authenticatingWithKey<Unit>(client) { throw cause } }

        assertSame(cause, thrown.cause)
        assertEquals("signed", authenticatingWithKey(client) { "signed" })
    }

    @Test
    fun `authenticating with a key lets the exceptions of the exchange through untouched`() {
        val refusal = UserAuthException("Exhausted available authentication methods")
        val lost = TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")

        for (connected in listOf(true, false)) {
            val client = clientConnected(connected)
            assertSame(refusal, assertThrows(UserAuthException::class.java) { authenticatingWithKey<Unit>(client) { throw refusal } })
            assertSame(lost, assertThrows(TransportException::class.java) { authenticatingWithKey<Unit>(client) { throw lost } })
        }
    }

    @Test
    fun `authenticating with a key lets a cancellation through`() {
        // CancellationException is an IllegalStateException, so a plain RuntimeException catch would take it.
        val cancelled = CancellationException("cancelled")

        for (connected in listOf(true, false)) {
            val client = clientConnected(connected)
            val thrown = assertThrows(CancellationException::class.java) { authenticatingWithKey<Unit>(client) { throw cancelled } }

            assertSame(cancelled, thrown)
        }
    }

    @Test
    fun `a connection lost before the authentication is not blamed on the key`() {
        // SSHClient.auth starts with checkConnected(): IllegalStateException("Not connected").
        val notConnected = IllegalStateException("Not connected")
        val client = clientConnected(false)

        val thrown = assertThrows(UserAuthException::class.java) {
            authenticatingWithKey<Unit>(client) { throw notConnected }
        }

        // The shape of a transport that dies during the authentication: a transport failure, retried.
        assertEquals(UserAuthFailure.TRANSPORT, classifyUserAuthFailure(thrown))
        assertEquals(SshErrorCode.HOST_UNREACHABLE, userAuthFailureCode(classifyUserAuthFailure(thrown)))
        assertNull(classifyLocalKeyFailure(thrown))
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === notConnected }, "the original exception stays in the chain")
    }

    @Test
    fun `a runtime exception is a key failure only while the client is connected`() {
        val failure = IllegalStateException("Unsupported cipher")

        assertThrows(KeyFailure::class.java) { authenticatingWithKey<Unit>(clientConnected(true)) { throw failure } }
        assertThrows(UserAuthException::class.java) { authenticatingWithKey<Unit>(clientConnected(false)) { throw failure } }
    }
}
