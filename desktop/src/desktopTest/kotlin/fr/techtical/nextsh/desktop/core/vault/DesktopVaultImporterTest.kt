// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Edge-case tests for the importer: size cap, malformed headers, wrong passphrase,
 * version check. The happy-path roundtrip is covered in [DesktopVaultBackupRoundtripTest].
 */
class DesktopVaultImporterTest {

    private fun emptyImporter(): DesktopVaultImporter = DesktopVaultImporter(
        FakeVaultManagerMem(),
        FakeSshKeyRepositoryMem(),
        FakeHostRepositoryMem(),
        FakeTunnelRepositoryMem(),
    )

    @Test
    fun `rejects buffers larger than 50 MB with FileTooLarge`() = runTest {
        val importer = emptyImporter()
        // Allocate one byte over the cap: we never decrypt it, so this is cheap(ish) but still 50 MB + 1.
        val oversize = ByteArray(DesktopVaultImporter.MAX_BACKUP_SIZE + 1)
        val result = importer.import(oversize, "whatever".toCharArray())
        assertIs<VaultImportError.FileTooLarge>(result.exceptionOrNull())
    }

    @Test
    fun `rejects buffers shorter than the 4-byte header with Malformed`() = runTest {
        val importer = emptyImporter()
        val result = importer.import(byteArrayOf(1, 2, 3), "pass".toCharArray())
        assertIs<VaultImportError.Malformed>(result.exceptionOrNull())
    }

    @Test
    fun `rejects negative or zero salt length with Malformed`() = runTest {
        val importer = emptyImporter()
        // salt length 0 → malformed
        val zeroSaltLen = byteArrayOf(0, 0, 0, 0) + ByteArray(32)
        val r1 = importer.import(zeroSaltLen, "pass".toCharArray())
        assertIs<VaultImportError.Malformed>(r1.exceptionOrNull())
        // salt length larger than remaining buffer → malformed
        val bogus = byteArrayOf(0x7F, -1, -1, -1) + ByteArray(8)  // huge salt len
        val r2 = importer.import(bogus, "pass".toCharArray())
        assertIs<VaultImportError.Malformed>(r2.exceptionOrNull())
    }

    @Test
    fun `rejects wrong passphrase with WrongPassphrase`() = runTest {
        val hosts = FakeHostRepositoryMem()
        val exporter = DesktopVaultExporter(
            FakeVaultManagerMem(), FakeSshKeyRepositoryMem(), hosts, FakeTunnelRepositoryMem(),
        )
        val blob = exporter.export("correct-pass".toCharArray())

        val importer = emptyImporter()
        val result = importer.import(blob, "wrong-pass".toCharArray())
        assertIs<VaultImportError.WrongPassphrase>(result.exceptionOrNull())
    }

    @Test
    fun `rejects garbage ciphertext body with WrongPassphrase or Malformed`() = runTest {
        val importer = emptyImporter()
        // Valid header (salt=32, 32 bytes of salt), garbage body.
        val buf = byteArrayOf(0, 0, 0, 32) + ByteArray(32) + byteArrayOf(12) + ByteArray(12) + ByteArray(32)
        val result = importer.import(buf, "anything".toCharArray())
        val err = result.exceptionOrNull()
        // AEAD on fake ciphertext fails as WrongPassphrase (AEADBadTagException path).
        assertTrue(err is VaultImportError.WrongPassphrase || err is VaultImportError.Malformed)
    }

    @Test
    fun `rejects unsupported version`() = runTest {
        // Build a backup blob with version=999 by encrypting a hand-crafted JSON.
        val pass = "v2-test".toCharArray()
        val salt = DesktopCryptoUtils.generateSalt(32)
        val key = DesktopCryptoUtils.deriveKeyFromPassphrase(pass.copyOf(), salt)
        val json = """{"version":999,"exportedAt":0,"hosts":[],"sshKeys":[],"tunnels":[],"credentials":{},"privateKeys":{}}"""
        val encrypted = DesktopCryptoUtils.encryptAesGcm(json.toByteArray(Charsets.UTF_8), key)
        val blob = byteArrayOf(0, 0, 0, 32) + salt + encrypted

        val importer = emptyImporter()
        val result = importer.import(blob, pass)
        val err = result.exceptionOrNull()
        assertIs<VaultImportError.UnsupportedVersion>(err)
        assertEquals(999, err.version)
    }
}
