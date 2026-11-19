// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.desktop.data.db.repository.seedClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Foreign keys must be enforced on the REAL database, i.e. on every connection a file-based driver opens.
 * An in-memory driver holds one static connection, which is why the earlier `PRAGMA foreign_keys = ON`
 * executed once on the driver looked fine in tests and did nothing in production.
 *
 * Everything here goes through [DatabaseFactory.openAt] on a file of its own, the production start-up path.
 */
class DatabaseFactoryForeignKeysTest {

    private lateinit var dir: File
    private val openDrivers = mutableListOf<SqlDriver>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nextsh-fk-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        openDrivers.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test
    fun `a database opened through openAt enforces foreign keys outside a transaction`() {
        val database = openAt(File(dir, "fresh.db").path)

        // Several reads, from this thread and from another one: none may land on a connection without it.
        repeat(3) { assertEquals(1L, database.driver.foreignKeys()) }
        var fromOtherThread = -1L
        thread { fromOtherThread = database.driver.foreignKeys() }.join()
        assertEquals(1L, fromOtherThread)
    }

    @Test
    fun `an existing database file is opened with foreign keys enforced too`() {
        val path = File(dir, "reopen.db").path
        openAt(path).driver.close()

        val reopened = openAt(path)

        assertEquals(1L, reopened.driver.foreignKeys())
    }

    @Test
    fun `a tunnel for a host that does not exist is refused`() = runTest {
        val database = openAt(File(dir, "fresh.db").path)
        val tunnels = DesktopTunnelRepository(database.db)

        assertFailsWith<Exception> { tunnels.save(tunnel("t1", hostId = "missing")) }

        assertNull(database.db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOneOrNull())
    }

    @Test
    fun `removing a host row cascades to its tunnels`() = runTest {
        val database = openAt(File(dir, "fresh.db").path)
        val hosts = DesktopHostRepository(database.db)
        val tunnels = DesktopTunnelRepository(database.db)
        hosts.save(host("h1"))
        tunnels.save(tunnel("t1", hostId = "h1"))
        assertNotNull(tunnels.getById("t1"))

        hosts.hardDelete("h1")

        assertNull(database.db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOneOrNull())
    }

    @Test
    fun `a v9 database keeps the tunnels that have a host row and loses only the orphans`() = runTest {
        val path = buildV9Database(
            "legacy.db",
            hosts = listOf("h-live" to false, "h-tomb" to true),
            tunnels = listOf(
                "t-valid" to "h-live",
                "t-of-tombstoned-host" to "h-tomb",
                "t-orphan" to "h-gone",
            ),
        )

        val database = openAt(path)
        val repository = DesktopTunnelRepository(database.db)

        val remaining = database.db.tunnelQueries.selectAllIncludingDeleted().executeAsList().map { it.id }.toSet()
        assertEquals(setOf("t-valid", "t-of-tombstoned-host"), remaining)
        assertNotNull(repository.getById("t-valid"))
        assertEquals(NextShDatabase.Schema.version, database.driver.userVersion().toLong())
        assertEquals(1L, database.driver.foreignKeys())
    }

