// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.ssh.StoredKnownHost
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.core.backup.BackupReportWarning
import fr.techtical.nextsh.shared.core.backup.BackupRestoreStep
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Edge-case tests for the importer: size cap, malformed headers, wrong passphrase, version check,
 * validation before any write, errors without content. The happy-path roundtrip is covered in
 * [DesktopVaultBackupRoundtripTest].
 */
class DesktopVaultImporterTest {

    private fun emptyImporter(): DesktopVaultImporter = importerOver(
        FakeVaultManagerMem(),
        FakeSshKeyRepositoryMem(),
        FakeHostRepositoryMem(),
        FakeTunnelRepositoryMem(),
    )

    /** A document with the v1 core empty, plus [extra] (already formatted as `,"key":value`). */
    private fun json(version: String? = "2", extra: String = ""): String {
        val versionPart = version?.let { "\"version\":$it," } ?: ""
        return "{$versionPart\"exportedAt\":0,\"hosts\":[],\"sshKeys\":[],\"tunnels\":[]," +
            "\"credentials\":{},\"privateKeys\":{}$extra}"
    }

    private fun snippetJson(id: String, label: String = "label", command: String = "uptime") =
        """{"id":"$id","label":"$label","command":"$command","createdAt":1}"""

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
        val blob = exporterOver(FakeVaultManagerMem(), hosts = FakeHostRepositoryMem()).export("correct-pass".toCharArray())

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
    fun `a cancelled import is rethrown, not turned into an IoError, and the passphrase is still wiped`() {
        val vault = FakeVaultManagerMem()
        val cancelling = object : VaultRestoreStore {
            override suspend fun restore(
                hosts: List<Host>,
                sshKeys: List<SshKey>,
                tunnels: List<TunnelConfig>,
                snippets: List<Snippet>,
                customThemes: List<CustomTerminalTheme>,
                knownHosts: List<StoredKnownHost>,
            ): VaultRestoreResult = throw CancellationException("scope cancelled")
        }
        val importer = DesktopVaultImporter(vault, cancelling, FakeBackupSettings())
        val passphrase = RESTORE_TEST_PASSPHRASE.toCharArray()

        runBlocking {
            val blob = buildBackupBlob(
                hosts = listOf(backupHost("h1", label = "Host")),
                credentials = mapOf("cred-h1" to "s3cret"),
            )
            assertFailsWith<CancellationException> { importer.import(blob, passphrase) }
        }

        assertTrue(passphrase.all { it == '\u0000' }, "the passphrase must be wiped even when the import is cancelled")
        assertEquals(emptySet<String>(), vault.passwords.keys, "nothing reaches the vault after the cancellation")
    }

    @Test
    fun `rejects unsupported version 999`() = runTest {
        val blob = encryptBackupJson(json(version = "999"), "v2-test")

        val err = emptyImporter().import(blob, "v2-test".toCharArray()).exceptionOrNull()

        assertIs<VaultImportError.UnsupportedVersion>(err)
        assertEquals(999, err.version)
    }

    @Test
    fun `rejects version 3 without writing anything, whatever the sections look like`() = runTest {
        // The version is read before the sections are decoded: a version 3 may have another structure.
        val v3 = """{"version":3,"exportedAt":0,"hosts":"another-structure","payload":{"a":1}}"""
        val vault = FakeVaultManagerMem()
        val hosts = FakeHostRepositoryMem()
        val importer = importerOver(vault, FakeSshKeyRepositoryMem(), hosts, FakeTunnelRepositoryMem())

        val err = importer.import(encryptBackupJson(v3, "v3-pass"), "v3-pass".toCharArray()).exceptionOrNull()

        assertIs<VaultImportError.UnsupportedVersion>(err)
        assertEquals(3, err.version)
        assertTrue(vault.passwords.isEmpty() && hosts.observeAllSnapshot().isEmpty())
    }

    @Test
    fun `version 0 and a negative version are Malformed, not unsupported`() = runTest {
        for (version in listOf("0", "-1", "\"2\"")) {
            val err = emptyImporter().import(
                encryptBackupJson(json(version = version), "pass-1234"), "pass-1234".toCharArray(),
            ).exceptionOrNull()
            assertIs<VaultImportError.Malformed>(err, "version $version")
        }
    }

    @Test
    fun `a file without the version key and a file saying 1 or 2 are all accepted`() = runTest {
        for (version in listOf(null, "1", "2")) {
            val result = emptyImporter().import(
                encryptBackupJson(json(version = version), "pass-1234"), "pass-1234".toCharArray(),
            )
            assertTrue(result.isSuccess, "version $version")
        }
    }

