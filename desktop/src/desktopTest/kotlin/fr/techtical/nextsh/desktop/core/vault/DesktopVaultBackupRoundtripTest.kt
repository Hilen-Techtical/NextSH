// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.shared.core.backup.BackupCommonSettings
import fr.techtical.nextsh.shared.core.backup.BackupDesktopSettings
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A FROZEN copy of the DTO that the 1.0.x writers used (no `version` key is ever written, because 1 is the
 * default value and the default Json does not encode defaults). Used to hand-build legacy files.
 */
@Serializable
private data class LegacyVaultBackupV1(
    val version: Int = 1,
    val exportedAt: Long,
    val hosts: List<Host>,
    val sshKeys: List<SshKey>,
    val tunnels: List<TunnelConfig>,
    val credentials: Map<String, String>,
    val privateKeys: Map<String, String>,
    val keyPassphrases: Map<String, String> = emptyMap(),
)

/**
 * Export + re-import roundtrip test, on the format version 2 shared with Android.
 *
 * Asserts:
 * - every section survives encoding + decoding unchanged (the core and the sections new in version 2)
 * - credentials, private keys, passphrases and certificates are written back to the target vault
 * - the import report reports the correct counts
 * - the file says `"version":2`, never carries the LAN secret
 *
 * The compatibility with files written by the other platform is in [DesktopBackupFixturesTest].
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
        // Non-default path: must survive the encrypted export and the re-import.
        browserPath = "/admin?tab=2#top",
    )
    private val sampleSnippet = backupSnippet("s1", "Restart nginx", command = "sudo systemctl restart nginx\n\tsudo nginx -t")
    private val sampleTheme = backupTheme(THEME_ID, "Night")

    @Test
    fun `roundtrip preserves every section of the format`() = runTest {
        // ── source (pre-export) ──
        val srcVault = FakeVaultManagerMem().apply {
            storePassword("cred1", "supers3cret".toCharArray())
            storePrivateKey("k1", "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----\n")
            storeKeyPassphrase("k1", "key-pass".toCharArray())
            storeCertificate("k1", "ssh-ed25519-cert-v01@openssh.com AAAA fixture")
        }
        val local = KnownHostPortable.fromPortable("ssh-ed25519", ED25519_BLOB) ?: error("fixture key unreadable")
        val srcKnownHosts = inMemoryKnownHostsStore().also { it.upsert("ed.example.test:22", local.algorithm, local.encoded) }
        val srcSettings = FakeBackupSettings(
            BackupSettings(
                common = BackupCommonSettings(connectionTimeoutSeconds = 15),
                desktop = BackupDesktopSettings(terminalFontSizePx = 16, language = "FR"),
            ),
        )
        val exporter = exporterOver(
            vault = srcVault,
            sshKeys = FakeSshKeyRepositoryMem(listOf(sampleKey)),
            hosts = FakeHostRepositoryMem(listOf(sampleHost)),
            tunnels = FakeTunnelRepositoryMem(listOf(sampleTunnel)),
            snippets = FakeSnippetRepositoryMem(listOf(sampleSnippet)),
            customThemes = FakeCustomThemeRepositoryMem(listOf(sampleTheme)),
            knownHosts = srcKnownHosts,
            settings = srcSettings,
        )

        val passphrase = "my-backup-pass".toCharArray()
        val blob = exporter.export(passphrase)
        assertTrue(blob.isNotEmpty(), "export must produce some bytes")
        // Passphrase must be wiped by the exporter.
        assertContentEquals(CharArray(passphrase.size) { '\u0000' }, passphrase)

        // ── destination (post-import, empty repos) ──
        val dstHosts = FakeHostRepositoryMem()
        val dstKeys = FakeSshKeyRepositoryMem()
        val dstTunnels = FakeTunnelRepositoryMem()
        val dstSnippets = FakeSnippetRepositoryMem()
        val dstThemes = FakeCustomThemeRepositoryMem()
        val dstVault = FakeVaultManagerMem()
        val dstSettings = FakeBackupSettings()
        val store = RepositoryVaultRestoreStore(dstKeys, dstHosts, dstTunnels, dstSnippets, dstThemes)
        val importer = DesktopVaultImporter(dstVault, store, dstSettings)
        val restorePass = "my-backup-pass".toCharArray()

        val result = importer.import(blob, restorePass)

        val report = result.getOrNull() ?: error("import failed: ${result.exceptionOrNull()}")
        assertEquals(1, report.hosts)
        assertEquals(1, report.sshKeys)
        assertEquals(1, report.tunnels)
        assertEquals(1, report.passwords)
        assertEquals(1, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(1, report.certificates)
        assertEquals(1, report.snippets)
        assertEquals(1, report.customThemes)
        assertEquals(1, report.knownHostsAdded)
        assertEquals(3, report.settingsApplied, "1 common + 2 desktop values")
        assertTrue(report.languageChanged)
        assertEquals(0, report.invalidIgnored)
        assertFalse(report.newerDataSkipped)

        // Contents preserved.
        assertEquals(sampleHost, dstHosts.getById("h1"))
        assertEquals(sampleKey, dstKeys.getById("k1"))
        assertEquals(sampleTunnel, dstTunnels.getById("t1"))
        assertEquals(sampleSnippet, dstSnippets.getById("s1"))
        assertEquals(sampleTheme, dstThemes.getById(THEME_ID))
        assertContentEquals("supers3cret".toCharArray(), dstVault.getPassword("cred1"))
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----\n",
            dstVault.getPrivateKey("k1"),
        )
        assertContentEquals("key-pass".toCharArray(), dstVault.getKeyPassphrase("k1"))
        assertEquals("ssh-ed25519-cert-v01@openssh.com AAAA fixture", dstVault.getCertificate("k1"))

        // The known host travelled as the key SSHJ itself rebuilds from the blob.
        val received = store.receivedKnownHosts.single()
        assertEquals("ed.example.test:22", received.hostPort)
        assertEquals(local.algorithm, received.algorithm)
        assertContentEquals(local.encoded, received.publicKeyEncoded)

        // The settings handed to the preferences are the ones that were exported.
        assertEquals(srcSettings.snapshotValue, dstSettings.applied)

        // Import passphrase wiped too.
        assertContentEquals(CharArray(restorePass.size) { '\u0000' }, restorePass)
    }

    @Test
    fun `the exported file says version 2 and carries the Desktop producer`() = runTest {
        val exporter = exporterOver(vault = FakeVaultManagerMem(), hosts = FakeHostRepositoryMem(listOf(sampleHost)))

        val json = decryptBackupJson(exporter.export("backup-pass".toCharArray()), "backup-pass")

        assertTrue(json.contains("\"version\":2"), "1.0.x readers must see version 2 and refuse the file")
        assertTrue(json.contains("\"platform\":\"DESKTOP\""))
        assertFalse(json.contains("\"android\""), "only the section of the own platform is written")
    }

    @Test
    fun `an export of an empty application restores as an empty report`() = runTest {
        val blob = exporterOver(vault = FakeVaultManagerMem()).export("backup-pass".toCharArray())

        val report = importerOver(
            FakeVaultManagerMem(), FakeSshKeyRepositoryMem(), FakeHostRepositoryMem(), FakeTunnelRepositoryMem(),
        ).import(blob, "backup-pass".toCharArray()).getOrNull() ?: error("import failed")

        assertTrue(report.isEmpty)
    }

    @Test
    fun `entry counts cover every section`() = runTest {
        val local = KnownHostPortable.fromPortable("ssh-ed25519", ED25519_BLOB) ?: error("fixture key unreadable")
        val knownHosts = inMemoryKnownHostsStore().also { it.upsert("ed.example.test:22", local.algorithm, local.encoded) }
        val vault = FakeVaultManagerMem().apply {
            storePassword("cred1", "p".toCharArray())
            storePassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1", "lan".toCharArray())
            storePrivateKey("k1", "pem")
            storeKeyPassphrase("k1", "pp".toCharArray())
            storeCertificate("k1", "cert")
        }
        val exporter = exporterOver(
            vault = vault,
            hosts = FakeHostRepositoryMem(listOf(sampleHost)),
            snippets = FakeSnippetRepositoryMem(listOf(sampleSnippet)),
            customThemes = FakeCustomThemeRepositoryMem(listOf(sampleTheme)),
            knownHosts = knownHosts,
        )

        val counts = exporter.entryCountSummary()

        assertEquals(
            BackupCounts(
                hosts = 1, sshKeys = 0, tunnels = 0, credentials = 1, privateKeys = 1,
                keyPassphrases = 1, certificates = 1, snippets = 1, customThemes = 1, knownHosts = 1,
            ),
            counts,
        )
        assertEquals(8, counts.total)
    }

    @Test
    fun `entry counts name the snippets and themes that an import would refuse, without blocking the export`() = runTest {
        // U+200B (zero width space) hides in a label: the import refuses it, the export still writes it.
        val sneaky = backupSnippet("s-bad", "Rest" + Char(0x200B) + "art")
        val exporter = exporterOver(
            vault = FakeVaultManagerMem(),
            snippets = FakeSnippetRepositoryMem(listOf(sampleSnippet, sneaky)),
            customThemes = FakeCustomThemeRepositoryMem(listOf(sampleTheme, backupTheme("not-a-uuid", "Bad id"))),
        )

        val counts = exporter.entryCountSummary()

        assertEquals(2, counts.snippets)
        assertEquals(2, counts.customThemes)
        assertEquals(2, counts.notRestorable)
        assertEquals(4, counts.total, "notRestorable is not part of the total")
        assertTrue(exporter.export("backup-pass".toCharArray()).isNotEmpty())
    }

    @Test
    fun `entry counts name a host that an import would refuse`() = runTest {
        val bad = sampleHost.copy(id = "h-bad", hostname = "my host")
        val exporter = exporterOver(
            vault = FakeVaultManagerMem(),
            hosts = FakeHostRepositoryMem(listOf(sampleHost, bad)),
        )

        val counts = exporter.entryCountSummary()

        assertEquals(2, counts.hosts)
        assertEquals(1, counts.notRestorable)
        assertTrue(exporter.export("backup-pass".toCharArray()).isNotEmpty())
    }

    @Test
    fun `an SSH key with a multi-line public key is restorable, its line is cleaned`() = runTest {
        val bad = sampleKey.copy(id = "k-bad", publicKey = "ssh-ed25519 AAAA\nBBBB")
        val exporter = exporterOver(
            vault = FakeVaultManagerMem(),
            sshKeys = FakeSshKeyRepositoryMem(listOf(sampleKey, bad)),
        )

        val counts = exporter.entryCountSummary()

        assertEquals(2, counts.sshKeys)
        assertEquals(0, counts.notRestorable)
    }

    @Test
    fun `entry counts name a tunnel with an invalid port`() = runTest {
        val bad = sampleTunnel.copy(id = "t-bad", localPort = 0)
        val exporter = exporterOver(
            vault = FakeVaultManagerMem(),
            hosts = FakeHostRepositoryMem(listOf(sampleHost)),
            tunnels = FakeTunnelRepositoryMem(listOf(sampleTunnel, bad)),
        )

        val counts = exporter.entryCountSummary()

        assertEquals(2, counts.tunnels)
        assertEquals(1, counts.notRestorable)
    }

    @Test
    fun `entry counts name a secret over the size bound`() = runTest {
        val vault = FakeVaultManagerMem().apply {
            storePassword("cred1", "ok".toCharArray())
            storePassword("cred-huge", CharArray(64 * 1024 + 1) { 'x' })
        }
        val exporter = exporterOver(vault = vault, hosts = FakeHostRepositoryMem(listOf(sampleHost)))

        val counts = exporter.exportWithSummary("backup-pass".toCharArray()).counts

        assertEquals(2, counts.credentials)
        assertEquals(1, counts.notRestorable)
        // The summary alone never reads a secret: it counts ids only, so it cannot see the oversized value.
        assertEquals(2, exporter.entryCountSummary().credentials)
        assertEquals(0, exporter.entryCountSummary().notRestorable)
    }

    @Test
    fun `the export result counts the entries of the file it just wrote`() = runTest {
        val vault = FakeVaultManagerMem().apply { storePassword("cred1", "p".toCharArray()) }
        val exporter = exporterOver(
            vault = vault,
            hosts = FakeHostRepositoryMem(listOf(sampleHost, sampleHost.copy(id = "h-bad", hostname = "a b"))),
        )

        val result = exporter.exportWithSummary("backup-pass".toCharArray())

        assertTrue(result.bytes.isNotEmpty())
        assertEquals(2, result.counts.hosts)
        assertEquals(1, result.counts.credentials)
        assertEquals(1, result.counts.notRestorable)
    }

    @Test
    fun `a tunnel that is invalid and whose host is refused too is counted once per entry`() = runTest {
        val badHost = sampleHost.copy(id = "h-bad", hostname = "a b")
        // Refused on its own AND cascaded from the refused host: still one entry, counted once.
        val badTunnel = sampleTunnel.copy(id = "t-bad", hostId = "h-bad", localPort = 0)
        val cascaded = sampleTunnel.copy(id = "t-cascade", hostId = "h-bad")
        val exporter = exporterOver(
            vault = FakeVaultManagerMem(),
            hosts = FakeHostRepositoryMem(listOf(sampleHost, badHost)),
            tunnels = FakeTunnelRepositoryMem(listOf(sampleTunnel, badTunnel, cascaded)),
        )

        val counts = exporter.entryCountSummary()

        assertEquals(3, counts.notRestorable, "1 host + 2 tunnels")
    }

    @Test
    fun `LAN sync secret is excluded from backup export`() = runTest {
        val srcVault = FakeVaultManagerMem().apply {
            storePassword("cred1", "supers3cret".toCharArray())
            // A device-local LAN sync secret living in the pwd_ namespace.
            storePassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1", "lan-auth-key".toCharArray())
        }
        val exporter = exporterOver(vault = srcVault, hosts = FakeHostRepositoryMem(listOf(sampleHost)))
        val blob = exporter.export("backup-pass".toCharArray())

        // The clear JSON must not contain it...
        val json = decryptBackupJson(blob, "backup-pass")
        assertFalse(json.contains("lan-auth-key"), "LAN sync secret must never be written into a backup")
        assertFalse(json.contains(LAN_SYNC_SECRET_KEY_PREFIX))

        // ...and a roundtrip into a fresh vault keeps the real host password only.
        val dstVault = FakeVaultManagerMem()
        val importer = importerOver(
            dstVault,
            FakeSshKeyRepositoryMem(),
            FakeHostRepositoryMem(),
            FakeTunnelRepositoryMem(),
        )
        val report = importer.import(blob, "backup-pass".toCharArray()).getOrNull()
            ?: error("import failed")

        assertEquals(1, report.passwords, "only the real host password is backed up")
        assertContentEquals("supers3cret".toCharArray(), dstVault.getPassword("cred1"))
        assertNull(
            dstVault.getPassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"),
            "LAN sync secret must never leave the device via a backup",
        )
    }

    @Test
    fun `import skips a LAN sync secret carried by a legacy backup`() = runTest {
        // Hand-build a pre-fix backup blob that still carries a LAN secret next to a
        // real host password, in the exact on-disk format of the 1.0.x writers.
        val legacy = LegacyVaultBackupV1(
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
        val blob = encryptBackupJson(Json.encodeToString(legacy), "legacy-pass")

        val dstVault = FakeVaultManagerMem()
        val importer = importerOver(
            dstVault,
            FakeSshKeyRepositoryMem(),
            FakeHostRepositoryMem(),
            FakeTunnelRepositoryMem(),
        )
        val report = importer.import(blob, "legacy-pass".toCharArray()).getOrNull()
            ?: error("import failed")

        assertContentEquals("supers3cret".toCharArray(), dstVault.getPassword("cred1"))
        assertNull(
            dstVault.getPassword("${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"),
            "a LAN secret in a legacy backup must not be restored",
        )
        assertEquals(1, report.passwords, "report counts only the restored host password")
    }

    @Test
    fun `a legacy file without the version key is read as version 1 and restores its core`() = runTest {
        val legacy = LegacyVaultBackupV1(
            exportedAt = 0L,
            hosts = listOf(sampleHost),
            sshKeys = listOf(sampleKey),
            tunnels = emptyList(),
            credentials = mapOf("cred1" to "supers3cret"),
            privateKeys = emptyMap(),
        )
        val json = Json.encodeToString(legacy)
        assertFalse(json.contains("\"version\""), "1.0.x never wrote the key")
        val blob = encryptBackupJson(json, "legacy-pass")

        val dstHosts = FakeHostRepositoryMem()
        val settings = FakeBackupSettings()
        val importer = importerOver(
            FakeVaultManagerMem(), FakeSshKeyRepositoryMem(), dstHosts, FakeTunnelRepositoryMem(), settings = settings,
        )
        val report = importer.import(blob, "legacy-pass".toCharArray()).getOrNull() ?: error("import failed")

        assertEquals(1, report.hosts)
        assertEquals(1, report.sshKeys)
        assertEquals(sampleHost, dstHosts.getById("h1"))
        assertEquals(0, report.settingsApplied)
        assertNull(settings.applied, "a file without settings hands nothing to the preferences")
    }

    @Test
    fun `import of a backup written before browserPath existed falls back to the root path`() = runTest {
        // A backup from a version without the field carries no browserPath key. The default
        // Json does not encode default values, so encoding a default-path tunnel gives that shape.
        // The host travels with its tunnel: a tunnel whose host is nowhere is skipped by the importer.
        val legacy = LegacyVaultBackupV1(
            exportedAt = 0L,
            hosts = listOf(sampleHost),
            sshKeys = emptyList(),
            tunnels = listOf(sampleTunnel.copy(browserPath = TunnelBrowserPath.DEFAULT)),
            credentials = emptyMap(),
            privateKeys = emptyMap(),
        )
        val json = Json.encodeToString(legacy)
        assertFalse(json.contains("browserPath"), "the legacy payload must not carry the field")
        val blob = encryptBackupJson(json, "legacy-pass")

        val dstTunnels = FakeTunnelRepositoryMem()
        val importer = importerOver(
            FakeVaultManagerMem(),
            FakeSshKeyRepositoryMem(),
            FakeHostRepositoryMem(),
            dstTunnels,
        )
        importer.import(blob, "legacy-pass".toCharArray()).getOrNull() ?: error("import failed")

        assertEquals("/", dstTunnels.getById("t1")?.browserPath)
    }

    @Test
    fun `backup is not produced as plaintext JSON`() = runTest {
        val hosts = FakeHostRepositoryMem(listOf(sampleHost))
        val exporter = exporterOver(vault = FakeVaultManagerMem(), hosts = hosts)
        val blob = exporter.export("passphrase-not-weak".toCharArray())
        val asString = blob.toString(Charsets.ISO_8859_1)
        // The label and hostname must NOT appear in cleartext in the encrypted blob.
        assertTrue(!asString.contains("prod-web"), "plaintext leaked in exported blob")
        assertTrue(!asString.contains("10.0.0.1"), "plaintext leaked in exported blob")
    }

    private companion object {
        const val THEME_ID = "7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d"

        /** Base64 SSH blob of the Ed25519 public key of the shared fixtures (a public key, no private part exists). */
        const val ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi"
    }
}
