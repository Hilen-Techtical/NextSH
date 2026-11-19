// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.testutil.LEAK_MARKER
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.security.KeyPairGenerator

/**
 * [ImportSshKeyUseCase] with mocked collaborators: how each way of failing is reported to the
 * screen. The screen picks its sentence from the type of the failure, so the types are the
 * contract: an unreadable file, a wrong passphrase, an encrypted key that needs one.
 */
class ImportSshKeyUseCaseTest {

    private lateinit var keyManager: SshKeyManager
    private lateinit var keyRepository: SshKeyRepository
    private lateinit var vaultManager: VaultManager
    private lateinit var useCase: ImportSshKeyUseCase

    private val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @BeforeEach
    fun setUp() {
        keyManager = mockk()
        keyRepository = mockk()
        vaultManager = mockk()
        useCase = ImportSshKeyUseCase(keyManager, keyRepository, vaultManager)
    }

    private fun verifyNothingStored() {
        verify(exactly = 0) { vaultManager.storePrivateKey(any(), any()) }
        verify(exactly = 0) { vaultManager.storeKeyPassphrase(any(), any()) }
        coVerify(exactly = 0) { keyRepository.save(any()) }
    }

    private fun CharArray.isWiped(): Boolean = all { it == '\u0000' }

    @Test
    fun `a text with no PEM header is an unreadable key, and the SSH library is not even asked`() = runTest {
        val passphrase = "a passphrase".toCharArray()

        val failure = useCase("work", "this is not a key", passphrase).exceptionOrNull()

        assertTrue(failure is UnreadableKeyException, failure.toString())
        assertTrue(passphrase.isWiped(), "the passphrase is wiped on this path too")
        verify(exactly = 0) { keyManager.loadKeyProviderFromString(any(), any()) }
        verifyNothingStored()
    }

    @Test
    fun `a passphrase error with a passphrase supplied is a wrong passphrase, the library failure kept as the cause`() = runTest {
        val libraryFailure = IllegalStateException("could not decrypt the key $LEAK_MARKER")
        every { keyManager.loadKeyProviderFromString(any(), any()) } throws libraryFailure

        val failure = useCase("work", pem, "a passphrase".toCharArray()).exceptionOrNull()

        assertTrue(failure is WrongKeyPassphraseException, failure.toString())
        assertSame(libraryFailure, failure!!.cause)
        // A fixed technical text, never built from what the library says.
        assertFalse(failure.message.orEmpty().contains(LEAK_MARKER), failure.message)
        verifyNothingStored()
    }

    @Test
    fun `the same error with no passphrase asks for one`() = runTest {
        every { keyManager.loadKeyProviderFromString(any(), any()) } throws
            IllegalStateException("could not decrypt the key $LEAK_MARKER")

        val failure = useCase("work", pem, null).exceptionOrNull()

        assertTrue(failure is PassphraseRequiredException, failure.toString())
        verifyNothingStored()
    }

    @Test
    fun `a bad padding is a passphrase error too, whatever its message`() = runTest {
        every { keyManager.loadKeyProviderFromString(any(), any()) } throws
            RuntimeException("wrapper", javax.crypto.BadPaddingException("x"))

        assertTrue(useCase("work", pem, "a passphrase".toCharArray()).exceptionOrNull() is WrongKeyPassphraseException)
        assertTrue(useCase("work", pem, null).exceptionOrNull() is PassphraseRequiredException)
    }

    @Test
    fun `any other error is an unreadable key, the library failure kept as the cause and its message out of it`() = runTest {
        val libraryFailure = IllegalArgumentException("unknown key format $LEAK_MARKER")
        every { keyManager.loadKeyProviderFromString(any(), any()) } throws libraryFailure

        val withPassphrase = useCase("work", pem, "a passphrase".toCharArray()).exceptionOrNull()
        val withoutPassphrase = useCase("work", pem, null).exceptionOrNull()

        listOf(withPassphrase, withoutPassphrase).forEach { failure ->
            assertTrue(failure is UnreadableKeyException, failure.toString())
            assertSame(libraryFailure, failure!!.cause)
            assertFalse(failure.message.orEmpty().contains(LEAK_MARKER), failure.message)
        }
        verifyNothingStored()
    }

    @Test
    fun `the typed failures carry a short technical text in English, never a French sentence`() {
        assertEquals("Wrong key passphrase", WrongKeyPassphraseException().message)
        assertEquals("Unreadable private key", UnreadableKeyException().message)
        assertEquals(null, WrongKeyPassphraseException().cause)
        assertEquals(null, UnreadableKeyException().cause)
    }

    @Test
    fun `a failure to read the key logs the class only, never the message`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            every { keyManager.loadKeyProviderFromString(any(), any()) } throws IllegalArgumentException(LEAK_MARKER)

            useCase("work", pem, null)

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertEquals(null, entry.throwable, "no throwable (its message and trace) may be logged")
            }
            assertTrue(tree.entries.any { it.message.contains("IllegalArgumentException") })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `a public key that cannot be extracted is a failure with a fixed text, and logs the class only`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            val provider = mockk<KeyProvider>()
            every { provider.private } returns mockk<java.security.PrivateKey>()
            every { provider.public } throws IllegalStateException(LEAK_MARKER)
            every { keyManager.loadKeyProviderFromString(any(), any()) } returns provider

            val failure = useCase("work", pem, null).exceptionOrNull()

            assertTrue(failure is UnreadableKeyException, failure.toString())
            assertNotNull(failure)
            assertFalse(failure!!.message.orEmpty().contains(LEAK_MARKER), failure.message)
            assertTrue(failure.cause is IllegalStateException)
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertEquals(null, entry.throwable)
            }
            verifyNothingStored()
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `a readable key is stored with its passphrase and saved, and the passphrase of the caller is not kept`() = runTest {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val provider = mockk<KeyProvider>()
        every { provider.public } returns pair.public
        every { provider.private } returns pair.private
        every { keyManager.loadKeyProviderFromString(any(), any()) } returns provider
        val storedPassphrase = slot<CharArray>()
        every { vaultManager.storePrivateKey(any(), any()) } returns Unit
        every { vaultManager.storeKeyPassphrase(any(), capture(storedPassphrase)) } returns Unit
        val saved = slot<SshKey>()
        coEvery { keyRepository.save(capture(saved)) } returns Unit

        val result = useCase("work", pem, "a passphrase".toCharArray())

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        assertEquals("work", saved.captured.label)
        assertEquals(SshKeyType.RSA_4096, saved.captured.keyType)
        assertFalse(saved.captured.isBiometric)
        verify(exactly = 1) { vaultManager.storePrivateKey(saved.captured.id, pem) }
        assertEquals("a passphrase", String(storedPassphrase.captured))
    }
}
