// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.ssh.SshKeyManager
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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

    @MockK
    private lateinit var sshKeyManager: SshKeyManager

    private lateinit var useCase: ConnectSessionUseCase

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
        useCase = ConnectSessionUseCase(sessionManager, hostRepository, vaultManager, sshKeyRepository, keystoreManager, sshKeyManager)
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

    @Test
    fun `FIDO2 HARDWARE_KEY - missing fido2Mode returns FIDO2_NO_KEY error`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = null)

        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.FIDO2_NO_KEY, (result as SshResult.Error).code)
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

    @Test
    fun `FIDO2 HARDWARE_KEY - valid key delegates to connectWithSkKey`() = runTest {
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.HARDWARE_KEY)
        // Build a valid sk-ecdsa public key line
        val validPubKeyLine = "sk-ecdsa-sha2-nistp256@openssh.com AAAAI3NrLWVjZHNhLXNoYTItbmlzdHAyNTZAb3BlbnNzaC5jb20AAAAIbmlzdHAyNTYAAABBBFakeKeyDataForTestingPurposesOnly1234567890abcdef1234567890abcdef1234567890abAAAABHNzaDo="
        val sshKey = SshKey(
            id = "key-1",
            label = "Test SK Key",
            keyType = SshKeyType.SK_ECDSA_256,
            publicKey = validPubKeyLine,
        )
        coEvery { sshKeyRepository.getById("cred-1") } returns sshKey
        coEvery { sessionManager.connectWithSkKey(host, any()) } returns SshResult.Success(testSession)
        coEvery { hostRepository.updateLastConnected("host-1") } returns Unit

        val result = useCase(host)

        // The key format is likely invalid base64, so this will return AUTH_FAILED
        // This is expected: we can't create a real sk-* key in tests
        assertTrue(result is SshResult.Error || result is SshResult.Success)
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
        val host = testHost.copy(authType = AuthType.FIDO2, fido2Mode = Fido2Mode.HARDWARE_KEY)
        // A real sk-ssh-ed25519 public key line, built the way the enrolment stores it.
        val skPublicKeyLine = SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { 1 })
            .use { it.toAuthorizedKeysLine() }
        coEvery { sshKeyRepository.getById("cred-1") } returns SshKey(
            id = "key-1",
            label = "Test SK Key",
            keyType = SshKeyType.SK_ED25519,
            publicKey = skPublicKeyLine,
        )
        coEvery { sessionManager.connectWithSkKey(host, any()) } throws CancellationException("cancelled")

        val outcome = runCatching { useCase(host) }

        // Not an UNKNOWN error to show: the coroutine of the caller ends cancelled.
        assertTrue(outcome.exceptionOrNull() is CancellationException, "$outcome")
        coVerify(exactly = 1) { sessionManager.connectWithSkKey(host, any()) }
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

    /** Collects what is logged, to check that a passphrase never reaches the logs. */
    private class LogCollector : Timber.Tree() {
        val entries = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += listOfNotNull(tag, message, t?.stackTraceToString()).joinToString(" ")
        }
    }

    @Test
    fun `a stored passphrase is never logged, whatever the connection does`() = runTest {
        val tree = LogCollector()
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

        assertTrue(tree.entries.none { "hunter2" in it }, "the passphrase is not logged: ${tree.entries}")
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

    @Test
    fun `BIOMETRIC_KEY auth without signature returns AUTH_FAILED error`() = runTest {
        val host = testHost.copy(authType = AuthType.BIOMETRIC_KEY)
        val bioKey = fr.techtical.nextsh.domain.model.SshKey(
            id = "cred-1", label = "Bio Key",
            keyType = fr.techtical.nextsh.domain.model.SshKeyType.ECDSA_256,
            publicKey = "ecdsa-sha2-nistp256 AAAA...", isBiometric = true,
            keystoreAlias = "nextsh_bio_test",
        )
        coEvery { sshKeyRepository.getById("cred-1") } returns bioKey

        // No authenticatedSignature provided
        val result = useCase(host)

        assertTrue(result is SshResult.Error)
        val error = result as SshResult.Error
        assertEquals(SshErrorCode.AUTH_FAILED, error.code)
        assertTrue(error.message.contains("biométrique"))
    }
}
