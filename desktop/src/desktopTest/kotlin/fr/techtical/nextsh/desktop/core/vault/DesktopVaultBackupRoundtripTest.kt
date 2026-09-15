// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export + re-import roundtrip test.
 *
 * Asserts:
 * - every host/key/tunnel survives encoding + decoding unchanged
 * - credentials and private keys are written back to the target vault
 * - the import summary reports the correct counts
 */
class DesktopVaultBackupRoundtripTest {

    private val sampleHost = Host(
        id = "h1",
        label = "prod-web",
        hostname = "10.0.0.1",
        port = 2222,
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred1",
        group = "prod",
        keepAliveSeconds = 60,
        autoReconnect = false,
        terminalTheme = "DRACULA",
        fido2Mode = null,
        isFavorite = true,
    )
    private val sampleKey = SshKey(
        id = "k1",
        label = "Ed25519 perso",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test@host",
        isBiometric = false,
        keystoreAlias = null,
    )
    private val sampleTunnel = TunnelConfig(
        id = "t1",
        label = "postgres",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 5432,
        remoteHost = "db.internal",
        remotePort = 5432,
        autoStart = true,
        openBrowserOnConnect = false,
        keepAliveAfterBrowserClose = true,
        isFavorite = false,
    )

    @Test
    fun `roundtrip preserves hosts, keys, tunnels, credentials and private keys`() = runTest {
        // ── source (pre-export) ──
        val srcHosts = FakeHostRepositoryMem(listOf(sampleHost))
        val srcKeys = FakeSshKeyRepositoryMem(listOf(sampleKey))
        val srcTunnels = FakeTunnelRepositoryMem(listOf(sampleTunnel))
        val srcVault = FakeVaultManagerMem().apply {
            storePassword("cred1", "supers3cret".toCharArray())
            storePrivateKey("k1", "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----\n")
        }

        val exporter = DesktopVaultExporter(srcVault, srcKeys, srcHosts, srcTunnels)

        val passphrase = "my-backup-pass".toCharArray()
        val blob = exporter.export(passphrase)
        assertTrue(blob.isNotEmpty(), "export must produce some bytes")
        // Passphrase must be wiped by the exporter.
        assertContentEquals(CharArray(passphrase.size) { '\u0000' }, passphrase)

        // ── destination (post-import, empty repos) ──
        val dstHosts = FakeHostRepositoryMem()
        val dstKeys = FakeSshKeyRepositoryMem()
        val dstTunnels = FakeTunnelRepositoryMem()
        val dstVault = FakeVaultManagerMem()

        val importer = DesktopVaultImporter(dstVault, dstKeys, dstHosts, dstTunnels)
        val restorePass = "my-backup-pass".toCharArray()
        val result = importer.import(blob, restorePass)

        val summary = result.getOrNull() ?: error("import failed: ${result.exceptionOrNull()}")
        assertEquals(1, summary.hosts)
        assertEquals(1, summary.sshKeys)
        assertEquals(1, summary.tunnels)
        assertEquals(1, summary.credentials)
        assertEquals(1, summary.privateKeys)
        assertEquals(5, summary.total)

        // Contents preserved.
        assertEquals(sampleHost, dstHosts.getById("h1"))
        assertEquals(sampleKey, dstKeys.getById("k1"))
        assertEquals(sampleTunnel, dstTunnels.getById("t1"))

        val restoredPwd = dstVault.getPassword("cred1")
        assertContentEquals("supers3cret".toCharArray(), restoredPwd)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----\n",
            dstVault.getPrivateKey("k1"),
        )

        // Import passphrase wiped too.
        assertContentEquals(CharArray(restorePass.size) { '\u0000' }, restorePass)
    }

    @Test
    fun `LAN sync secret is excluded from backup export`() = runTest {
        val srcVault = FakeVaultManagerMem().apply {
            storePassword("cred1", "supers3cret".toCharArray())
            // A device-local LAN sync secret living in the pwd_ namespace.
            storePassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1", "lan-auth-key".toCharArray())
        }
        val exporter = DesktopVaultExporter(
            srcVault,
            FakeSshKeyRepositoryMem(),
            FakeHostRepositoryMem(listOf(sampleHost)),
            FakeTunnelRepositoryMem(),
        )
        val blob = exporter.export("backup-pass".toCharArray())

        // Roundtrip into a fresh vault: the LAN secret must not be carried by the
        // backup, while the real host password must survive.
        val dstVault = FakeVaultManagerMem()
        val importer = DesktopVaultImporter(
            dstVault,
            FakeSshKeyRepositoryMem(),
            FakeHostRepositoryMem(),
            FakeTunnelRepositoryMem(),
        )
        val summary = importer.import(blob, "backup-pass".toCharArray()).getOrNull()
            ?: error("import failed")

        assertEquals(1, summary.credentials, "only the real host password is backed up")
        assertContentEquals("supers3cret".toCharArray(), dstVault.getPassword("cred1"))
        assertNull(
            dstVault.getPassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"),
            "LAN sync secret must never leave the device via a backup",
        )
    }

    @Test
    fun `import skips a LAN sync secret carried by a legacy backup`() = runTest {
        // Hand-build a pre-fix backup blob that still carries a LAN secret next to a
        // real host password, in the exact on-disk format the exporter produces.
        val legacy = VaultBackup(
            exportedAt = 0L,
            hosts = emptyList(),
            sshKeys = emptyList(),
            tunnels = emptyList(),
            credentials = mapOf(
                "cred1" to "supers3cret",
                "${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1" to "lan-auth-key",
            ),
            privateKeys = emptyMap(),
        )
        val json = Json.encodeToString(legacy).toByteArray(Charsets.UTF_8)
        val salt = DesktopCryptoUtils.generateSalt(32)
        val key = DesktopCryptoUtils.deriveKeyFromPassphrase("legacy-pass".toCharArray(), salt)
        val encrypted = DesktopCryptoUtils.encryptAesGcm(json, key)
        val saltLen = byteArrayOf(
            (salt.size shr 24 and 0xFF).toByte(),
            (salt.size shr 16 and 0xFF).toByte(),
            (salt.size shr 8 and 0xFF).toByte(),
            (salt.size and 0xFF).toByte(),
        )
        val blob = saltLen + salt + encrypted

        val dstVault = FakeVaultManagerMem()
        val importer = DesktopVaultImporter(
            dstVault,
            FakeSshKeyRepositoryMem(),
            FakeHostRepositoryMem(),
            FakeTunnelRepositoryMem(),
        )
        val summary = importer.import(blob, "legacy-pass".toCharArray()).getOrNull()
            ?: error("import failed")

        assertContentEquals("supers3cret".toCharArray(), dstVault.getPassword("cred1"))
        assertNull(
            dstVault.getPassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"),
            "a LAN secret in a legacy backup must not be restored",
        )
        assertEquals(1, summary.credentials, "summary counts only the restored host password")
    }

    @Test
    fun `backup is not produced as plaintext JSON`() = runTest {
        val hosts = FakeHostRepositoryMem(listOf(sampleHost))
        val exporter = DesktopVaultExporter(
            FakeVaultManagerMem(),
            FakeSshKeyRepositoryMem(),
            hosts,
            FakeTunnelRepositoryMem(),
        )
        val blob = exporter.export("passphrase-not-weak".toCharArray())
        val asString = blob.toString(Charsets.ISO_8859_1)
        // The label and hostname must NOT appear in cleartext in the encrypted blob.
        assertTrue(!asString.contains("prod-web"), "plaintext leaked in exported blob")
        assertTrue(!asString.contains("10.0.0.1"), "plaintext leaked in exported blob")
    }
}
