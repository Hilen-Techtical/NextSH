// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies that [FakeVaultManagerMem.deleteCertificate] (which mirrors the
 * [DesktopVaultManager] implementation) removes only the certificate entry
 * without touching the other credential types stored under the same id.
 *
 * We test through [FakeVaultManagerMem] because [DesktopVaultManager] writes
 * to a file path derived from the NEXTSH_HOME environment variable, which cannot
 * be overridden in-process on the JVM. The fake reproduces the exact same
 * per-prefix key logic, so these tests cover the contract and the delete
 * behaviour. The crypto layer is exercised separately in the backup roundtrip tests.
 */
class DesktopVaultManagerDeleteCertificateTest {

    @Test
    fun `deleteCertificate removes only the cert entry, leaving password, privateKey and keyPassphrase intact`() =
        runTest {
            val vault = FakeVaultManagerMem()
            val id = "shared-key-id"
            vault.storePassword(id, "s3cr3t".toCharArray())
            vault.storePrivateKey(id, "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----\n")
            vault.storeCertificate(id, "-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----\n")
            vault.storeKeyPassphrase(id, "my-passphrase".toCharArray())

            vault.deleteCertificate(id)

            assertNull(vault.getCertificate(id), "cert must be gone")
            assertNotNull(vault.getPassword(id), "password must remain")
            assertNotNull(vault.getPrivateKey(id), "private key must remain")
            assertNotNull(vault.getKeyPassphrase(id), "key passphrase must remain")
        }

    @Test
    fun `deleteCertificate on unknown id is idempotent and does not disturb other entries`() =
        runTest {
            val vault = FakeVaultManagerMem()
            vault.storePassword("other-id", "pwd".toCharArray())

            vault.deleteCertificate("non-existent-id")

            assertNotNull(vault.getPassword("other-id"), "unrelated entry must not be touched")
        }

    @Test
    fun `deleteCertificate removes only the targeted cert, leaving other certs intact`() =
        runTest {
            val vault = FakeVaultManagerMem()
            vault.storeCertificate("cert1", "CERT_CONTENT_1")
            vault.storeCertificate("cert2", "CERT_CONTENT_2")

            vault.deleteCertificate("cert1")

            assertNull(vault.getCertificate("cert1"), "cert1 must be gone")
            assertEquals("CERT_CONTENT_2", vault.getCertificate("cert2"), "cert2 must remain")
        }
}
