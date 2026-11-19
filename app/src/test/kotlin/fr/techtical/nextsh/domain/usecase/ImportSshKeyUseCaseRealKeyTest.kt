// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.ui.vault.keyImportFailureRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.schmizz.sshj.common.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [ImportSshKeyUseCase] against the real SSH library: the keys are generated here (an OpenSSH v1
 * Ed25519 key, encrypted with bcrypt and AES-256-CTR like `ssh-keygen -N` does), never simulated by a
 * loader that throws. SSHJ reads a key only at its first `private` / `public` access, which is what
 * these tests guard: an encrypted key must be detected at import.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportSshKeyUseCaseRealKeyTest {

    private lateinit var keyRepository: SshKeyRepository
    private lateinit var vaultManager: VaultManager
    private lateinit var useCase: ImportSshKeyUseCase

    @BeforeEach
    fun setUp() {
        keyRepository = mockk()
        vaultManager = mockk()
        useCase = ImportSshKeyUseCase(SshKeyManager(), keyRepository, vaultManager, UnconfinedTestDispatcher())
    }

    private fun verifyNothingStored() {
        verify(exactly = 0) { vaultManager.storePrivateKey(any(), any()) }
        verify(exactly = 0) { vaultManager.storeKeyPassphrase(any(), any()) }
        coVerify(exactly = 0) { keyRepository.save(any()) }
    }

    // ── A real encrypted key ────────────────────────────────────────────────────────────────────

    @Test
    fun `an encrypted key with no passphrase asks for one`() = runTest {
        val failure = useCase("work", openSshKey("correct horse"), null).exceptionOrNull()

        assertTrue(failure is PassphraseRequiredException, failure.toString())
        verifyNothingStored()
    }

    @Test
    fun `an encrypted key with a wrong passphrase is a wrong passphrase, not an unreadable file`() = runTest {
        val passphrase = "not the one".toCharArray()

        val failure = useCase("work", openSshKey("correct horse"), passphrase).exceptionOrNull()

        assertTrue(failure is WrongKeyPassphraseException, failure.toString())
        verifyNothingStored()
    }

    @Test
    fun `an encrypted key with its passphrase is imported and the passphrase is stored`() = runTest {
        val pem = openSshKey("correct horse")
        val storedPassphrase = slot<CharArray>()
        every { vaultManager.storePrivateKey(any(), any()) } returns Unit
        every { vaultManager.storeKeyPassphrase(any(), capture(storedPassphrase)) } returns Unit
        val saved = slot<SshKey>()
        coEvery { keyRepository.save(capture(saved)) } returns Unit

        val result = useCase("work", pem, "correct horse".toCharArray())

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        assertEquals(SshKeyType.ED25519, saved.captured.keyType)
        assertTrue(saved.captured.publicKey.startsWith("ssh-ed25519 "), saved.captured.publicKey)
        assertEquals("correct horse", String(storedPassphrase.captured))
        verify(exactly = 1) { vaultManager.storePrivateKey(saved.captured.id, pem) }
    }

    @Test
    fun `a key with no passphrase is imported without one`() = runTest {
        every { vaultManager.storePrivateKey(any(), any()) } returns Unit
        coEvery { keyRepository.save(any()) } returns Unit

        val result = useCase("work", openSshKey(null), null)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verify(exactly = 0) { vaultManager.storeKeyPassphrase(any(), any()) }
    }

    // ── Bounds ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a key over 64 KiB is too large and the SSH library is not asked`() = runTest {
        val keyManager = mockk<SshKeyManager>()
        val bounded = ImportSshKeyUseCase(keyManager, keyRepository, vaultManager, UnconfinedTestDispatcher())
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(MAX_PRIVATE_KEY_BYTES + 1) + "\n-----END OPENSSH PRIVATE KEY-----"
        val passphrase = "a passphrase".toCharArray()

        val failure = bounded("work", pem, passphrase).exceptionOrNull()

        assertTrue(failure is KeyTooLargeException, failure.toString())
        assertTrue(passphrase.all { it == '\u0000' }, "the passphrase is wiped on this path too")
        verify(exactly = 0) { keyManager.loadKeyProviderFromString(any(), any()) }
        verifyNothingStored()
    }

    @Test
    fun `the limit counts UTF-8 bytes, not characters`() = runTest {
        val keyManager = mockk<SshKeyManager>()
        val bounded = ImportSshKeyUseCase(keyManager, keyRepository, vaultManager, UnconfinedTestDispatcher())
        // 40 000 characters, 80 000 bytes.
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + EACUTE.repeat(40_000)

        assertTrue(bounded("work", pem, null).exceptionOrNull() is KeyTooLargeException)
        verify(exactly = 0) { keyManager.loadKeyProviderFromString(any(), any()) }
    }

    @Test
    fun `the size rule is the same for a pasted text`() {
        assertEquals(false, isPrivateKeyTooLarge("a".repeat(MAX_PRIVATE_KEY_BYTES)))
        assertEquals(true, isPrivateKeyTooLarge("a".repeat(MAX_PRIVATE_KEY_BYTES + 1)))
        assertEquals(true, isPrivateKeyTooLarge(EACUTE.repeat(MAX_PRIVATE_KEY_BYTES / 2 + 1)))
        assertEquals(false, isPrivateKeyTooLarge(""))
    }

    @Test
    fun `an Ed25519 envelope that announces two gibibytes is an unreadable key, not a crash`() = runTest {
        val envelope = Buffer.PlainBuffer()
            .putString("ssh-ed25519")
            .putUInt32(Int.MAX_VALUE.toLong())
            .compactData
        val pem = wrap(
            Buffer.PlainBuffer()
                .putRawBytes(AUTH_MAGIC)
                .putString("none")
                .putString("none")
                .putBytes(ByteArray(0))
                .putUInt32(1)
                .putBytes(envelope)
                .putBytes(ByteArray(8))
                .compactData,
        )

        val failure = useCase("work", pem, null).exceptionOrNull()

        assertTrue(failure is UnreadableKeyException, failure.toString())
        verifyNothingStored()
    }

    @Test
    fun `an out of memory error while loading is an unreadable key`() = runTest {
        val keyManager = mockk<SshKeyManager>()
        every { keyManager.loadKeyProviderFromString(any(), any()) } throws OutOfMemoryError("Requested array size exceeds VM limit")
        val bounded = ImportSshKeyUseCase(keyManager, keyRepository, vaultManager, UnconfinedTestDispatcher())

        val failure = bounded("work", "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----", null)
            .exceptionOrNull()

        assertTrue(failure is UnreadableKeyException, failure.toString())
        verifyNothingStored()
    }

    @Test
    fun `a key that is too large has its own sentence`() {
        assertEquals(R.string.vault_key_import_too_large, keyImportFailureRes(KeyTooLargeException()))
    }

    // ── Key generation (test only) ──────────────────────────────────────────────────────────────

    private companion object {
        val AUTH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        const val ROUNDS = 16
        val EACUTE = String(Character.toChars(0xE9))

        fun wrap(binary: ByteArray): String {
            val lines = Base64.getEncoder().encodeToString(binary).chunked(70)
            return (listOf("-----BEGIN OPENSSH PRIVATE KEY-----") + lines + "-----END OPENSSH PRIVATE KEY-----")
                .joinToString("\n")
        }

        /**
         * An OpenSSH v1 Ed25519 key with a fixed seed: unencrypted when [passphrase] is null, else
         * aes256-ctr with a bcrypt key derivation (a few rounds only, the test stays fast).
         */
        fun openSshKey(passphrase: String?): String {
            val curve = EdDSANamedCurveTable.getByName("Ed25519")
            val seed = ByteArray(32) { (it + 1).toByte() }
            val pub = EdDSAPrivateKeySpec(seed, curve).a.toByteArray()
            val pubBlob = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(pub).compactData

            val blockSize = if (passphrase == null) 8 else 16
            val section = Buffer.PlainBuffer()
                .putUInt32(0x1234ABCDL)
                .putUInt32(0x1234ABCDL)
                .putString("ssh-ed25519")
                .putBytes(pub)
                .putBytes(seed + pub)
                .putString("test")
                .compactData
            val padding = ByteArray((blockSize - section.size % blockSize) % blockSize) { (it + 1).toByte() }
            val plain = section + padding

            if (passphrase == null) {
                return wrap(
                    Buffer.PlainBuffer()
                        .putRawBytes(AUTH_MAGIC).putString("none").putString("none").putBytes(ByteArray(0))
                        .putUInt32(1).putBytes(pubBlob).putBytes(plain).compactData,
                )
            }

            val salt = ByteArray(16) { (it * 3).toByte() }
            val derived = ByteArray(48)
            BCrypt().pbkdf(passphrase.toByteArray(Charsets.UTF_8), salt, ROUNDS, derived)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derived.copyOfRange(0, 32), "AES"),
                IvParameterSpec(derived.copyOfRange(32, 48)),
            )
            val encrypted = cipher.doFinal(plain)
            val kdfOptions = Buffer.PlainBuffer().putBytes(salt).putUInt32(ROUNDS.toLong()).compactData
            return wrap(
                Buffer.PlainBuffer()
                    .putRawBytes(AUTH_MAGIC).putString("aes256-ctr").putString("bcrypt").putBytes(kdfOptions)
                    .putUInt32(1).putBytes(pubBlob).putBytes(encrypted).compactData,
            )
        }
    }
}
