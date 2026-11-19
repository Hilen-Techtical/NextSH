// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.shared.core.ssh.fido2.SkAuthMethod
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.userauth.AuthParams
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthMethod
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeoutException

/**
 * The connect functions of [SshSessionManager] against a fake client (see
 * [SshSessionManager.clientFactory]): no SSH server in a unit test, but the code that follows
 * the authentication (timeouts, error classification, clean-up) is the code under test.
 */
class SshSessionManagerConnectTest {

    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsDataStore>().also {
        every { it.settingsFlow } returns flowOf(SettingsDataStore.Settings())
    }
    private val sshKeyManager = mockk<SshKeyManager>()
    private val keyProvider = mockk<KeyProvider>()
    private val manager = SshSessionManager(
        knownHostsVerifier = mockk(),
        sshKeyManager = sshKeyManager,
        settingsDataStore = settings,
        context = context,
    )

    private val transport = mockk<Transport>(relaxed = true)
    // Connected unless a test says the link died (a relaxed mock would answer false).
    private val client = mockk<SSHClient>(relaxed = true).also {
        every { it.transport } returns transport
        every { it.isConnected } returns true
    }

    /** Every value written to `Transport.timeoutMs`, in order, with `-1` for the authentication itself. */
    private val timeline = mutableListOf<Int>()

    init {
        manager.clientFactory = { client }
        every { transport.timeoutMs = any() } answers { timeline += firstArg<Int>() }
        // A string is its resource id, so a test can tell which message the user would read.
        every { context.getString(any<Int>()) } answers { "res:${firstArg<Int>()}" }
        every { context.getString(any<Int>(), *anyVararg<Any>()) } answers { "res:${firstArg<Int>()}" }
    }

    private val host = Host(
        id = "host-1",
        label = "Host 1",
        hostname = "host1.example.com",
        username = "user",
        authType = AuthType.FIDO2,
        credentialId = "cred-1",
    )

