// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.core.ssh.KnownHostCandidate
import fr.techtical.nextsh.core.ssh.KnownHostsImportOutcome
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.data.db.FakeTransactionRunner
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.data.repository.BackupRestoreStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.backup.BackupAndroidSettings
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupCommonSettings
import fr.techtical.nextsh.shared.core.backup.BackupCustomTheme
import fr.techtical.nextsh.shared.core.backup.BackupDesktopSettings
import fr.techtical.nextsh.shared.core.backup.BackupDocument
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupKnownHost
import fr.techtical.nextsh.shared.core.backup.BackupReportWarning
import fr.techtical.nextsh.shared.core.backup.BackupRestoreStep
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import fr.techtical.nextsh.shared.core.backup.BackupSnippet
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.testutil.LEAK_MARKER
import fr.techtical.nextsh.testutil.RecordingTree
import fr.techtical.nextsh.testutil.assertNoPayloadLeak
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import timber.log.Timber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Restore semantics of [VaultImporter]: a tunnel whose host is nowhere to be found is skipped and
 * counted instead of failing the whole restore, the database part is one transaction, and the
 * vault secrets are written only once the database part succeeded. Format 2: the new sections are
 * validated before the first write, themes come before hosts, known hosts are inserted only,
 * settings go through the allow-list.
 *
 * The backup is built and encrypted for real (real [CryptoUtils], the file format of
 * [VaultExporter]); only the repositories, the vault, the known hosts and the settings are mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class VaultImporterTest {

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var tunnelRepository: TunnelRepository

    @MockK
    private lateinit var backupRestoreStore: BackupRestoreStore

    @MockK
    private lateinit var knownHostsVerifier: KnownHostsVerifier

    @MockK
    private lateinit var settingsDataStore: SettingsDataStore

    private val cryptoUtils = CryptoUtils()
    private val transactionRunner = FakeTransactionRunner()

    private lateinit var importer: VaultImporter

    private val passphrase = "correct horse battery"

    @BeforeEach
    fun setUp() {
        // No SSH key on the device unless a test says otherwise (the restore reads the live row of each id).
        coEvery { sshKeyRepository.getById(any()) } returns null
        importer = VaultImporter(
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            hostRepository = hostRepository,
            tunnelRepository = tunnelRepository,
            backupRestoreStore = backupRestoreStore,
            knownHostsVerifier = knownHostsVerifier,
            settingsDataStore = settingsDataStore,
            cryptoUtils = cryptoUtils,
            transactionRunner = transactionRunner,
        )
    }

    private fun host(id: String) = Host(
        id = id,
        label = "Host $id",
        hostname = "$id.example.org",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private fun tunnel(id: String, hostId: String) = TunnelConfig(
        id = id,
        label = "Tunnel $id",
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
    )

    private fun key(id: String) = SshKey(
        id = id,
        label = "Key $id",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA",
    )

    private fun snippet(id: String) = BackupSnippet(id = id, label = "Snippet $id", command = "uptime", createdAt = 1L)

    private fun theme(id: String = THEME_ID) = BackupCustomTheme(
        id = id,
        name = "Night",
        background = OPAQUE_BLACK,
        foreground = OPAQUE_WHITE,
        cursor = OPAQUE_WHITE,
        selectionBg = 0x40FFFFFF,
        ansi = List(16) { OPAQUE_BLACK },
        createdAt = 1L,
    )

    private fun knownHost(hostPort: String = "ed25519.example.org:22") = BackupKnownHost(
        hostPort = hostPort,
        keyType = "ssh-ed25519",
        publicKey = ED25519_BLOB,
        addedAt = 0L,
    )

    /** Builds a .nextsh file exactly like [VaultExporter]: the shared document, then the shared frame. */
    private fun backupFile(backup: BackupDocument, withPassphrase: String = passphrase): ByteArray =
        encryptedFile(BackupCodec.encode(backup), withPassphrase)

    /** Encrypts any text like a backup file: lets a test build a correctly encrypted but malformed backup. */
    private fun encryptedFile(plaintext: String, withPassphrase: String = passphrase): ByteArray {
        val salt = cryptoUtils.generateSalt(32)
        val derived = cryptoUtils.deriveKeyFromPassphrase(withPassphrase.toCharArray(), salt)
        val sealed = cryptoUtils.encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8), derived)
        return BackupEnvelope.pack(salt, sealed)
    }

    private fun backup(
        hosts: List<Host> = emptyList(),
        sshKeys: List<SshKey> = emptyList(),
        tunnels: List<TunnelConfig> = emptyList(),
        credentials: Map<String, String> = emptyMap(),
        privateKeys: Map<String, String> = emptyMap(),
        keyPassphrases: Map<String, String> = emptyMap(),
        certificates: Map<String, String> = emptyMap(),
        snippets: List<BackupSnippet> = emptyList(),
        customThemes: List<BackupCustomTheme> = emptyList(),
        knownHosts: List<BackupKnownHost> = emptyList(),
        settings: BackupSettings? = null,
    ) = BackupDocument(
        exportedAt = 1_000L,
        hosts = hosts,
        sshKeys = sshKeys,
        tunnels = tunnels,
        credentials = credentials,
        privateKeys = privateKeys,
        keyPassphrases = keyPassphrases,
        certificates = certificates,
        snippets = snippets,
        customThemes = customThemes,
        knownHosts = knownHosts,
        settings = settings,
    )

    private fun stubAllWrites() {
        coEvery { hostRepository.save(any()) } returns Unit
        coEvery { sshKeyRepository.save(any()) } returns Unit
        coEvery { tunnelRepository.save(any()) } returns Unit
        coEvery { backupRestoreStore.restoreSnippets(any()) } returns Unit
        coEvery { backupRestoreStore.restoreThemes(any()) } returns Unit
        coEvery { backupRestoreStore.localHostsIncludingDeleted() } returns emptyList()
        justRun { vaultManager.storePassword(any(), any()) }
        justRun { vaultManager.storePrivateKey(any(), any()) }
        justRun { vaultManager.storeKeyPassphrase(any(), any()) }
        justRun { vaultManager.storeCertificate(any(), any()) }
        every { vaultManager.isAuthRequired() } returns false
    }

    @Test
    fun `tunnel whose host is in neither the backup nor the local database is skipped and counted`() = runTest {
        stubAllWrites()
        // getById hides tombstones, so a locally deleted host answers null like an unknown one.
        coEvery { hostRepository.getById("local-host") } returns host("local-host")
        coEvery { hostRepository.getById("unknown-host") } returns null
        val inBackup = tunnel("t-backup-host", "host-in-backup")
        val onLocalHost = tunnel("t-local-host", "local-host")
        val orphan = tunnel("t-orphan", "unknown-host")
        val file = backupFile(
            backup(
                hosts = listOf(host("host-in-backup")),
                tunnels = listOf(inBackup, orphan, onLocalHost),
                credentials = mapOf("cred-host-in-backup" to "pw"),
                privateKeys = mapOf("key-1" to "PEM"),
                keyPassphrases = mapOf("key-1" to "pp"),
            )
        )

        val result = importer.import(file, passphrase.toCharArray())

        assertTrue(result.isSuccess, "a backup with an orphan tunnel must still restore: ${result.exceptionOrNull()}")
        coVerify(exactly = 1) { tunnelRepository.save(inBackup) }
        coVerify(exactly = 1) { tunnelRepository.save(onLocalHost) }
        coVerify(exactly = 0) { tunnelRepository.save(orphan) }
        val report = result.getOrThrow()
        assertEquals(1, report.tunnelsSkipped)
        assertTrue(BackupReportWarning.TUNNELS_SKIPPED in report.warnings())
        // 1 host + 2 restored tunnels + 1 credential + 1 private key + 1 passphrase.
        assertEquals(1, report.hosts)
        assertEquals(2, report.tunnels)
        assertEquals(1, report.passwords)
        assertEquals(1, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        // The secrets are still restored.
        verify(exactly = 1) { vaultManager.storePassword("cred-host-in-backup", any()) }
        verify(exactly = 1) { vaultManager.storePrivateKey("key-1", "PEM") }
        verify(exactly = 1) { vaultManager.storeKeyPassphrase("key-1", any()) }
    }

    @Test
    fun `a backup without orphan tunnel reports none skipped`() = runTest {
        stubAllWrites()
        val file = backupFile(backup(hosts = listOf(host("h1")), tunnels = listOf(tunnel("t1", "h1")), sshKeys = listOf(key("k1"))))

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(0, report.tunnelsSkipped)
        assertEquals(1, report.hosts)
        assertEquals(1, report.sshKeys)
        assertEquals(1, report.tunnels)
        // The host of the backup is enough: no database lookup for it.
        coVerify(exactly = 0) { hostRepository.getById(any()) }
    }

    @Test
    fun `database writes run in one transaction and the vault secrets are written after it`() = runTest {
        every { vaultManager.isAuthRequired() } returns false
        val activeDuring = mutableMapOf<String, Boolean>()
        coEvery { hostRepository.save(any()) } coAnswers { activeDuring["host"] = transactionRunner.active }
        coEvery { sshKeyRepository.save(any()) } coAnswers { activeDuring["key"] = transactionRunner.active }
        coEvery { tunnelRepository.save(any()) } coAnswers { activeDuring["tunnel"] = transactionRunner.active }
        coEvery { backupRestoreStore.restoreThemes(any()) } coAnswers { activeDuring["theme"] = transactionRunner.active }
        coEvery { backupRestoreStore.restoreSnippets(any()) } coAnswers { activeDuring["snippet"] = transactionRunner.active }
        every { vaultManager.storePassword(any(), any()) } answers { activeDuring["password"] = transactionRunner.active }
        every { vaultManager.storePrivateKey(any(), any()) } answers { activeDuring["privateKey"] = transactionRunner.active }
        every { vaultManager.storeKeyPassphrase(any(), any()) } answers { activeDuring["passphrase"] = transactionRunner.active }
        every { vaultManager.storeCertificate(any(), any()) } answers { activeDuring["certificate"] = transactionRunner.active }
        val file = backupFile(
            backup(
                hosts = listOf(host("h1")),
                sshKeys = listOf(key("k1")),
                tunnels = listOf(tunnel("t1", "h1")),
                credentials = mapOf("cred-h1" to "pw"),
                privateKeys = mapOf("k1" to "PEM"),
                keyPassphrases = mapOf("k1" to "pp"),
                certificates = mapOf("k1" to "CERT"),
                snippets = listOf(snippet("s1")),
                customThemes = listOf(theme()),
            )
        )

        importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, transactionRunner.runs)
        assertEquals(
            mapOf(
                "theme" to true,
                "snippet" to true,
                "host" to true,
                "key" to true,
                "tunnel" to true,
                "password" to false,
                "privateKey" to false,
                "passphrase" to false,
                "certificate" to false,
            ),
            activeDuring,
        )
    }

    @Test
    fun `a failing database write fails the import before any vault secret is written`() = runTest {
        coEvery { hostRepository.save(any()) } returns Unit
        coEvery { backupRestoreStore.localHostsIncludingDeleted() } returns emptyList()
        coEvery { tunnelRepository.save(any()) } throws IllegalStateException("database write failed")
        every { vaultManager.isAuthRequired() } returns false
        val file = backupFile(
            backup(
                hosts = listOf(host("h1")),
                tunnels = listOf(tunnel("t1", "h1")),
                credentials = mapOf("cred-h1" to "pw"),
                privateKeys = mapOf("k1" to "PEM"),
                keyPassphrases = mapOf("k1" to "pp"),
                certificates = mapOf("k1" to "CERT"),
                knownHosts = listOf(knownHost()),
                settings = BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 15)),
            )
        )

        val result = importer.import(file, passphrase.toCharArray())

        assertTrue(result.isFailure)
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.storePrivateKey(any(), any()) }
        verify(exactly = 0) { vaultManager.storeKeyPassphrase(any(), any()) }
        verify(exactly = 0) { vaultManager.storeCertificate(any(), any()) }
        // Neither the known hosts nor the settings are touched when the database part failed.
        verify(exactly = 0) { knownHostsVerifier.importEntries(any(), any()) }
        coVerify(exactly = 0) { settingsDataStore.applyBackupSettings(any()) }
    }

    @Test
    fun `a LAN sync secret carried by an old backup is not restored nor counted`() = runTest {
        stubAllWrites()
        val file = backupFile(
            backup(credentials = mapOf("${LAN_SYNC_SECRET_KEY_PREFIX}device-1" to "secret", "cred-1" to "pw"))
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.passwords)
        verify(exactly = 1) { vaultManager.storePassword("cred-1", any()) }
        verify(exactly = 0) { vaultManager.storePassword("${LAN_SYNC_SECRET_KEY_PREFIX}device-1", any()) }
    }

    @Test
    fun `a wrong passphrase fails without touching the database or the vault`() = runTest {
        val file = backupFile(backup(hosts = listOf(host("h1"))), withPassphrase = "another passphrase")

        val result = importer.import(file, passphrase.toCharArray())

        assertTrue(result.isFailure)
        assertEquals(0, transactionRunner.runs)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `the passphrase is wiped after the import`() = runTest {
        stubAllWrites()
        val file = backupFile(backup())
        val chars = passphrase.toCharArray()

        importer.import(file, chars).getOrThrow()

        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun `hosts are saved before the tunnels that point at them`() = runTest {
        stubAllWrites()
        val h1 = host("h1")
        val t1 = tunnel("t1", "h1")
        val file = backupFile(backup(hosts = listOf(h1), tunnels = listOf(t1)))

        importer.import(file, passphrase.toCharArray()).getOrThrow()

        coVerifyOrder {
            hostRepository.save(h1)
            tunnelRepository.save(t1)
        }
    }

    @Test
    fun `an expired vault authentication fails before the database is touched`() = runTest {
        stubAllWrites()
        every { vaultManager.isAuthRequired() } returns true
        val file = backupFile(backup(hosts = listOf(host("h1")), credentials = mapOf("cred-h1" to "pw")))

        val thrown = runCatching { importer.import(file, passphrase.toCharArray()) }.exceptionOrNull()

        assertTrue(thrown is VaultAuthExpiredException, "expected VaultAuthExpiredException, got $thrown")
        assertEquals(0, transactionRunner.runs)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `a backup without any secret does not need the vault`() = runTest {
        stubAllWrites()
        every { vaultManager.isAuthRequired() } returns true
        val file = backupFile(backup(hosts = listOf(host("h1"))))

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.hosts)
        verify(exactly = 0) { vaultManager.isAuthRequired() }
        coVerify(exactly = 1) { hostRepository.save(any()) }
    }

    @Test
    fun `a backup whose secrets are all invalid does not need the vault and counts them`() = runTest {
        stubAllWrites()
        every { vaultManager.isAuthRequired() } returns true
        val file = backupFile(
            backup(
                hosts = listOf(host("h1")),
                credentials = mapOf("" to "pw"),
                privateKeys = mapOf("k1" to "", "k2" to "x".repeat(64 * 1024 + 1)),
                keyPassphrases = mapOf("a".repeat(129) to "pp"),
            ),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.hosts)
        assertEquals(4, report.invalidIgnored)
        assertEquals(0, report.passwords + report.privateKeys + report.keyPassphrases)
        verify(exactly = 0) { vaultManager.isAuthRequired() }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.storePrivateKey(any(), any()) }
        verify(exactly = 0) { vaultManager.storeKeyPassphrase(any(), any()) }
    }

    @Test
    fun `a backup whose only credential is a LAN sync secret does not need the vault`() = runTest {
        stubAllWrites()
        every { vaultManager.isAuthRequired() } returns true
        val file = backupFile(backup(credentials = mapOf("${LAN_SYNC_SECRET_KEY_PREFIX}device-1" to "secret")))

        importer.import(file, passphrase.toCharArray()).getOrThrow()

        verify(exactly = 0) { vaultManager.isAuthRequired() }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    // ── Format 2 ─────────────────────────────────────────────────────────────

    @Test
    fun `a backup whose only secret is a certificate needs the vault and restores it`() = runTest {
        stubAllWrites()
        every { vaultManager.isAuthRequired() } returns true

        val expired = runCatching {
            importer.import(backupFile(backup(certificates = mapOf("k1" to "CERT"))), passphrase.toCharArray())
        }.exceptionOrNull()
        assertTrue(expired is VaultAuthExpiredException)

        every { vaultManager.isAuthRequired() } returns false
        val report = importer.import(
            backupFile(backup(certificates = mapOf("k1" to "CERT"))),
            passphrase.toCharArray(),
        ).getOrThrow()

        assertEquals(1, report.certificates)
        verify(exactly = 1) { vaultManager.storeCertificate("k1", "CERT") }
    }

    @Test
    fun `themes are restored before the hosts that point at them, snippets with them`() = runTest {
        stubAllWrites()
        val h1 = host("h1")
        val file = backupFile(backup(hosts = listOf(h1), snippets = listOf(snippet("s1")), customThemes = listOf(theme())))

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        coVerifyOrder {
            backupRestoreStore.restoreThemes(match { it.single().id == THEME_ID })
            backupRestoreStore.restoreSnippets(match { it.single().id == "s1" })
            hostRepository.save(h1)
        }
        assertEquals(1, report.snippets)
        assertEquals(1, report.customThemes)
    }

    @Test
    fun `a file without a version key is read as format 1`() = runTest {
        stubAllWrites()
        val v1 = """{"exportedAt":1,"hosts":[],"sshKeys":[],"tunnels":[],"credentials":{"cred-1":"pw"},"privateKeys":{}}"""

        val report = importer.import(encryptedFile(v1), passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.passwords)
        assertFalse(report.newerDataSkipped)
        coVerify(exactly = 0) { backupRestoreStore.restoreSnippets(any()) }
        coVerify(exactly = 0) { backupRestoreStore.restoreThemes(any()) }
        verify(exactly = 0) { knownHostsVerifier.importEntries(any(), any()) }
        coVerify(exactly = 0) { settingsDataStore.applyBackupSettings(any()) }
    }

    @Test
    fun `an invalid entry of a new section is ignored and counted, the valid ones are restored`() = runTest {
        stubAllWrites()
        val badSnippet = snippet("s-bad").copy(label = "bad" + String(Character.toChars(0x202E)))
        val file = backupFile(
            backup(
                snippets = listOf(snippet("s-ok"), badSnippet),
                customThemes = listOf(theme(), theme(id = "DRACULA")),
            )
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.snippets)
        assertEquals(1, report.customThemes)
        assertEquals(2, report.invalidIgnored)
        assertTrue(BackupReportWarning.INVALID_IGNORED in report.warnings())
        coVerify(exactly = 1) { backupRestoreStore.restoreSnippets(match { list -> list.map { it.id } == listOf("s-ok") }) }
        coVerify(exactly = 1) { backupRestoreStore.restoreThemes(match { list -> list.map { it.id } == listOf(THEME_ID) }) }
    }

    @Test
    fun `a section over its maximum count keeps the first entries and counts the rest as invalid`() = runTest {
        stubAllWrites()
        val tooMany = (0..10_000).map { snippet("s$it") }
        val file = backupFile(backup(hosts = listOf(host("h1")), snippets = tooMany))

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(10_000, report.snippets)
        assertEquals(1, report.invalidIgnored)
        assertTrue(BackupReportWarning.INVALID_IGNORED in report.warnings())
        coVerify(exactly = 1) {
            backupRestoreStore.restoreSnippets(match { list -> list.size == 10_000 && list.first().id == "s0" && list.last().id == "s9999" })
        }
    }

    @Test
    fun `known hosts are inserted only and a different local key is named in the report`() = runTest {
        stubAllWrites()
        val candidates = slot<List<KnownHostCandidate>>()
        every { knownHostsVerifier.importEntries(capture(candidates), any()) } returns
            KnownHostsImportOutcome(added = 1, alreadyKnown = 2, conflicts = listOf("a:22", "b:22", "c:22", "d:22"))
        val file = backupFile(
            backup(knownHosts = listOf(knownHost("new.example.org:22"), knownHost("known.example.org:22")))
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.knownHostsAdded)
        assertEquals(2, report.knownHostsAlreadyKnown)
        assertEquals(4, report.knownHostConflicts)
        assertEquals(listOf("a:22", "b:22", "c:22"), report.knownHostConflictNames)
        assertEquals(1, report.knownHostConflictsNotNamed)
        assertTrue(BackupReportWarning.KNOWN_HOST_CONFLICTS in report.warnings())
        // The key reaches the verifier in local storage form, read by the SSHJ handshake path.
        assertEquals(listOf("new.example.org:22", "known.example.org:22"), candidates.captured.map { it.hostPort })
        assertTrue(candidates.captured.all { it.encoded.isNotEmpty() && it.algorithm.isNotEmpty() })
    }

    @Test
    fun `a known host whose key SSHJ cannot read is counted invalid and never written`() = runTest {
        stubAllWrites()
        // Valid base64 and a plausible length, but not an Ed25519 blob.
        val broken = knownHost("broken.example.org:22").copy(publicKey = "AAAAAAAAAAAAAAAAAAAAAAAA")
        val file = backupFile(backup(knownHosts = listOf(broken)))

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(1, report.invalidIgnored)
        assertEquals(0, report.knownHostsAdded)
        verify(exactly = 0) { knownHostsVerifier.importEntries(any(), any()) }
    }

    @Test
    fun `settings go through the store, the invalid ones dropped and counted, the desktop section carried but unused`() = runTest {
        stubAllWrites()
        val applied = slot<BackupSettings>()
        coEvery { settingsDataStore.applyBackupSettings(capture(applied)) } returns 2
        val file = backupFile(
            backup(
                settings = BackupSettings(
                    common = BackupCommonSettings(connectionTimeoutSeconds = 7),
                    android = BackupAndroidSettings(terminalFontSize = 30, autoReconnect = false),
                    desktop = BackupDesktopSettings(terminalFontSizePx = 16),
                ),
            )
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(2, report.settingsApplied)
        assertEquals(1, report.invalidIgnored)
        assertNull(applied.captured.common?.connectionTimeoutSeconds)
        assertEquals(30, applied.captured.android?.terminalFontSize)
        assertFalse(report.languageChanged)
    }

    @Test
    fun `top-level keys of a newer version are reported and the known data is restored`() = runTest {
        stubAllWrites()
        val future = """{"version":2,"exportedAt":1,"hosts":[],"sshKeys":[],"tunnels":[],"credentials":{"cred-1":"pw"},"privateKeys":{},"futureSection":{"a":1}}"""

        val report = importer.import(encryptedFile(future), passphrase.toCharArray()).getOrThrow()

        assertTrue(report.newerDataSkipped)
        assertTrue(BackupReportWarning.NEWER_DATA_NOT_RESTORED in report.warnings())
        assertEquals(1, report.passwords)
        verify(exactly = 1) { vaultManager.storePassword("cred-1", any()) }
    }

    @Test
    fun `a failing known hosts step gives a partial restore, not a failure, and no log line holds the content`() = runTest {
        stubAllWrites()
        every { knownHostsVerifier.importEntries(any(), any()) } throws java.io.IOException("disk full for $LEAK_MARKER")
        coEvery { settingsDataStore.applyBackupSettings(any()) } returns 1
        val tree = RecordingTree()
        Timber.plant(tree)
        val result = try {
            importer.import(
                backupFile(
                    backup(
                        hosts = listOf(host("h1")),
                        credentials = mapOf("cred-1" to "pw"),
                        knownHosts = listOf(knownHost()),
                        settings = BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 15)),
                    ),
                ),
                passphrase.toCharArray(),
            )
        } finally {
            Timber.uproot(tree)
        }

        val report = result.getOrThrow()
        assertEquals(setOf(BackupRestoreStep.KNOWN_HOSTS), report.failedSteps)
        assertTrue(BackupReportWarning.PARTIAL_RESTORE in report.warnings())
        // The other steps went through.
        assertEquals(1, report.hosts)
        assertEquals(1, report.passwords)
        assertEquals(1, report.settingsApplied)
        verify(exactly = 1) { vaultManager.storePassword("cred-1", any()) }
        assertFalse(tree.entries.any { it.message.contains(LEAK_MARKER) || it.throwable != null })
        assertTrue(tree.entries.any { it.message.contains("known hosts step failed (IOException)") })
    }

    @Test
    fun `a failing vault secrets step gives a partial restore and the later steps still run`() = runTest {
        stubAllWrites()
        every { vaultManager.storePrivateKey(any(), any()) } throws IllegalStateException("vault closed $LEAK_MARKER")
        every { knownHostsVerifier.importEntries(any(), any()) } returns KnownHostsImportOutcome(added = 1, alreadyKnown = 0, conflicts = emptyList())
        coEvery { settingsDataStore.applyBackupSettings(any()) } returns 1
        val file = backupFile(
            backup(
                hosts = listOf(host("h1")),
                credentials = mapOf("cred-1" to "pw"),
                privateKeys = mapOf("k1" to "PEM"),
                certificates = mapOf("k1" to "CERT"),
                knownHosts = listOf(knownHost()),
                settings = BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 15)),
            ),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(setOf(BackupRestoreStep.VAULT_SECRETS), report.failedSteps)
        assertEquals(1, report.hosts, "the database part was written")
        assertEquals(1, report.passwords, "only what was really stored is counted")
        assertEquals(0, report.privateKeys)
        assertEquals(0, report.certificates)
        assertEquals(1, report.knownHostsAdded)
        assertEquals(1, report.settingsApplied)
    }

    @Test
    fun `a failing settings step gives a partial restore`() = runTest {
        stubAllWrites()
        coEvery { settingsDataStore.applyBackupSettings(any()) } throws IllegalStateException("datastore")
        val file = backupFile(
            backup(hosts = listOf(host("h1")), settings = BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 15))),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(setOf(BackupRestoreStep.SETTINGS), report.failedSteps)
        assertEquals(0, report.settingsApplied)
        assertEquals(1, report.hosts)
    }

    @Test
    fun `all steps failing still returns the report of the database part`() = runTest {
        stubAllWrites()
        every { vaultManager.storePassword(any(), any()) } throws IllegalStateException("x")
        every { knownHostsVerifier.importEntries(any(), any()) } throws java.io.IOException("x")
        coEvery { settingsDataStore.applyBackupSettings(any()) } throws IllegalStateException("x")
        val file = backupFile(
            backup(
                hosts = listOf(host("h1")),
                credentials = mapOf("cred-1" to "pw"),
                knownHosts = listOf(knownHost()),
                settings = BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 15)),
            ),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(
            setOf(BackupRestoreStep.VAULT_SECRETS, BackupRestoreStep.KNOWN_HOSTS, BackupRestoreStep.SETTINGS),
            report.failedSteps,
        )
        assertEquals(1, report.hosts)
    }

    // ── Held addresses of the known hosts ────────────────────────────────────
    //
    // For a host already on the device (same id, alive or deleted) the backup never brings the
    // first fingerprint of its address, whether the address changed or not.

    private fun localHost(id: String, hostname: String, port: Int = 22) =
        host(id).copy(hostname = hostname, port = port)

    @Test
    fun `the held addresses are those of the hosts of the file whose id is already local`() = runTest {
        stubAllWrites()
        coEvery { backupRestoreStore.localHostsIncludingDeleted() } returns listOf(
            localHost("same", "same.example.org"),
            localHost("moved", "old.example.org"),
        )
        val held = slot<Set<String>>()
        every { knownHostsVerifier.importEntries(any(), capture(held)) } returns
            KnownHostsImportOutcome(added = 1, alreadyKnown = 0, conflicts = emptyList(), held = listOf("same.example.org:22", "new.example.org:22"))
        val file = backupFile(
            backup(
                hosts = listOf(
                    localHost("same", "same.example.org"),
                    localHost("moved", "new.example.org"),
                    localHost("brand-new", "brand.example.org"),
                ),
                knownHosts = listOf(knownHost("same.example.org:22"), knownHost("new.example.org:22"), knownHost("brand.example.org:22")),
            ),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(setOf("same.example.org:22", "new.example.org:22"), held.captured)
        assertEquals(2, report.knownHostsHeld)
        assertEquals(listOf("same.example.org:22", "new.example.org:22"), report.knownHostHeldNames)
        assertTrue(BackupReportWarning.KNOWN_HOST_HELD in report.warnings())
    }

    @Test
    fun `a host that is new on the device holds nothing back`() = runTest {
        stubAllWrites()
        coEvery { backupRestoreStore.localHostsIncludingDeleted() } returns emptyList()
        val held = slot<Set<String>>()
        every { knownHostsVerifier.importEntries(any(), capture(held)) } returns
            KnownHostsImportOutcome(added = 1, alreadyKnown = 0, conflicts = emptyList())
        val file = backupFile(
            backup(hosts = listOf(localHost("new", "new.example.org")), knownHosts = listOf(knownHost("new.example.org:22"))),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertTrue(held.captured.isEmpty())
        assertEquals(1, report.knownHostsAdded)
        assertEquals(0, report.knownHostsHeld)
        assertFalse(BackupReportWarning.KNOWN_HOST_HELD in report.warnings())
    }

    @Test
    fun `a deleted local host that the file revives holds its address back too`() = runTest {
        stubAllWrites()
        // The list holds tombstones too.
        coEvery { backupRestoreStore.localHostsIncludingDeleted() } returns listOf(localHost("gone", "before.example.org"))
        val held = slot<Set<String>>()
        every { knownHostsVerifier.importEntries(any(), capture(held)) } returns
            KnownHostsImportOutcome(added = 0, alreadyKnown = 0, conflicts = emptyList(), held = listOf("after.example.org:22"))
        val file = backupFile(
            backup(hosts = listOf(localHost("gone", "after.example.org")), knownHosts = listOf(knownHost("after.example.org:22"))),
        )

        val report = importer.import(file, passphrase.toCharArray()).getOrThrow()

        assertEquals(setOf("after.example.org:22"), held.captured)
        assertEquals(listOf("after.example.org:22"), report.knownHostHeldNames)
    }

    @Test
    fun `without hosts in the file nothing is held and the local hosts are not even read`() = runTest {
        stubAllWrites()
        val held = slot<Set<String>>()
        every { knownHostsVerifier.importEntries(any(), capture(held)) } returns
            KnownHostsImportOutcome(added = 1, alreadyKnown = 0, conflicts = emptyList())

        importer.import(backupFile(backup(knownHosts = listOf(knownHost()))), passphrase.toCharArray()).getOrThrow()

        assertTrue(held.captured.isEmpty())
        coVerify(exactly = 0) { backupRestoreStore.localHostsIncludingDeleted() }
    }

    @Test
    fun `the local hosts are read before any host is written`() = runTest {
        stubAllWrites()
        every { knownHostsVerifier.importEntries(any(), any()) } returns KnownHostsImportOutcome(1, 0, emptyList())
        val h1 = localHost("h1", "a.example.org")
        val file = backupFile(backup(hosts = listOf(h1), knownHosts = listOf(knownHost("a.example.org:22"))))

        importer.import(file, passphrase.toCharArray()).getOrThrow()

        coVerifyOrder {
            backupRestoreStore.localHostsIncludingDeleted()
            hostRepository.save(h1)
        }
    }

    @Test
    fun `a cancellation during the settings step is not swallowed into a partial restore`() = runTest {
        stubAllWrites()
        coEvery { settingsDataStore.applyBackupSettings(any()) } throws kotlinx.coroutines.CancellationException("cancelled")
        val file = backupFile(
            backup(settings = BackupSettings(common = BackupCommonSettings(connectionTimeoutSeconds = 15))),
        )

        val thrown = runCatching { importer.import(file, passphrase.toCharArray()) }.exceptionOrNull()

        assertTrue(thrown is kotlinx.coroutines.CancellationException, "got $thrown")
    }

    // ── A backup that cannot be read never quotes its decrypted content ─────
    //
    // The messages of kotlinx.serialization exceptions copy the JSON input, so a malformed backup
    // that decrypts fine would put its passwords into the failure shown or logged.

    /** Correctly encrypted, but not a valid backup document, with [LEAK_MARKER] where a password could be. */
    private val malformedPlaintexts = mapOf(
        "truncated json" to """{"version":1,"exportedAt":1,"hosts":[{"id":"h1","label":"$LEAK_MARKER","hostname":""",
        "wrong field type" to """{"version":1,"exportedAt":"$LEAK_MARKER","hosts":[],"sshKeys":[],"tunnels":[],"credentials":{},"privateKeys":{}}""",
        "unknown enum value" to """{"version":1,"exportedAt":1,"hosts":[{"id":"h1","label":"l","hostname":"h","username":"u","authType":"$LEAK_MARKER","credentialId":"c"}],"sshKeys":[],"tunnels":[],"credentials":{},"privateKeys":{}}""",
        "not json at all" to "$LEAK_MARKER is not json",
        "credentials of the wrong shape" to """{"version":1,"exportedAt":1,"hosts":[],"sshKeys":[],"tunnels":[],"credentials":["$LEAK_MARKER"],"privateKeys":{}}""",
    )

    @Test
    fun `for every malformed case the raw decoder itself quotes the decrypted content`() {
        // Guards the leak tests against passing for the wrong reason. Each case must be a real
        // leak when decoded without the wrapper: a case whose raw failure does not contain the
        // marker would prove nothing about the wrapper and must be fixed or removed.
        for ((name, text) in malformedPlaintexts) {
            val raw = runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString(BackupDocument.serializer(), text)
            }.exceptionOrNull()
            assertTrue(raw != null, "$name: the case must fail to decode")
            assertTrue(
                raw!!.message.orEmpty().contains(LEAK_MARKER),
                "$name: the raw decoder failure does not quote the marker, this case is vacuous",
            )
        }
    }

    @Test
    fun `a malformed but correctly encrypted backup fails with a fixed message and no cause`() = runTest {
        for ((name, plaintext) in malformedPlaintexts) {
            val result = importer.import(encryptedFile(plaintext), passphrase.toCharArray())

            val e = result.exceptionOrNull()
            assertTrue(e is VaultBackupReadException, "$name: expected VaultBackupReadException, got $e")
            e as VaultBackupReadException
            assertEquals(VaultBackupReadException.Reason.MALFORMED, e.reason, name)
            assertEquals("MALFORMED", e.message, name)
            assertNull(e.cause, "$name: the failure must not keep the decoder exception as its cause")
            assertNoPayloadLeak(e)
        }
        // Nothing was written, nothing was asked of the vault.
        assertEquals(0, transactionRunner.runs)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `a wrong passphrase is reported as such, without any detail from the crypto layer`() = runTest {
        val file = backupFile(backup(hosts = listOf(host("h1"))), withPassphrase = "another passphrase")

        val e = importer.import(file, passphrase.toCharArray()).exceptionOrNull()

        assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
        e as VaultBackupReadException
        assertEquals(VaultBackupReadException.Reason.WRONG_PASSPHRASE, e.reason)
        assertNull(e.cause)
        assertNoPayloadLeak(e)
    }

    @Test
    fun `a tampered backup is reported like a wrong passphrase`() = runTest {
        val file = backupFile(backup(hosts = listOf(host("h1"))))
        file[file.size - 1] = (file[file.size - 1].toInt() xor 0x01).toByte()

        val e = importer.import(file, passphrase.toCharArray()).exceptionOrNull()

        assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
        assertEquals(VaultBackupReadException.Reason.WRONG_PASSPHRASE, (e as VaultBackupReadException).reason)
    }

    @Test
    fun `a truncated encrypted container is reported as unreadable`() = runTest {
        // [SALT_LEN(4)][SALT(32)] followed by a single byte: shorter than the IV header.
        val file = byteArrayOf(0, 0, 0, 32) + ByteArray(32) { 7 } + byteArrayOf(1)

        val e = importer.import(file, passphrase.toCharArray()).exceptionOrNull()

        assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
        assertEquals(VaultBackupReadException.Reason.MALFORMED, (e as VaultBackupReadException).reason)
        assertNull(e.cause)
    }

    @Test
    fun `the failure log line holds the exception class name only`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            for (plaintext in malformedPlaintexts.values) {
                importer.import(encryptedFile(plaintext), passphrase.toCharArray())
            }
        } finally {
            Timber.uproot(tree)
        }

        val failures = tree.entries.filter { it.message.contains("import failed") }
        assertEquals(malformedPlaintexts.size, failures.size)
        for (entry in failures) {
            assertTrue(entry.message.contains("VaultBackupReadException"), "class name expected in: ${entry.message}")
            assertNull(entry.throwable, "the log must not carry the exception (message, cause and stack trace)")
        }
        assertFalse(tree.entries.any { it.message.contains(LEAK_MARKER) || it.throwable?.stackTraceToString()?.contains(LEAK_MARKER) == true })
    }

    @Test
    fun `the success log line holds counts only`() = runTest {
        stubAllWrites()
        every { knownHostsVerifier.importEntries(any(), any()) } returns
            KnownHostsImportOutcome(added = 0, alreadyKnown = 0, conflicts = listOf("secret-host.example.org:22"))
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            importer.import(
                backupFile(backup(hosts = listOf(host("h1")), snippets = listOf(snippet("s1")), knownHosts = listOf(knownHost()))),
                passphrase.toCharArray(),
            ).getOrThrow()
        } finally {
            Timber.uproot(tree)
        }

        assertFalse(tree.entries.any { it.message.contains("example.org") || it.message.contains("Snippet") })
    }

    // ── Structural checks: the commonest wrong-file cases are typed (so localized) too ──

    @Test
    fun `a file too short to hold a salt length is reported as unreadable`() = runTest {
        for (size in 0..3) {
            val e = importer.import(ByteArray(size) { 1 }, passphrase.toCharArray()).exceptionOrNull()

            assertTrue(e is VaultBackupReadException, "size $size: expected VaultBackupReadException, got $e")
            e as VaultBackupReadException
            assertEquals(VaultBackupReadException.Reason.MALFORMED, e.reason, "size $size")
            assertNull(e.cause)
        }
    }

    @Test
    fun `an impossible salt length is reported as unreadable`() = runTest {
        val body = ByteArray(40) { 3 }
        val saltLengths = listOf(
            byteArrayOf(0, 0, 0, 0),                                  // zero
            byteArrayOf(-1, -1, -1, -1),                              // negative
            byteArrayOf(0x7F, -1, -1, -1),                            // huge
            byteArrayOf(0, 0, 0, 44),                                 // the whole rest: nothing left to decrypt
        )
        for (saltLen in saltLengths) {
            val e = importer.import(saltLen + body, passphrase.toCharArray()).exceptionOrNull()

            assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
            assertEquals(VaultBackupReadException.Reason.MALFORMED, (e as VaultBackupReadException).reason)
            assertNull(e.cause)
        }
        assertEquals(0, transactionRunner.runs)
    }

    @Test
    fun `an ordinary file that is not a backup is reported as unreadable, not as a wrong passphrase`() = runTest {
        // A few kilobytes of anything: the usual "picked the wrong file" case.
        val e = importer.import(ByteArray(4096) { (it * 31).toByte() }, passphrase.toCharArray()).exceptionOrNull()

        assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
        assertNull(e!!.cause)
    }

    @Test
    fun `a backup of an unknown format version is reported with its own reason`() = runTest {
        // BackupCodec.encode always writes the current version: build the version 3 text by hand.
        // Its sections no longer have the version 2 shape, the version is read first.
        val v3 = """{"version":3,"exportedAt":1,"hosts":[{"shape":"changed"}]}"""

        val e = importer.import(encryptedFile(v3), passphrase.toCharArray()).exceptionOrNull()

        assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
        e as VaultBackupReadException
        assertEquals(VaultBackupReadException.Reason.UNSUPPORTED_VERSION, e.reason)
        assertNull(e.cause)
        assertEquals(0, transactionRunner.runs)
    }

    @Test
    fun `a version of zero or less is unreadable, not unsupported`() = runTest {
        for (version in listOf(0, -1)) {
            val text = """{"version":$version,"exportedAt":1,"hosts":[],"sshKeys":[],"tunnels":[],"credentials":{},"privateKeys":{}}"""

            val e = importer.import(encryptedFile(text), passphrase.toCharArray()).exceptionOrNull()

            assertTrue(e is VaultBackupReadException, "expected VaultBackupReadException, got $e")
            assertEquals(VaultBackupReadException.Reason.MALFORMED, (e as VaultBackupReadException).reason)
        }
        assertEquals(0, transactionRunner.runs)
    }

    private companion object {
        const val THEME_ID = "7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d"
        const val OPAQUE_BLACK = -16777216 // 0xFF000000
        const val OPAQUE_WHITE = -1        // 0xFFFFFFFF

        // A real Ed25519 public key blob (the one of testdata/backup/v2-complet.json), no private key.
        const val ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi"
    }
}
