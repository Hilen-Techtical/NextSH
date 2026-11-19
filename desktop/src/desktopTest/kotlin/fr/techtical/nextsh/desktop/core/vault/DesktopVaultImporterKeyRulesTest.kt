// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopVaultRestoreStore
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.ssh.OpenSshPrivateKeyWriter
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlinx.coroutines.test.runTest
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.schmizz.sshj.common.Buffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "add only" rules of the SSH keys at restore (`BackupKeyRules`), through the real importer, the real
 * restore store and a real database. The keys are generated here: a real private key and the public line
 * computed independently of the code under test.
 */
class DesktopVaultImporterKeyRulesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var keyRepository: DesktopSshKeyRepository
    private lateinit var vault: FakeVaultManagerMem
    private lateinit var importer: DesktopVaultImporter

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        keyRepository = DesktopSshKeyRepository(db)
        vault = FakeVaultManagerMem()
        importer = DesktopVaultImporter(vault, DesktopVaultRestoreStore(db), FakeBackupSettings())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a key already on the device keeps its private key and its public key, the file only renames it`() = runTest {
        val local = ed25519(1, "local")
        val other = ed25519(2, "other")
        keyRepository.save(sshKey("k1", "Local", local.line))
        vault.storePrivateKey("k1", local.pem)

        val report = import(
            keys = listOf(sshKey("k1", "Renamed", other.line)),
            privateKeys = mapOf("k1" to other.pem),
        )

        val row = assertNotNull(keyRepository.getById("k1"))
        assertEquals("Renamed", row.label)
        assertEquals(local.line, row.publicKey)
        assertEquals(local.pem, vault.getPrivateKey("k1"), "the local private key is never replaced")
        assertEquals(1, report.localKeysKept)
        assertEquals(0, report.publicKeysRecomputed)
        assertEquals(0, report.privateKeys, "the private key of the file was not written")
    }

    @Test
    fun `a private key of the file without a row never replaces the local one`() = runTest {
        val local = ed25519(1, "local")
        val other = ed25519(2, "other")
        keyRepository.save(sshKey("k1", "Local", local.line))
        vault.storePrivateKey("k1", local.pem)

        val report = import(keys = emptyList(), privateKeys = mapOf("k1" to other.pem))

        assertEquals(local.pem, vault.getPrivateKey("k1"))
        assertEquals(local.line, keyRepository.getById("k1")?.publicKey)
        assertEquals(1, report.localKeysKept)
        assertEquals(0, report.privateKeys)
    }

    @Test
    fun `a new key whose public line belongs to someone else gets the one derived from its private key`() = runTest {
        val real = ed25519(1, "real")
        val third = ed25519(2, "third-party")

        val report = import(
            keys = listOf(sshKey("k1", "Imported", third.line)),
            privateKeys = mapOf("k1" to real.pem),
        )

        val row = assertNotNull(keyRepository.getById("k1"))
        assertTrue(row.publicKey.startsWith("ssh-ed25519 ${real.blob} "), "the stored key is the one of the private key")
        assertEquals(1, report.publicKeysRecomputed)
        assertEquals(0, report.localKeysKept)
        assertEquals(real.pem, vault.getPrivateKey("k1"))
    }

    @Test
    fun `a local row with a wrong public line is repaired when the file brings the same private key`() = runTest {
        val real = ed25519(1, "real")
        val third = ed25519(2, "third-party")
        keyRepository.save(sshKey("k1", "Local", third.line))
        vault.storePrivateKey("k1", real.pem)

        val report = import(
            keys = listOf(sshKey("k1", "Local", third.line)),
            privateKeys = mapOf("k1" to real.pem),
        )

        assertTrue(assertNotNull(keyRepository.getById("k1")).publicKey.startsWith("ssh-ed25519 ${real.blob} "))
        assertEquals(1, report.publicKeysRecomputed)
    }

    @Test
    fun `a correct public line is kept as it is and nothing is counted`() = runTest {
        val real = ed25519(1, "real")

        val report = import(
            keys = listOf(sshKey("k1", "Imported", real.line)),
            privateKeys = mapOf("k1" to real.pem),
        )

        assertEquals(real.line, keyRepository.getById("k1")?.publicKey)
        assertEquals(0, report.publicKeysRecomputed)
        assertEquals(0, report.localKeysKept)
    }

    @Test
    fun `an encrypted private key with its passphrase in the file is written, its line is not touched`() = runTest {
        val pem = encryptedOpenSshKey(1, "correct horse")
        val third = ed25519(2, "third-party")

        val report = import(
            keys = listOf(sshKey("k1", "Encrypted", third.line)),
            privateKeys = mapOf("k1" to pem),
            passphrases = mapOf("k1" to "correct horse"),
        )

        // No key derivation function at import: an encrypted key proves nothing and changes nothing.
        assertEquals(third.line, keyRepository.getById("k1")?.publicKey)
        assertEquals(0, report.publicKeysRecomputed)
        assertEquals(1, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(pem, vault.getPrivateKey("k1"))
        assertEquals("correct horse", vault.getKeyPassphrase("k1")?.concatToString())
    }

    @Test
    fun `an encrypted private key without passphrase is written and its line is not touched`() = runTest {
        val pem = encryptedOpenSshKey(1, "correct horse")
        val line = ed25519(1, "enc").line

        val report = import(
            keys = listOf(sshKey("k1", "Encrypted", line)),
            privateKeys = mapOf("k1" to pem),
        )

        assertEquals(line, keyRepository.getById("k1")?.publicKey)
        assertEquals(0, report.publicKeysRecomputed)
        assertEquals(1, report.privateKeys)
        assertNull(vault.getKeyPassphrase("k1"))
    }

    @Test
    fun `a FIDO2 key on the device is never replaced, nor is a private key written for it`() = runTest {
        val software = ed25519(1, "software")
        val fido = SshKey(
            id = "k1",
            label = "Hardware",
            keyType = SshKeyType.SK_ED25519,
            publicKey = "sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29t fido",
            isBiometric = false,
            keystoreAlias = null,
            fido2CredentialId = "Y3JlZGVudGlhbA==",
            fido2RpId = "ssh:",
        )
        keyRepository.save(fido)

        val report = import(
            keys = listOf(sshKey("k1", "Renamed", software.line)),
            privateKeys = mapOf("k1" to software.pem),
        )

        val row = assertNotNull(keyRepository.getById("k1"))
        assertEquals("Renamed", row.label)
        assertEquals(SshKeyType.SK_ED25519, row.keyType)
        assertEquals(fido.publicKey, row.publicKey)
        assertEquals("Y3JlZGVudGlhbA==", row.fido2CredentialId)
        assertEquals("ssh:", row.fido2RpId)
        assertNull(vault.getPrivateKey("k1"), "no private key of the vault can be the private key of a FIDO2 key")
        assertEquals(1, report.localKeysKept)
        assertEquals(0, report.privateKeys)
    }

    @Test
    fun `a file with no private key never makes the importer decrypt a local private key`() = runTest {
        val local = ed25519(1, "local")
        keyRepository.save(sshKey("k1", "Local", local.line))
        vault.storePrivateKey("k1", local.pem)
        val counting = CountingVault(vault)
        importer = DesktopVaultImporter(counting, DesktopVaultRestoreStore(db), FakeBackupSettings())

        val report = import(
            keys = listOf(sshKey("k1", "Renamed", local.line)),
            passphrases = mapOf("k1" to "from the file"),
        )

        assertEquals(0, counting.privateKeyReads, "presence is read from the id list, nothing is decrypted")
        assertEquals(local.pem, vault.getPrivateKey("k1"))
        assertNull(vault.getKeyPassphrase("k1"), "the local private key stays with its own (absent) passphrase")
        assertEquals(0, report.keyPassphrases)
        assertEquals("Renamed", keyRepository.getById("k1")?.label)
    }

    @Test
    fun `a file with a private key decrypts the local one once to compare`() = runTest {
        val local = ed25519(1, "local")
        keyRepository.save(sshKey("k1", "Local", local.line))
        vault.storePrivateKey("k1", local.pem)
        val counting = CountingVault(vault)
        importer = DesktopVaultImporter(counting, DesktopVaultRestoreStore(db), FakeBackupSettings())

        import(keys = listOf(sshKey("k1", "Local", local.line)), privateKeys = mapOf("k1" to local.pem))

        assertEquals(1, counting.privateKeyReads)
    }

    @Test
    fun `the same private key under two ids is derived once during a restore`() = runTest {
        val real = ed25519(1, "real")
        val third = ed25519(2, "third-party")
        var calls = 0
        val counting = PublicKeyDeriver { pem, passphrase ->
            calls++
            SshPublicKeyDeriver.derive(pem, passphrase)
        }
        importer = DesktopVaultImporter(vault, DesktopVaultRestoreStore(db), FakeBackupSettings(), counting)

        val report = import(
            keys = listOf(sshKey("k1", "One", third.line), sshKey("k2", "Two", third.line)),
            privateKeys = mapOf("k1" to real.pem, "k2" to real.pem),
        )

        assertEquals(1, calls, "one derivation for the two ids")
        assertEquals(2, report.publicKeysRecomputed, "both rows are repaired from the one result")
        assertTrue(assertNotNull(keyRepository.getById("k2")).publicKey.startsWith("ssh-ed25519 ${real.blob} "))
    }

    @Test
    fun `a new restore derives again, nothing is kept between two restores`() = runTest {
        val real = ed25519(1, "real")
        var calls = 0
        val counting = PublicKeyDeriver { pem, passphrase ->
            calls++
            SshPublicKeyDeriver.derive(pem, passphrase)
        }
        importer = DesktopVaultImporter(vault, DesktopVaultRestoreStore(db), FakeBackupSettings(), counting)

        import(keys = listOf(sshKey("k1", "One", real.line)), privateKeys = mapOf("k1" to real.pem))
        import(keys = listOf(sshKey("k2", "Two", real.line)), privateKeys = mapOf("k2" to real.pem))

        assertEquals(2, calls)
    }

    // -- Helpers -------------------------------------------------------------------------------

    /** Counts the reads of private keys (a read decrypts the key). */
    private class CountingVault(private val inner: FakeVaultManagerMem) :
        fr.techtical.nextsh.shared.domain.vault.VaultManager by inner {
        var privateKeyReads = 0

        override suspend fun getPrivateKey(keyId: String): String? {
            privateKeyReads++
            return inner.getPrivateKey(keyId)
        }
    }

    private suspend fun import(
        keys: List<SshKey>,
        privateKeys: Map<String, String> = emptyMap(),
        passphrases: Map<String, String> = emptyMap(),
    ): BackupImportReport {
        val source = FakeVaultManagerMem().apply {
            privateKeys.forEach { (id, pem) -> storePrivateKey(id, pem) }
            passphrases.forEach { (id, pass) -> storeKeyPassphrase(id, pass.toCharArray()) }
        }
        val blob = exporterOver(vault = source, sshKeys = FakeSshKeyRepositoryMem(keys))
            .export(RESTORE_TEST_PASSPHRASE.toCharArray())
        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())
        return result.getOrNull() ?: error("import failed: ${result.exceptionOrNull()}")
    }

    private fun sshKey(id: String, label: String, line: String) = SshKey(
        id = id,
        label = label,
        keyType = SshKeyType.ED25519,
        publicKey = line,
    )

    /** A real Ed25519 key: its OpenSSH v1 PEM, and its public blob and line computed from the seed. */
    private class TestKey(val pem: String, val blob: String, val line: String)

    private fun ed25519(seedOffset: Int, comment: String): TestKey {
        val seed = ByteArray(32) { (it + seedOffset).toByte() }
        val a = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName("Ed25519")).a.toByteArray()
        val blob = Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(a).compactData)
        return TestKey(OpenSshPrivateKeyWriter.ed25519(seed, comment), blob, "ssh-ed25519 $blob $comment")
    }

    private companion object {
        val AUTH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        const val ROUNDS = 16

        /** An OpenSSH v1 Ed25519 key with the seed of [ed25519], aes256-ctr with a bcrypt derivation (few rounds). */
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
