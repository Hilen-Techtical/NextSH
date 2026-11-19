// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopVaultRestoreStore
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.data.preferences.InMemoryPreferences
import fr.techtical.nextsh.desktop.data.preferences.LanguagePref
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.domain.model.SshKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-platform compatibility: reads the `.nextsh` files of `testdata/backup/` at the root of the repository,
 * encrypted by a neutral generator (`EncryptFixture.java`, JDK only) that is neither Android's `CryptoUtils`
 * nor [fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils]. The Android tests read the very same files
 * and expect the same counters, written in `testdata/backup/README.md`.
 *
 * The passphrase is NOT ASCII on purpose: PBKDF2 goes through BouncyCastle on Android and through SunJCE
 * here, and a different handling of the accent would only show with such a passphrase.
 *
 * A missing folder fails the test (it never skips it: a guard that skips itself guards nothing).
 */
class DesktopBackupFixturesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var vault: FakeVaultManagerMem
    private lateinit var settings: DesktopSettingsStore
    private lateinit var importer: DesktopVaultImporter

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        vault = FakeVaultManagerMem()
        // In memory: never the real preferences of the user (the Windows registry).
        settings = DesktopSettingsStore(InMemoryPreferences())
        importer = DesktopVaultImporter(vault, DesktopVaultRestoreStore(db), settings)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `v1 file without a version key, written by the 1_0_x format`() = runTest {
        val report = importFixture("v1-sans-version.nextsh")

        assertEquals(2, report.hosts)
        assertEquals(1, report.sshKeys)
        assertEquals(1, report.tunnels)
        assertEquals(1, report.passwords)
        assertEquals(1, report.privateKeys)
        assertEquals(0, report.keyPassphrases)
        assertEquals(0, report.certificates)
        assertEquals(0, report.snippets)
        assertEquals(0, report.customThemes)
        assertEquals(0, report.knownHostsAdded)
        assertEquals(0, report.settingsApplied)
        assertEquals(0, report.invalidIgnored)
        assertFalse(report.newerDataSkipped)
        assertEquals("DRACULA", db.hostQueries.selectByIdIncludingDeleted("1a2b3c4d-0000-4000-8000-000000000002").executeAsOne().terminalTheme)
        assertEquals("FIXTURE-legacy-password", vault.getPassword("legacy-cred-1")?.concatToString())
    }

    @Test
    fun `v2 complete file restores every section`() = runTest {
        val report = importFixture("v2-complet.nextsh")

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
        assertEquals(0, report.knownHostsAlreadyKnown)
        assertEquals(0, report.knownHostConflicts)
        assertEquals(0, report.invalidIgnored)
        assertEquals(0, report.tunnelsSkipped)
        assertFalse(report.newerDataSkipped)
        assertEquals(3, report.settingsApplied, "Desktop applies common (1) + desktop (2), never the android section")
        assertTrue(report.languageChanged, "SYSTEM to FR takes effect at the next start")

        // Secrets, including the certificate that 1.0.x never carried.
        assertEquals("FIXTURE-password-web", vault.getPassword("fixture-cred-web")?.concatToString())
        assertEquals("FIXTURE-NOT-A-REAL-PRIVATE-KEY", vault.getPrivateKey("fixture-key-1"))
        assertEquals("FIXTURE-key-passphrase", vault.getKeyPassphrase("fixture-key-1")?.concatToString())
        assertEquals("FIXTURE-NOT-A-REAL-CERTIFICATE", vault.getCertificate("fixture-cert-1"))

        // The accent of the first snippet survived the encryption and the JSON.
        val snippet = db.snippetQueries.selectByIdIncludingDeleted("fixture-snippet-1").executeAsOne()
        assertTrue(snippet.label.contains(Char(0xE9)))
        assertEquals(
            "df -h\n\tdu -sh /var/log",
            db.snippetQueries.selectByIdIncludingDeleted("fixture-snippet-2").executeAsOne().command,
        )

        // The custom theme and the host that uses it.
        val themeId = "7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d"
        assertNotNull(db.customTerminalThemeQueries.selectByIdIncludingDeleted(themeId).executeAsOneOrNull())
        assertEquals(
            themeId,
            db.hostQueries.selectByIdIncludingDeleted("0b6c1d2e-0000-4000-8000-000000000003").executeAsOne().terminalTheme,
        )

        // Known hosts: one per key type, stored as the key SSHJ itself reads from the blob.
        val rows = db.knownHostQueries.selectAll().executeAsList()
        assertEquals(
            setOf(
                "ed25519.fixture.test:22",
                "rsa.fixture.test:22",
                "ecdsa256.fixture.test:22",
                "ecdsa384.fixture.test:22",
                "ecdsa521.fixture.test:2222",
            ),
            rows.map { it.hostPort }.toSet(),
        )
        val ed = KnownHostPortable.fromPortable("ssh-ed25519", ED25519_BLOB) ?: error("fixture key unreadable")
        val edRow = rows.first { it.hostPort == "ed25519.fixture.test:22" }
        assertEquals(ed.algorithm, edRow.algorithm)
        assertContentEquals(ed.encoded, edRow.publicKeyEncoded)
        // The same key now verifies as known: a second import counts them all as already known.
        val again = importFixture("v2-complet.nextsh")
        assertEquals(0, again.knownHostsAdded)
        assertEquals(5, again.knownHostsAlreadyKnown)

        // Settings: common + desktop applied, the android values (font 28) are not.
        val s = settings.settings.value
        assertEquals(15, s.connectionTimeout)
        assertEquals(16, s.terminalFontSize)
        assertEquals(LanguagePref.FR, s.languagePreference)
    }

    @Test
    fun `v2 file with unknown top-level sections restores the known part and warns`() = runTest {
        val report = importFixture("v2-sections-inconnues.nextsh")

        assertEquals(1, report.hosts)
        assertEquals(1, report.passwords)
        assertEquals(1, report.snippets)
        assertEquals(0, report.invalidIgnored)
        assertTrue(report.newerDataSkipped)
    }

    @Test
    fun `v1 file with an invalid core restores only the valid entries and counts the rest once`() = runTest {
        val report = importFixture("v1-coeur-invalide.nextsh")

        assertEquals(5, report.hosts, "a user name with a control character is cleaned, its host is kept")
        assertEquals(2, report.sshKeys, "a multi-line public key line is cleaned, its key is kept")
        // 3 tunnels pass the validation; t-orphan has no host in the file nor on this empty device, so the
        // store skips it (reported as skipped, not as invalid): no tunnel is counted twice.
        assertEquals(2, report.tunnels)
        assertEquals(1, report.tunnelsSkipped)
        assertEquals(2, report.passwords)
        assertEquals(1, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(18, report.invalidIgnored, "6 hosts + 3 keys + 4 tunnels (cascade included) + 5 secrets")
        assertEquals(
            setOf("h-ok-1", "h-ok-2", "h-ok-3", "h-label-cleaned", "h-user-cleaned"),
            db.hostQueries.selectAll().executeAsList().map { it.id }.toSet())
        assertEquals(
            "Line break",
            db.hostQueries.selectByIdIncludingDeleted("h-label-cleaned").executeAsOne().label,
            "a label with a line break is cleaned, not refused",
        )
        assertEquals(setOf("t-ok", "t-socks"), db.tunnelQueries.selectAll().executeAsList().map { it.id }.toSet())
        assertNull(vault.getPassword("lan_sync_secret_fixture"), "the LAN secret is never imported")
    }

    @Test
    fun `v2 inconsistent keys file on a blank device recomputes the public key of key-a-line-b only`() = runTest {
        val report = importFixture("v2-cles-incoherentes.nextsh")

        assertEquals(0, report.invalidIgnored)
        assertEquals(5, report.sshKeys)
        assertEquals(5, report.privateKeys)
        assertEquals(1, report.keyPassphrases)
        assertEquals(1, report.publicKeysRecomputed)
        assertEquals(0, report.localKeysKept)
        val keys = DesktopSshKeyRepository(db)
        val fixture = fixtureKeys()
        // The line of B was replaced by the one of the private key A, with the comment of B.
        val lineA = fixture.getValue("key-two-lines").publicKey.lineSequence().first()
        assertEquals(
            lineA.substringBeforeLast(' ') + " fixture-b",
            keys.getById("key-a-line-b")?.publicKey,
        )
        // An encrypted key proves nothing without its passphrase: the line of the file stays, uncounted.
        assertEquals(fixture.getValue("key-enc-line-b").publicKey, keys.getById("key-enc-line-b")?.publicKey)
        // Two lines become one, still the key of A (the second line is now part of the comment).
        val oneLine = assertNotNull(keys.getById("key-two-lines")).publicKey
        assertTrue(oneLine.startsWith(lineA))
        assertTrue(oneLine.none { it.isISOControl() })
        assertEquals(fixture.getValue("key-rsa").publicKey, keys.getById("key-rsa")?.publicKey)
        assertEquals(fixture.getValue("key-kept").publicKey, keys.getById("key-kept")?.publicKey)
    }

    @Test
    fun `v2 inconsistent keys file keeps the key a device already holds`() = runTest {
        val keys = DesktopSshKeyRepository(db)
        val fixture = fixtureKeys()
        val lineA = fixture.getValue("key-two-lines").publicKey.lineSequence().first()
        val privateA = fixtureJson().getValue("privateKeys").jsonObject.getValue("key-a-line-b").jsonPrimitive.content
        keys.save(fixture.getValue("key-kept").copy(label = "Local label", publicKey = lineA))
        vault.storePrivateKey("key-kept", privateA)

        val report = importFixture("v2-cles-incoherentes.nextsh")

        assertEquals(4, report.privateKeys, "the private key of key-kept is not written")
        assertEquals(1, report.keyPassphrases)
        assertEquals(1, report.localKeysKept)
        assertEquals(1, report.publicKeysRecomputed)
        val kept = assertNotNull(keys.getById("key-kept"))
        assertEquals(lineA, kept.publicKey, "the local line stays")
        assertEquals(fixture.getValue("key-kept").label, kept.label, "the label of the file is taken")
        assertEquals(privateA, vault.getPrivateKey("key-kept"))
    }

    @Test
    fun `v3 file is refused as unsupported and nothing is written`() = runTest {
        val result = importer.import(fixtureBytes("v3.nextsh"), passphrase())

        val err = result.exceptionOrNull()
        assertIs<VaultImportError.UnsupportedVersion>(err)
        assertEquals(3, err.version)
        assertTrue(db.hostQueries.selectAll().executeAsList().isEmpty())
        assertTrue(vault.passwords.isEmpty())
    }

    @Test
    fun `the same passphrase with the accent stripped does not open the file`() = runTest {
        val ascii = "Fixture-vault-e-2026".toCharArray()

        val result = importer.import(fixtureBytes("v2-complet.nextsh"), ascii)

        assertIs<VaultImportError.WrongPassphrase>(result.exceptionOrNull())
    }

    // -- Helpers -------------------------------------------------------------------

    private suspend fun importFixture(name: String): BackupImportReport {
        val result = importer.import(fixtureBytes(name), passphrase())
        return result.getOrNull() ?: error("import of $name failed: ${result.exceptionOrNull()}")
    }

    /** The clear JSON of the keys fixture (test keys, no value). */
    private fun fixtureJson(): JsonObject =
        Json.parseToJsonElement(decryptBackupJson(fixtureBytes("v2-cles-incoherentes.nextsh"), String(passphrase()))).jsonObject

    /** The SSH key rows of the keys fixture, by id. */
    private fun fixtureKeys(): Map<String, SshKey> =
        fixtureJson().getValue("sshKeys").jsonArray.map { Json.decodeFromJsonElement<SshKey>(it) }.associateBy { it.id }

    /** `Fixture-vault-` + U+00E9 + `-2026`, the accent built by its code point (composed form, not `e` + U+0301). */
    private fun passphrase(): CharArray = ("Fixture-vault-" + Char(0x00E9) + "-2026").toCharArray()

    private fun fixtureBytes(name: String): ByteArray {
        val file = File(fixturesDirectory, name)
        if (!file.isFile) fail("Missing fixture $name in $fixturesDirectory")
        return file.readBytes()
    }

    private companion object {
        const val ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi"

        /** `testdata/backup/` at the root of the repository; Gradle runs the tests with the module as working directory. */
        val fixturesDirectory: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            // Stop at the root of THIS repository (the folder with settings.gradle.kts): a worktree must never
            // fall back on the testdata of the main checkout above it.
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) {
                    val candidate = File(dir, "testdata/backup")
                    if (candidate.isDirectory) return@lazy candidate
                    fail("testdata/backup missing at the root of the repository ($dir)")
                }
                dir = dir.parentFile
            }
            fail("repository root (settings.gradle.kts) not found above ${System.getProperty("user.dir")}")
        }
    }
}
