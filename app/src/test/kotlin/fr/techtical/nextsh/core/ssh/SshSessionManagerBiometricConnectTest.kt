// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import fr.techtical.nextsh.R
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.signature.SignatureECDSA
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.AuthParams
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `SshSessionManager.connectWithBiometricKey` against a fake client: the real [BiometricAuthMethod]
 * is driven the way SSHJ drives it (feeler, then PK_OK), the key is a software EC P-256 key standing
 * in for the Keystore one, and the prompt is whatever the test makes the callback answer. What is
 * under test is what happens around the signature: when it is opened, when the prompt is asked,
 * when it signs, and how each way out reads for the user.
 */
class SshSessionManagerBiometricConnectTest {

    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsDataStore>().also {
        every { it.settingsFlow } returns flowOf(SettingsDataStore.Settings())
    }
    private val manager = SshSessionManager(
        knownHostsVerifier = mockk(),
        sshKeyManager = mockk(),
        settingsDataStore = settings,
        context = context,
    )

    private val sessionId = ByteArray(32) { (0x20 + it).toByte() }
    private val written = mutableListOf<ByteArray>()
    private val transport = mockk<Transport>(relaxed = true)
    private val client = mockk<SSHClient>(relaxed = true).also {
        every { it.transport } returns transport
        every { it.isConnected } returns true
    }

    /** Every value written to `Transport.timeoutMs`, in order, with `-1` for the authentication itself. */
    private val timeline = mutableListOf<Int>()

    private val params = object : AuthParams {
        override fun getNextServiceName(): String = "ssh-connection"
        override fun getTransport(): Transport = this@SshSessionManagerBiometricConnectTest.transport
        override fun getUsername(): String = "user"
    }

    init {
        manager.clientFactory = { client }
        every { transport.timeoutMs = any() } answers { timeline += firstArg<Int>() }
        every { transport.sessionID } returns sessionId
        every { transport.write(any<SSHPacket>()) } answers {
            written += firstArg<SSHPacket>().compactData
            0L
        }
        // A string is its resource id, so a test can tell which message the user would read.
        every { context.getString(any<Int>()) } answers { "res:${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg<Any>()) } answers { "res:${firstArg<Int>()}" }
    }

    private val host = Host(
        id = "host-1",
        label = "Host 1",
        hostname = "host1.example.com",
        username = "user",
        authType = AuthType.BIOMETRIC_KEY,
        credentialId = "key-1",
    )

    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** What happened to the Keystore signature, in order, with the prompt in between. */
    private val events = mutableListOf<String>()

    /**
     * A `Signature` that stands for the Keystore one: it signs with the software key and says when
     * it is updated and signed. [onSign] can make the signing fail, as the Keystore does for a key
     * that was invalidated, or that the fingerprint did not authorize. [shapeSignature] changes what it
     * returns, to hand the method a signature that is not DER.
     */
    private inner class FakeKeystoreSignature(
        private val onSign: () -> Unit = {},
        private val shapeSignature: (ByteArray) -> ByteArray = { it },
    ) : Signature("SHA256withECDSA") {
        private val delegate = Signature.getInstance("SHA256withECDSA")

        override fun engineInitVerify(publicKey: PublicKey) = throw UnsupportedOperationException()
        override fun engineInitSign(privateKey: PrivateKey) = delegate.initSign(privateKey)
        override fun engineUpdate(b: Byte) {
            events += "update"
            delegate.update(b)
        }

        override fun engineUpdate(b: ByteArray, off: Int, len: Int) {
            events += "update"
            delegate.update(b, off, len)
        }

        override fun engineSign(): ByteArray {
            events += "sign"
            onSign()
            return shapeSignature(delegate.sign())
        }

        override fun engineVerify(sigBytes: ByteArray): Boolean = throw UnsupportedOperationException()

        @Deprecated("Deprecated in Java")
        override fun engineSetParameter(param: String, value: Any) = Unit

        @Deprecated("Deprecated in Java")
        override fun engineGetParameter(param: String): Any = ""
    }

    private fun keystoreSignature(onSign: () -> Unit = {}, shapeSignature: (ByteArray) -> ByteArray = { it }): Signature =
        FakeKeystoreSignature(onSign, shapeSignature).also { it.initSign(pair.private) }

    /** Counted, so a test can say the Keystore was not touched. */
    private var opened = 0
    private val openedSignatures = mutableListOf<Signature>()

    private fun opener(onSign: () -> Unit = {}, shapeSignature: (ByteArray) -> ByteArray = { it }): () -> Signature = {
        opened++
        keystoreSignature(onSign, shapeSignature).also { openedSignatures += it }
    }

    /**
     * Makes the fake client run the authentication as SSHJ does: the feeler goes out, the server
     * accepts the key (USERAUTH_60), and the method asks for the signature. [afterHandler] runs once
     * the handler returned without throwing, that is once a signed request is on the transport.
     */
    private fun serverAcceptsTheKey(afterHandler: () -> Unit = {}) {
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            val method = (args[1] as Array<*>).single() as BiometricAuthMethod
            method.init(params)
            method.request()
            method.handle(Message.USERAUTH_60, SSHPacket())
            afterHandler()
        }
    }

