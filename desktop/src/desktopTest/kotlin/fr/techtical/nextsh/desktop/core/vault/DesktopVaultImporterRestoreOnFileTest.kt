// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import app.cash.sqldelight.db.SqlDriver
import fr.techtical.nextsh.desktop.data.db.DatabaseFactory
import fr.techtical.nextsh.desktop.data.db.DesktopDatabase
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopVaultRestoreStore
import fr.techtical.nextsh.desktop.data.db.repository.SEED_CREATED_AT
import fr.techtical.nextsh.desktop.data.db.repository.SEED_LAST_CONNECTED_AT
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import fr.techtical.nextsh.desktop.data.db.repository.seedClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Restore on a FILE database opened through `DatabaseFactory.openAt`, the production path. A file-based
 * JdbcSqliteDriver opens connections through a thread-bound connection manager: this is where a transaction
 * can silently stop covering its statements, which an in-memory driver (one static connection) never shows.
 */
class DesktopVaultImporterRestoreOnFileTest {

    private lateinit var dir: File
    private val openDrivers = mutableListOf<SqlDriver>()
    private lateinit var database: DesktopDatabase
    private lateinit var vault: FakeVaultManagerMem
    private lateinit var importer: DesktopVaultImporter

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nextsh-restore-file-test").toFile()
        database = DatabaseFactory.openAt(File(dir, "nextsh.db").path).also { openDrivers += it.driver }
        vault = FakeVaultManagerMem()
        importer = DesktopVaultImporter(vaultManager = vault, restoreStore = DesktopVaultRestoreStore(database.db), settings = FakeBackupSettings())
    }

    @AfterTest
    fun tearDown() {
        openDrivers.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test
    fun `restore over an existing host on a file database keeps its local columns and tunnels`() = runTest {
        val db = database.db
        db.insertHostRow("h1")
        db.insertHostRow("h-dead")
        db.insertTunnelRow("t-local", hostId = "h1")
        DesktopHostRepository(db).delete("h-dead")
        val localTunnelBefore = db.tunnelQueries.selectByIdIncludingDeleted("t-local").executeAsOne()
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "From backup"), backupHost("h-dead", label = "Revived")),
            tunnels = listOf(
                backupTunnel("t-new", hostId = "h1", label = "New tunnel"),
                backupTunnel("t-orphan", hostId = "h-nowhere", label = "Host nowhere"),
            ),
        )

        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())

        val summary = result.getOrNull() ?: error("import failed: ${result.exceptionOrNull()}")
        assertEquals(1, summary.tunnels)
        assertEquals(1, summary.tunnelsSkipped)
        val hostRow = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals("From backup", hostRow.label)
        assertEquals(SEED_CREATED_AT, hostRow.createdAt)
        assertEquals(SEED_LAST_CONNECTED_AT, hostRow.lastConnectedAt)
        assertTrue(VectorClockCodec.decode(hostRow.vectorClock).dominates(seedClock()))
        assertEquals(0L, db.hostQueries.selectByIdIncludingDeleted("h-dead").executeAsOne().deleted)
        assertEquals(localTunnelBefore, db.tunnelQueries.selectByIdIncludingDeleted("t-local").executeAsOne())
        assertNotNull(DesktopTunnelRepository(db).getById("t-new"))
        assertNull(db.tunnelQueries.selectByIdIncludingDeleted("t-orphan").executeAsOneOrNull())
    }

    @Test
    fun `a failing row rolls the whole restore back on a file database`() = runTest {
        val db = database.db
        db.insertHostRow("h1")
        val hostBefore = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        database.driver.execute(
            null,
            "CREATE TRIGGER refuse_tunnel BEFORE INSERT ON tunnels BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )
        val blob = buildBackupBlob(
            hosts = listOf(backupHost("h1", label = "Changed by the backup"), backupHost("h-new", label = "New host")),
            tunnels = listOf(backupTunnel("t-new", hostId = "h-new", label = "New tunnel")),
            credentials = mapOf("cred-h-new" to "s3cret"),
        )

        val result = importer.import(blob, RESTORE_TEST_PASSPHRASE.toCharArray())

        assertIs<VaultImportError.IoError>(result.exceptionOrNull())
        assertEquals(hostBefore, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne())
        assertNull(db.hostQueries.selectByIdIncludingDeleted("h-new").executeAsOneOrNull())
        assertNull(vault.getPassword("cred-h-new"))
    }
}