    @Test
    fun `a v9 database without orphans is migrated without losing anything`() = runTest {
        val path = buildV9Database(
            "clean.db",
            hosts = listOf("h1" to false),
            tunnels = listOf("t1" to "h1", "t2" to "h1"),
        )

        val database = openAt(path)

        assertEquals(2, database.db.tunnelQueries.selectAllIncludingDeleted().executeAsList().size)
        assertEquals(1, database.db.hostQueries.selectAllIncludingDeleted().executeAsList().size)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun openAt(dbPath: String): DesktopDatabase =
        DatabaseFactory.openAt(dbPath).also { openDrivers += it.driver }

    private fun SqlDriver.foreignKeys(): Long {
        var value = -1L
        executeQuery(null, "PRAGMA foreign_keys", parameters = 0, mapper = { cursor ->
            if (cursor.next().value) value = cursor.getLong(0) ?: -1L
            QueryResult.Unit
        })
        return value
    }

    private fun SqlDriver.userVersion(): Int {
        var version = -1
        executeQuery(null, "PRAGMA user_version", parameters = 0, mapper = { cursor ->
            if (cursor.next().value) version = cursor.getLong(0)?.toInt() ?: -1
            QueryResult.Unit
        })
        return version
    }

    private fun host(id: String) = Host(
        id = id,
        label = "Host $id",
        hostname = "$id.local",
        username = "user",
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

    /**
     * Writes a database in the v9 shape from the frozen DDL below (user_version 9) on a plain connection,
     * where foreign keys are off: that is how orphan tunnels got into real databases. [hosts] is a list of
     * (id, tombstoned), [tunnels] a list of (id, hostId).
     */
    private fun buildV9Database(
        fileName: String,
        hosts: List<Pair<String, Boolean>>,
        tunnels: List<Pair<String, String>>,
    ): String {
        val path = File(dir, fileName).path
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        try {
            FROZEN_V9_DDL.forEach { driver.execute(null, it, 0) }
            driver.execute(null, "PRAGMA user_version = 9", 0)
            val clock = VectorClockCodec.encode(seedClock())
            hosts.forEach { (id, tombstoned) ->
                driver.execute(
                    null,
                    "INSERT INTO hosts(id, label, hostname, port, username, authType, credentialId, " +
                        "keepAliveSeconds, autoReconnect, terminalTheme, isFavorite, createdAt, vectorClock, " +
                        "deleted, deletedAt, updatedAt) VALUES (?, ?, ?, 22, 'user', 'PASSWORD', ?, 30, 1, " +
                        "'TECHTICAL_DARK', 0, 1, ?, ?, ?, 1)",
                    7,
                ) {
                    bindString(0, id)
                    bindString(1, "Host $id")
                    bindString(2, "$id.local")
                    bindString(3, "cred-$id")
                    bindString(4, clock)
                    bindLong(5, if (tombstoned) 1L else 0L)
                    bindLong(6, if (tombstoned) 2L else null)
                }
            }
            tunnels.forEach { (id, hostId) ->
                driver.execute(
                    null,
                    "INSERT INTO tunnels(id, label, hostId, type, localPort, remoteHost, remotePort, createdAt, " +
                        "vectorClock, updatedAt) VALUES (?, ?, ?, 'LOCAL_FORWARD', 8080, 'localhost', 80, 1, ?, 1)",
                    4,
                ) {
                    bindString(0, id)
                    bindString(1, "Tunnel $id")
                    bindString(2, hostId)
                    bindString(3, clock)
                }
            }
        } finally {
            driver.close()
        }
        return path
    }

    private companion object {
        /**
         * The `hosts` and `tunnels` tables exactly as schema version 9 defined them. FROZEN: never edit this to
         * follow a later schema, that is what the migrations under test are for. Migration 9.sqm only touches
         * these two tables.
         */
        val FROZEN_V9_DDL = listOf(
            """
            CREATE TABLE hosts (
                id TEXT NOT NULL PRIMARY KEY,
                label TEXT NOT NULL,
                hostname TEXT NOT NULL,
                port INTEGER NOT NULL DEFAULT 22,
                username TEXT NOT NULL,
                authType TEXT NOT NULL,
                credentialId TEXT NOT NULL,
                "group" TEXT,
                keepAliveSeconds INTEGER NOT NULL DEFAULT 30,
                autoReconnect INTEGER NOT NULL DEFAULT 1,
                terminalTheme TEXT NOT NULL DEFAULT 'TECHTICAL_DARK',
                fido2Mode TEXT,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                lastConnectedAt INTEGER,
                vectorClock TEXT NOT NULL DEFAULT '{}',
                deleted INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE tunnels (
                id TEXT NOT NULL PRIMARY KEY,
                label TEXT NOT NULL,
                hostId TEXT NOT NULL,
                type TEXT NOT NULL,
                localPort INTEGER NOT NULL,
                remoteHost TEXT NOT NULL,
                remotePort INTEGER NOT NULL,
                autoStart INTEGER NOT NULL DEFAULT 0,
                openBrowserOnConnect INTEGER NOT NULL DEFAULT 0,
                keepAliveAfterBrowserClose INTEGER NOT NULL DEFAULT 1,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                vectorClock TEXT NOT NULL DEFAULT '{}',
                deleted INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                browserPath TEXT NOT NULL DEFAULT '/',
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX idx_tunnels_hostId ON tunnels(hostId)",
        )
    }
}
