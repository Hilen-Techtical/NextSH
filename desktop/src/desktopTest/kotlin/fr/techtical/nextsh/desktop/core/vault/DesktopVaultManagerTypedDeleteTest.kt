// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Typed deletions of the real [DesktopVaultManager] (encrypted file in a per-test
 * temp dir via [VaultPaths.withBaseDir]). One raw id can hold a password, a private
 * key, a certificate and a passphrase at once (a host switched from key to password
 * keeps the key id as its credentialId): deleting one type must never touch the others.
 */
class DesktopVaultManagerTypedDeleteTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-vault-typed-delete-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** Fresh unlocked vault with the four types stored under the same id. */
    private fun vaultWithEveryTypeUnder(id: String): DesktopVaultManager {
        val pm = VaultPinManager()
        pm.setupNewVault("typed-delete-pin".toCharArray()).getOrThrow()
        val vault = DesktopVaultManager(pm)
        runBlocking {
            vault.storePassword(id, "s3cr3t".toCharArray())
            vault.storePrivateKey(id, "PEM")
            vault.storeCertificate(id, "CERT")
            vault.storeKeyPassphrase(id, "passphrase".toCharArray())
        }
        return vault
    }

    private fun DesktopVaultManager.remainingTypesUnder(id: String): Set<String> = runBlocking {
        buildSet {
            if (getPassword(id) != null) add("pwd")
            if (getPrivateKey(id) != null) add("key")
            if (getCertificate(id) != null) add("cert")
            if (getKeyPassphrase(id) != null) add("keypass")
        }
    }

    @Test
    fun `deletePassword removes only the password`() {
        VaultPaths.withBaseDir(tempDir) {
            val vault = vaultWithEveryTypeUnder("X")

            runBlocking { vault.deletePassword("X") }

            assertEquals(setOf("key", "cert", "keypass"), vault.remainingTypesUnder("X"))
        }
    }

    @Test
    fun `deletePrivateKey removes only the private key`() {
        VaultPaths.withBaseDir(tempDir) {
            val vault = vaultWithEveryTypeUnder("X")

            runBlocking { vault.deletePrivateKey("X") }

            assertEquals(setOf("pwd", "cert", "keypass"), vault.remainingTypesUnder("X"))
        }
    }

    @Test
    fun `typed deletes leave other ids alone and tolerate a missing entry`() {
        VaultPaths.withBaseDir(tempDir) {
            val vault = vaultWithEveryTypeUnder("X")
            runBlocking {
                vault.storePassword("Y", "other".toCharArray())
                vault.storePrivateKey("Y", "OTHER-PEM")

                vault.deletePassword("unknown")
                vault.deletePrivateKey("unknown")
                vault.deletePassword("X")
                vault.deletePrivateKey("X")
            }

            assertEquals(setOf("pwd", "key"), vault.remainingTypesUnder("Y"))
            assertNull(runBlocking { vault.getPrivateKey("X") })
            assertEquals(setOf("cert", "keypass"), vault.remainingTypesUnder("X"))
        }
    }
}