    @Test
    fun `top-level keys of a newer version are reported, the known part is imported`() = runTest {
        val vault = FakeVaultManagerMem()
        val importer = importerOver(vault, FakeSshKeyRepositoryMem(), FakeHostRepositoryMem(), FakeTunnelRepositoryMem())
        val doc = json(extra = ""","credentials2":{"x":"y"},"futureSection":{"a":[1,2]}""")
            .replace("\"credentials\":{}", "\"credentials\":{\"cred1\":\"s3cret\"}")

        val report = importer.import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).getOrNull()
            ?: error("import failed")

        assertTrue(report.newerDataSkipped)
        assertEquals(1, report.passwords)
        assertEquals("s3cret", vault.getPassword("cred1")?.concatToString())
    }

    @Test
    fun `an invalid entry of a new section is ignored and counted, the valid ones are restored`() = runTest {
        val snippets = FakeSnippetRepositoryMem()
        val importer = importerOver(
            FakeVaultManagerMem(), FakeSshKeyRepositoryMem(), FakeHostRepositoryMem(), FakeTunnelRepositoryMem(),
            snippets = snippets,
        )
        // The second command holds an ESC control character (U+001B), written as a JSON escape.
        val doc = json(
            extra = ""","snippets":[${snippetJson("ok-1")},${snippetJson("bad-1", command = "ls\u001b[2J")}]""",
        )
        val doc2 = doc.replace("\u001b", "\\u001b")

        val report = importer.import(encryptBackupJson(doc2, "pass-1234"), "pass-1234".toCharArray()).getOrNull()
            ?: error("import failed")

        assertEquals(1, report.snippets)
        assertEquals(1, report.invalidIgnored)
        assertEquals("uptime", snippets.getById("ok-1")?.command)
        assertNull(snippets.getById("bad-1"))
    }

    @Test
    fun `a known host unreadable by the SSH library is counted as invalid and never reaches the store`() = runTest {
        val sshKeys = FakeSshKeyRepositoryMem()
        val store = RepositoryVaultRestoreStore(sshKeys, FakeHostRepositoryMem(), FakeTunnelRepositoryMem())
        val importer = DesktopVaultImporter(FakeVaultManagerMem(), store, FakeBackupSettings())
        val doc = json(
            extra = ""","knownHosts":[{"hostPort":"a.example.test:22","keyType":"ssh-ed25519","publicKey":"AAAAAAAA"}]""",
        )

        val report = importer.import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).getOrNull()
            ?: error("import failed")

        assertEquals(1, report.invalidIgnored)
        assertEquals(0, report.knownHostsAdded)
        assertTrue(store.receivedKnownHosts.isEmpty())
    }

    @Test
    fun `a section over its maximum count keeps the first entries and counts the rest as invalid`() = runTest {
        val vault = FakeVaultManagerMem()
        val snippets = FakeSnippetRepositoryMem()
        val hosts = FakeHostRepositoryMem()
        val settings = FakeBackupSettings()
        val importer = importerOver(
            vault, FakeSshKeyRepositoryMem(), hosts, FakeTunnelRepositoryMem(), snippets = snippets, settings = settings,
        )
        val tooMany = (0..10_000).joinToString(",") { snippetJson("s-$it") }
        val doc = json(extra = ""","snippets":[$tooMany]""")
            .replace("\"credentials\":{}", "\"credentials\":{\"cred1\":\"s3cret\"}")

        val report = importer.import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).getOrThrow()

        assertEquals(10_000, report.snippets)
        assertEquals(1, report.invalidIgnored, "the entry beyond the maximum count is counted, not restored")
        assertNotNull(snippets.getById("s-0"))
        assertNotNull(snippets.getById("s-9999"))
        assertNull(snippets.getById("s-10000"))
        assertEquals(1, vault.passwords.size, "the rest of the file is restored")
    }

    @Test
    fun `a structure error is Malformed and its message never carries the decrypted content`() = runTest {
        val marker = "LEAK-MARKER-9f3a"
        // "hosts" is a string: the decoder would quote the document, secrets included, in its message.
        val doc = """{"exportedAt":0,"hosts":"$marker","sshKeys":[],"tunnels":[],"credentials":{"c":"$marker"},"privateKeys":{}}"""

        val err = emptyImporter().import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).exceptionOrNull()

        assertIs<VaultImportError.Malformed>(err)
        assertFalse(err.message.orEmpty().contains(marker))
        assertNull(err.cause)
    }

    @Test
    fun `a restore failure is an IoError without the message or the cause of the underlying error`() = runTest {
        val marker = "LEAK-MARKER-77c1"
        val failing = object : VaultRestoreStore {
            override suspend fun restore(
                hosts: List<Host>,
                sshKeys: List<SshKey>,
                tunnels: List<TunnelConfig>,
                snippets: List<Snippet>,
                customThemes: List<CustomTerminalTheme>,
                knownHosts: List<StoredKnownHost>,
            ): VaultRestoreResult = throw IllegalStateException("disk said $marker")
        }
        val importer = DesktopVaultImporter(FakeVaultManagerMem(), failing, FakeBackupSettings())

        val err = importer.import(encryptBackupJson(json(), "pass-1234"), "pass-1234".toCharArray()).exceptionOrNull()

        assertIs<VaultImportError.IoError>(err)
        assertFalse(err.message.orEmpty().contains(marker))
        assertNull(err.cause)
    }

    @Test
    fun `a failing preference store does not turn a done restore into a failure`() = runTest {
        val broken = object : BackupSettingsAccess {
            override fun snapshot() = fr.techtical.nextsh.shared.core.backup.BackupSettings()
            override fun apply(settings: fr.techtical.nextsh.shared.core.backup.BackupSettings?): BackupSettingsApplyResult =
                throw IllegalStateException("registry unavailable")
        }
        val importer = importerOver(
            FakeVaultManagerMem(), FakeSshKeyRepositoryMem(), FakeHostRepositoryMem(), FakeTunnelRepositoryMem(),
            settings = broken,
        )
        val doc = json(extra = ""","settings":{"common":{"connectionTimeoutSeconds":15}}""")

        val report = importer.import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).getOrNull()
            ?: error("import failed")

        assertEquals(0, report.settingsApplied)
        assertEquals(setOf(BackupRestoreStep.SETTINGS), report.failedSteps, "no silent success at 0")
        assertEquals(listOf(BackupReportWarning.PARTIAL_RESTORE), report.warnings())
    }

    @Test
    fun `a failing vault step is a partial restore, the database stays and the report names the step`() = runTest {
        val inner = FakeVaultManagerMem()
        val failing = object : VaultManager by inner {
            override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) {
                throw IllegalStateException("vault said LEAK-MARKER")
            }
        }
        val hosts = FakeHostRepositoryMem()
        val importer = importerOver(failing, FakeSshKeyRepositoryMem(), hosts, FakeTunnelRepositoryMem())
        val host = backupHost("h1", "Host")
        val doc = """{"exportedAt":0,"hosts":[${hostJson(host)}],"sshKeys":[],"tunnels":[],"credentials":{"c1":"pw"},"privateKeys":{"k1":"pem"}}"""

        val report = importer.import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).getOrNull()
            ?: error("a partial restore is not a failure")

        assertEquals(setOf(BackupRestoreStep.VAULT_SECRETS), report.failedSteps)
        assertEquals(1, report.hosts)
        assertEquals(host, hosts.getById("h1"))
        assertEquals(1, report.passwords, "what was written before the failure is counted")
        assertEquals(0, report.privateKeys)
        assertTrue(BackupReportWarning.PARTIAL_RESTORE in report.warnings())
    }

    private fun hostJson(h: Host) =
        """{"id":"${h.id}","label":"${h.label}","hostname":"${h.hostname}","username":"${h.username}","authType":"PASSWORD","credentialId":"${h.credentialId}"}"""

    @Test
    fun `an out-of-domain setting is dropped by the validator and counted as invalid`() = runTest {
        val settings = FakeBackupSettings()
        val importer = importerOver(
            FakeVaultManagerMem(), FakeSshKeyRepositoryMem(), FakeHostRepositoryMem(), FakeTunnelRepositoryMem(),
            settings = settings,
        )
        val doc = json(extra = ""","settings":{"common":{"connectionTimeoutSeconds":17},"desktop":{"terminalFontSizePx":16}}""")

        val report = importer.import(encryptBackupJson(doc, "pass-1234"), "pass-1234".toCharArray()).getOrNull()
            ?: error("import failed")

        assertEquals(1, report.invalidIgnored)
        assertNull(settings.applied?.common?.connectionTimeoutSeconds)
        assertEquals(16, settings.applied?.desktop?.terminalFontSizePx)
    }

    private suspend fun FakeHostRepositoryMem.observeAllSnapshot(): List<Host> =
        observeAll().first()
}