    private fun skKey() = SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { 1 })

    private fun authFails(failure: Throwable) {
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            throw failure
        }
    }

    private fun authSucceeds() {
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers { timeline += -1 }
    }

    // ── FIDO2: the transport wait does not cut the prompt short ───────────────

    @Test
    fun `the FIDO2 authentication runs with the transport wait raised, then the usual timeout is back`() = runTest {
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        authSucceeds()

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(listOf(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, -1, TRANSPORT_TIMEOUT_MS), timeline)
    }

    @Test
    fun `the usual timeout is back when the FIDO2 authentication fails`() = runTest {
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        authFails(UserAuthException("FIDO2 challenge cancelled by user"))

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(listOf(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, -1, TRANSPORT_TIMEOUT_MS), timeline)
    }

    @Test
    fun `without the FIDO2 callback nothing is attempted`() = runTest {
        manager.fido2ChallengeCallback = null

        val result = manager.connectWithSkKey(host, skKey())

        assertEquals(SshErrorCode.FIDO2_NO_KEY, (result as SshResult.Error).code)
        assertEquals("res:${R.string.auth_error_prompt_unavailable}", result.message)
        assertEquals(emptyList<Int>(), timeline)
    }

    // ── Liveness of a registered session: isConnected and isAuthenticated ─────

    @Test
    fun `an unknown session is neither connected nor authenticated`() {
        assertFalse(manager.isConnected("no-such-session"))
        assertFalse(manager.isAuthenticated("no-such-session"))
    }

    @Test
    fun `a registered session is asked to its client each time`() = runTest {
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        authSucceeds()
        every { client.isAuthenticated } returns true
        val sessionId = (manager.connectWithSkKey(host, skKey()) as SshResult.Success).data.id

        assertTrue(manager.isConnected(sessionId))
        assertTrue(manager.isAuthenticated(sessionId))

        // The link dies: the registry still knows the session, the client answers for it.
        every { client.isConnected } returns false
        assertFalse(manager.isConnected(sessionId))
        assertTrue(manager.isAuthenticated(sessionId), "SSHJ does not reset the authenticated flag")

        every { client.isAuthenticated } returns false
        assertFalse(manager.isAuthenticated(sessionId))
    }

    @Test
    fun `a session whose authentication failed is not registered`() = runTest {
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        authFails(UserAuthException("refused"))

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Error, "$result")
        // A client only enters the registry after a successful authentication.
        assertTrue(manager.sessions.first().isEmpty())
    }

    // ── Every connect path: a refusal is AUTH_FAILED, a broken exchange is not ─

    private enum class Path { PASSWORD, KEY, CERTIFICATE, BIOMETRIC, FIDO2 }

    /** The Keystore key of the biometric paths, as a software EC P-256 key. */
    private val biometricKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    /** What `KeystoreManager.getSignatureForBiometricKey` gives, with a software key. */
    private fun biometricSignature(): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(biometricKey.private) }

    /** Makes the authentication of [path] throw [failure]. */
    private fun failAuthWith(path: Path, failure: Throwable) {
        when (path) {
            Path.PASSWORD -> every { client.authPassword(any<String>(), any<String>()) } throws failure
            Path.KEY -> {
                every { sshKeyManager.loadKeyProviderFromString(any(), any()) } returns keyProvider
                every { client.authPublickey(any<String>(), *anyVararg<KeyProvider>()) } throws failure
            }
            Path.CERTIFICATE -> {
                every { sshKeyManager.loadKeyProviderWithCertificate(any(), any(), any()) } returns keyProvider
                every { client.authPublickey(any<String>(), *anyVararg<KeyProvider>()) } throws failure
            }
            Path.BIOMETRIC -> {
                manager.biometricSignatureCallback = { BiometricPromptResponse.Cancelled }
                authFails(failure)
            }
            Path.FIDO2 -> {
                manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
                authFails(failure)
            }
        }
    }

    private suspend fun connect(path: Path): SshResult<*> = when (path) {
        Path.PASSWORD -> manager.connectWithPassword(host, "secret".toCharArray())
        Path.KEY -> manager.connectWithKey(host, "PEM", null)
        Path.CERTIFICATE -> manager.connectWithCertificate(host, "PEM", "CERT", null)
        Path.BIOMETRIC -> manager.connectWithBiometricKey(host, biometricKey.public, "Bio Key") { biometricSignature() }
        Path.FIDO2 -> manager.connectWithSkKey(host, skKey())
    }

    private fun codeOf(path: Path, failure: Throwable): SshErrorCode = runBlocking {
        failAuthWith(path, failure)
        (connect(path) as SshResult.Error).code
    }

    @Test
    fun `a refusal is an authentication failure on every path`() {
        for (path in Path.entries) {
            assertEquals(
                SshErrorCode.AUTH_FAILED,
                codeOf(path, UserAuthException("Exhausted available authentication methods")),
                "$path",
            )
        }
    }

    @Test
    fun `a transport that breaks during the authentication is not an authentication failure on any path`() {
        for (path in Path.entries) {
            val lost = TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost")
            assertEquals(SshErrorCode.HOST_UNREACHABLE, codeOf(path, UserAuthException(lost)), "$path")
        }
    }

    @Test
    fun `a wait that runs out during the authentication is a timeout on every path`() {
        for (path in Path.entries) {
            val late = UserAuthException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))
            assertEquals(SshErrorCode.TIMEOUT, codeOf(path, late), "$path")
        }
    }

    @Test
    fun `a wrong passphrase reads as an unreadable key on the key file paths, and is not retried`() {
        // The key file is read and decrypted during the authentication; SSHJ wraps the failure.
        for (path in listOf(Path.KEY, Path.CERTIFICATE)) {
            val wrongPassphrase = UserAuthException(
                "Problem getting private key from FileKeyProvider",
                KeyDecryptionFailedException(),
            )

            val result = runBlocking { failAuthWith(path, wrongPassphrase); connect(path) } as SshResult.Error

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, "$path")
            assertEquals("res:${R.string.auth_error_key_unreadable}", result.message, "$path")
            assertTrue(result.code.isNonRetryable(), "$path")
        }
    }

    // ── A key that is wrong before the server is asked anything ───────────────

    private fun unreadableKeyResult(path: Path) = runBlocking { connect(path) } as SshResult.Error

    private fun assertUnreadableKey(path: Path, result: SshResult.Error) {
        assertEquals(SshErrorCode.AUTH_FAILED, result.code, "$path")
        assertEquals("res:${R.string.auth_error_key_unreadable}", result.message, "$path")
        assertTrue(result.code.isNonRetryable(), "$path: the same key is not sent again by the reconnection")
        verify(exactly = 1) { client.disconnect() }
        assertTrue(manager.activeSessions.value.isEmpty(), "$path: the session is forgotten")
    }

    @Test
    fun `a key whose format is not recognized is an unreadable key, not an unknown error`() {
        // SshKeyManager throws IllegalArgumentException for an unrecognized or corrupt PEM.
        clearMocks(client, answers = false)
        every { sshKeyManager.loadKeyProviderFromString(any(), any()) } throws
            IllegalArgumentException("Format de cle non reconnu")

        assertUnreadableKey(Path.KEY, unreadableKeyResult(Path.KEY))
    }

    @Test
    fun `a bad certificate is an unreadable key, not an unknown error`() {
        for (failure in listOf<Throwable>(
            IllegalArgumentException("Certificat illisible"),
            java.io.IOException("Invalid certificate: bad buffer"),
            net.schmizz.sshj.common.Buffer.BufferException("Underflow"),
        )) {
            clearMocks(client, answers = false)
            every { sshKeyManager.loadKeyProviderWithCertificate(any(), any(), any()) } throws failure

            assertUnreadableKey(Path.CERTIFICATE, unreadableKeyResult(Path.CERTIFICATE))
        }
    }

    @Test
    fun `an unsupported cipher or KDF raised while authenticating is an unreadable key`() {
        // SSHJ throws IllegalStateException (not an IOException) for an OpenSSH key it cannot decrypt.
        for (path in listOf(Path.KEY, Path.CERTIFICATE)) {
            clearMocks(client, answers = false)
            failAuthWith(path, IllegalStateException("Unsupported cipher: aes256-gcm@openssh.com"))

            assertUnreadableKey(path, unreadableKeyResult(path))
        }
    }

    @Test
    fun `a connection lost before the authentication is a lost connection on every path, not an unreadable key`() {
        // SSHClient.auth starts with checkConnected(): IllegalStateException("Not connected") when the
        // link died between connect() and the authentication. The key paths read it that way, and
        // so do the password and the FIDO2 key, which have no key of this device to blame.
        for (path in Path.entries) {
            clearMocks(client, answers = false)
            every { client.isConnected } returns false
            failAuthWith(path, IllegalStateException("Not connected"))

            val result = runBlocking { connect(path) } as SshResult.Error

            assertEquals(SshErrorCode.HOST_UNREACHABLE, result.code, "$path")
            assertFalse(result.code.isNonRetryable(), "$path: retried, not held")
            assertEquals("res:${R.string.connect_error_generic}", result.message, "$path")
            verify(exactly = 1) { client.disconnect() }
            assertTrue(manager.activeSessions.value.isEmpty(), "$path: the session is forgotten")
        }
    }

    @Test
    fun `any runtime exception of the authentication on a link that is gone is a lost connection, on the paths without a key of the device`() {
        // Whatever the exception says: it is the state of the link that decides.
        for (path in listOf(Path.PASSWORD, Path.FIDO2)) {
            for (failure in listOf<RuntimeException>(
                IllegalStateException("Not connected"),
                IllegalStateException("something else entirely"),
                NullPointerException(),
            )) {
                clearMocks(client, answers = false)
                every { client.isConnected } returns false
                failAuthWith(path, failure)

                val result = runBlocking { connect(path) } as SshResult.Error

                assertEquals(SshErrorCode.HOST_UNREACHABLE, result.code, "$path ${failure.javaClass.simpleName}")
                assertFalse(result.code.isNonRetryable(), "$path: retried")
            }
        }
    }

    @Test
    fun `the same exception on a link that is still up is not taken for a lost connection by the password and the FIDO2 key`() {
        for (path in listOf(Path.PASSWORD, Path.FIDO2)) {
            clearMocks(client, answers = false)
            every { client.isConnected } returns true
            failAuthWith(path, IllegalStateException("Not connected"))

            val result = runBlocking { connect(path) } as SshResult.Error

            // Left alone, as before: an unexpected error, not a retry that would hide a bug.
            assertEquals(SshErrorCode.UNKNOWN, result.code, "$path")
            // Told in a sentence of ours: what the exception says stays out of the result.
            assertEquals("res:${R.string.connect_error_unexpected}", result.message, "$path")
            verify(exactly = 1) { client.disconnect() }
        }
    }

    @Test
    fun `the message of a refusal by SSHJ or of an unexpected runtime exception never reaches the user, on any path`() {
        // Every message of this fixture is "res:<id>": a raw message of SSHJ would not be.
        // This claims no more than it checks. An IOException (a TransportException is one) is not covered:
        // the connect error mapper passes its detail to connect_error_generic_detail as a format
        // argument, which this fixture drops (see the note on connectErrorMessage).
        val leaks = listOf<Throwable>(
            UserAuthException("Exhausted available authentication methods"),
            IllegalStateException("leaky detail at 10.0.0.12"),
        )
        for (path in Path.entries) {
            for (failure in leaks) {
                clearMocks(client, answers = false)
                every { client.isConnected } returns true
                failAuthWith(path, failure)

                val result = runBlocking { connect(path) } as SshResult.Error

                assertTrue(result.message.startsWith("res:"), "$path ${failure.javaClass.simpleName}: ${result.message}")
            }
        }
    }

    @Test
    fun `a key or a certificate refused by the server says authentication failed, not what SSHJ said`() {
        for (path in listOf(Path.KEY, Path.CERTIFICATE)) {
            clearMocks(client, answers = false)
            failAuthWith(path, UserAuthException("Exhausted available authentication methods"))

            val result = runBlocking { connect(path) } as SshResult.Error

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, "$path")
            assertEquals("res:${R.string.auth_error_failed}", result.message, "$path")
        }
    }

    // ── Opening the terminal of a session ─────────────────────────────────────

    @Test
    fun `a shell asked of a session that is not registered says so, without quoting its id`() = runTest {
        val result = manager.openShell("5c1b7e0e-0000-4000-8000-000000000000") as SshResult.Error

        assertEquals(SshErrorCode.UNKNOWN, result.code)
        assertEquals("res:${R.string.shell_error_session_not_found}", result.message)
    }

    @Test
    fun `a shell that cannot be opened says so, not what the exception said`() = runTest {
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        authSucceeds()
        val sessionId = (manager.connectWithSkKey(host, skKey()) as SshResult.Success).data.id
        every { client.startSession() } throws java.io.IOException("leaky detail at 10.0.0.12")

        val result = manager.openShell(sessionId) as SshResult.Error

        assertEquals(SshErrorCode.UNKNOWN, result.code)
        assertEquals("res:${R.string.shell_error_open_failed}", result.message)
    }

    @Test
    fun `the same exception on a client that is still connected is a key problem`() {
        // The counterpart: only the state of the link tells the two apart.
        for (path in listOf(Path.KEY, Path.CERTIFICATE)) {
            clearMocks(client, answers = false)
            failAuthWith(path, IllegalStateException("Not connected"))

            assertUnreadableKey(path, unreadableKeyResult(path))
        }
    }

    @Test
    fun `a cancellation while the key is loaded is not taken for a key problem, nor for any result`() {
        every { sshKeyManager.loadKeyProviderFromString(any(), any()) } throws CancellationException("cancelled")

        // Neither an unreadable key nor an UNKNOWN error: it goes up as the cancellation it is.
        val failure = runCatching { unreadableKeyResult(Path.KEY) }.exceptionOrNull()

        assertTrue(failure is CancellationException, "$failure")
        assertTrue(manager.activeSessions.value.isEmpty())
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `a cipher or MAC failure of the transport is a lost connection on every path, never a key problem`() {
        for (path in Path.entries) {
            val mac = TransportException(DisconnectReason.MAC_ERROR, "bad mac", GeneralSecurityException("Mac check failed"))
            for (failure in listOf<Throwable>(UserAuthException(mac), mac)) {
                val result = runBlocking { failAuthWith(path, failure); connect(path) } as SshResult.Error

                // Wrapped by the authentication it is the transport that died; bare it is whatever
                // the connect error mapper says. Neither is a verdict on the key.
                if (failure is UserAuthException) {
                    assertEquals(SshErrorCode.HOST_UNREACHABLE, result.code, "$path")
                }
                assertFalse(result.code.isNonRetryable(), "$path ${failure.javaClass.simpleName}: ${result.code} is retried")
                assertTrue(
                    result.message != "res:${R.string.auth_error_key_unreadable}" &&
                        result.message != "res:${R.string.auth_error_biometric_key_invalid}" &&
                        result.message != "res:${R.string.auth_error_biometric_key_unusable}",
                    "$path: ${result.message}",
                )
            }
        }
    }

    // ── The Android Keystore key ──────────────────────────────────────────────

    private fun windowOver(): UserNotAuthenticatedException =
        mockk<UserNotAuthenticatedException>(relaxed = true).also { every { it.cause } returns null }

    private fun keyInvalidated(): KeyPermanentlyInvalidatedException =
        mockk<KeyPermanentlyInvalidatedException>(relaxed = true).also { every { it.cause } returns null }

    private fun keystoreResult(failure: Throwable): SshResult.Error {
        clearMocks(client, answers = false)
        return runBlocking { failAuthWith(Path.BIOMETRIC, failure); connect(Path.BIOMETRIC) } as SshResult.Error
    }

    @Test
    fun `a biometric window that ran out asks the user to authenticate again`() {
        // The signature is initialized by SSHJ, which wraps the Keystore exception in an SSHRuntimeException.
        for (failure in listOf<Throwable>(
            SSHRuntimeException(windowOver()),
            UserAuthException("signing", SSHRuntimeException(windowOver())),
        )) {
            val result = keystoreResult(failure)

            assertEquals(SshErrorCode.AUTH_EXPIRED, result.code, failure.javaClass.simpleName)
            assertEquals("res:${R.string.auth_error_biometric_expired}", result.message, failure.javaClass.simpleName)
            assertFalse(result.code.isNonRetryable())
            verify(exactly = 1) { client.disconnect() }
            assertTrue(manager.activeSessions.value.isEmpty())
        }
    }

    @Test
    fun `a biometric key that was invalidated says so, and is not retried`() {
        for (failure in listOf<Throwable>(
            SSHRuntimeException(keyInvalidated()),
            UserAuthException("signing", SSHRuntimeException(keyInvalidated())),
        )) {
            val result = keystoreResult(failure)

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, failure.javaClass.simpleName)
            assertEquals("res:${R.string.auth_error_biometric_key_invalid}", result.message, failure.javaClass.simpleName)
            assertTrue(result.code.isNonRetryable())
            verify(exactly = 1) { client.disconnect() }
        }
    }

    @Test
    fun `a Keystore key that is gone says to regenerate it, without the passphrase hint`() {
        // Only the signs that prove it: regenerating a healthy key cuts the access to every server.
        for (failure in listOf<Throwable>(
            SSHRuntimeException(java.security.KeyStoreException(fr.techtical.nextsh.core.crypto.MISSING_BIOMETRIC_KEY_MESSAGE)),
            UserAuthException("Problem getting private key from KeystoreKeyProvider", java.security.UnrecoverableKeyException("deleted")),
        )) {
            val result = keystoreResult(failure)

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, failure.javaClass.simpleName)
            assertEquals("res:${R.string.auth_error_biometric_key_invalid}", result.message, failure.javaClass.simpleName)
            assertTrue(result.message != "res:${R.string.auth_error_key_unreadable}")
            assertTrue(result.code.isNonRetryable())
        }
    }

    @Test
    fun `a Keystore failure that proves nothing is neutral, never the passphrase hint nor the order to regenerate`() {
        for (failure in listOf<Throwable>(
            SSHRuntimeException(java.security.KeyStoreException("Key not found")),
            SSHRuntimeException(java.security.ProviderException("Keystore restarted")),
            UserAuthException("signing", SSHRuntimeException(java.security.SignatureException("operation pruned"))),
        )) {
            val result = keystoreResult(failure)

            assertEquals(SshErrorCode.AUTH_FAILED, result.code, failure.javaClass.simpleName)
            assertEquals("res:${R.string.auth_error_biometric_key_unusable}", result.message, failure.javaClass.simpleName)
            assertTrue(result.message != "res:${R.string.auth_error_key_unreadable}")
            assertTrue(result.message != "res:${R.string.auth_error_biometric_key_invalid}")
        }
    }

    @Test
    fun `a refusal of a biometric key is still the usual refusal`() {
        val result = keystoreResult(UserAuthException("Exhausted available authentication methods"))

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_failed_biometric}", result.message)
    }

    // ── A host key that does not match is recognized on every path, at connect time too ──

    private fun hostKeyRefused() = TransportException(
        DisconnectReason.HOST_KEY_NOT_VERIFIABLE,
        "Could not verify `ssh-ed25519` host key with fingerprint SHA256:abc for host1.example.com on port 22",
    )

    /** What a path returns when the client factory itself throws [failure] (client.connect() runs there). */
    private fun resultWhenConnectThrows(path: Path, failure: Throwable): SshResult.Error = runBlocking {
        manager.clientFactory = { throw failure }
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        every { sshKeyManager.loadKeyProviderFromString(any(), any()) } returns keyProvider
        every { sshKeyManager.loadKeyProviderWithCertificate(any(), any(), any()) } returns keyProvider
        connect(path) as SshResult.Error
    }

    @Test
    fun `a host key refused while connecting is a host key mismatch on every path`() {
        for (path in Path.entries) {
            val result = resultWhenConnectThrows(path, hostKeyRefused())

            assertEquals(SshErrorCode.HOST_KEY_MISMATCH, result.code, "$path")
            assertEquals("res:${R.string.auth_error_host_key}", result.message, "$path")
            assertTrue(result.code.isNonRetryable(), "$path: a security event is never replayed")
            assertTrue(manager.activeSessions.value.isEmpty(), "$path")
        }
    }

    @Test
    fun `a host key refused under a wrapper is still a host key mismatch on every path`() {
        for (path in Path.entries) {
            val result = resultWhenConnectThrows(path, java.io.IOException("connect failed", hostKeyRefused()))

            assertEquals(SshErrorCode.HOST_KEY_MISMATCH, result.code, "$path")
        }
    }

    @Test
    fun `a transport failure that only mentions a host key in its message is not a host key mismatch`() {
        for (path in Path.entries) {
            val lookalike = TransportException(DisconnectReason.CONNECTION_LOST, "Could not verify host key")

            val result = resultWhenConnectThrows(path, lookalike)

            assertTrue(result.code != SshErrorCode.HOST_KEY_MISMATCH, "$path: ${result.code}")
            assertFalse(result.code.isNonRetryable(), "$path: a lost connection is retried")
        }
    }

    @Test
    fun `OpenSSH too many authentication failures is a refusal on every path, so a wrong key is not retried`() {
        for (path in Path.entries) {
            val tooMany = TransportException(DisconnectReason.PROTOCOL_ERROR, "Too many authentication failures")
            val code = codeOf(path, UserAuthException(tooMany))

            assertEquals(SshErrorCode.AUTH_FAILED, code, "$path")
            assertTrue(code.isNonRetryable(), "$path")
        }
    }

    @Test
    fun `a host key refused during the authentication keeps the host key error on every path`() {
        for (path in Path.entries) {
            val rekey = TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "Could not verify host key")
            val result = runBlocking { failAuthWith(path, UserAuthException(rekey)); connect(path) } as SshResult.Error

            assertEquals(SshErrorCode.HOST_KEY_MISMATCH, result.code, "$path")
            assertEquals("res:${R.string.auth_error_host_key}", result.message, "$path")
        }
    }

    @Test
    fun `a server that hangs up after too many attempts is a refusal on every path`() {
        for (path in Path.entries) {
            val hangup = TransportException(DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE, "Too many authentication failures")
            assertEquals(SshErrorCode.AUTH_FAILED, codeOf(path, UserAuthException(hangup)), "$path")
        }
    }

    // ── A failed attempt closes its client, on every path ─────────────────────

    @Test
    fun `a refused authentication closes the client and forgets the session on every path`() {
        for (path in Path.entries) {
            clearMocks(client, answers = false)
            failAuthWith(path, UserAuthException("Exhausted available authentication methods"))

            runBlocking { connect(path) }

            verify(exactly = 1) { client.disconnect() }
            assertTrue(manager.activeSessions.value.isEmpty(), "$path: the session is forgotten")
        }
    }

    @Test
    fun `a transport that breaks closes the client on every path`() {
        for (path in Path.entries) {
            clearMocks(client, answers = false)
            failAuthWith(path, TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"))

            runBlocking { connect(path) }

            verify(exactly = 1) { client.disconnect() }
        }
    }

    @Test
    fun `a cancellation during the authentication closes the client on every path, and goes up`() {
        for (path in Path.entries) {
            clearMocks(client, answers = false)
            failAuthWith(path, CancellationException("connection cancelled"))

            // The cancellation is not folded into an UNKNOWN result: the caller is gone.
            val failure = runCatching { runBlocking { connect(path) } }.exceptionOrNull()

            assertTrue(failure is CancellationException, "$path: $failure")
            verify(exactly = 1) { client.disconnect() }
            assertTrue(manager.activeSessions.value.isEmpty(), "$path")
        }
    }

    @Test
    fun `a client that fails to close does not hide the result`() {
        every { client.disconnect() } throws java.io.IOException("already closed")
        failAuthWith(Path.PASSWORD, UserAuthException("Exhausted available authentication methods"))

        val result = runBlocking { connect(Path.PASSWORD) } as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertTrue(manager.activeSessions.value.isEmpty())
    }

    @Test
    fun `an authenticated session keeps its client open`() {
        every { client.authPassword(any<String>(), any<String>()) } returns Unit

        val result = runBlocking { connect(Path.PASSWORD) }

        assertTrue(result is SshResult.Success, "$result")
        verify(exactly = 0) { client.disconnect() }
        assertEquals(1, manager.activeSessions.value.size)
        val session = (result as SshResult.Success<*>).data as SshSession
        assertSame(client, manager.getClient(session.id))
    }

    @Test
    fun `without the FIDO2 callback the client is closed and the session forgotten`() = runTest {
        manager.fido2ChallengeCallback = null

        manager.connectWithSkKey(host, skKey())

        verify(exactly = 1) { client.disconnect() }
        assertTrue(manager.activeSessions.value.isEmpty())
    }

    // ── FIDO2: the real SkAuthMethod drives the prompt, as SSHJ does on PK_OK ──

    /**
     * Makes the fake client run the [SkAuthMethod] it is given the way SSHJ does when the server
     * accepts the key (USERAUTH_60): its handler asks the FIDO2 callback for a signature, and
     * throws the [UserAuthException] SSHJ delivers to the caller when there is none.
     */
    private fun driveSkAuthWith(response: Fido2ChallengeResponse?) = driveSkAuthWithCallback { response }

    /**
     * [afterHandler] runs once the handler returned without throwing, that is once a signature was
     * handed over: what the server does next (here, nothing, or answering late) is up to the test.
     */
    private fun driveSkAuthWithCallback(
        afterHandler: () -> Unit = {},
        callback: suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?,
    ) {
        manager.fido2ChallengeCallback = callback
        val params = mockk<AuthParams>(relaxed = true)
        every { params.transport } returns transport
        every { transport.sessionID } returns ByteArray(32) { 5 }
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            val method = (args[1] as Array<*>).single() as SkAuthMethod
            method.init(params)
            method.handle(Message.USERAUTH_60, SSHPacket())
            afterHandler()
        }
    }

    private fun signedResponse() = Fido2ChallengeResponse.Signed(
        SkSshSignature.create(
            keyType = SkKeyType.SK_ED25519,
            rawSignature = ByteArray(64) { 7 },
            flags = SkSshSignature.FLAG_USER_PRESENT,
            counter = 1u,
        ),
    )

    /**
     * What the transport does when the connection is closed under a waiting `client.auth`: it wakes
     * it with an exception. Blocks like a silent server until [client] is disconnected, and fails
     * the test, rather than hang it, if nobody closes the connection.
     */
    private val serverClosed = java.util.concurrent.CountDownLatch(1)

    private fun silentUntilDisconnected() {
        if (!serverClosed.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw AssertionError("the watchdog never closed the connection of a silent server")
        }
        throw UserAuthException(TransportException(DisconnectReason.CONNECTION_LOST, "closed under the wait"))
    }

    // ── A silent server is cut by the watchdog, in the bound it gives ─────────

    @Test
    fun `a server that goes silent after the signature is cut at the watchdog bound`() = runBlocking {
        manager.fido2ServerReplyTimeoutMs = 200
        every { client.disconnect() } answers { serverClosed.countDown() }
        driveSkAuthWithCallback(afterHandler = ::silentUntilDisconnected) { signedResponse() }

        val startedAt = System.nanoTime()
        val result = manager.connectWithSkKey(host, skKey()) as SshResult.Error
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(SshErrorCode.TIMEOUT, result.code, "a late server is retried, not a refusal")
        assertEquals("res:${R.string.connect_error_timeout}", result.message)
        assertTrue(tookMs in 150..5_000, "cut about 200 ms after the signature, took $tookMs ms")
        verify(atLeast = 1) { client.disconnect() }
        assertTrue(manager.activeSessions.value.isEmpty())
    }

    @Test
    fun `a server that never answers the feeler is cut at the watchdog bound`() = runBlocking {
        manager.fido2ServerReplyTimeoutMs = 200
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        every { client.disconnect() } answers { serverClosed.countDown() }
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            silentUntilDisconnected()
        }

        val result = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        assertEquals(SshErrorCode.TIMEOUT, result.code)
        verify(atLeast = 1) { client.disconnect() }
    }

    @Test
    fun `an authentication that comes back successfully after the watchdog fired is a cut attempt, not a session`() = runBlocking {
        // The timer fires (and closes the client) just as client.auth returns without an error.
        manager.fido2ServerReplyTimeoutMs = 100
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            Thread.sleep(500) // long enough for the watchdog to fire first; then it "succeeds"
        }

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(SshErrorCode.TIMEOUT, (result as SshResult.Error).code, "retried like any silent server")
        assertTrue(manager.activeSessions.value.isEmpty(), "no session registered on a client that is going away")
        verify(atLeast = 1) { client.disconnect() }
    }

    @Test
    fun `the time the user takes with the key is not the server's silence`() = runBlocking {
        // A prompt three times longer than the watchdog bound, answered in the end.
        manager.fido2ServerReplyTimeoutMs = 150
        driveSkAuthWithCallback {
            kotlinx.coroutines.delay(450)
            signedResponse()
        }

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Success, "$result")
        verify(exactly = 0) { client.disconnect() }
    }

    @Test
    fun `the watchdog is cancelled when the authentication returns, so nothing outlives it`() = runBlocking {
        manager.fido2ServerReplyTimeoutMs = 2_000
        driveSkAuthWithCallback { signedResponse() }

        val startedAt = System.nanoTime()
        val result = manager.connectWithSkKey(host, skKey())
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(result is SshResult.Success, "$result")
        // A timer left running would hold the end of the function until it fired (and close the session).
        assertTrue(tookMs < 1_500, "returned in $tookMs ms")
        kotlinx.coroutines.delay(200)
        verify(exactly = 0) { client.disconnect() }
    }

    @Test
    fun `the watchdog is cancelled when the authentication fails too`() = runBlocking {
        manager.fido2ServerReplyTimeoutMs = 2_000
        driveSkAuthWithCallback { Fido2ChallengeResponse.Cancelled }

        val startedAt = System.nanoTime()
        manager.connectWithSkKey(host, skKey())
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMs < 1_500, "returned in $tookMs ms")
        // One disconnect, the clean-up of the failed attempt: the watchdog did not add its own.
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `cancelling the connection cancels the prompt it is waiting for and closes the client`() = runBlocking {
        // Production wiring, run: the callback is what Fido2PromptCoordinator is, a suspension
        // that only ends with an answer. Cancelling the coroutine that connects must end it.
        val started = CompletableDeferred<Unit>()
        val promptCancelled = CompletableDeferred<Unit>()
        // Only a cancellation ends the prompt, except for the safety valve below.
        val safetyValve = CompletableDeferred<Unit>()
        driveSkAuthWithCallback {
            started.complete(Unit)
            try {
                safetyValve.await()
                null
            } finally {
                promptCancelled.complete(Unit)
            }
        }

        // A scope of its own: if the wiring ever breaks, the blocked SSH thread must fail this test
        // by its timeout, not hang a runBlocking that waits for its children.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = scope.launch { manager.connectWithSkKey(host, skKey()) }
            withTimeout(5_000) { started.await() }
            assertTrue(!promptCancelled.isCompleted, "the prompt waits until something ends it")
            connection.cancel()

            withTimeout(5_000) { promptCancelled.await() }
            withTimeout(5_000) { connection.join() }
            assertTrue(connection.isCancelled)
            // Closed by the cancellation guard and by the clean-up of the failed attempt.
            verify(atLeast = 1) { client.disconnect() }
            assertTrue(manager.activeSessions.value.isEmpty())
        } finally {
            safetyValve.complete(Unit)
            scope.cancel()
        }
    }

    // ── Cancelling the connection ends a client.auth that blocks on a silent server ──

    /** Counted down when the (fake) authentication is under way, so a test can cancel at that point. */
    private val authUnderWay = java.util.concurrent.CountDownLatch(1)

    @Test
    fun `cancelling the connection closes the client at once when the server is silent before the key`() = runBlocking {
        // The watchdog bound is far away: only the cancellation can end the call in the time of the test.
        manager.fido2ServerReplyTimeoutMs = 60_000
        manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
        every { client.disconnect() } answers { serverClosed.countDown() }
        every { client.auth(any<String>(), *anyVararg<AuthMethod>()) } answers {
            timeline += -1
            authUnderWay.countDown()
            silentUntilDisconnected()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = scope.launch { manager.connectWithSkKey(host, skKey()) }
            assertTrue(authUnderWay.await(5, java.util.concurrent.TimeUnit.SECONDS), "the authentication started")

            connection.cancel()

            withTimeout(5_000) { connection.join() }
            assertTrue(connection.isCancelled)
            assertEquals(0L, serverClosed.count, "the client was closed, which woke the blocked call up")
            verify(atLeast = 1) { client.disconnect() }
            assertTrue(manager.activeSessions.value.isEmpty())
        } finally {
            serverClosed.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `cancelling the connection closes the client at once when the server goes silent after the signature`() = runBlocking {
        manager.fido2ServerReplyTimeoutMs = 60_000
        every { client.disconnect() } answers { serverClosed.countDown() }
        driveSkAuthWithCallback(
            afterHandler = {
                authUnderWay.countDown()
                silentUntilDisconnected()
            },
        ) { signedResponse() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = scope.launch { manager.connectWithSkKey(host, skKey()) }
            assertTrue(authUnderWay.await(5, java.util.concurrent.TimeUnit.SECONDS), "the signature was handed over")

            connection.cancel()

            withTimeout(5_000) { connection.join() }
            assertEquals(0L, serverClosed.count)
            verify(atLeast = 1) { client.disconnect() }
        } finally {
            serverClosed.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `the cancellation guard leaves a client alone once the authentication has returned`() = runBlocking {
        driveSkAuthWithCallback { signedResponse() }
        every { client.disconnect() } answers { serverClosed.countDown() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val result = withTimeout(5_000) { scope.async { manager.connectWithSkKey(host, skKey()) }.await() }

            assertTrue(result is SshResult.Success, "$result")
            // The guard ended with the function and did not close the session's client.
            kotlinx.coroutines.delay(200)
            verify(exactly = 0) { client.disconnect() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a cancelled prompt reads as a cancellation, not as a refused key`() = runTest {
        driveSkAuthWith(Fido2ChallengeResponse.Cancelled)

        val result = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, result.code)
        assertEquals("res:${R.string.auth_error_fido2_cancelled}", result.message)
    }

    @Test
    fun `a key that failed is an authentication failure with its own message, not a cancellation`() = runTest {
        driveSkAuthWith(Fido2ChallengeResponse.KeyError(HwKeyError.Unexpected))

        val result = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals("res:${R.string.auth_error_fido2_key_error}", result.message)
        assertTrue(result.code.isNonRetryable(), "not retried by the reconnection")
    }

    @Test
    fun `a key that failed tells why in the message of the failed connection`() = runTest {
        driveSkAuthWith(Fido2ChallengeResponse.KeyError(HwKeyError.NoMatchingCredential))

        val result = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals(
            "res:${R.string.fido2_error_no_matching_credential}",
            result.message,
            "the sheet showed the reason for two seconds: the connection must keep it",
        )
        assertTrue(result.code.isNonRetryable())
    }

    @Test
    fun `a blocked PIN and a pulled key do not read the same`() = runTest {
        driveSkAuthWith(Fido2ChallengeResponse.KeyError(HwKeyError.PinBlocked))
        val blocked = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        driveSkAuthWith(Fido2ChallengeResponse.KeyError(HwKeyError.KeyRemoved))
        val removed = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        assertEquals("res:${R.string.fido2_pin_blocked}", blocked.message)
        assertEquals("res:${R.string.fido2_error_key_removed}", removed.message)
    }

    @Test
    fun `a prompt nobody answered reads as a timeout of the key, not as a refused key`() = runTest {
        driveSkAuthWith(null)

        val result = manager.connectWithSkKey(host, skKey()) as SshResult.Error

        assertEquals(SshErrorCode.FIDO2_TIMEOUT, result.code)
        assertEquals("res:${R.string.auth_error_fido2_timeout}", result.message)
    }

    // ── The request the prompt receives: what the key needs to answer ─────────

    @Test
    fun `the request given to the prompt carries the credential id of the key`() = runTest {
        val credentialId = ByteArray(32) { (it + 10).toByte() }
        val requests = mutableListOf<Fido2ChallengeRequest>()
        driveSkAuthWithCallback { request ->
            requests += request
            signedResponse()
        }

        val result = manager.connectWithSkKey(host, skKey(), listOf(credentialId))

        assertTrue(result is SshResult.Success, "$result")
        val request = requests.single()
        assertEquals(1, request.allowedCredentialIds.size)
        assertArrayEquals(credentialId, request.allowedCredentialIds.single())
        // The rest of the request is still that of the key.
        assertEquals("ssh:", request.rpId)
        assertEquals(SkKeyType.SK_ED25519, request.keyType)
    }

    @Test
    fun `the request carries the application of the public key as its relying party`() = runTest {
        val requests = mutableListOf<Fido2ChallengeRequest>()
        driveSkAuthWithCallback { request ->
            requests += request
            signedResponse()
        }

        manager.connectWithSkKey(host, SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { 1 }, "ssh:work"), listOf(byteArrayOf(1)))

        assertEquals("ssh:work", requests.single().rpId)
    }

    @Test
    fun `the request carries every credential id it is given, in order`() = runTest {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6, 7)
        val requests = mutableListOf<Fido2ChallengeRequest>()
        driveSkAuthWithCallback { request ->
            requests += request
            signedResponse()
        }

        manager.connectWithSkKey(host, skKey(), listOf(first, second))

        assertEquals(listOf(first.toList(), second.toList()), requests.single().allowedCredentialIds.map { it.toList() })
    }

    @Test
    fun `without a credential id the request has an empty allow list, as a resident key needs`() = runTest {
        val requests = mutableListOf<Fido2ChallengeRequest>()
        driveSkAuthWithCallback { request ->
            requests += request
            signedResponse()
        }

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Success, "$result")
        assertTrue(requests.single().allowedCredentialIds.isEmpty())
    }

    @Test
    fun `a signature obtained at the prompt authenticates the session`() = runTest {
        val signature = SkSshSignature.create(
            keyType = SkKeyType.SK_ED25519,
            rawSignature = ByteArray(64) { 7 },
            flags = SkSshSignature.FLAG_USER_PRESENT,
            counter = 1u,
        )
        driveSkAuthWith(Fido2ChallengeResponse.Signed(signature))

        val result = manager.connectWithSkKey(host, skKey())

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(listOf(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, -1, TRANSPORT_TIMEOUT_MS), timeline)
    }

    // ── An address the known hosts file could not store ──────────────────────

    @Test
    fun `a host whose address holds a blank is refused before the client is built`() = runBlocking {
        var built = false
        manager.clientFactory = { built = true; client }
        val badHosts = listOf(
            "10.0.0.5 x",
            "10.0.0.5\nvictime.example:22 ssh-ed25519 AAAA\n#",
            "a$INVISIBLE_FORMAT_CHAR.test",
            "",
        )

        for (hostname in badHosts) {
            val result = manager.connectWithPassword(host.copy(hostname = hostname), "secret".toCharArray())

            assertTrue(result is SshResult.Error, "$result")
            result as SshResult.Error
            assertEquals(SshErrorCode.HOST_UNREACHABLE, result.code)
            assertEquals("res:${R.string.connect_error_invalid_host_address}", result.message)
        }
        assertFalse(built, "no DNS lookup, no socket")
    }

    private companion object {
        /** U+202E, built from its code point: never raw in a source. */
        val INVISIBLE_FORMAT_CHAR = String(Character.toChars(0x202E))
    }
}
