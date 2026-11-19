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
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupDecodeResult
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupPlatform
import fr.techtical.nextsh.shared.core.backup.BackupSettings
import fr.techtical.nextsh.shared.core.backup.BackupValidationResult
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.backup.SshPublicKeyLine
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.testutil.ProjectFiles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.Security
import javax.crypto.SecretKeyFactory

/**
 * Cross-platform compatibility: reads the shared fixtures of `testdata/backup/` (encrypted by a
 * neutral JDK-only generator, never by NextSH code) with the real Android [CryptoUtils], and finds
 * the counts of `testdata/backup/README.md`. The passphrase is NOT ASCII, so the PBKDF2 key must be
 * the same whether the provider is SunJCE (Desktop) or BouncyCastle in first position, as
 * `App.setupBouncyCastleProvider` does on Android.
 *
 * The files must be there: a missing fixture fails the test (never skipped).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupFixtureCompatTest {

    private val crypto = CryptoUtils()

    /** `Fixture-vault-` + U+00E9 + `-2026`, built by code point: the file must stay ASCII. */
    private val passphrase: String = "Fixture-vault-" + String(Character.toChars(0x00E9)) + "-2026"

    private fun fixture(name: String): ByteArray {
        val file = File(ProjectFiles.checkout.root, "testdata/backup/$name")
        assertTrue(file.isFile, "fixture ${file.path} not found: the shared fixtures were moved, update this test")
        return file.readBytes()
    }

    /** Runs [block] with BouncyCastle in first position, like the Android app, then puts the providers back. */
    private fun <T> withBouncyCastleFirst(block: () -> T): T {
        // SSHJ remembers the provider it finds at its first use: initialize it BEFORE touching the
        // providers, or it would memorize "BC" and fail once BC is removed again.
        SecurityUtils.getSecurityProvider()
        val saved = Security.getProviders().toList()
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        try {
            assertEquals(
                "BC",
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").provider.name,
                "the test must really derive the key through BouncyCastle",
            )
            return block()
        } finally {
            // Put back EXACTLY the providers of before, same instances, same order.
            Security.getProviders().forEach { Security.removeProvider(it.name) }
            saved.forEachIndexed { index, provider -> Security.insertProviderAt(provider, index + 1) }
        }
    }

    private fun decrypt(file: ByteArray): ByteArray {
        val unpacked = requireFrame(BackupEnvelope.unpack(file), "the fixture must hold a valid frame")
        val key = crypto.deriveKeyFromPassphrase(passphrase.toCharArray(), unpacked.salt)
        return crypto.decryptAesGcm(unpacked.sealed, key)
    }

    private fun decoded(name: String): BackupDecodeResult.Decoded {
        val result = BackupCodec.decode(decrypt(fixture(name)))
        assertTrue(result is BackupDecodeResult.Decoded, "$name must decode, got $result")
        return result as BackupDecodeResult.Decoded
    }

    private fun requireFrame(value: BackupEnvelope.Unpacked?, message: String): BackupEnvelope.Unpacked {
        org.junit.jupiter.api.Assertions.assertNotNull(value, message)
        return value!!
    }

    // ── Key derivation ───────────────────────────────────────────────────────

    @Test
    fun `a non ASCII passphrase derives the same key through SunJCE and BouncyCastle`() {
        val salt = ByteArray(32) { (it * 7).toByte() }

        val viaDefault = crypto.deriveKeyFromPassphrase(passphrase.toCharArray(), salt)
        val viaBc = withBouncyCastleFirst { crypto.deriveKeyFromPassphrase(passphrase.toCharArray(), salt) }

        assertArrayEquals(viaDefault, viaBc)
    }

    @Test
    fun `every fixture decrypts with the BouncyCastle first provider order of the app`() {
        withBouncyCastleFirst {
            for (name in listOf("v1-sans-version.nextsh", "v1-coeur-invalide.nextsh", "v2-complet.nextsh", "v2-sections-inconnues.nextsh", "v3.nextsh")) {
                val json = decrypt(fixture(name))
                assertTrue(json.isNotEmpty(), "$name must decrypt")
                assertTrue(BackupCodec.decode(json) !is BackupDecodeResult.Malformed, "$name must decode")
            }
        }
    }

    @Test
    fun `a wrong passphrase does not open a fixture`() {
        val unpacked = requireFrame(BackupEnvelope.unpack(fixture("v2-complet.nextsh")), "frame")
        val key = crypto.deriveKeyFromPassphrase("Fixture-vault-e-2026".toCharArray(), unpacked.salt)

        val failure = runCatching { crypto.decryptAesGcm(unpacked.sealed, key) }.exceptionOrNull()

        assertTrue(failure is java.security.GeneralSecurityException, "the plain e instead of U+00E9 must not open the file")
    }

    // ── Documents ────────────────────────────────────────────────────────────

    @Test
    fun `v1 without version is read as version 1 with the README counts`() {
        val d = decoded("v1-sans-version.nextsh")

        assertEquals(1, d.document.version)
        assertEquals(2, d.document.hosts.size)
        assertEquals(1, d.document.sshKeys.size)
        assertEquals(1, d.document.tunnels.size)
        assertEquals(1, d.document.credentials.size)
        assertEquals(1, d.document.privateKeys.size)
        assertEquals(0, d.document.keyPassphrases.size)
        assertTrue(d.document.certificates.isEmpty())
        assertTrue(d.document.snippets.isEmpty())
        assertTrue(d.document.customThemes.isEmpty())
        assertTrue(d.document.knownHosts.isEmpty())
        assertEquals(null, d.document.settings)
        assertFalse(d.hasUnknownSections)
        assertEquals("DRACULA", d.document.hosts[1].terminalTheme)
    }

    @Test
    fun `v2 complete has the README counts and every entry is valid`() {
        val d = decoded("v2-complet.nextsh")
        val doc = d.document

        assertEquals(2, doc.version)
        assertEquals("DESKTOP", doc.producer?.platform)
        assertEquals(3, doc.hosts.size)
        assertEquals(1, doc.sshKeys.size)
        assertEquals(2, doc.tunnels.size)
        assertEquals(2, doc.credentials.size)
        assertEquals(1, doc.privateKeys.size)
        assertEquals(1, doc.keyPassphrases.size)
        assertEquals(1, doc.certificates.size)
        assertEquals(3, doc.snippets.size)
        assertEquals(1, doc.customThemes.size)
        assertEquals(5, doc.knownHosts.size)
        assertFalse(d.hasUnknownSections)

        val validation = BackupValidator.validate(doc) { KnownHostPortable.fromPortable(it.keyType, it.publicKey) != null }
        assertTrue(validation is BackupValidationResult.Valid)
        val validated = (validation as BackupValidationResult.Valid).backup
        assertEquals(0, validated.invalidTotal, "no invalid entry in the complete fixture")
        assertEquals(5, validated.document.knownHosts.size, "one known host per key type, all readable by SSHJ")
        assertEquals(5, validated.document.settings!!.countFor(BackupPlatform.ANDROID))
        assertEquals(3, validated.document.settings!!.countFor(BackupPlatform.DESKTOP))
    }

    @Test
    fun `v2 with unknown sections is read and the unknown keys are flagged`() {
        val d = decoded("v2-sections-inconnues.nextsh")

        assertTrue(d.hasUnknownSections)
        assertEquals(1, d.document.hosts.size)
        assertEquals(1, d.document.credentials.size)
        assertEquals(1, d.document.snippets.size)
    }

    @Test
    fun `v3 is refused as unsupported before its sections are decoded`() {
        val result = BackupCodec.decode(decrypt(fixture("v3.nextsh")))

        assertTrue(result is BackupDecodeResult.UnsupportedVersion)
        assertEquals(3, (result as BackupDecodeResult.UnsupportedVersion).version)
    }

    // ── End to end through the real importer ─────────────────────────────────

    private fun importerWith(
        knownHosts: KnownHostsVerifier,
        settings: SettingsDataStore,
    ) = VaultImporter(
        vaultManager = mockk<VaultManager>(relaxed = true).also { every { it.isAuthRequired() } returns false },
        sshKeyRepository = mockk<SshKeyRepository>(relaxed = true).also { coEvery { it.getById(any()) } returns null },
        hostRepository = mockk<HostRepository>(relaxed = true),
        tunnelRepository = mockk<TunnelRepository>(relaxed = true),
        backupRestoreStore = mockk<BackupRestoreStore>(relaxed = true),
        knownHostsVerifier = knownHosts,
        settingsDataStore = settings,
        cryptoUtils = crypto,
        transactionRunner = FakeTransactionRunner(),
    )

    /** The collaborators of an import of the complete fixture, kept to check what was really written. */
    private class Rig {
        val vault = mockk<VaultManager>(relaxed = true).also { every { it.isAuthRequired() } returns false }
        val keys = mockk<SshKeyRepository>(relaxed = true)
        val hosts = mockk<HostRepository>(relaxed = true)
        val tunnels = mockk<TunnelRepository>(relaxed = true)
        val restore = mockk<BackupRestoreStore>(relaxed = true)
        val verifier = mockk<KnownHostsVerifier>()
        val settingsStore = mockk<SettingsDataStore>()
        val candidates = slot<List<KnownHostCandidate>>()
        val settings = slot<BackupSettings>()

        init {
            coEvery { restore.localHostsIncludingDeleted() } returns emptyList()
            coEvery { keys.getById(any()) } returns null
        }
    }

    private fun importComplete(rig: Rig = Rig()): Pair<BackupImportReport, Rig> {
        every { rig.verifier.importEntries(capture(rig.candidates), any()) } returns
            KnownHostsImportOutcome(added = 5, alreadyKnown = 0, conflicts = emptyList())
        coEvery { rig.settingsStore.applyBackupSettings(capture(rig.settings)) } answers {
            rig.settings.captured.countFor(BackupPlatform.ANDROID)
        }
        val importer = VaultImporter(
            vaultManager = rig.vault,
            sshKeyRepository = rig.keys,
            hostRepository = rig.hosts,
            tunnelRepository = rig.tunnels,
            backupRestoreStore = rig.restore,
            knownHostsVerifier = rig.verifier,
            settingsDataStore = rig.settingsStore,
            cryptoUtils = crypto,
            transactionRunner = FakeTransactionRunner(),
        )

        var report: BackupImportReport? = null
        kotlinx.coroutines.runBlocking {
            report = importer.import(fixture("v2-complet.nextsh"), passphrase.toCharArray()).getOrThrow()
        }
        return report!! to rig
    }

    /** What the complete fixture must have written, by the identifiers of the file itself. */
    private fun verifyCompleteFixtureWritten(rig: Rig) {
        val hostIds = listOf(
            "0b6c1d2e-0000-4000-8000-000000000001",
            "0b6c1d2e-0000-4000-8000-000000000002",
            "0b6c1d2e-0000-4000-8000-000000000003",
        )
        for (id in hostIds) coVerify(exactly = 1) { rig.hosts.save(match { it.id == id }) }
        coVerify(exactly = 1) { rig.keys.save(match { it.id == "fixture-key-1" }) }
        for (id in listOf("fixture-tunnel-1", "fixture-tunnel-2")) {
            coVerify(exactly = 1) { rig.tunnels.save(match { it.id == id }) }
        }
        coVerify(exactly = 1) {
            rig.restore.restoreSnippets(match { list -> list.map { it.id } == listOf("fixture-snippet-1", "fixture-snippet-2", "fixture-snippet-3") })
        }
        coVerify(exactly = 1) {
            rig.restore.restoreThemes(match { list -> list.map { it.id } == listOf("7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d") })
        }
        verify(exactly = 1) { rig.vault.storePassword("fixture-cred-web", any()) }
        verify(exactly = 1) { rig.vault.storePassword("fixture-cred-bastion", any()) }
        verify(exactly = 1) { rig.vault.storePrivateKey("fixture-key-1", "FIXTURE-NOT-A-REAL-PRIVATE-KEY") }
        verify(exactly = 1) { rig.vault.storeKeyPassphrase("fixture-key-1", any()) }
        verify(exactly = 1) { rig.vault.storeCertificate("fixture-cert-1", "FIXTURE-NOT-A-REAL-CERTIFICATE") }
        verify(exactly = 1) { rig.verifier.importEntries(any(), any()) }
        coVerify(exactly = 1) { rig.settingsStore.applyBackupSettings(any()) }
        assertEquals(15, rig.settings.captured.common?.connectionTimeoutSeconds)
        assertEquals(28, rig.settings.captured.android?.terminalFontSize)
    }

    @Test
    fun `the real importer restores the complete fixture with the README counts`() {
        val (report, rig) = importComplete()
        val candidates = rig.candidates.captured
        verifyCompleteFixtureWritten(rig)

        assertEquals(3, report.hosts)
        assertEquals(1, report.sshKeys)
        assertEquals(2, report.tunnels)
        assertEquals(2, report.passwords)
        assertEquals(1, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(1, report.certificates)
        assertEquals(3, report.snippets)
        assertEquals(1, report.customThemes)
        assertEquals(5, report.knownHostsAdded)
        assertEquals(5, report.settingsApplied)
        assertEquals(0, report.invalidIgnored)
        assertEquals(0, report.tunnelsSkipped)
        assertFalse(report.newerDataSkipped)
        assertEquals(
            listOf(
                "ed25519.fixture.test:22",
                "rsa.fixture.test:22",
                "ecdsa256.fixture.test:22",
                "ecdsa384.fixture.test:22",
                "ecdsa521.fixture.test:2222",
            ),
            candidates.map { it.hostPort },
        )
    }

    @Test
    fun `the real importer reads the complete fixture the same way under BouncyCastle first`() {
        val (report, rig) = withBouncyCastleFirst { importComplete() }

        verifyCompleteFixtureWritten(rig)
        assertEquals(3, report.hosts)
        assertEquals(5, report.knownHostsAdded)
        assertEquals(0, report.invalidIgnored)
    }

    @Test
    fun `the real importer refuses v3 and restores the known data of the unknown sections fixture`() = runTest {
        val verifier = mockk<KnownHostsVerifier>()
        val store = mockk<SettingsDataStore>()
        val importer = importerWith(verifier, store)

        val refused = importer.import(fixture("v3.nextsh"), passphrase.toCharArray()).exceptionOrNull()
        assertTrue(refused is VaultBackupReadException)
        assertEquals(VaultBackupReadException.Reason.UNSUPPORTED_VERSION, (refused as VaultBackupReadException).reason)

        val report = importer.import(fixture("v2-sections-inconnues.nextsh"), passphrase.toCharArray()).getOrThrow()
        assertTrue(report.newerDataSkipped)
        assertEquals(1, report.hosts)
        assertEquals(1, report.passwords)
        assertEquals(1, report.snippets)
        verify(exactly = 0) { verifier.importEntries(any(), any()) }
    }

    // ── Invalid v1 core ──────────────────────────────────────────────────────

    @Test
    fun `the invalid core fixture is read as version 1 with the raw README counts`() {
        val d = decoded("v1-coeur-invalide.nextsh")
        val doc = d.document

        assertEquals(1, doc.version)
        assertEquals(11, doc.hosts.size)
        assertEquals(5, doc.sshKeys.size)
        assertEquals(7, doc.tunnels.size)
        assertEquals(5, doc.credentials.size)
        assertEquals(3, doc.privateKeys.size)
        assertEquals(2, doc.keyPassphrases.size)
        assertTrue(doc.certificates.isEmpty())
        assertTrue(doc.snippets.isEmpty())
        assertTrue(doc.customThemes.isEmpty())
        assertTrue(doc.knownHosts.isEmpty())
        assertFalse(d.hasUnknownSections)
    }

    @Test
    fun `the invalid core fixture is reduced by the validator to the README counts`() {
        val validation = BackupValidator.validate(decoded("v1-coeur-invalide.nextsh").document)
        assertTrue(validation is BackupValidationResult.Valid)
        val validated = (validation as BackupValidationResult.Valid).backup

        assertEquals(6, validated.invalidHosts)
        assertEquals(3, validated.invalidSshKeys)
        assertEquals(4, validated.invalidTunnels)
        assertEquals(5, validated.invalidSecrets)
        assertEquals(0, validated.invalidSnippets + validated.invalidThemes + validated.invalidKnownHosts +
            validated.invalidSettings + validated.invalidCertificates)
        assertEquals(18, validated.invalidTotal)
        val doc = validated.document
        assertEquals(setOf("h-ok-1", "h-ok-2", "h-ok-3", "h-label-cleaned", "h-user-cleaned"), doc.hosts.map { it.id }.toSet())
        assertEquals(5, doc.hosts.size)
        // A control character in a label is replaced, not refused: the host is kept.
        assertEquals("Line break", doc.hosts.single { it.id == "h-label-cleaned" }.label)
        // A control character in a user name is replaced too: the host and its tunnels are kept.
        assertEquals("ro ot", doc.hosts.single { it.id == "h-user-cleaned" }.username)
        assertEquals(listOf("key-ok", "key-multiline"), doc.sshKeys.map { it.id })
        assertEquals(listOf("t-ok", "t-socks", "t-orphan"), doc.tunnels.map { it.id })
        assertEquals(setOf("cred-ok", "cred-empty"), doc.credentials.keys)
        assertEquals(listOf("key-ok"), doc.privateKeys.keys.toList())
        assertEquals(listOf("key-ok"), doc.keyPassphrases.keys.toList())
    }

    /** Imports the invalid core fixture, [t-orphan]'s host being [orphanHostPresentLocally] on the device. */
    private fun importInvalidCore(orphanHostPresentLocally: Boolean): Pair<BackupImportReport, Rig> {
        val rig = Rig()
        coEvery { rig.hosts.getById(any()) } returns null
        if (orphanHostPresentLocally) {
            coEvery { rig.hosts.getById("host-not-in-file") } returns fr.techtical.nextsh.domain.model.Host(
                id = "host-not-in-file",
                label = "Local",
                hostname = "local.example.test",
                username = "root",
                authType = fr.techtical.nextsh.domain.model.AuthType.PASSWORD,
                credentialId = "",
            )
        }
        val importer = VaultImporter(
            vaultManager = rig.vault,
            sshKeyRepository = rig.keys,
            hostRepository = rig.hosts,
            tunnelRepository = rig.tunnels,
            backupRestoreStore = rig.restore,
            knownHostsVerifier = rig.verifier,
            settingsDataStore = rig.settingsStore,
            cryptoUtils = crypto,
            transactionRunner = FakeTransactionRunner(),
        )
        var report: BackupImportReport? = null
        kotlinx.coroutines.runBlocking {
            report = importer.import(fixture("v1-coeur-invalide.nextsh"), passphrase.toCharArray()).getOrThrow()
        }
        return report!! to rig
    }

    @Test
    fun `the real importer restores only the valid core of the invalid core fixture`() {
        val (report, rig) = importInvalidCore(orphanHostPresentLocally = true)

        assertEquals(5, report.hosts)
        assertEquals(2, report.sshKeys)
        assertEquals(3, report.tunnels)
        assertEquals(2, report.passwords)
        assertEquals(1, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(18, report.invalidIgnored)
        assertEquals(0, report.tunnelsSkipped)
        for (id in listOf("h-ok-1", "h-ok-2", "h-ok-3", "h-label-cleaned", "h-user-cleaned")) {
            coVerify(exactly = 1) { rig.hosts.save(match { it.id == id }) }
        }
        coVerify(exactly = 1) { rig.hosts.save(match { it.id == "h-label-cleaned" && it.label == "Line break" }) }
        coVerify(exactly = 5) { rig.hosts.save(any()) }
        coVerify(exactly = 1) { rig.keys.save(match { it.id == "key-ok" }) }
        coVerify(exactly = 1) { rig.keys.save(match { it.id == "key-multiline" && it.publicKey.lines().size == 1 }) }
        coVerify(exactly = 2) { rig.keys.save(any()) }
        for (id in listOf("t-ok", "t-socks", "t-orphan")) coVerify(exactly = 1) { rig.tunnels.save(match { it.id == id }) }
        coVerify(exactly = 3) { rig.tunnels.save(any()) }
        verify(exactly = 1) { rig.vault.storePassword("cred-ok", any()) }
        verify(exactly = 1) { rig.vault.storePassword("cred-empty", any()) }
        verify(exactly = 2) { rig.vault.storePassword(any(), any()) }
        verify(exactly = 0) { rig.vault.storePassword(match { it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }, any()) }
        verify(exactly = 1) { rig.vault.storePrivateKey("key-ok", any()) }
        verify(exactly = 1) { rig.vault.storePrivateKey(any(), any()) }
        verify(exactly = 1) { rig.vault.storeKeyPassphrase("key-ok", any()) }
        verify(exactly = 1) { rig.vault.storeKeyPassphrase(any(), any()) }
    }

    @Test
    fun `a tunnel dropped with its host is counted invalid only, never also skipped`() {
        val (report, rig) = importInvalidCore(orphanHostPresentLocally = false)

        // t-orphan has no host in the file nor on the device: the importer skips it (existing rule).
        // t-cascade (host dropped by the validator) never reaches that loop: invalid, not skipped.
        assertEquals(2, report.tunnels)
        assertEquals(1, report.tunnelsSkipped)
        assertEquals(18, report.invalidIgnored)
        coVerify(exactly = 0) { rig.tunnels.save(match { it.id == "t-cascade" }) }
        coVerify(exactly = 0) { rig.tunnels.save(match { it.id == "t-orphan" }) }
    }

    // ── Inconsistent keys fixture (v2-cles-incoherentes) ─────────────────────

    private val keysFixture = "v2-cles-incoherentes.nextsh"

    /** The public line of key A: the first line of `key-two-lines` (its second line is the one of B). */
    private fun lineOfA(): String =
        decoded(keysFixture).document.sshKeys.single { it.id == "key-two-lines" }.publicKey.lines().first()

    private fun lineOfB(): String = decoded(keysFixture).document.sshKeys.single { it.id == "key-kept" }.publicKey

    private fun blobOf(line: String): String = checkNotNull(SshPublicKeyLine.blobKey(line)) { "not a public key line" }

    private fun importKeysFixture(rig: Rig): Pair<BackupImportReport, List<SshKey>> {
        val saved = mutableListOf<SshKey>()
        coEvery { rig.keys.save(capture(saved)) } returns Unit
        val importer = VaultImporter(
            vaultManager = rig.vault,
            sshKeyRepository = rig.keys,
            hostRepository = rig.hosts,
            tunnelRepository = rig.tunnels,
            backupRestoreStore = rig.restore,
            knownHostsVerifier = rig.verifier,
            settingsDataStore = rig.settingsStore,
            cryptoUtils = crypto,
            transactionRunner = FakeTransactionRunner(),
        )
        var report: BackupImportReport? = null
        kotlinx.coroutines.runBlocking {
            report = importer.import(fixture(keysFixture), passphrase.toCharArray()).getOrThrow()
        }
        return report!! to saved
    }

    @Test
    fun `the inconsistent keys fixture is entirely valid and has the README counts`() {
        val validation = BackupValidator.validate(decoded(keysFixture).document)
        assertTrue(validation is BackupValidationResult.Valid)
        val validated = (validation as BackupValidationResult.Valid).backup

        assertEquals(0, validated.invalidTotal)
        assertEquals(5, validated.document.sshKeys.size)
        assertEquals(5, validated.document.privateKeys.size)
        assertEquals(1, validated.document.keyPassphrases.size)
    }

    @Test
    fun `on a blank device only the key with a wrong line and a clear private key is recomputed`() {
        val (report, saved) = importKeysFixture(Rig())
        val byId = saved.associateBy { it.id }

        assertEquals(0, report.invalidIgnored)
        assertEquals(5, report.sshKeys)
        assertEquals(5, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(1, report.publicKeysRecomputed)
        assertEquals(0, report.localKeysKept)
        // A's line, with the comment of the file (the one of the line of B).
        val recomputed = byId.getValue("key-a-line-b").publicKey
        assertEquals(blobOf(lineOfA()), blobOf(recomputed))
        assertEquals("fixture-b", SshPublicKeyLine.comment(recomputed))
        // Encrypted key: nothing proven without a passphrase, the line of the file stays.
        assertEquals(lineOfB(), byId.getValue("key-enc-line-b").publicKey)
        // Two lines become one, starting with the line of A.
        val twoLines = byId.getValue("key-two-lines").publicKey
        assertFalse(twoLines.contains('\n'))
        assertTrue(twoLines.startsWith(lineOfA()), twoLines)
        // Consistent keys are unchanged.
        val doc = decoded(keysFixture).document
        assertEquals(doc.sshKeys.single { it.id == "key-rsa" }.publicKey, byId.getValue("key-rsa").publicKey)
        assertEquals(lineOfB(), byId.getValue("key-kept").publicKey)
    }

    @Test
    fun `on a device that already holds key-kept its line and private key stay, the label comes from the file`() {
        val doc = decoded(keysFixture).document
        val rig = Rig()
        // The device holds key-kept with the line of A and the private key A (the file's one is B).
        coEvery { rig.keys.getById("key-kept") } returns SshKey(
            id = "key-kept",
            label = "Local label",
            keyType = fr.techtical.nextsh.domain.model.SshKeyType.ED25519,
            publicKey = lineOfA(),
        )
        every { rig.vault.getPrivateKey("key-kept") } returns doc.privateKeys.getValue("key-a-line-b")

        val (report, saved) = importKeysFixture(rig)
        val kept = saved.single { it.id == "key-kept" }

        assertEquals(lineOfA(), kept.publicKey)
        assertEquals(doc.sshKeys.single { it.id == "key-kept" }.label, kept.label)
        verify(exactly = 0) { rig.vault.storePrivateKey("key-kept", any()) }
        verify(exactly = 4) { rig.vault.storePrivateKey(any(), any()) }
        assertEquals(4, report.privateKeys)
        assertEquals(1, report.localKeysKept)
        assertEquals(1, report.publicKeysRecomputed)
    }
}
