// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.crypto.BiometricKeyAlias
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import timber.log.Timber

@ExtendWith(MockKExtension::class)
class ConnectSessionUseCaseTest {

    @MockK
    private lateinit var sessionManager: SshSessionManager

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var keystoreManager: KeystoreManager

    private lateinit var useCase: ConnectSessionUseCase

    /** The real lock on a clock that never moves; it starts locked, like the app. */
    private val appLock = AppLock { 0L }

    /**
     * A string is its resource id, followed by its arguments: a test can tell which message the
     * user would read, and what it names, without depending on the wording of either language.
     */
    private val context = mockk<Context>().also {
        every { it.getString(any<Int>()) } answers { res(firstArg<Int>()) }
        every { it.getString(any<Int>(), *anyVararg<Any>()) } answers {
            // Whether the varargs arrive spread or packed in one array depends on the mock layer.
            val formatArgs = args.drop(1).flatMap { arg -> if (arg is Array<*>) arg.toList() else listOf(arg) }
            res(firstArg<Int>(), *formatArgs.filterNotNull().toTypedArray())
        }
    }

    private fun res(id: Int, vararg args: Any): String =
        if (args.isEmpty()) "res:$id" else "res:$id:${args.joinToString("|")}"

    private val testHost = Host(
        id = "host-1",
        label = "My Server",
        hostname = "192.168.1.1",
        port = 22,
        username = "admin",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private val testSession = SshSession(
        id = "session-1",
        host = testHost,
        status = SessionStatus.CONNECTED,
        connectedAt = System.currentTimeMillis(),
    )

    @BeforeEach
    fun setUp() {
        useCase = ConnectSessionUseCase(sessionManager, hostRepository, vaultManager, sshKeyRepository, keystoreManager, context, appLock)
    }

    // --- PASSWORD auth ---

    @Test
    fun `PASSWORD auth - success calls connectWithPassword and updates last connected`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(host, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sessionManager.connectWithPassword(host, any()) }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
    }

