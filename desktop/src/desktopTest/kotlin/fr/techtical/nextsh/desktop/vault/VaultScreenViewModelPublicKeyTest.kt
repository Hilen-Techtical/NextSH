// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.FakeVaultManagerMem
import fr.techtical.nextsh.shared.core.ssh.OpenSshPrivateKeyWriter
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.schmizz.sshj.common.Buffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Copy the public key" and "Save the .pub": the line comes from the private key of the vault
 * (`SshPublicKeyExportRules`), never from the stored line alone. The keys are generated here.
 */
class VaultScreenViewModelPublicKeyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val vault = FakeVaultManagerMem()
    private val keyRepository = mockk<SshKeyRepository>(relaxed = true)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = Unit
    }

    private fun viewModel(): VaultScreenViewModel {
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
        )
    }

    private class TestKey(val pem: String, val blob: String, val line: String)

    private fun ed25519(seedOffset: Int, comment: String): TestKey {
        val seed = ByteArray(32) { (it + seedOffset).toByte() }
        val a = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName("Ed25519")).a.toByteArray()
        val blob = Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(a).compactData)
        return TestKey(OpenSshPrivateKeyWriter.ed25519(seed, comment), blob, "ssh-ed25519 $blob $comment")
    }

    private fun sshKey(line: String, type: SshKeyType = SshKeyType.ED25519) = SshKey(
        id = "k1",
        label = "My key",
        keyType = type,
        publicKey = line,
    )

    @Test
    fun `a stored line that matches the private key is copied with no notice and no update`() = runTest {
        val key = ed25519(1, "laptop")
        vault.storePrivateKey("k1", key.pem)
        val vm = viewModel()

        val line = vm.publicKeyLine(sshKey(key.line))

        assertEquals(key.line, line)
        assertNull(vm.status.value.notice)
        coVerify(exactly = 0) { keyRepository.update(any()) }
    }

    @Test
    fun `a stored line of another key is replaced by the derived one, the key is corrected and a notice says so`() = runTest {
        val real = ed25519(1, "real")
        val third = ed25519(2, "third-party")
        vault.storePrivateKey("k1", real.pem)
        val vm = viewModel()
        val updated = slot<SshKey>()

        val line = vm.publicKeyLine(sshKey(third.line))

        assertTrue(line!!.startsWith("ssh-ed25519 ${real.blob} "), "the copied line is the one of the private key")
        coVerify(exactly = 1) { keyRepository.update(capture(updated)) }
        assertEquals(line, updated.captured.publicKey)
        assertEquals(VaultNotice.PublicKeyCorrected, vm.status.value.notice)
        assertTrue(!vm.status.value.notice!!.autoDismiss, "the advice to remove the old key stays on screen")
    }

    @Test
    fun `a key without private key in the vault is copied as stored with an unverified notice`() = runTest {
        val key = ed25519(1, "laptop")
        val vm = viewModel()

        val line = vm.publicKeyLine(sshKey(key.line))

        assertEquals(key.line, line)
        assertEquals(VaultNotice.PublicKeyUnverified, vm.status.value.notice)
        coVerify(exactly = 0) { keyRepository.update(any()) }
    }

    @Test
    fun `an encrypted key is verified with the passphrase stored in the vault`() = runTest {
        val key = ed25519(1, "enc")
        vault.storePrivateKey("k1", encryptedOpenSshKey(1, "correct horse"))
        vault.storeKeyPassphrase("k1", "correct horse".toCharArray())
        val vm = viewModel()

        val line = vm.publicKeyLine(sshKey(key.line))

        assertEquals(key.line, line)
        assertNull(vm.status.value.notice, "verified, nothing to say")
    }

    @Test
    fun `an encrypted key without stored passphrase is copied as stored with an unverified notice`() = runTest {
        val key = ed25519(1, "enc")
        vault.storePrivateKey("k1", encryptedOpenSshKey(1, "correct horse"))
        val vm = viewModel()

        val line = vm.publicKeyLine(sshKey(key.line))

        assertEquals(key.line, line)
        assertEquals(VaultNotice.PublicKeyUnverified, vm.status.value.notice)
    }

    @Test
    fun `a FIDO2 key is copied as stored, with no notice and without looking in the vault`() = runTest {
        val fido = sshKey("sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29t fido", SshKeyType.SK_ED25519)
        // A private key under the same id must not be used to "correct" a hardware key.
        vault.storePrivateKey("k1", ed25519(1, "other").pem)
        val vm = viewModel()

        val line = vm.publicKeyLine(fido)

        assertEquals(fido.publicKey, line)
        assertNull(vm.status.value.notice)
        coVerify(exactly = 0) { keyRepository.update(any()) }
    }

    @Test
    fun `a key with no stored line and no private key gives nothing and an error notice`() = runTest {
        val vm = viewModel()

        val line = vm.publicKeyLine(sshKey(""))

        assertNull(line)
        assertEquals(VaultNotice.PublicKeyUnavailable, vm.status.value.notice)
        assertTrue(vm.status.value.notice!!.isError)
    }

    @Test
    fun `a key with no stored line is rebuilt from its private key`() = runTest {
        val key = ed25519(1, "laptop")
        vault.storePrivateKey("k1", key.pem)
        val vm = viewModel()

        val line = vm.publicKeyLine(sshKey(""))

        assertTrue(line!!.startsWith("ssh-ed25519 ${key.blob} "))
        coVerify(exactly = 1) { keyRepository.update(any()) }
        assertNull(vm.status.value.notice, "nothing wrong was stored, so no warning")
    }

    private companion object {
        val AUTH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        const val ROUNDS = 16

        fun encryptedOpenSshKey(seedOffset: Int, passphrase: String): String {
            val curve = EdDSANamedCurveTable.getByName("Ed25519")
            val seed = ByteArray(32) { (it + seedOffset).toByte() }
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
            val binary = Buffer.PlainBuffer()
                .putRawBytes(AUTH_MAGIC).putString("aes256-ctr").putString("bcrypt").putBytes(kdfOptions)
                .putUInt32(1).putBytes(pubBlob).putBytes(encrypted).compactData
            val lines = Base64.getEncoder().encodeToString(binary).chunked(70)
            return (listOf("-----BEGIN OPENSSH PRIVATE KEY-----") + lines + "-----END OPENSSH PRIVATE KEY-----")
                .joinToString("\n")
        }
    }
}
