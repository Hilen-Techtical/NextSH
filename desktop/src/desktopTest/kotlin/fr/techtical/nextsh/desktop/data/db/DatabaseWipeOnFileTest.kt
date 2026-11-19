// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db

import app.cash.sqldelight.db.SqlDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopPendingConflictRepository
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [DesktopDatabase.wipeAllTables] on a FILE database opened through [DatabaseFactory.openAt]: every table is
 * emptied in one transaction, and the observers of the tables are NOTIFIED (the raw `DELETE` it replaced left
 * the host list, the tunnel watches and the conflict badge on the old content).
 */
class DatabaseWipeOnFileTest {

    private lateinit var dir: File
    private val openDrivers = mutableListOf<SqlDriver>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nextsh-wipe-file-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        openDrivers.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openAt(): DesktopDatabase =
        DatabaseFactory.openAt(File(dir, "nextsh.db").path).also { openDrivers += it.driver }

    private fun DesktopDatabase.insertConflict(id: String) {
        db.pendingConflictQueries.upsert(
            id = id,
            entityType = "HOST",
            entityId = "h1",
            localJson = "{}",
            remoteJson = "{}",
            detectedAt = 1L,
        )
    }

    @Test
    fun `every table is empty after the wipe, tombstones included`() {
        val database = openAt()
        database.db.insertHostRow("h1")
        database.db.insertTunnelRow("t1", hostId = "h1")
        database.insertConflict("c1")

        database.wipeAllTables()

        assertTrue(database.db.hostQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
        assertTrue(database.db.tunnelQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
        assertTrue(database.db.sshKeyQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
        assertTrue(database.db.snippetQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
        assertTrue(database.db.customTerminalThemeQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
        assertEquals(0L, database.db.pendingConflictQueries.selectCount().executeAsOne())
    }

    @Test
    fun `a watcher of the host list sees the empty list after the wipe`() = runBlocking {
        val database = openAt()
        val hosts = DesktopHostRepository(database.db)
        database.db.insertHostRow("h1")
        database.db.insertHostRow("h2")
        val emissions = Channel<Int>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { hosts.observeAll().collect { emissions.send(it.size) } }

        assertEquals(2, withTimeout(5_000) { emissions.receive() })
        database.wipeAllTables()
        // A raw DELETE would never notify: this receive would time out on the old list.
        assertEquals(0, withTimeout(5_000) { emissions.receive() })
        collector.cancel()
    }

    @Test
    fun `the conflict count goes to zero for its observer after the wipe`() = runBlocking {
        val database = openAt()
        val conflicts = DesktopPendingConflictRepository(database.db)
        database.insertConflict("c1")
        database.insertConflict("c2")
        val emissions = Channel<Int>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { conflicts.observeCount().collect { emissions.send(it) } }

        assertEquals(2, withTimeout(5_000) { emissions.receive() })
        database.wipeAllTables()
        assertEquals(0, withTimeout(5_000) { emissions.receive() })
        collector.cancel()
    }

    @Test
    fun `a failure in the middle leaves the tables as they were`() {
        val database = openAt()
        database.db.insertHostRow("h1")
        database.db.insertTunnelRow("t1", hostId = "h1")
        // Rows are deleted tunnels first, then hosts: make the host delete fail.
        database.driver.execute(
            null,
            "CREATE TRIGGER fail_host_delete BEFORE DELETE ON hosts BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )

        assertFailsWith<Exception> { database.wipeAllTables() }

        assertEquals(1, database.db.hostQueries.selectAllIncludingDeleted().executeAsList().size)
        assertEquals(1, database.db.tunnelQueries.selectAllIncludingDeleted().executeAsList().size)
    }
}