    @Test
    fun `PASSWORD auth - missing password returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithPassword(any(), any()) }
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    // --- A session that cannot be delivered is not left registered ---

    @Test
    fun `a cancellation while the connection is recorded releases the session and goes on`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(host, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } throws CancellationException("screen left")
        every { sessionManager.release(any()) } returns Unit

        val failure = runCatching { useCase(host) }.exceptionOrNull()

        // The caller never gets the session, so nobody else would give its reference back.
        assertTrue(failure is CancellationException, "$failure")
        io.mockk.verify(exactly = 1) { sessionManager.release("session-1") }
    }

    @Test
    fun `a failure of the recording releases the session and is reported as an error`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(host, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } throws IllegalStateException("database closed")
        every { sessionManager.release(any()) } returns Unit

        val result = useCase(host)

        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        io.mockk.verify(exactly = 1) { sessionManager.release("session-1") }
    }

    @Test
    fun `the key paths release the session too when the recording is cancelled`() = runTest {
        val host = testHost.copy(authType = AuthType.SSH_KEY)
        every { vaultManager.getPrivateKey("cred-1") } returns "-----BEGIN PRIVATE KEY-----..."
        every { vaultManager.getKeyPassphrase("cred-1") } returns null
        coEvery { sessionManager.connectWithKey(host, "-----BEGIN PRIVATE KEY-----...") } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } throws CancellationException("screen left")
        every { sessionManager.release(any()) } returns Unit

        val failure = runCatching { useCase(host) }.exceptionOrNull()

        assertTrue(failure is CancellationException, "$failure")
        io.mockk.verify(exactly = 1) { sessionManager.release("session-1") }
    }

    @Test
    fun `a delivered session keeps its reference for the caller`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(host, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success)
        // No release is stubbed on the strict mock: a release here would fail the test.
        io.mockk.verify(exactly = 0) { sessionManager.release(any()) }
    }

    // --- SSH_KEY auth ---

    @Test
    fun `SSH_KEY auth - success calls connectWithKey and updates last connected`() = runTest {
        val host = testHost.copy(authType = AuthType.SSH_KEY)
        every { vaultManager.getPrivateKey("cred-1") } returns "-----BEGIN PRIVATE KEY-----..."
        every { vaultManager.getKeyPassphrase("cred-1") } returns null
        coEvery { sessionManager.connectWithKey(host, "-----BEGIN PRIVATE KEY-----...") } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sessionManager.connectWithKey(host, "-----BEGIN PRIVATE KEY-----...") }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
    }

    @Test
    fun `SSH_KEY auth - missing private key returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.SSH_KEY)
        every { vaultManager.getPrivateKey("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        // The passphrase is read last: nothing of it is fetched when the key itself is missing.
        coVerify(exactly = 0) { vaultManager.getKeyPassphrase(any()) }
        coVerify(exactly = 0) { sessionManager.connectWithKey(any(), any(), any()) }
    }

    // --- CERTIFICATE auth ---

    @Test
    fun `CERTIFICATE auth - success calls connectWithCertificate and updates last connected`() = runTest {
        val host = testHost.copy(authType = AuthType.CERTIFICATE)
        val privateKey = "-----BEGIN PRIVATE KEY-----..."
        val certificate = "-----BEGIN CERTIFICATE-----..."
        every { vaultManager.getPrivateKey("cred-1") } returns privateKey
        every { vaultManager.getCertificate("cred-1") } returns certificate
        every { vaultManager.getKeyPassphrase("cred-1") } returns null
        coEvery { sessionManager.connectWithCertificate(host, privateKey, certificate) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sessionManager.connectWithCertificate(host, privateKey, certificate) }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
    }

    @Test
    fun `CERTIFICATE auth - missing private key returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.CERTIFICATE)
        every { vaultManager.getPrivateKey("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { vaultManager.getKeyPassphrase(any()) }
        coVerify(exactly = 0) { sessionManager.connectWithCertificate(any(), any(), any(), any()) }
    }

    @Test
    fun `CERTIFICATE auth - missing certificate returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.CERTIFICATE)
        every { vaultManager.getPrivateKey("cred-1") } returns "-----BEGIN PRIVATE KEY-----..."
        every { vaultManager.getCertificate("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { vaultManager.getKeyPassphrase(any()) }
        coVerify(exactly = 0) { sessionManager.connectWithCertificate(any(), any(), any(), any()) }
    }

    // --- FIDO2 HARDWARE_KEY auth ---

    // --- FIDO2 host without a mode (a host received from the Desktop has none) ---

    @Test
    fun `FIDO2 without a mode is a security key host and connects with the sk key`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = null)
        val storedId = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val allowList = slot<List<ByteArray>>()
        coEvery { sshKeyRepository.getById("cred-1") } returns skKeyRecord(storedId)
        coEvery { sessionManager.connectWithSkKey(host, any(), capture(allowList)) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success, "$result")
        coVerify(exactly = 1) { sessionManager.connectWithSkKey(host, any(), any()) }
        assertArrayEquals(byteArrayOf(9, 8, 7), allowList.captured.single())
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
        // Not read as a passkey host: no private key is fetched, no ordinary key authentication runs.
        coVerify(exactly = 0) { vaultManager.getPrivateKey(any()) }
        coVerify(exactly = 0) { sessionManager.connectWithKey(any(), any(), any()) }
    }

    @Test
    fun `FIDO2 without a mode and without its key says the security key is missing, not that the mode is`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = null)
        coEvery { sshKeyRepository.getById("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        val error = result as SshResult.Error
        assertEquals(SshErrorCode.FIDO2_NO_KEY, error.code)
        // The key is what is missing, named by the host it was meant for: not the mode.
        assertEquals(res(R.string.connect_error_fido2_key_missing, "My Server"), error.message)
    }

    @Test
    fun `FIDO2 without a mode and with an invalid sk key line is an authentication failure`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = null)
        coEvery { sshKeyRepository.getById("cred-1") } returns skKeyRecord("AAAA").copy(publicKey = "invalid-key-format")

        val result = useCase(host)

        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithSkKey(any(), any(), any()) }
    }


    @Test
    fun `FIDO2 HARDWARE_KEY - missing key returns FIDO2_NO_KEY error`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.HARDWARE_KEY)
        coEvery { sshKeyRepository.getById("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.FIDO2_NO_KEY, (result as SshResult.Error).code)
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - invalid key format returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.HARDWARE_KEY)
        val sshKey = SshKey(
            id = "key-1",
            label = "Test SK Key",
            keyType = SshKeyType.SK_ECDSA_256,
            publicKey = "invalid-key-format",
        )
        coEvery { sshKeyRepository.getById("cred-1") } returns sshKey

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
    }

    // A real sk-ssh-ed25519 public key line, built the way the enrolment stores it.
    private fun skPublicKeyLine(): String =
        SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { 1 }).use { it.toAuthorizedKeysLine() }

    private fun skHost() = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.HARDWARE_KEY)

    private fun skKeyRecord(credentialId: String?) = SshKey(
        id = "key-1",
        label = "Test SK Key",
        keyType = SshKeyType.SK_ED25519,
        publicKey = skPublicKeyLine(),
        fido2CredentialId = credentialId,
        fido2RpId = "ssh:",
    )

    /** Runs the connection of [skHost] with a key record carrying [storedId], and returns the list given to `connectWithSkKey`. */
    private suspend fun allowListSentFor(storedId: String?): List<ByteArray> {
        val host = skHost()
        val allowList = slot<List<ByteArray>>()
        coEvery { sshKeyRepository.getById("cred-1") } returns skKeyRecord(storedId)
        coEvery { sessionManager.connectWithSkKey(host, any(), capture(allowList)) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success, "$result")
        return allowList.captured
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - valid key delegates to connectWithSkKey with the public key it was given`() = runTest {
        val host = skHost()
        val connectedWith = slot<SkSshPublicKey>()
        coEvery { sshKeyRepository.getById("cred-1") } returns skKeyRecord(credentialId = null)
        coEvery { sessionManager.connectWithSkKey(host, capture(connectedWith), any()) } answers {
            // The key is only wiped once the connection is over: it is whole while it is used.
            assertEquals(SkKeyType.SK_ED25519, connectedWith.captured.keyType)
            assertTrue(connectedWith.captured.rawKeyData.all { it == 1.toByte() })
            SshResult.Success(testSession)
        }
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success, "$result")
        coVerify(exactly = 1) { sessionManager.connectWithSkKey(host, any(), any()) }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
        // The key material is wiped once the connection is over.
        assertTrue(connectedWith.captured.rawKeyData.all { it == 0.toByte() })
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - the stored credential id reaches the key decoded`() = runTest {
        // The bytes are chosen so that their Base64 uses '+', '/' and padding, the whole alphabet
        // of the standard encoding that both enrolments write.
        val credentialId = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte(), 1, 2)
        val stored = java.util.Base64.getEncoder().encodeToString(credentialId)
        assertEquals("+//+AQI=", stored)

        val sent = allowListSentFor(stored)

        assertEquals(1, sent.size)
        assertArrayEquals(credentialId, sent.single())
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a long credential id is decoded whole`() = runTest {
        val credentialId = ByteArray(64) { it.toByte() }

        val sent = allowListSentFor(java.util.Base64.getEncoder().encodeToString(credentialId))

        assertArrayEquals(credentialId, sent.single())
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a missing credential id gives an empty allow list and still connects`() = runTest {
        assertTrue(allowListSentFor(null).isEmpty())
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a blank credential id gives an empty allow list`() = runTest {
        assertTrue(allowListSentFor("").isEmpty())
        assertTrue(allowListSentFor("   ").isEmpty())
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a credential id that is not Base64 gives an empty allow list and still connects`() = runTest {
        for (stored in listOf("not base64 at all!", "AAAA*AAA", "-_-_", "A")) {
            assertTrue(allowListSentFor(stored).isEmpty(), "stored value [$stored]")
        }
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a credential id that is not Base64 is logged with a fixed text, never its value`() = runTest {
        val stored = "secret-credential-id!"
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            allowListSentFor(stored)
        } finally {
            Timber.uproot(tree)
        }

        val warning = tree.entries.single()
        assertEquals(5, warning.priority) // android.util.Log.WARN
        assertTrue(warning.message.contains("not valid Base64"), warning.message)
        assertFalse(warning.message.contains(stored))
        assertNull(warning.throwable)
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a valid credential id is not logged`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            allowListSentFor(java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 9 }))
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(tree.entries.isEmpty(), "nothing is logged: ${tree.entries}")
    }

    // --- FIDO2 PASSKEY auth ---

    @Test
    fun `FIDO2 PASSKEY - missing key returns FIDO2_NO_KEY error`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.PASSKEY)
        coEvery { sshKeyRepository.getById("cred-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.FIDO2_NO_KEY, (result as SshResult.Error).code)
    }

    @Test
    fun `FIDO2 PASSKEY - missing private key returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.PASSKEY)
        val sshKey = SshKey(
            id = "key-1",
            label = "Test Key",
            keyType = SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAA...",
        )
        coEvery { sshKeyRepository.getById("cred-1") } returns sshKey
        every { vaultManager.getPrivateKey("key-1") } returns null

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { vaultManager.getKeyPassphrase(any()) }
    }

    @Test
    fun `FIDO2 PASSKEY - success delegates to connectWithKey`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.PASSKEY)
        val sshKey = SshKey(
            id = "key-1",
            label = "Test Key",
            keyType = SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAA...",
        )
        coEvery { sshKeyRepository.getById("cred-1") } returns sshKey
        every { vaultManager.getPrivateKey("key-1") } returns "-----BEGIN PRIVATE KEY-----..."
        every { vaultManager.getKeyPassphrase("key-1") } returns null
        coEvery { sessionManager.connectWithKey(host, "-----BEGIN PRIVATE KEY-----...") } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sessionManager.connectWithKey(host, "-----BEGIN PRIVATE KEY-----...") }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
    }

    // --- Passphrase of an encrypted key: SSH_KEY, CERTIFICATE and FIDO2 PASSKEY all open the key with it ---

    private enum class KeyedAuth { SSH_KEY, CERTIFICATE, PASSKEY }

    private val keyPem = "-----BEGIN OPENSSH PRIVATE KEY-----..."
    private val certPem = "ssh-ed25519-cert-v01@openssh.com AAAA..."

    private fun keyedHost(kind: KeyedAuth): Host = when (kind) {
        KeyedAuth.SSH_KEY -> testHost.copy(authType = AuthType.SSH_KEY)
        KeyedAuth.CERTIFICATE -> testHost.copy(authType = AuthType.CERTIFICATE)
        KeyedAuth.PASSKEY -> testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.PASSKEY)
    }

    /**
     * The id the vault keeps the key (and so its passphrase) under: the credential of the host, except
     * for a passkey host, whose key is the SSH key it points to.
     */
    private fun keyIdOf(kind: KeyedAuth): String = if (kind == KeyedAuth.PASSKEY) "key-1" else "cred-1"

    /** The SSH key a passkey host points to (the host credential is its id). */
    private fun passkeyKey() = SshKey(
        id = "key-1",
        label = "Test Key",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA...",
    )

    /** What the session manager saw of a connection with a key, and what the use case did with it. */
    private class KeyedRun(val outcome: Result<SshResult<SshSession>>, val seen: List<String?>) {
        val result: SshResult<SshSession> get() = outcome.getOrThrow()
    }

    /**
     * Connects a host of [kind] whose vault holds [stored] as the passphrase of its key. The session
     * manager notes the passphrase as it receives it (the array is wiped afterwards, so its content is
     * read at the call), then answers with [connect].
     */
    private suspend fun connectKeyed(
        kind: KeyedAuth,
        stored: CharArray?,
        connect: () -> SshResult<SshSession>,
    ): KeyedRun {
        val host = keyedHost(kind)
        val keyId = keyIdOf(kind)
        coEvery { sshKeyRepository.getById("cred-1") } returns passkeyKey()
        every { vaultManager.getPrivateKey(keyId) } returns keyPem
        every { vaultManager.getCertificate(keyId) } returns certPem
        every { vaultManager.getKeyPassphrase(keyId) } returns stored
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val seen = mutableListOf<String?>()
        when (kind) {
            KeyedAuth.CERTIFICATE ->
                coEvery { sessionManager.connectWithCertificate(host, keyPem, certPem, any()) } answers {
                    // Index 3: the passphrase (host, key, certificate, passphrase). The continuation of
                    // the suspend function comes after it, at index 4.
                    seen += arg<CharArray?>(3)?.concatToString()
                    connect()
                }
            KeyedAuth.SSH_KEY, KeyedAuth.PASSKEY ->
                coEvery { sessionManager.connectWithKey(host, keyPem, any()) } answers {
                    seen += thirdArg<CharArray?>()?.concatToString()
                    connect()
                }
        }
        return KeyedRun(runCatching { useCase(host) }, seen)
    }

    private fun assertWiped(stored: CharArray) {
        assertTrue(stored.isNotEmpty() && stored.all { it == '\u0000' }, "the passphrase must be wiped")
    }

    private suspend fun checkPassphraseReachesTheConnection(kind: KeyedAuth) {
        val stored = "hunter2".toCharArray()

        val run = connectKeyed(kind, stored) { SshResult.Success(testSession) }

        assertTrue(run.result is SshResult.Success, "${run.outcome}")
        assertEquals(listOf<String?>("hunter2"), run.seen)
        assertWiped(stored)
        coVerify(exactly = 1) { vaultManager.getKeyPassphrase(keyIdOf(kind)) }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
        if (kind == KeyedAuth.PASSKEY) {
            // The passphrase of the chosen key, not the one of whatever the host credential is.
            coVerify(exactly = 0) { vaultManager.getKeyPassphrase("cred-1") }
        }
    }

    private suspend fun checkPassphraseIsWipedAfterAFailure(kind: KeyedAuth) {
        val stored = "hunter2".toCharArray()

        val run = connectKeyed(kind, stored) { SshResult.Error(SshErrorCode.AUTH_FAILED, "refused") }

        assertEquals(SshErrorCode.AUTH_FAILED, (run.result as SshResult.Error).code)
        assertEquals(listOf<String?>("hunter2"), run.seen)
        assertWiped(stored)
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    private suspend fun checkPassphraseIsWipedAfterAnException(kind: KeyedAuth) {
        val stored = "hunter2".toCharArray()

        val run = connectKeyed(kind, stored) { throw IllegalStateException("boom") }

        assertEquals(SshErrorCode.UNKNOWN, (run.result as SshResult.Error).code)
        assertWiped(stored)
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    private suspend fun checkNoStoredPassphraseConnectsAsBefore(kind: KeyedAuth) {
        val run = connectKeyed(kind, stored = null) { SshResult.Success(testSession) }

        assertTrue(run.result is SshResult.Success, "${run.outcome}")
        // The connection function is given no passphrase at all, as for a key that is not encrypted.
        assertEquals(listOf<String?>(null), run.seen)
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
    }

    @Test
    fun `SSH_KEY auth - the stored passphrase reaches connectWithKey and is wiped after a success`() =
        runTest { checkPassphraseReachesTheConnection(KeyedAuth.SSH_KEY) }

    @Test
    fun `CERTIFICATE auth - the stored passphrase reaches connectWithCertificate and is wiped after a success`() =
        runTest { checkPassphraseReachesTheConnection(KeyedAuth.CERTIFICATE) }

    @Test
    fun `FIDO2 PASSKEY - the passphrase stored for the chosen key reaches connectWithKey and is wiped after a success`() =
        runTest { checkPassphraseReachesTheConnection(KeyedAuth.PASSKEY) }

    @Test
    fun `SSH_KEY auth - the passphrase is wiped when the connection fails`() =
        runTest { checkPassphraseIsWipedAfterAFailure(KeyedAuth.SSH_KEY) }

    @Test
    fun `CERTIFICATE auth - the passphrase is wiped when the connection fails`() =
        runTest { checkPassphraseIsWipedAfterAFailure(KeyedAuth.CERTIFICATE) }

    @Test
    fun `FIDO2 PASSKEY - the passphrase is wiped when the connection fails`() =
        runTest { checkPassphraseIsWipedAfterAFailure(KeyedAuth.PASSKEY) }

    @Test
    fun `SSH_KEY auth - the passphrase is wiped when the connection throws`() =
        runTest { checkPassphraseIsWipedAfterAnException(KeyedAuth.SSH_KEY) }

    @Test
    fun `CERTIFICATE auth - the passphrase is wiped when the connection throws`() =
        runTest { checkPassphraseIsWipedAfterAnException(KeyedAuth.CERTIFICATE) }

    @Test
    fun `FIDO2 PASSKEY - the passphrase is wiped when the connection throws`() =
        runTest { checkPassphraseIsWipedAfterAnException(KeyedAuth.PASSKEY) }

    @Test
    fun `SSH_KEY auth - without a stored passphrase the connection gets none`() =
        runTest { checkNoStoredPassphraseConnectsAsBefore(KeyedAuth.SSH_KEY) }

    @Test
    fun `CERTIFICATE auth - without a stored passphrase the connection gets none`() =
        runTest { checkNoStoredPassphraseConnectsAsBefore(KeyedAuth.CERTIFICATE) }

    @Test
    fun `FIDO2 PASSKEY - without a stored passphrase the connection gets none`() =
        runTest { checkNoStoredPassphraseConnectsAsBefore(KeyedAuth.PASSKEY) }

    private suspend fun checkCancellationIsRethrown(kind: KeyedAuth) {
        val stored = "hunter2".toCharArray()

        val run = connectKeyed(kind, stored) { throw CancellationException("cancelled") }

        // Not an UNKNOWN error to show: the coroutine of the caller ends cancelled.
        assertTrue(run.outcome.exceptionOrNull() is CancellationException, "${run.outcome}")
        assertWiped(stored)
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `SSH_KEY auth - a cancelled connection is rethrown and the passphrase is wiped`() =
        runTest { checkCancellationIsRethrown(KeyedAuth.SSH_KEY) }

    @Test
    fun `CERTIFICATE auth - a cancelled connection is rethrown and the passphrase is wiped`() =
        runTest { checkCancellationIsRethrown(KeyedAuth.CERTIFICATE) }

    @Test
    fun `FIDO2 PASSKEY - a cancelled connection is rethrown and the passphrase is wiped`() =
        runTest { checkCancellationIsRethrown(KeyedAuth.PASSKEY) }

    @Test
    fun `PASSWORD auth - a cancelled connection is rethrown`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(host, any()) } throws CancellationException("cancelled")

        val outcome = runCatching { useCase(host) }

        assertTrue(outcome.exceptionOrNull() is CancellationException, "$outcome")
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - a cancelled connection is rethrown`() = runTest {
        val host = skHost()
        coEvery { sshKeyRepository.getById("cred-1") } returns skKeyRecord(credentialId = null)
        coEvery { sessionManager.connectWithSkKey(host, any(), any()) } throws CancellationException("cancelled")

        val outcome = runCatching { useCase(host) }

        assertTrue(outcome.exceptionOrNull() is CancellationException, "$outcome")
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `SSH_KEY auth - cancelling the caller during the connection delivers no result and wipes the passphrase`() = runTest {
        val host = keyedHost(KeyedAuth.SSH_KEY)
        val stored = "hunter2".toCharArray()
        every { vaultManager.getPrivateKey("cred-1") } returns keyPem
        every { vaultManager.getKeyPassphrase("cred-1") } returns stored
        coEvery { sessionManager.connectWithKey(host, keyPem, any()) } coAnswers { awaitCancellation() }
        var delivered: SshResult<SshSession>? = null

        // Runs up to the connection, which waits there until the caller is cancelled.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { delivered = useCase(host) }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(delivered, "a cancelled connection reports nothing: $delivered")
        assertWiped(stored)
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `a stored passphrase is never logged, whatever the connection does`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            for (kind in KeyedAuth.entries) {
                connectKeyed(kind, "hunter2".toCharArray()) { SshResult.Success(testSession) }
                connectKeyed(kind, "hunter2".toCharArray()) { SshResult.Error(SshErrorCode.AUTH_FAILED, "refused") }
                connectKeyed(kind, "hunter2".toCharArray()) { throw IllegalStateException("boom") }
            }
        } finally {
            Timber.uproot(tree)
        }

        // A connection that throws is noted (the class of the exception, nothing else): neither
        // the passphrase nor what the exception says gets into the log, and no throwable is passed on.
        assertEquals(KeyedAuth.entries.size, tree.entries.size, "one note per exception: ${tree.entries}")
        tree.entries.forEach { entry ->
            assertFalse(entry.message.contains("hunter2"), entry.message)
            assertFalse(entry.message.contains("boom"), entry.message)
            assertNull(entry.throwable)
        }
    }

    @Test
    fun `SSH_KEY auth - an expired vault when reading the passphrase returns AUTH_EXPIRED and does not connect`() = runTest {
        val host = keyedHost(KeyedAuth.SSH_KEY)
        every { vaultManager.getPrivateKey("cred-1") } returns keyPem
        every { vaultManager.getKeyPassphrase("cred-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithKey(any(), any(), any()) }
    }

    @Test
    fun `CERTIFICATE auth - an expired vault when reading the passphrase returns AUTH_EXPIRED and does not connect`() = runTest {
        val host = keyedHost(KeyedAuth.CERTIFICATE)
        every { vaultManager.getPrivateKey("cred-1") } returns keyPem
        every { vaultManager.getCertificate("cred-1") } returns certPem
        every { vaultManager.getKeyPassphrase("cred-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithCertificate(any(), any(), any(), any()) }
    }

    @Test
    fun `FIDO2 PASSKEY - an expired vault when reading the private key returns AUTH_EXPIRED and does not connect`() = runTest {
        val host = keyedHost(KeyedAuth.PASSKEY)
        coEvery { sshKeyRepository.getById("cred-1") } returns passkeyKey()
        every { vaultManager.getPrivateKey("key-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithKey(any(), any(), any()) }
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `FIDO2 PASSKEY - an expired vault when reading the passphrase returns AUTH_EXPIRED and does not connect`() = runTest {
        val host = keyedHost(KeyedAuth.PASSKEY)
        coEvery { sshKeyRepository.getById("cred-1") } returns passkeyKey()
        every { vaultManager.getPrivateKey("key-1") } returns keyPem
        every { vaultManager.getKeyPassphrase("key-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithKey(any(), any(), any()) }
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    // --- VaultAuthExpiredException handling ---

    @Test
    fun `PASSWORD auth - VaultAuthExpiredException returns AUTH_EXPIRED error`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithPassword(any(), any()) }
    }

    @Test
    fun `SSH_KEY auth - VaultAuthExpiredException returns AUTH_EXPIRED error`() = runTest {
        val host = testHost.copy(authType = AuthType.SSH_KEY)
        every { vaultManager.getPrivateKey("cred-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithKey(any(), any(), any()) }
    }

    @Test
    fun `CERTIFICATE auth - VaultAuthExpiredException returns AUTH_EXPIRED error`() = runTest {
        val host = testHost.copy(authType = AuthType.CERTIFICATE)
        every { vaultManager.getPrivateKey("cred-1") } throws VaultAuthExpiredException()

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_EXPIRED, (result as SshResult.Error).code)
        coVerify(exactly = 0) { sessionManager.connectWithCertificate(any(), any(), any(), any()) }
    }

    // --- BIOMETRIC_KEY: the fingerprint is the session manager's business, not the caller's ---

    private val bioKeyId = "0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val bioAlias = BiometricKeyAlias.forKeyId(bioKeyId)

    private fun bioHost() = testHost.copy(authType = AuthType.BIOMETRIC_KEY, credentialId = bioKeyId)

    private fun bioKey(alias: String?) = SshKey(
        id = bioKeyId,
        label = "Bio Key",
        keyType = SshKeyType.ECDSA_256,
        publicKey = "ecdsa-sha2-nistp256 AAAA...",
        isBiometric = true,
        keystoreAlias = alias,
    )

    /** The Keystore holds the key: its public half is there, and the signature opens when asked. */
    private fun keystoreHoldsTheKey(publicKey: java.security.PublicKey = mockk()): java.security.PublicKey {
        every { keystoreManager.getBiometricPublicKey(bioAlias) } returns publicKey
        return publicKey
    }

    @Test
    fun `BIOMETRIC_KEY auth connects with the public key and the label of the key, and updates last connected`() = runTest {
        val host = bioHost()
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
        val publicKey = keystoreHoldsTheKey()
        coEvery { sessionManager.connectWithBiometricKey(host, publicKey, "Bio Key", any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sessionManager.connectWithBiometricKey(host, publicKey, "Bio Key", any()) }
        coVerify(exactly = 1) { hostRepository.updateLastConnected("host-1") }
    }

    @Test
    fun `BIOMETRIC_KEY auth opens the Keystore signature only when the session manager asks for it`() = runTest {
        // The Signature is opened at PK_OK, after the server accepted the key: opening it here, before
        // the connection, would make a key the server refuses cost an open Keystore operation.
        val host = bioHost()
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
        val publicKey = keystoreHoldsTheKey()
        val opener = slot<() -> java.security.Signature>()
        coEvery { sessionManager.connectWithBiometricKey(host, publicKey, "Bio Key", capture(opener)) } returns
            SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected(any()) } returns Unit
        val signature = mockk<java.security.Signature>()
        every { keystoreManager.getSignatureForBiometricKey(bioAlias) } returns signature

        useCase(host)

        coVerify(exactly = 0) { keystoreManager.getSignatureForBiometricKey(any()) }
        assertTrue(opener.captured() === signature, "the opener gives the Keystore signature of the key's own alias")
        coVerify(exactly = 1) { keystoreManager.getSignatureForBiometricKey(bioAlias) }
    }

    @Test
    fun `BIOMETRIC_KEY auth takes no signature from the caller any more`() {
        // The parameter that nobody passed (and that nothing signed with) is gone from the contract.
        val parameters = ConnectSessionUseCase::class.java.methods.filter { it.name == "invoke" }
            .map { it.parameterTypes.toList() }

        assertTrue(parameters.none { types -> types.any { it == java.security.Signature::class.java } }, "$parameters")
    }

    @Test
    fun `BIOMETRIC_KEY auth does not update last connected when the connection fails`() = runTest {
        val host = bioHost()
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
        keystoreHoldsTheKey()
        coEvery { sessionManager.connectWithBiometricKey(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "message of the session manager")

        val result = useCase(host)

        // The error of the session manager reaches the caller as it is.
        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, (result as SshResult.Error).code)
        assertEquals("message of the session manager", result.message)
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `BIOMETRIC_KEY auth with a key that left the Keystore asks to regenerate it and never connects`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
        every { keystoreManager.getBiometricPublicKey(bioAlias) } throws java.security.KeyStoreException("Biometric key not found")

        val result = useCase(bioHost())

        val error = result as SshResult.Error
        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(res(R.string.auth_error_biometric_key_invalid), error.message)
        coVerify(exactly = 0) { sessionManager.connectWithBiometricKey(any(), any(), any(), any()) }
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `BIOMETRIC_KEY auth with an alias the Keystore manager refuses asks to regenerate the key`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
        every { keystoreManager.getBiometricPublicKey(bioAlias) } throws
            java.security.KeyStoreException(fr.techtical.nextsh.core.crypto.NOT_A_BIOMETRIC_ALIAS_MESSAGE)

        val result = useCase(bioHost())

        assertEquals(res(R.string.auth_error_biometric_key_invalid), (result as SshResult.Error).message)
    }

    @Test
    fun `BIOMETRIC_KEY auth with any other Keystore failure is neutral, not an order to regenerate`() = runTest {
        // Regenerating a healthy key would cut the access to every server it is authorized on.
        for (failure in listOf(
            java.security.KeyStoreException("KeyStore not initialized"),
            java.security.KeyStoreException("AndroidKeyStore not found"),
            java.security.KeyStoreException(),
        )) {
            coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
            every { keystoreManager.getBiometricPublicKey(bioAlias) } throws failure

            val result = useCase(bioHost())

            val error = result as SshResult.Error
            assertEquals(SshErrorCode.AUTH_FAILED, error.code)
            assertEquals(res(R.string.auth_error_biometric_key_unusable), error.message)
            assertFalse(error.message == res(R.string.auth_error_biometric_key_invalid), "not an order to regenerate")
        }
        coVerify(exactly = 0) { sessionManager.connectWithBiometricKey(any(), any(), any(), any()) }
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    @Test
    fun `BIOMETRIC_KEY auth with a key record that is gone reports it`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns null

        val result = useCase(bioHost())

        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        assertEquals(res(R.string.connect_error_biometric_key_missing, "My Server"), result.message)
        coVerify(exactly = 0) { sessionManager.connectWithBiometricKey(any(), any(), any(), any()) }
    }

    @Test
    fun `BIOMETRIC_KEY auth lets a cancellation of the connection through`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
        keystoreHoldsTheKey()
        coEvery { sessionManager.connectWithBiometricKey(any(), any(), any(), any()) } throws CancellationException("left the screen")

        var cancelled = false
        try {
            useCase(bioHost())
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled, "a cancelled connection ends cancelled, it is not an UNKNOWN error to show")
        coVerify(exactly = 0) { hostRepository.updateLastConnected(any()) }
    }

    // --- BIOMETRIC_KEY: the alias read from the database is only used if it is the key's own ---

    @Test
    fun `BIOMETRIC_KEY auth refuses an alias that is not the one of that key`() = runTest {
        val foreignAliases = listOf(
            "nextsh_vault_auth_key",
            BiometricKeyAlias.forKeyId("11111111-2222-4333-8444-555555555555"),
            "nextsh_bio_test",
            "",
        )
        for (alias in foreignAliases) {
            coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = alias)

            val result = useCase(bioHost())

            assertTrue(result is SshResult.Error, "alias [$alias] must be refused")
            val error = result as SshResult.Error
            assertEquals(SshErrorCode.AUTH_FAILED, error.code)
            // The key must be regenerated: said in the words of every other key that cannot be used.
            assertEquals(res(R.string.auth_error_biometric_key_invalid), error.message, "alias [$alias]")
        }
        // Nothing reached the Keystore or the SSH session.
        coVerify(exactly = 0) { keystoreManager.getBiometricPublicKey(any()) }
        coVerify(exactly = 0) { keystoreManager.getSignatureForBiometricKey(any()) }
        coVerify(exactly = 0) { sessionManager.connectWithBiometricKey(any(), any(), any(), any()) }
    }

    @Test
    fun `BIOMETRIC_KEY auth refusal logs a fixed message without the alias`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = "nextsh_vault_auth_key")

            useCase(bioHost())
        } finally {
            Timber.uproot(tree)
        }

        val warning = tree.entries.single()
        assertEquals(5, warning.priority) // android.util.Log.WARN
        assertTrue(warning.message.contains("refused"))
        assertFalse(warning.message.contains("nextsh_vault_auth_key"))
        assertNull(warning.throwable)
    }

    @Test
    fun `BIOMETRIC_KEY auth with a missing alias still reports the missing alias`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = null)

        val result = useCase(bioHost())

        assertTrue(result is SshResult.Error)
        // A key with no alias of the Keystore cannot sign any more: told like a key that is gone.
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
        assertEquals(res(R.string.auth_error_biometric_key_invalid), result.message)
    }

    // --- A biometric key that is refused leaves a trace in the log, with nothing of the key in it ---

    private fun logged(block: suspend () -> Unit): List<RecordingTree.Entry> {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            kotlinx.coroutines.runBlocking { block() }
        } finally {
            Timber.uproot(tree)
        }
        return tree.entries
    }

    @Test
    fun `BIOMETRIC_KEY auth with no alias logs a fixed message`() {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = null)

        val entries = logged { useCase(bioHost()) }

        val note = entries.single()
        assertEquals(5, note.priority) // android.util.Log.WARN
        assertEquals("BIOMETRIC_KEY connect refused: the key has no Keystore alias", note.message)
        assertNull(note.throwable)
    }

    @Test
    fun `BIOMETRIC_KEY auth with a Keystore failure logs whether the key is gone, never what the Keystore said`() {
        val failures = mapOf(
            fr.techtical.nextsh.core.crypto.MISSING_BIOMETRIC_KEY_MESSAGE to true,
            "KeyStore not initialized, alias $bioAlias" to false,
        )
        for ((said, gone) in failures) {
            coEvery { sshKeyRepository.getById(bioKeyId) } returns bioKey(alias = bioAlias)
            every { keystoreManager.getBiometricPublicKey(bioAlias) } throws java.security.KeyStoreException(said)

            val entries = logged { useCase(bioHost()) }

            val note = entries.single()
            assertEquals(5, note.priority)
            assertTrue(note.message.contains("keyIsGone=$gone"), note.message)
            assertTrue(note.message.contains("KeyStoreException"), "the class of what failed: ${note.message}")
            assertFalse(note.message.contains(bioAlias), "the alias stays out of the log: ${note.message}")
            assertFalse(note.message.contains("not initialized"), "what the Keystore said stays out: ${note.message}")
            assertNull(note.throwable)
        }
    }

    // --- What the user reads comes from a resource of ours, and names what is missing ---

    @Test
    fun `PASSWORD auth - a missing password says so, naming the host`() = runTest {
        every { vaultManager.getPassword("cred-1") } returns null

        val error = useCase(testHost.copy(authType = AuthType.PASSWORD)) as SshResult.Error

        assertEquals(res(R.string.connect_error_password_missing, "My Server"), error.message)
    }

    @Test
    fun `SSH_KEY and CERTIFICATE auth - a missing key says so, naming the host`() = runTest {
        every { vaultManager.getPrivateKey("cred-1") } returns null

        for (type in listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE)) {
            val error = useCase(testHost.copy(authType = type)) as SshResult.Error

            assertEquals(res(R.string.connect_error_key_missing, "My Server"), error.message, "$type")
        }
    }

    @Test
    fun `CERTIFICATE auth - a missing certificate says so, naming the host`() = runTest {
        every { vaultManager.getPrivateKey("cred-1") } returns "-----BEGIN PRIVATE KEY-----..."
        every { vaultManager.getCertificate("cred-1") } returns null

        val error = useCase(testHost.copy(authType = AuthType.CERTIFICATE)) as SshResult.Error

        assertEquals(res(R.string.connect_error_certificate_missing, "My Server"), error.message)
    }

    @Test
    fun `FIDO2 HARDWARE_KEY - an invalid key line names the key`() = runTest {
        coEvery { sshKeyRepository.getById("cred-1") } returns skKeyRecord("AAAA").copy(publicKey = "invalid-key-format")

        val error = useCase(skHost()) as SshResult.Error

        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertEquals(res(R.string.connect_error_fido2_key_invalid, "Test SK Key"), error.message)
    }

    @Test
    fun `FIDO2 PASSKEY - a missing SSH key names the host and a missing private key names the key`() = runTest {
        val host = keyedHost(KeyedAuth.PASSKEY)
        coEvery { sshKeyRepository.getById("cred-1") } returns null
        val noKey = useCase(host) as SshResult.Error

        coEvery { sshKeyRepository.getById("cred-1") } returns passkeyKey()
        every { vaultManager.getPrivateKey("key-1") } returns null
        val noPrivateKey = useCase(host) as SshResult.Error

        assertEquals(res(R.string.connect_error_passkey_key_missing, "My Server"), noKey.message)
        assertEquals(res(R.string.connect_error_key_missing, "Test Key"), noPrivateKey.message)
    }

    @Test
    fun `an expired vault is told the same way whatever the authentication method`() = runTest {
        every { vaultManager.getPassword("cred-1") } throws VaultAuthExpiredException()
        every { vaultManager.getPrivateKey("cred-1") } throws VaultAuthExpiredException()
        coEvery { sshKeyRepository.getById("cred-1") } returns passkeyKey()
        every { vaultManager.getPrivateKey("key-1") } throws VaultAuthExpiredException()

        val hosts = listOf(
            testHost.copy(authType = AuthType.PASSWORD),
            keyedHost(KeyedAuth.SSH_KEY),
            keyedHost(KeyedAuth.CERTIFICATE),
            keyedHost(KeyedAuth.PASSKEY),
        )
        for (host in hosts) {
            val error = useCase(host) as SshResult.Error

            assertEquals(SshErrorCode.AUTH_EXPIRED, error.code, "${host.authType}")
            assertEquals(res(R.string.connect_error_vault_auth_expired), error.message, "${host.authType}")
        }
    }

    @Test
    fun `an expired vault locks the app, so that the lock screen comes up over the current screen`() = runTest {
        every { vaultManager.getPassword("cred-1") } throws VaultAuthExpiredException()
        appLock.unlock()

        useCase(testHost.copy(authType = AuthType.PASSWORD))

        assertTrue(appLock.isLocked())
    }

    @Test
    fun `a connection that goes through leaves the app unlocked`() = runTest {
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(testHost, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit
        appLock.unlock()

        useCase(testHost)

        assertFalse(appLock.isLocked())
    }

    @Test
    fun `an exception nothing names is reported with a sentence of ours, never with what it says`() = runTest {
        val failures = listOf<Exception>(
            IllegalStateException("database closed: /data/user/0/fr.techtical.nextsh/databases/secret.db"),
            java.io.IOException("socket closed by 10.0.0.12"),
            RuntimeException(),
        )
        for (failure in failures) {
            every { vaultManager.getPassword("cred-1") } throws failure

            val error = useCase(testHost.copy(authType = AuthType.PASSWORD)) as SshResult.Error

            assertEquals(SshErrorCode.UNKNOWN, error.code)
            assertEquals(res(R.string.connect_error_unexpected), error.message, "$failure")
        }
    }

    @Test
    fun `a failure of the recording does not leak what the database said`() = runTest {
        val host = testHost.copy(authType = AuthType.PASSWORD)
        every { vaultManager.getPassword("cred-1") } returns "secret123".toCharArray()
        coEvery { sessionManager.connectWithPassword(host, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } throws IllegalStateException("database closed")
        every { sessionManager.release(any()) } returns Unit

        val error = useCase(host) as SshResult.Error

        assertEquals(res(R.string.connect_error_unexpected), error.message)
    }

    @Test
    fun `an unexpected failure is logged by its class only`() = runTest {
        every { vaultManager.getPassword("cred-1") } throws IllegalStateException("secret detail")
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            useCase(testHost.copy(authType = AuthType.PASSWORD))
        } finally {
            Timber.uproot(tree)
        }

        val note = tree.entries.single()
        assertEquals(5, note.priority) // android.util.Log.WARN
        assertTrue(note.message.contains("IllegalStateException"), note.message)
        assertFalse(note.message.contains("secret detail"), note.message)
        assertFalse(note.message.contains("host-1"), "the host id is hashed: ${note.message}")
        assertNull(note.throwable)
    }
}
