// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db

import app.cash.sqldelight.db.SqlDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The host delete cascade on a FILE database opened through [DatabaseFactory.openAt], the production path.
 *
 * An in-memory driver holds one static connection. A file-based JdbcSqliteDriver opens connections through a
 * thread-bound connection manager, which is where a transaction can silently stop covering its statements:
 * these tests prove the host and tunnel tombstones really are one transaction there.
 */
class DesktopHostRepositoryDeleteOnFileTest {

    private lateinit var dir: File
    private val openDrivers = mutableListOf<SqlDriver>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nextsh-delete-file-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        openDrivers.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openAt(): DesktopDatabase =
        DatabaseFactory.openAt(File(dir, "nextsh.db").path).also { openDrivers += it.driver }

    @Test
    fun `deleting a host tombstones the host and its live tunnels on a file database`() = runTest {
        val database = openAt()
        val hosts = DesktopHostRepository(database.db)
        val tunnels = DesktopTunnelRepository(database.db)
        database.db.insertHostRow("h1")
        database.db.insertHostRow("h2")
        database.db.insertTunnelRow("t1", hostId = "h1")
        database.db.insertTunnelRow("t2", hostId = "h1")
        database.db.insertTunnelRow("other", hostId = "h2")

        hosts.delete("h1")

        assertNull(hosts.getById("h1"))
        assertNull(tunnels.getById("t1"))
        assertNull(tunnels.getById("t2"))
        assertEquals(1L, database.db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne().deleted)
        assertEquals(1L, database.db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne().deleted)
        assertEquals(1L, database.db.tunnelQueries.selectByIdIncludingDeleted("t2").executeAsOne().deleted)
        assertNotNull(hosts.getById("h2"))
        assertNotNull(tunnels.getById("other"))
    }

    @Test
    fun `a failure on a tunnel rolls the host tombstone back on a file database`() = runTest {
        val database = openAt()
        val hosts = DesktopHostRepository(database.db)
        val tunnels = DesktopTunnelRepository(database.db)
        database.db.insertHostRow("h1")
        database.db.insertTunnelRow("t1", hostId = "h1")
        database.driver.execute(
            null,
            "CREATE TRIGGER fail_tunnel_update BEFORE UPDATE ON tunnels BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )

        assertFailsWith<Exception> { hosts.delete("h1") }

        assertNotNull(hosts.getById("h1"), "the host tombstone must be rolled back with the failed tunnel one")
        assertNotNull(tunnels.getById("t1"))
    }
}
