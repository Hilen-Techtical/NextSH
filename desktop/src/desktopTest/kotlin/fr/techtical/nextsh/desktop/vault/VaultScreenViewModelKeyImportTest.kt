// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.FakeVaultManagerMem
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.schmizz.sshj.common.Buffer
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Importing a private key file on the Vault screen, against the real SSH library. The keys are generated
 * here (an OpenSSH v1 Ed25519 key encrypted with bcrypt and AES-256-CTR, like `ssh-keygen -N`). SSHJ reads
 * a key only at its first `private` / `public` access, so an encrypted key must be detected at import.
 */
class VaultScreenViewModelKeyImportTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = mutableListOf<File>()
    private val vault = FakeVaultManagerMem()
    private val keyRepository = mockk<SshKeyRepository>(relaxed = true)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = Unit
    }

    private fun viewModel(deriver: PublicKeyDeriver = SshPublicKeyDeriver): VaultScreenViewModel {
        every { keyRepository.observeAll() } returns flowOf(emptyList())
        val hostRepository = mockk<HostRepository>()
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        return VaultScreenViewModel(
            appScope = TestAppScope(scope),
            keyRepository = keyRepository,
            hostRepository = hostRepository,
            vaultManager = vault,
            generate = mockk(relaxed = true),
            exporter = mockk(relaxed = true),
            importer = mockk<DesktopVaultImporter>(),
            deriver = deriver,
        )
    }

    private fun fileOf(text: String): File = File.createTempFile("nextsh-key", ".key").also {
        it.writeText(text)
        tempFiles += it
    }

    private fun importAndAwait(file: File, passphrase: CharArray?): VaultOperationState {
        val vm = viewModel()
        vm.importKey(file, "work", passphrase)
        return runBlocking { withTimeout(20_000) { vm.status.first { !it.isBusy } } }
    }

    @Test
    fun `an encrypted key with no passphrase asks for one`() {
        val state = importAndAwait(fileOf(openSshKey("correct horse")), null)

        assertEquals(VaultNotice.KeyPassphraseRequired, state.notice)
        assertTrue(vault.privateKeys.isEmpty())
    }

    @Test
    fun `an encrypted key with a wrong passphrase is a wrong passphrase, not an import failure`() {
        val state = importAndAwait(fileOf(openSshKey("correct horse")), "not the one".toCharArray())

        assertEquals(VaultNotice.KeyPassphraseWrong, state.notice)
        assertTrue(vault.privateKeys.isEmpty())
    }

    @Test
    fun `an encrypted key with its passphrase is imported and the passphrase is stored`() {
        val saved = slot<SshKey>()
        val state = importAndAwait(fileOf(openSshKey("correct horse")), "correct horse".toCharArray())

        assertEquals(VaultNotice.KeyImported("work"), state.notice)
        coVerify(exactly = 1) { keyRepository.save(capture(saved)) }
        assertEquals(SshKeyType.ED25519, saved.captured.keyType)
        assertTrue(saved.captured.publicKey.startsWith("ssh-ed25519 "))
        assertEquals("correct horse", String(vault.keyPassphrases.getValue(saved.captured.id)))
    }

    @Test
    fun `a key file over 64 KiB is too large and nothing is stored`() {
        val big = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(BackupLimits.MAX_SECRET_VALUE_BYTES + 1)

        val state = importAndAwait(fileOf(big), null)

        assertEquals(VaultNotice.KeyTooLarge, state.notice)
        assertTrue(state.notice!!.isError)
        assertTrue(vault.privateKeys.isEmpty())
    }

    @Test
    fun `a file of several megabytes is refused without being read whole`() {
        val state = importAndAwait(fileOf("A".repeat(3 * 1024 * 1024)), null)

        assertEquals(VaultNotice.KeyTooLarge, state.notice)
    }

    @Test
    fun `the limit counts UTF-8 bytes`() {
        val eacute = String(Character.toChars(0xE9))
        val state = importAndAwait(fileOf("-----BEGIN OPENSSH PRIVATE KEY-----\n" + eacute.repeat(40_000)), null)

        assertEquals(VaultNotice.KeyTooLarge, state.notice)
    }

    @Test
    fun `an Ed25519 envelope that announces two gibibytes is an unreadable key, not a crash`() {
        val envelope = Buffer.PlainBuffer().putString("ssh-ed25519").putUInt32(Int.MAX_VALUE.toLong()).compactData
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

        val state = importAndAwait(fileOf(pem), null)

        assertEquals(VaultNotice.KeyUnreadable, state.notice)
        assertTrue(vault.privateKeys.isEmpty())
    }

    @Test
    fun `a key file that asks for two billion bcrypt rounds is refused at once and nothing is stored`() {
        val state = importAndAwait(fileOf(forgedRoundsKey()), "whatever".toCharArray())

        assertEquals(VaultNotice.KeyTooCostly, state.notice)
        assertTrue(state.notice!!.isError)
        assertTrue(vault.privateKeys.isEmpty())
    }

    @Test
    fun `a key file whose public copy is another key is refused as inconsistent and nothing is stored`() {
        val state = importAndAwait(fileOf(inconsistentKey()), null)

        assertEquals(VaultNotice.KeyInconsistent, state.notice)
        assertTrue(vault.privateKeys.isEmpty())
        coVerify(exactly = 0) { keyRepository.save(any()) }
    }

    @Test
    fun `a key whose encryption is unsupported is refused and never handed to SSHJ`() {
        // A clear, perfectly readable key: if it reached SSHJ it would be imported, so the refusal proves
        // that the deriver's verdict stops the import before the loader.
        val vm = viewModel(deriver = { _, _ -> SshPublicKeyDerivation.UnsupportedEncryption })
        vm.importKey(fileOf(inconsistentKey()), "work", "typed passphrase".toCharArray())
        val state = runBlocking { withTimeout(20_000) { vm.status.first { !it.isBusy } } }

        assertEquals(VaultNotice.KeyEncryptionUnsupported, state.notice)
        assertTrue(state.notice!!.isError)
        assertTrue(vault.privateKeys.isEmpty())
        assertTrue(vault.keyPassphrases.isEmpty())
        coVerify(exactly = 0) { keyRepository.save(any()) }
    }

    @Test
    fun `a text that is not a key is unreadable`() {
        val state = importAndAwait(fileOf("this is not a key"), null)

        assertEquals(VaultNotice.KeyUnreadable, state.notice)
    }

    // ── Key generation (test only) ──────────────────────────────────────────────────────────────

    private companion object {
        val AUTH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        const val ROUNDS = 16

        fun wrap(binary: ByteArray): String {
            val lines = Base64.getEncoder().encodeToString(binary).chunked(70)
            return (listOf("-----BEGIN OPENSSH PRIVATE KEY-----") + lines + "-----END OPENSSH PRIVATE KEY-----")
                .joinToString("\n")
        }

        /** Encrypted OpenSSH v1 file whose bcrypt rounds field is 2^31-1: only the header is real, the rest is filler. */
        fun forgedRoundsKey(): String {
            val kdfOptions = Buffer.PlainBuffer()
                .putBytes(ByteArray(16) { it.toByte() })
                .putUInt32(Int.MAX_VALUE.toLong())
                .compactData
            val pubBlob = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(ByteArray(32) { 1 }).compactData
            return wrap(
                Buffer.PlainBuffer()
                    .putRawBytes(AUTH_MAGIC).putString("aes256-ctr").putString("bcrypt").putBytes(kdfOptions)
                    .putUInt32(1).putBytes(pubBlob).putBytes(ByteArray(64) { 7 }).compactData,
            )
        }

        /** Clear OpenSSH v1 Ed25519 file: the private section holds the seed 1 key, the envelope the public key of seed 2. */
        fun inconsistentKey(): String {
            val curve = EdDSANamedCurveTable.getByName("Ed25519")
            val seed = ByteArray(32) { (it + 1).toByte() }
            val pub = EdDSAPrivateKeySpec(seed, curve).a.toByteArray()
            val otherPub = EdDSAPrivateKeySpec(ByteArray(32) { (it + 2).toByte() }, curve).a.toByteArray()
            val envelope = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(otherPub).compactData
            val section = Buffer.PlainBuffer()
                .putUInt32(0x1234ABCDL)
                .putUInt32(0x1234ABCDL)
                .putString("ssh-ed25519")
                .putBytes(pub)
                .putBytes(seed + pub)
                .putString("test")
                .compactData
            val padding = ByteArray((8 - section.size % 8) % 8) { (it + 1).toByte() }
            return wrap(
                Buffer.PlainBuffer()
                    .putRawBytes(AUTH_MAGIC).putString("none").putString("none").putBytes(ByteArray(0))
                    .putUInt32(1).putBytes(envelope).putBytes(section + padding).compactData,
            )
        }

        /** An OpenSSH v1 Ed25519 key with a fixed seed, aes256-ctr with a bcrypt derivation (few rounds). */
        fun openSshKey(passphrase: String): String {
            val curve = EdDSANamedCurveTable.getByName("Ed25519")
            val seed = ByteArray(32) { (it + 1).toByte() }
            val pub = EdDSAPrivateKeySpec(seed, curve).a.toByteArray()
            val pubBlob = Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(pub).compactData

            val section = Buffer.PlainBuffer()
                .putUInt32(0x1234ABCDL)
                .putUInt32(0x1234ABCDL)
                .putString("ssh-ed25519")
                .putBytes(pub)
                .putBytes(seed + pub)
                .putString("test")
                .compactData
            val padding = ByteArray((16 - section.size % 16) % 16) { (it + 1).toByte() }

            val salt = ByteArray(16) { (it * 3).toByte() }
            val derived = ByteArray(48)
            BCrypt().pbkdf(passphrase.toByteArray(Charsets.UTF_8), salt, ROUNDS, derived)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derived.copyOfRange(0, 32), "AES"),
                IvParameterSpec(derived.copyOfRange(32, 48)),
            )
            val encrypted = cipher.doFinal(section + padding)
            val kdfOptions = Buffer.PlainBuffer().putBytes(salt).putUInt32(ROUNDS.toLong()).compactData
            return wrap(
                Buffer.PlainBuffer()
                    .putRawBytes(AUTH_MAGIC).putString("aes256-ctr").putString("bcrypt").putBytes(kdfOptions)
                    .putUInt32(1).putBytes(pubBlob).putBytes(encrypted).compactData,
            )
        }
    }
}
