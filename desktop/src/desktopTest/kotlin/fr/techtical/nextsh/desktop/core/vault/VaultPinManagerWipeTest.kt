// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VaultPinManager.wipe]: both vault files are deleted, and a file that stays on disk makes the wipe fail
 * instead of reporting a done wipe while the encrypted vault is still there.
 */
class VaultPinManagerWipeTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-vault-wipe-pin-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `wipe deletes the two vault files`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPaths.metaFile.parentFile?.mkdirs()
            VaultPaths.metaFile.writeText("{}")
            VaultPaths.dataFile.writeText("x")

            VaultPinManager().wipe()

            assertFalse(VaultPaths.metaFile.exists())
            assertFalse(VaultPaths.dataFile.exists())
        }
    }

    @Test
    fun `wipe with nothing on disk is fine`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPinManager().wipe()
        }
    }

    @Test
    fun `a vault file that cannot be deleted makes the wipe fail, the other one is still deleted`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPaths.metaFile.parentFile?.mkdirs()
            VaultPaths.metaFile.writeText("{}")
            // A non-empty directory under the name of the data file: delete() returns false on it.
            VaultPaths.dataFile.mkdirs()
            File(VaultPaths.dataFile, "child").writeText("x")

            assertFailsWith<IOException> { VaultPinManager().wipe() }

            assertFalse(VaultPaths.metaFile.exists(), "the file that can be deleted is deleted")
            assertTrue(VaultPaths.dataFile.exists())
        }
    }

    @Test
    fun `the temporary siblings of a killed write are deleted too`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPaths.metaFile.parentFile?.mkdirs()
            val metaTmp = File(VaultPaths.metaFile.parentFile, VaultPaths.metaFile.name + ".tmp").apply { writeText("wrapped dek") }
            val dataTmp = File(VaultPaths.dataFile.parentFile, VaultPaths.dataFile.name + ".tmp").apply { writeText("ciphertext") }

            VaultPinManager().wipe()

            assertFalse(metaTmp.exists())
            assertFalse(dataTmp.exists())
        }
    }

    @Test
    fun `a leftover vault dat alone counts as a vault that is not gone`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPaths.dataFile.parentFile?.mkdirs()
            val manager = VaultPinManager()
            assertFalse(manager.hasVaultFiles())

            VaultPaths.dataFile.writeText("ciphertext")

            assertTrue(manager.hasVaultFiles())
            assertFalse(manager.isSetup(), "vault.meta alone decides isSetup, which is why hasVaultFiles exists")
        }
    }

    @Test
    fun `creating a vault deletes an orphan vault dat and its temporary file`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPaths.dataFile.parentFile?.mkdirs()
            VaultPaths.dataFile.writeText("old ciphertext under a lost DEK")
            val dataTmp = File(VaultPaths.dataFile.parentFile, VaultPaths.dataFile.name + ".tmp").apply { writeText("x") }

            val result = VaultPinManager().setupNewVault("a-long-enough-pin".toCharArray())

            assertTrue(result.isSuccess)
            assertFalse(dataTmp.exists())
            assertFalse(VaultPaths.dataFile.exists(), "the new vault does not read the old data")
        }
    }

    @Test
    fun `creating a vault is refused when an orphan vault dat cannot be deleted`() {
        VaultPaths.withBaseDir(tempDir) {
            VaultPaths.dataFile.mkdirs()
            File(VaultPaths.dataFile, "child").writeText("x")

            val result = VaultPinManager().setupNewVault("a-long-enough-pin".toCharArray())

            assertTrue(result.isFailure)
            assertFalse(VaultPaths.metaFile.exists(), "no vault.meta is written on top of the orphan")
        }
    }
}
