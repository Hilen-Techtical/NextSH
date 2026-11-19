// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.data.db.repository.DesktopVaultRestoreStore
import fr.techtical.nextsh.desktop.data.db.repository.SEED_OTHER_COUNTER
import fr.techtical.nextsh.desktop.data.db.repository.SEED_OTHER_DEVICE
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.seedClock
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sections new in format 2 restored over a real database through [DesktopVaultRestoreStore]:
 * snippets and themes (own restore path: tombstone included, nothing for an identical live row, clock ticked
 * from the stored one) and known hosts (insertion only, same transaction as the rest).
 */
class DesktopVaultImporterRestoreSectionsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var importer: DesktopVaultImporter
    private lateinit var localKnownHosts: DesktopKnownHostsStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        localKnownHosts = DesktopKnownHostsStore(db, nowMs = { LOCAL_NOW })
        importer = DesktopVaultImporter(FakeVaultManagerMem(), DesktopVaultRestoreStore(db), FakeBackupSettings())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // -- Snippets ----------------------------------------------------------------

    @Test
    fun `a new snippet is inserted live with a clock ticked by this device`() = runTest {
        val report = importOk(buildBackupBlob(snippets = listOf(backupSnippet("s-new", "New one"))))

        assertEquals(1, report.snippets)
        val row = db.snippetQueries.selectByIdIncludingDeleted("s-new").executeAsOne()
        assertEquals("New one", row.label)
        assertEquals(0L, row.deleted)
        assertTrue(VectorClockCodec.decode(row.vectorClock).entries.isNotEmpty())
    }

    @Test
    fun `a snippet tombstoned with a peer clock is revived and its clock dominates the tombstone`() = runTest {
        insertSnippetRow("s1", label = "Old label", deleted = true)
        val tombstone = db.snippetQueries.selectByIdIncludingDeleted("s1").executeAsOne()

        importOk(buildBackupBlob(snippets = listOf(backupSnippet("s1", "Back from the dead"))))

        val row = db.snippetQueries.selectByIdIncludingDeleted("s1").executeAsOne()
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals("Back from the dead", row.label)
        val clock = VectorClockCodec.decode(row.vectorClock)
        assertTrue(clock.dominates(VectorClockCodec.decode(tombstone.vectorClock)), "no false conflict on the peers")
        assertEquals(SEED_OTHER_COUNTER, clock.entries[SEED_OTHER_DEVICE], "the peer entry of the stored clock is kept")
    }

    @Test
    fun `a live snippet that already holds the backup content is not touched`() = runTest {
        val snippet = backupSnippet("s1", "Same")
        insertSnippetRow("s1", label = snippet.label, command = snippet.command, createdAt = snippet.createdAt)
        val before = db.snippetQueries.selectByIdIncludingDeleted("s1").executeAsOne()

        importOk(buildBackupBlob(snippets = listOf(snippet)))

        assertEquals(before, db.snippetQueries.selectByIdIncludingDeleted("s1").executeAsOne())
    }

    @Test
    fun `a live snippet that differs is rewritten and ticks, a local one absent from the backup is kept`() = runTest {
        insertSnippetRow("s1", label = "Local label")
        insertSnippetRow("s-local", label = "Only here")
        val localBefore = db.snippetQueries.selectByIdIncludingDeleted("s-local").executeAsOne()

        importOk(buildBackupBlob(snippets = listOf(backupSnippet("s1", "Edited in the backup"))))

        val row = db.snippetQueries.selectByIdIncludingDeleted("s1").executeAsOne()
        assertEquals("Edited in the backup", row.label)
        assertTrue(VectorClockCodec.decode(row.vectorClock).dominates(seedClock()))
        assertEquals(localBefore, db.snippetQueries.selectByIdIncludingDeleted("s-local").executeAsOne())
    }

    // -- Themes ------------------------------------------------------------------

    @Test
    fun `a theme tombstoned with a peer clock is revived and its clock dominates the tombstone`() = runTest {
        val theme = backupTheme(THEME_ID, "Night")
        insertThemeRow(theme.id, name = "Old name", deleted = true)
        val tombstone = db.customTerminalThemeQueries.selectByIdIncludingDeleted(THEME_ID).executeAsOne()

        val report = importOk(buildBackupBlob(customThemes = listOf(theme)))

        assertEquals(1, report.customThemes)
        val row = db.customTerminalThemeQueries.selectByIdIncludingDeleted(THEME_ID).executeAsOne()
        assertEquals(0L, row.deleted)
        assertEquals("Night", row.name)
        assertTrue(VectorClockCodec.decode(row.vectorClock).dominates(VectorClockCodec.decode(tombstone.vectorClock)))
    }

    @Test
    fun `a live theme that already holds the backup content is not touched`() = runTest {
        val theme = backupTheme(THEME_ID, "Same")
        insertThemeRow(theme.id, name = theme.name, background = theme.background)
        val before = db.customTerminalThemeQueries.selectByIdIncludingDeleted(THEME_ID).executeAsOne()

        importOk(buildBackupBlob(customThemes = listOf(theme)))

        assertEquals(before, db.customTerminalThemeQueries.selectByIdIncludingDeleted(THEME_ID).executeAsOne())
    }

    @Test
    fun `a host that uses a custom theme and the theme arrive together`() = runTest {
        val host = backupHost("h1", "Themed").copy(terminalTheme = THEME_ID)

        importOk(buildBackupBlob(hosts = listOf(host), customThemes = listOf(backupTheme(THEME_ID, "Night"))))

        assertEquals(THEME_ID, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne().terminalTheme)
        assertNotNull(db.customTerminalThemeQueries.selectByIdIncludingDeleted(THEME_ID).executeAsOneOrNull())
    }

    // -- Known hosts ---------------------------------------------------------------

    @Test
    fun `known hosts are inserted when absent and counted as already known when identical`() = runTest {
        val ed = localKey("ssh-ed25519", ED25519_BLOB)
        localKnownHosts.upsert("same.example.test:22", ed.algorithm, ed.encoded)
        val source = inMemoryKnownHostsStore()
        source.upsert("same.example.test:22", ed.algorithm, ed.encoded)
        source.upsert("new.example.test:2222", ed.algorithm, ed.encoded)

        val report = importOk(buildBackupBlob(knownHosts = source))

        assertEquals(1, report.knownHostsAdded)
        assertEquals(1, report.knownHostsAlreadyKnown)
        assertEquals(0, report.knownHostConflicts)
        val added = localKnownHosts.getStored("new.example.test:2222")
        assertNotNull(added)
        assertContentEquals(ed.encoded, added.second)
    }

    @Test
    fun `a different key for a known host is never written, the local key is kept and named`() = runTest {
        val local = localKey("ecdsa-sha2-nistp256", ECDSA256_BLOB)
        val fromBackup = localKey("ssh-ed25519", ED25519_BLOB)
        localKnownHosts.upsert("prod.example.test:22", local.algorithm, local.encoded)
        val source = inMemoryKnownHostsStore()
        source.upsert("prod.example.test:22", fromBackup.algorithm, fromBackup.encoded)

        val report = importOk(buildBackupBlob(knownHosts = source))

        assertEquals(0, report.knownHostsAdded)
        assertEquals(1, report.knownHostConflicts)
        assertEquals(listOf("prod.example.test:22"), report.knownHostConflictNames)
        val stored = assertNotNull(localKnownHosts.getStored("prod.example.test:22"))
        assertEquals(local.algorithm, stored.first)
        assertContentEquals(local.encoded, stored.second, "a known host is a trust decision: no silent replacement")
    }

    @Test
    fun `an imported known host gets the date of the import when the file has none`() = runTest {
        val ed = localKey("ssh-ed25519", ED25519_BLOB)
        val source = inMemoryKnownHostsStore(nowMs = { 0L }).also { it.upsert("dateless.example.test:22", ed.algorithm, ed.encoded) }

        val before = System.currentTimeMillis()
        importOk(buildBackupBlob(knownHosts = source))

        val row = db.knownHostQueries.selectByHostPort("dateless.example.test:22").executeAsOne()
        assertTrue(row.addedAt >= before, "a date of 0 would show 1970 in the list")
    }

    // -- Held destinations ---------------------------------------------------------
    // For a host already on this device (same id, live or tombstoned) the file never brings the FIRST
    // fingerprint of its address.

    private fun fileWithKeyFor(vararg hostPorts: String): DesktopKnownHostsStore {
        val ed = localKey("ssh-ed25519", ED25519_BLOB)
        return inMemoryKnownHostsStore().also { s -> hostPorts.forEach { s.upsert(it, ed.algorithm, ed.encoded) } }
    }

    @Test
    fun `a host that is new on this device gets the fingerprint of its address`() = runTest {
        val source = fileWithKeyFor("h2.backup:22")

        val report = importOk(buildBackupBlob(hosts = listOf(backupHost("h2", "New")), knownHosts = source))

        assertEquals(1, report.knownHostsAdded)
        assertEquals(0, report.knownHostsHeld)
        assertNotNull(localKnownHosts.getStored("h2.backup:22"))
    }

    @Test
    fun `an existing host at the same address does not get a first fingerprint from the file`() = runTest {
        importOk(buildBackupBlob(hosts = listOf(backupHost("h1", "Same"))))

        val report = importOk(
            buildBackupBlob(hosts = listOf(backupHost("h1", "Same")), knownHosts = fileWithKeyFor("h1.backup:22")),
        )

        assertEquals(1, report.knownHostsHeld)
        assertEquals(listOf("h1.backup:22"), report.knownHostHeldNames)
        assertEquals(0, report.knownHostsAdded)
        assertNull(localKnownHosts.getStored("h1.backup:22"))
    }

    @Test
    fun `a host that the backup moves does not get a fingerprint for its new address`() = runTest {
        db.insertHostRow("h1") // local address: h1.local:22, the backup moves it to h1.backup:22

        val report = importOk(
            buildBackupBlob(
                hosts = listOf(backupHost("h1", "Moved")),
                knownHosts = fileWithKeyFor("h1.backup:22", "other.example.test:22"),
            ),
        )

        assertEquals(1, report.knownHostsHeld)
        assertEquals(1, report.knownHostsAdded)
        assertNull(localKnownHosts.getStored("h1.backup:22"), "the file must not approve the new address")
        assertNotNull(localKnownHosts.getStored("other.example.test:22"))
    }

    @Test
    fun `a tombstoned local host counts as present`() = runTest {
        db.insertHostRow("h1")
        fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository(db).delete("h1")

        val report = importOk(
            buildBackupBlob(hosts = listOf(backupHost("h1", "Back")), knownHosts = fileWithKeyFor("h1.backup:22")),
        )

        assertEquals(1, report.knownHostsHeld)
        assertNull(localKnownHosts.getStored("h1.backup:22"))
    }

    @Test
    fun `a held address that already has the same key is already known, not held`() = runTest {
        val ed = localKey("ssh-ed25519", ED25519_BLOB)
        localKnownHosts.upsert("h1.backup:22", ed.algorithm, ed.encoded)
        importOk(buildBackupBlob(hosts = listOf(backupHost("h1", "Same"))))

        val report = importOk(
            buildBackupBlob(hosts = listOf(backupHost("h1", "Same")), knownHosts = fileWithKeyFor("h1.backup:22")),
        )

        assertEquals(0, report.knownHostsHeld)
        assertEquals(1, report.knownHostsAlreadyKnown)
    }

    @Test
    fun `importing the same file a second time does not approve what the first one moved`() = runTest {
        db.insertHostRow("h1")
        val blob = buildBackupBlob(hosts = listOf(backupHost("h1", "Moved")), knownHosts = fileWithKeyFor("h1.backup:22"))

        val first = importOk(blob)
        val second = importOk(blob)

        assertEquals(1, first.knownHostsHeld)
        assertEquals(1, second.knownHostsHeld)
        assertNull(localKnownHosts.getStored("h1.backup:22"))
    }

    // -- Atomicity -----------------------------------------------------------------

    @Test
    fun `a failing row rolls back snippets, themes and known hosts with the rest`() = runTest {
        val ed = localKey("ssh-ed25519", ED25519_BLOB)
        val source = inMemoryKnownHostsStore().also { it.upsert("never.example.test:22", ed.algorithm, ed.encoded) }
        // Every new tunnel row is refused: the restore fails after the other sections were already written.
        driver.execute(
            null,
            "CREATE TRIGGER refuse_tunnel BEFORE INSERT ON tunnels BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h-new", "New host")),
            tunnels = listOf(backupTunnel("t-new", hostId = "h-new", label = "New tunnel")),
            snippets = listOf(backupSnippet("s-new", "New snippet")),
            customThemes = listOf(backupTheme(THEME_ID, "Night")),
            knownHosts = source,
        )

        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())

        assertIs<VaultImportError.IoError>(result.exceptionOrNull())
        assertNull(db.snippetQueries.selectByIdIncludingDeleted("s-new").executeAsOneOrNull())
        assertNull(db.customTerminalThemeQueries.selectByIdIncludingDeleted(THEME_ID).executeAsOneOrNull())
        assertNull(db.hostQueries.selectByIdIncludingDeleted("h-new").executeAsOneOrNull())
        assertNull(localKnownHosts.getStored("never.example.test:22"))
    }

    // -- Helpers -------------------------------------------------------------------

    private suspend fun importOk(blob: ByteArray): BackupImportReport {
        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())
        return result.getOrNull() ?: error("import failed: ${result.exceptionOrNull()}")
    }

    private fun localKey(keyType: String, blob: String): KnownHostPortable.LocalKey =
        KnownHostPortable.fromPortable(keyType, blob) ?: error("fixture key unreadable")

    private fun insertSnippetRow(
        id: String,
        label: String,
        command: String = "echo local",
        createdAt: Long = BACKUP_TEST_CREATED_AT,
        deleted: Boolean = false,
    ) {
        db.snippetQueries.insert(
            id = id,
            label = label,
            command = command,
            category = null,
            hostId = null,
            createdAt = createdAt,
            vectorClock = VectorClockCodec.encode(seedClock()),
            deleted = if (deleted) 1L else 0L,
            deletedAt = if (deleted) 5_000L else null,
            updatedAt = 1_000L,
        )
    }

    private fun insertThemeRow(
        id: String,
        name: String,
        background: Int = 0xFF101010.toInt(),
        deleted: Boolean = false,
    ) {
        val theme = backupTheme(id, name, background)
        db.customTerminalThemeQueries.insert(
            id = id,
            name = name,
            background = theme.background.toLong(),
            foreground = theme.foreground.toLong(),
            cursor = theme.cursor.toLong(),
            selectionBg = theme.selectionBg.toLong(),
            ansi = theme.ansi.joinToString(","),
            createdAt = theme.createdAt,
            vectorClock = VectorClockCodec.encode(seedClock()),
            deleted = if (deleted) 1L else 0L,
            deletedAt = if (deleted) 5_000L else null,
            updatedAt = 1_000L,
        )
    }

    private companion object {
        const val LOCAL_NOW = 1_800_000_000_000L
        const val THEME_ID = "7d1f0c2a-5b3e-4a6d-9c8b-1e2f3a4b5c6d"

        // Public keys of the shared fixtures (testdata/backup): no private part exists.
        const val ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi"
        const val ECDSA256_BLOB =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBItZyn1g+M6HbsGnbGmOW3NLq4WxdDPrbrVfbNarB2k2teIzQ/ZJ3/+f+V5hNYPJ1issB5z2wkKgyaLLdkyAgqc="
    }
}