    private fun serverRefusesTheKey(failure: Throwable = UserAuthException("Exhausted available authentication methods")) {
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            throw failure
        }
    }

    private suspend fun connect(openSignature: () -> Signature = opener()) =
        manager.connectWithBiometricKey(host, pair.public, "Bio Key", openSignature)

    private fun promptReturns(response: BiometricPromptResponse?) {
        manager.biometricSignatureCallback = { events += "prompt"; response }
    }

    private fun assertSessionForgotten(): Unit = assertTrue(manager.activeSessions.value.isEmpty(), "the session is forgotten")

    // ── The way in: one fingerprint, after PK_OK ──────────────────────────────

    @Test
    fun `a recognized fingerprint signs and authenticates the session`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Authorized)
        val result = connect()

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(1, manager.activeSessions.value.size)
        verify(exactly = 0) { client.disconnect() }
        assertEquals(listOf(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, -1, TRANSPORT_TIMEOUT_MS), timeline)
    }

    @Test
    fun `the signed request goes out after the feeler and verifies with the public key`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Authorized)
        connect()

        assertEquals(2, written.size, "the feeler, then the signed request")
        val signed = Buffer.PlainBuffer(written[1])
        signed.readByte()
        assertEquals("user", signed.readString())
        assertEquals("ssh-connection", signed.readString())
        assertEquals("publickey", signed.readString())
        assertTrue(signed.readBoolean())
        assertEquals("ecdsa-sha2-nistp256", signed.readString())
        val keyBlob = signed.readStringAsBytes()
        val signatureField = signed.readStringAsBytes()
        assertEquals(0, signed.available())

        val signingData = Buffer.PlainBuffer()
            .putString(sessionId)
            .putByte(Message.USERAUTH_REQUEST.toByte())
            .putString("user")
            .putString("ssh-connection")
            .putString("publickey")
            .putBoolean(true)
            .putString("ecdsa-sha2-nistp256")
            .putString(keyBlob)
            .compactData
        val verifier = SignatureECDSA.Factory256().create().apply {
            initVerify(pair.public)
            update(signingData)
        }
        assertTrue(verifier.verify(signatureField), "the server accepts what the key signed")
    }

    @Test
    fun `the Keystore signature is opened at PK_OK, the prompt authorizes that very one, and it signs after`() = runBlocking {
        val requests = mutableListOf<BiometricSignRequest>()
        serverAcceptsTheKey()
        manager.biometricSignatureCallback = { request ->
            requests += request
            events += "prompt"
            BiometricPromptResponse.Authorized
        }

        connect()

        assertEquals(1, opened)
        val request = requests.single()
        assertSame(openedSignatures.single(), request.signature, "the CryptoObject wraps the signature that will sign")
        assertEquals("Host 1", request.hostLabel)
        assertEquals("Bio Key", request.keyLabel)
        // The data to sign is never given to the prompt: it signs after the prompt, here.
        assertEquals(listOf("prompt", "update", "sign"), events)
    }

    @Test
    fun `one fingerprint per connection`() = runBlocking {
        var prompts = 0
        serverAcceptsTheKey()
        manager.biometricSignatureCallback = {
            prompts++
            BiometricPromptResponse.Authorized
        }

        connect()

        assertEquals(1, prompts)
    }

    @Test
    fun `a key the server refuses gets no prompt and no open Keystore signature`() = runBlocking {
        serverRefusesTheKey()
        var prompts = 0
        manager.biometricSignatureCallback = {
            prompts++
            BiometricPromptResponse.Authorized
        }

        val result = connect() as SshResult.Error

        assertEquals(0, prompts, "the user is not asked for a finger for a key the server does not know")
        assertEquals(0, opened, "nor is a Keystore operation opened")
        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_failed_biometric}", result.message)
        verify(exactly = 1) { client.disconnect() }
        assertSessionForgotten()
    }

    // ── The way out without a signature ───────────────────────────────────────

    @Test
    fun `a cancelled prompt reads as a cancellation, never signs, and is not retried`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Cancelled)
        val result = connect() as SshResult.Error

        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, result.code)
        assertEquals("res:${R.string.auth_error_fido2_cancelled}", result.message)
        assertTrue(result.code.isNonRetryable(), "no reconnection asks again for a finger the user refused")
        assertEquals(listOf("prompt"), events, "nothing was updated or signed")
        assertEquals(1, written.size, "only the feeler went out")
        verify(exactly = 1) { client.disconnect() }
        assertSessionForgotten()
    }

    @Test
    fun `a prompt nobody answered reads as a timeout of the fingerprint, and is not retried`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(null)
        val result = connect() as SshResult.Error

        assertEquals(SshErrorCode.FIDO2_TIMEOUT, result.code)
        assertEquals("res:${R.string.auth_error_biometric_timeout}", result.message)
        assertTrue(result.code.isNonRetryable())
        assertEquals(listOf("prompt"), events)
        assertSessionForgotten()
    }

    @Test
    fun `each reason a sensor fails has its own sentence, as an authentication failure that is not retried`() {
        val expected = mapOf(
            BiometricFailure.LOCKOUT to R.string.auth_error_biometric_lockout,
            BiometricFailure.LOCKOUT_PERMANENT to R.string.auth_error_biometric_lockout_permanent,
            BiometricFailure.NO_BIOMETRICS to R.string.auth_error_biometric_none_enrolled,
            BiometricFailure.HW_UNAVAILABLE to R.string.auth_error_biometric_sensor_unavailable,
            BiometricFailure.HW_NOT_PRESENT to R.string.auth_error_biometric_no_sensor,
            BiometricFailure.OTHER to R.string.auth_error_biometric_error,
        )
        assertEquals(BiometricFailure.entries.toSet(), expected.keys, "a reason without a sentence")

        for ((reason, message) in expected) {
            serverAcceptsTheKey()
            promptReturns(BiometricPromptResponse.Failed(reason))
            val result = runBlocking { connect() } as SshResult.Error

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, "$reason")
            assertEquals("res:$message", result.message, "$reason")
            assertTrue(result.code.isNonRetryable(), "$reason")
        }
        assertEquals(expected.values.toSet().size, expected.size, "no two reasons say the same thing")
    }

    // ── A key that the Keystore will not use ──────────────────────────────────

    private fun keyInvalidated(): KeyPermanentlyInvalidatedException =
        mockk<KeyPermanentlyInvalidatedException>(relaxed = true).also { every { it.cause } returns null }

    private fun windowOver(): UserNotAuthenticatedException =
        mockk<UserNotAuthenticatedException>(relaxed = true).also { every { it.cause } returns null }

    @Test
    fun `a key invalidated when the signature is opened says to regenerate it, with no prompt, and is not retried`() = runBlocking {
        serverAcceptsTheKey()
        var prompts = 0
        manager.biometricSignatureCallback = {
            prompts++
            BiometricPromptResponse.Authorized
        }

        val result = connect { throw keyInvalidated() } as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_biometric_key_invalid}", result.message)
        assertTrue(result.code.isNonRetryable())
        assertEquals(0, prompts, "no fingerprint for a key that cannot sign")
        verify(exactly = 1) { client.disconnect() }
        assertSessionForgotten()
    }

    @Test
    fun `a Keystore key that is gone at PK_OK says to regenerate it, never the passphrase hint`() = runBlocking {
        // The three signs that prove it: invalidated (above), deleted (the missing entry of
        // KeystoreManager) and unrecoverable. Regenerating a healthy key would cut the access to
        // every server it is authorized on, so nothing weaker may say it.
        for (failure in listOf<Throwable>(
            java.security.KeyStoreException(fr.techtical.nextsh.core.crypto.MISSING_BIOMETRIC_KEY_MESSAGE),
            java.security.UnrecoverableKeyException("deleted"),
        )) {
            serverAcceptsTheKey()
            promptReturns(BiometricPromptResponse.Authorized)
            val result = connect { throw failure } as SshResult.Error

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, failure.javaClass.simpleName)
            assertEquals("res:${R.string.auth_error_biometric_key_invalid}", result.message, failure.javaClass.simpleName)
            assertTrue(result.message != "res:${R.string.auth_error_key_unreadable}")
            assertTrue(result.code.isNonRetryable())
        }
    }

    @Test
    fun `any other Keystore failure at PK_OK is neutral, the user may try again and the key is not blamed`() = runBlocking {
        // A transient provider error, a Keystore that restarted, a pruned operation, an entry that
        // read null: none proves the key is gone.
        for (failure in listOf<Throwable>(
            java.security.KeyStoreException("Keystore not available"),
            java.security.ProviderException("Keystore restarted"),
            java.security.InvalidKeyException("operation failed"),
            IllegalStateException("keystore went away"),
            NullPointerException("entry is null"),
        )) {
            serverAcceptsTheKey()
            promptReturns(BiometricPromptResponse.Authorized)
            val result = connect { throw failure } as SshResult.Error

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, failure.javaClass.simpleName)
            assertEquals("res:${R.string.auth_error_biometric_key_unusable}", result.message, failure.javaClass.simpleName)
            assertTrue(result.message != "res:${R.string.auth_error_biometric_key_invalid}")
            assertTrue(result.message != "res:${R.string.auth_error_key_unreadable}")
        }
    }

    @Test
    fun `a key that is invalidated between the fingerprint and the signature says to regenerate it`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Authorized)
        val result = connect(opener { throw keyInvalidated() }) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_biometric_key_invalid}", result.message)
        assertEquals(listOf("prompt", "update", "sign"), events)
        assertSessionForgotten()
    }

    @Test
    fun `a signature the Keystore did not authorize asks to try again`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Authorized)
        val result = connect(opener { throw windowOver() }) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_EXPIRED, result.code)
        assertEquals("res:${R.string.auth_error_biometric_expired}", result.message)
    }

    @Test
    fun `a signing failure of the Keystore is neutral, not an order to regenerate the key`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Authorized)
        val result = connect(opener { throw SignatureException("hardware said no") }) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_biometric_key_unusable}", result.message)
        assertSessionForgotten()
    }

    @Test
    fun `a signature that is not DER reads as a signing problem, never as the server refusing the key`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Authorized)
        val garbage = opener(shapeSignature = { ByteArray(70) { 0x11 } })

        val result = connect(garbage) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_biometric_key_unusable}", result.message)
        assertTrue(result.message != "res:${R.string.auth_error_failed_biometric}", "that text blames the server")
        assertEquals(listOf("prompt", "update", "sign"), events)
        assertEquals(1, written.size, "only the feeler went out, nothing malformed")
        assertSessionForgotten()
    }

    @Test
    fun `the log names the class of the root cause of a Keystore failure and nothing it says`() = runBlocking {
        val tree = fr.techtical.nextsh.testutil.RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            serverAcceptsTheKey()
            promptReturns(BiometricPromptResponse.Authorized)
            connect(opener { throw SignatureException("outer", IllegalStateException("alias-and-detail-that-must-not-leak")) })
        } finally {
            timber.log.Timber.uproot(tree)
        }

        val lines = tree.entries.map { it.message }
        assertTrue(lines.any { it.contains("IllegalStateException") }, "the root cause class is logged: $lines")
        assertTrue(lines.none { it.contains("alias-and-detail-that-must-not-leak") || it.contains("Host 1") || it.contains("Bio Key") }, "$lines")
    }

    @Test
    fun `an unexpected failure of a Keystore key is logged by the class of its root cause, without the throwable`() = runBlocking {
        // A runtime failure of the authentication itself, not of the exchange: the catch-all of the
        // connection. The throwable would carry the message, and the message may name the alias.
        promptReturns(BiometricPromptResponse.Authorized)
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } throws
            IllegalStateException("outer", UnsupportedOperationException("alias-nextsh-bio-key-1234"))
        val tree = fr.techtical.nextsh.testutil.RecordingTree()
        timber.log.Timber.plant(tree)
        val result = try {
            connect()
        } finally {
            timber.log.Timber.uproot(tree)
        }

        assertTrue(result is SshResult.Error, "$result")
        val errors = tree.entries.filter { it.priority == android.util.Log.ERROR }
        assertEquals(1, errors.size, "${tree.entries}")
        assertNull(errors.single().throwable, "no throwable: its message and its causes would reach the log")
        assertTrue(errors.single().message.contains("UnsupportedOperationException"), errors.single().message)
        assertTrue(tree.entries.none { it.message.contains("alias-nextsh-bio-key-1234") }, "${tree.entries}")
    }

    @Test
    fun `a signing problem does not hide a broken transport behind a key problem`() = runBlocking {
        // The signer's thread is interrupted by the disconnect: SSHJ's own reading (a lost
        // transport, retried) wins over the neutral key message.
        serverAcceptsTheKey()
        manager.biometricSignatureCallback = { throw InterruptedException("woken by a disconnect") }

        val result = try {
            connect() as SshResult.Error
        } finally {
            Thread.interrupted()
        }

        assertEquals(SshErrorCode.HOST_UNREACHABLE, result.code)
        assertFalse(result.code.isNonRetryable())
    }

    @Test
    fun `a refusal after a fingerprint is still the usual refusal, with the authorized_keys hint`() = runBlocking {
        // The server refuses after the signed request: no prompt end, no key problem, no signer problem.
        serverAcceptsTheKey(afterHandler = {
            throw UserAuthException("Exhausted available authentication methods")
        })
        promptReturns(BiometricPromptResponse.Authorized)

        val result = connect() as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_failed_biometric}", result.message)
    }

    @Test
    fun `a key that is not EC P-256 is refused before any connection is made`() = runBlocking {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var built = 0
        manager.clientFactory = { built++; client }
        promptReturns(BiometricPromptResponse.Authorized)
        val result = manager.connectWithBiometricKey(host, rsa.public, "Bio Key", opener()) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_biometric_key_invalid}", result.message)
        assertEquals(0, built, "no connection for a key that cannot be used")
        assertEquals(0, opened)
        assertTrue(manager.activeSessions.value.isEmpty())
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    @Test
    fun `without the fingerprint callback nothing is attempted and the client is closed`() = runBlocking {
        manager.biometricSignatureCallback = null
        serverAcceptsTheKey()

        val result = connect() as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_prompt_unavailable}", result.message)
        assertTrue(result.code.isNonRetryable())
        assertEquals(emptyList<Int>(), timeline)
        assertEquals(0, opened)
        verify(exactly = 1) { client.disconnect() }
        assertSessionForgotten()
    }

    @Test
    fun `the time the user takes is not the server's silence`() = runBlocking {
        // A prompt three times longer than the watchdog bound, answered in the end.
        manager.fido2ServerReplyTimeoutMs = 150
        serverAcceptsTheKey()
        manager.biometricSignatureCallback = {
            delay(450)
            BiometricPromptResponse.Authorized
        }

        val result = connect()

        assertTrue(result is SshResult.Success, "$result")
        verify(exactly = 0) { client.disconnect() }
    }

    private val serverClosed = CountDownLatch(1)

    @Test
    fun `a server that goes silent after the signature is cut at the watchdog bound`() = runBlocking {
        manager.fido2ServerReplyTimeoutMs = 200
        every { client.disconnect() } answers { serverClosed.countDown() }
        serverAcceptsTheKey(afterHandler = {
            if (!serverClosed.await(10, TimeUnit.SECONDS)) throw AssertionError("the watchdog never closed the connection")
            throw UserAuthException(TransportException(DisconnectReason.CONNECTION_LOST, "closed under the wait"))
        })
        promptReturns(BiometricPromptResponse.Authorized)
        val result = connect() as SshResult.Error

        assertEquals(SshErrorCode.TIMEOUT, result.code, "a late server is retried, not a refusal")
        assertSessionForgotten()
    }

    // ── With the real coordinator: the dialog and the connection end each other ──

    private fun <T> withRealCoordinator(block: suspend (BiometricPromptCoordinator, CoroutineScope) -> T): T = runBlocking {
        val coordinator = BiometricPromptCoordinator()
        coordinator.install(manager)
        // A scope of its own: if the wiring ever breaks, the blocked SSH thread must fail the test
        // by its timeout, not hang a runBlocking that waits for its children.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(coordinator, scope)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the dialog authorizes the signature that the connection waits for`() {
        serverAcceptsTheKey()

        val result = withRealCoordinator { coordinator, scope ->
            val connection = scope.async { connect() }
            val shown = withTimeout(5_000) { coordinator.state.first { it != null } }!!
            assertTrue(coordinator.markLaunched(shown.request), "the host starts the dialog")
            coordinator.authorize(shown.request)
            withTimeout(5_000) { connection.await() }
        }

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(listOf("update", "sign"), events)
    }

    @Test
    fun `a cancelled dialog ends the connection as a cancellation`() {
        serverAcceptsTheKey()

        val result = withRealCoordinator { coordinator, scope ->
            val connection = scope.async { connect() }
            val shown = withTimeout(5_000) { coordinator.state.first { it != null } }!!
            coordinator.reportError(shown.request, androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON)
            withTimeout(5_000) { connection.await() }
        } as SshResult.Error

        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, result.code)
        assertTrue(events.isEmpty(), "never signed")
    }

    @Test
    fun `cancelling the connection removes the fingerprint prompt and closes the client`() {
        serverAcceptsTheKey()

        withRealCoordinator { coordinator, scope ->
            val connection = scope.launch { connect() }
            val shown = withTimeout(5_000) { coordinator.state.first { it != null } }
            assertNotNull(shown)
            assertSame(openedSignatures.single(), (shown!!.request as BiometricSignRequest).signature)

            connection.cancel()
            withTimeout(5_000) { connection.join() }

            assertTrue(connection.isCancelled)
            assertNull(coordinator.state.value, "the prompt goes with its connection")
        }

        verify(atLeast = 1) { client.disconnect() }
        assertSessionForgotten()
    }

    @Test
    fun `a connection that waits for a prompt shown to another one takes its turn, then asks`() {
        serverAcceptsTheKey()

        withRealCoordinator { coordinator, scope ->
            val first = scope.async { connect() }
            val shownFirst = withTimeout(5_000) { coordinator.state.first { it != null } }!!
            val second = scope.async { connect() }
            delay(200)
            assertSame(shownFirst.request, coordinator.state.value?.request, "the second one waits behind the first")
            assertFalse(second.isCompleted)

            coordinator.cancel(shownFirst.request)
            withTimeout(5_000) { first.await() }
            val shownSecond = withTimeout(5_000) { coordinator.state.first { it != null && it.request !== shownFirst.request } }!!
            coordinator.authorize(shownSecond.request)

            val result = withTimeout(5_000) { second.await() }
            assertTrue(result is SshResult.Success, "$result")
        }
    }

    // ── Nothing sensitive on the way ──────────────────────────────────────────

    @Test
    fun `the labels given to the prompt never reach the message of a failed connection`() = runBlocking {
        serverAcceptsTheKey()
        promptReturns(BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT))
        val result = connect() as SshResult.Error

        assertFalse(result.message.contains("Host 1"))
        assertFalse(result.message.contains("Bio Key"))
    }

}
