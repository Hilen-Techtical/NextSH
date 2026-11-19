// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.core.ssh.KnownHostRecord
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.backup.BackupAndroidSettings
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupCommonSettings
import fr.techtical.nextsh.shared.core.backup.BackupDecodeResult
import fr.techtical.nextsh.shared.core.backup.BackupDocument
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import fr.techtical.nextsh.shared.core.backup.BackupValidationResult
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import timber.log.Timber

/**
 * What [VaultExporter] writes: every section of format 2, `"version":2` always, the LAN sync secret
 * and the security settings never. The file is decrypted with the real [CryptoUtils] and read back
 * by the shared codec.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class VaultExporterTest {

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var tunnelRepository: TunnelRepository

    @MockK
    private lateinit var snippetRepository: SnippetRepository

    @MockK
    private lateinit var themeRepository: CustomTerminalThemeRepository

    @MockK
    private lateinit var knownHostsVerifier: KnownHostsVerifier

    @MockK
    private lateinit var settingsDataStore: SettingsDataStore

    private val cryptoUtils = CryptoUtils()
    private val passphrase = "correct horse battery"

    private lateinit var exporter: VaultExporter

    @BeforeEach
    fun setUp() {
        exporter = VaultExporter(
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            hostRepository = hostRepository,
            tunnelRepository = tunnelRepository,
            snippetRepository = snippetRepository,
            customTerminalThemeRepository = themeRepository,
            knownHostsVerifier = knownHostsVerifier,
            settingsDataStore = settingsDataStore,
            cryptoUtils = cryptoUtils,
        )
        every { hostRepository.observeAll() } returns flowOf(listOf(host("h1")))
        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        every { tunnelRepository.observeAll() } returns flowOf(emptyList())
        every { snippetRepository.observeAll() } returns flowOf(
            listOf(Snippet(id = "s1", label = "Uptime", command = "uptime", category = "System", hostId = "h1", createdAt = 5L)),
        )
        every { themeRepository.observeAll() } returns flowOf(
            listOf(
                CustomTerminalTheme(
                    id = THEME_ID,
                    name = "Night",
                    background = -16777216,
                    foreground = -1,
                    cursor = -1,
                    selectionBg = 0x40FFFFFF,
                    ansi = List(16) { -16777216 },
                    createdAt = 7L,
                ),
            ),
        )
        every { vaultManager.listStoredCredentialIds() } returns
            listOf("cred-h1", "${LAN_SYNC_SECRET_KEY_PREFIX}device-1")
        every { vaultManager.getPassword("cred-h1") } answers { "pw".toCharArray() }
        every { vaultManager.getPassword("${LAN_SYNC_SECRET_KEY_PREFIX}device-1") } answers { "lan-secret".toCharArray() }
        every { vaultManager.listStoredKeyIds() } returns listOf("k1")
        every { vaultManager.getPrivateKey("k1") } returns "PEM"
        every { vaultManager.listStoredKeyPassphraseIds() } returns listOf("k1")
        every { vaultManager.getKeyPassphrase("k1") } answers { "pp".toCharArray() }
        every { vaultManager.listStoredCertificateIds() } returns listOf("k1")
        every { vaultManager.getCertificate("k1") } returns "CERT"
        every { knownHostsVerifier.exportEntries() } returns emptyList()
        coEvery { settingsDataStore.backupSettings() } returns BackupSettings(
            common = BackupCommonSettings(connectionTimeoutSeconds = 15),
            android = BackupAndroidSettings(terminalFontSize = 28, scrollbackLines = 20000, autoReconnect = false),
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
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    /** The decrypted JSON text of an exported file. */
    private fun plaintextOf(file: ByteArray): String {
        val unpacked = BackupEnvelope.unpack(file)
        assertNotNull(unpacked, "the file must hold a valid frame")
        val key = cryptoUtils.deriveKeyFromPassphrase(passphrase.toCharArray(), unpacked!!.salt)
        return String(cryptoUtils.decryptAesGcm(unpacked.sealed, key), Charsets.UTF_8)
    }

    private fun documentOf(file: ByteArray): BackupDocument {
        val decoded = BackupCodec.decode(plaintextOf(file))
        assertTrue(decoded is BackupDecodeResult.Decoded, "the export must decode, got $decoded")
        return (decoded as BackupDecodeResult.Decoded).document
    }

    @Test
    fun `the export carries every section of format 2 and always writes version 2`() = runTest {
        val file = exporter.export(passphrase.toCharArray()).bytes

        val text = plaintextOf(file)
        assertTrue(text.contains("\"version\":2"), "the version key must always be written")
        val doc = documentOf(file)
        assertEquals(2, doc.version)
        assertEquals(listOf("h1"), doc.hosts.map { it.id })
        assertEquals(mapOf("cred-h1" to "pw"), doc.credentials)
        assertEquals(mapOf("k1" to "PEM"), doc.privateKeys)
        assertEquals(mapOf("k1" to "pp"), doc.keyPassphrases)
        assertEquals(mapOf("k1" to "CERT"), doc.certificates)
        assertEquals(listOf("s1"), doc.snippets.map { it.id })
        assertEquals("System", doc.snippets.single().category)
        assertEquals("h1", doc.snippets.single().hostId)
        assertEquals(5L, doc.snippets.single().createdAt)
        assertEquals(listOf(THEME_ID), doc.customThemes.map { it.id })
        assertEquals(16, doc.customThemes.single().ansi.size)
        assertEquals("ANDROID", doc.producer?.platform)
        assertTrue(doc.producer!!.appVersion.isNotEmpty())
    }

    @Test
    fun `the exported document passes the shared validator without losing an entry`() = runTest {
        val doc = documentOf(exporter.export(passphrase.toCharArray()).bytes)

        val result = BackupValidator.validate(doc)

        assertTrue(result is BackupValidationResult.Valid)
        val validated = (result as BackupValidationResult.Valid).backup
        assertEquals(0, validated.invalidTotal)
        assertEquals(doc.snippets, validated.document.snippets)
        assertEquals(doc.customThemes, validated.document.customThemes)
    }

    @Test
    fun `the LAN sync secret is never exported`() = runTest {
        val file = exporter.export(passphrase.toCharArray()).bytes

        val text = plaintextOf(file)
        assertFalse(text.contains("lan-secret"))
        assertFalse(text.contains(LAN_SYNC_SECRET_KEY_PREFIX))
    }

    @Test
    fun `settings come from the allow-list only and no security setting is in the file`() = runTest {
        val doc = documentOf(exporter.export(passphrase.toCharArray()).bytes)

        val settings = doc.settings
        assertNotNull(settings)
        assertEquals(15, settings!!.common?.connectionTimeoutSeconds)
        assertEquals(28, settings.android?.terminalFontSize)
        assertNull(settings.desktop, "an Android export carries no desktop section")
        val text = plaintextOf(exporter.export(passphrase.toCharArray()).bytes).lowercase()
        for (forbidden in listOf("autolock", "auto_lock", "clipboard", "syncenabled", "sync_enabled", "syncinterval", "onboarding")) {
            assertFalse(text.contains(forbidden), "$forbidden must never be exported")
        }
    }

    @Test
    fun `a known host is exported in the portable form and reads back to the same local key`() = runTest {
        val local = KnownHostPortable.fromPortable("ssh-ed25519", ED25519_BLOB)
        assertNotNull(local)
        every { knownHostsVerifier.exportEntries() } returns
            listOf(KnownHostRecord("ed25519.example.org:22", local!!.algorithm, local.encoded))

        val doc = documentOf(exporter.export(passphrase.toCharArray()).bytes)

        val known = doc.knownHosts.single()
        assertEquals("ed25519.example.org:22", known.hostPort)
        assertEquals("ssh-ed25519", known.keyType)
        assertEquals(ED25519_BLOB, known.publicKey)
        assertEquals(local, KnownHostPortable.fromPortable(known.keyType, known.publicKey))
    }

    @Test
    fun `a known host that cannot be converted is skipped, the export still succeeds`() = runTest {
        val local = KnownHostPortable.fromPortable("ssh-ed25519", ED25519_BLOB)!!
        every { knownHostsVerifier.exportEntries() } returns listOf(
            KnownHostRecord("odd.example.org:22", "DSA", byteArrayOf(1, 2, 3)),
            KnownHostRecord("ed25519.example.org:22", local.algorithm, local.encoded),
        )

        val doc = documentOf(exporter.export(passphrase.toCharArray()).bytes)

        assertEquals(listOf("ed25519.example.org:22"), doc.knownHosts.map { it.hostPort })
    }

    @Test
    fun `the file uses the shared frame with a 32 byte salt`() = runTest {
        val file = exporter.export(passphrase.toCharArray()).bytes

        val unpacked = BackupEnvelope.unpack(file)
        assertNotNull(unpacked)
        assertEquals(32, unpacked!!.salt.size)
        // [SALT_LEN(4 BE)] first, like the 1.0.x files.
        assertEquals(listOf<Byte>(0, 0, 0, 32), file.take(4))
    }

    @Test
    fun `the passphrase is wiped after the export`() = runTest {
        val chars = passphrase.toCharArray()

        exporter.export(chars)

        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun `a clean export has nothing that cannot be restored`() = runTest {
        assertEquals(0, exporter.export(passphrase.toCharArray()).notRestorable)
    }

    @Test
    fun `snippets and themes that the import would refuse are counted and still exported`() = runTest {
        val bidi = String(Character.toChars(0x202E))
        every { snippetRepository.observeAll() } returns flowOf(
            listOf(
                Snippet(id = "ok", label = "Fine", command = "ls", createdAt = 1L),
                Snippet(id = "bad-label", label = "x$bidi", command = "ls", createdAt = 1L),
                Snippet(id = "bad-command", label = "Cmd", command = "ls" + String(Character.toChars(0x200B)), createdAt = 1L),
            ),
        )
        every { themeRepository.observeAll() } returns flowOf(
            listOf(
                CustomTerminalTheme(
                    id = THEME_ID,
                    name = "Bad$bidi",
                    background = -16777216,
                    foreground = -1,
                    cursor = -1,
                    selectionBg = 0x40FFFFFF,
                    ansi = List(16) { -16777216 },
                    createdAt = 7L,
                ),
            ),
        )

        val export = exporter.export(passphrase.toCharArray())

        assertEquals(3, export.notRestorable)
        // Not blocked: everything is in the file.
        val doc = documentOf(export.bytes)
        assertEquals(3, doc.snippets.size)
        assertEquals(1, doc.customThemes.size)
    }

    @Test
    fun `a host with a port out of range or a host name with a space is counted and still exported`() = runTest {
        every { hostRepository.observeAll() } returns flowOf(
            listOf(
                host("h1"),
                host("bad-port").copy(port = 0),
                host("bad-name").copy(hostname = "inner space.example.org"),
            ),
        )

        val export = exporter.export(passphrase.toCharArray())

        assertEquals(2, export.notRestorable)
        assertEquals(3, documentOf(export.bytes).hosts.size)
    }

    @Test
    fun `an SSH key whose public key spans two lines is cleaned to one line, so not counted, and still exported`() = runTest {
        every { sshKeyRepository.observeAll() } returns flowOf(
            listOf(
                SshKey(id = "k-ok", label = "Fine", keyType = SshKeyType.ED25519, publicKey = "ssh-ed25519 AAAA fine"),
                SshKey(
                    id = "k-two-lines",
                    label = "Two lines",
                    keyType = SshKeyType.ED25519,
                    publicKey = "ssh-ed25519 AAAA first\nssh-ed25519 BBBB second",
                ),
            ),
        )

        val export = exporter.export(passphrase.toCharArray())

        assertEquals(0, export.notRestorable)
        assertEquals(2, documentOf(export.bytes).sshKeys.size)
    }

    @Test
    fun `a tunnel with a local port of 0 is counted and still exported`() = runTest {
        every { tunnelRepository.observeAll() } returns flowOf(
            listOf(
                tunnel("t-ok", "h1"),
                tunnel("t-port-0", "h1").copy(localPort = 0),
            ),
        )

        val export = exporter.export(passphrase.toCharArray())

        assertEquals(1, export.notRestorable)
        assertEquals(2, documentOf(export.bytes).tunnels.size)
    }

    @Test
    fun `a secret over 64 KiB is counted and still exported`() = runTest {
        val tooBig = "x".repeat(64 * 1024 + 1)
        every { vaultManager.getPrivateKey("k1") } returns tooBig
        every { vaultManager.getPassword("cred-h1") } answers { tooBig.toCharArray() }

        val export = exporter.export(passphrase.toCharArray())

        assertEquals(2, export.notRestorable)
        val doc = documentOf(export.bytes)
        assertEquals(tooBig, doc.privateKeys["k1"])
        assertEquals(tooBig, doc.credentials["cred-h1"])
    }

    @Test
    fun `a tunnel of an invalid host is counted once, like the import counts it`() = runTest {
        every { hostRepository.observeAll() } returns flowOf(listOf(host("h1"), host("bad-port").copy(port = 70000)))
        every { tunnelRepository.observeAll() } returns flowOf(listOf(tunnel("t-ok", "h1"), tunnel("t-cascade", "bad-port")))

        val export = exporter.export(passphrase.toCharArray())

        // The host, and its tunnel which the import drops with it: two items, neither counted twice.
        assertEquals(2, export.notRestorable)
        val validated = (BackupValidator.validate(documentOf(export.bytes)) as BackupValidationResult.Valid).backup
        assertEquals(1, validated.invalidHosts)
        assertEquals(1, validated.invalidTunnels)
        assertEquals(export.notRestorable, validated.invalidTotal)
    }

    @Test
    fun `the export result shows no content in toString`() = runTest {
        val export = exporter.export(passphrase.toCharArray())

        assertFalse(export.toString().contains("pw"))
        assertTrue(export.toString().startsWith("VaultExport("))
    }

    @Test
    fun `the log holds counts only`() = runTest {
        val local = KnownHostPortable.fromPortable("ssh-ed25519", ED25519_BLOB)!!
        every { knownHostsVerifier.exportEntries() } returns
            listOf(KnownHostRecord("secret-host.example.org:22", local.algorithm, local.encoded))
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            exporter.export(passphrase.toCharArray()).bytes
        } finally {
            Timber.uproot(tree)
        }

        val logged = tree.entries.joinToString("\n") { it.message }
        for (secret in listOf("example.org", "Uptime", "Night", "pw", "PEM", "CERT", "lan-secret")) {
            // "pw" is too short to be meaningful on its own: look for it as a whole word.
            assertFalse(Regex("\\b${Regex.escape(secret)}\\b").containsMatchIn(logged), "$secret must not be logged")
        }
        assertTrue(tree.entries.all { it.throwable == null })
    }

    private companion object {
        const val THEME_ID = "7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d"
        const val ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi"
    }
}
