// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.SEED_CREATED_AT
import fr.techtical.nextsh.desktop.data.db.repository.SEED_UPDATED_AT_MS
import fr.techtical.nextsh.desktop.data.db.repository.seedClock
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Migration 8.sqm adds `tunnels.browserPath` without disturbing any other column.
 *
 * The v8 database comes from a FROZEN literal DDL (the `hosts` and `tunnels` tables as they were at
 * schema version 8), never from the current schema, so a later migration cannot break this test for
 * the wrong reason. It is opened through [DatabaseFactory.openAt], the real start-up path: user_version
 * check, [NextShDatabase.Schema.migrate], user_version bump.
 *
 * `ALTER TABLE ADD COLUMN` appends at the end, so `browserPath` is the last column of the CREATE TABLE
 * in Tunnel.sq and a fresh install and a migrated database share one layout. SQLDelight 2.0.2 expands
 * `SELECT *` into an explicit column list, so the position does not change the values read today. The
 * value assertions still check every column of a real v8 row, and the layout test pins the position.
 */
class TunnelBrowserPathMigrationTest {

    private lateinit var dir: File
    private val openDrivers = mutableListOf<SqlDriver>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("nextsh-migration-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        openDrivers.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test
    fun `a v8 database migrates and every tunnel column keeps its meaning`() = runTest {
        val database = openAt(buildV8Database("legacy.db"))
        val repository = DesktopTunnelRepository(database.db)

        // Raw row: catches a shifted column even where the domain model would hide it.
        val row = database.db.tunnelQueries.selectById("t1").executeAsOne()
        assertEquals("t1", row.id)
        assertEquals("Grafana", row.label)
        assertEquals("h1", row.hostId)
        assertEquals("LOCAL_FORWARD", row.type)
        assertEquals(3000L, row.localPort)
        assertEquals("grafana.internal", row.remoteHost)
        assertEquals(3001L, row.remotePort)
        assertEquals(1L, row.autoStart)
        assertEquals(1L, row.openBrowserOnConnect)
        assertEquals(0L, row.keepAliveAfterBrowserClose)
        assertEquals(1L, row.isFavorite)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(VectorClockCodec.encode(seedClock()), row.vectorClock)
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals(SEED_UPDATED_AT_MS, row.updatedAt)
        assertEquals("/", row.browserPath)

        assertEquals(
            TunnelConfig(
                id = "t1",
                label = "Grafana",
                hostId = "h1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = 3000,
                remoteHost = "grafana.internal",
                remotePort = 3001,
                autoStart = true,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = false,
                isFavorite = true,
                browserPath = TunnelBrowserPath.DEFAULT,
            ),
            repository.getById("t1"),
        )
    }

    @Test
    fun `startup moves user_version to the current schema version`() {
        val database = openAt(buildV8Database("legacy.db"))

        assertEquals(NextShDatabase.Schema.version, database.driver.userVersion().toLong())
    }

    @Test
    fun `a migrated database has the same tunnels columns as a fresh install, in the same order`() {
        val migrated = openAt(buildV8Database("legacy.db"))
        val fresh = openAt(File(dir, "fresh.db").path)

        val freshColumns = fresh.driver.columns("tunnels")
        assertEquals("browserPath", freshColumns.last().name, "browserPath must be the last CREATE TABLE column")
        // Name, declared type, NOT NULL, default and primary key, column by column.
        assertEquals(freshColumns, migrated.driver.columns("tunnels"))
    }

    @Test
    fun `a migrated tunnel accepts a new browserPath and still reads back unchanged`() = runTest {
        val database = openAt(buildV8Database("legacy.db"))
        val repository = DesktopTunnelRepository(database.db)
        val stored = checkNotNull(repository.getById("t1"))

        repository.update(stored.copy(browserPath = "/d/abc?orgId=1#top"))

        assertEquals(stored.copy(browserPath = "/d/abc?orgId=1#top"), repository.getById("t1"))
    }

    @Test
    fun `the frozen v8 fixture lacks the column before the migration`() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${buildV8Database("legacy.db")}").also { openDrivers += it }

        assertEquals(8, driver.userVersion())
        val columns = driver.columns("tunnels").map { it.name }
        assertEquals(16, columns.size)
        assertEquals("updatedAt", columns.last())
    }

    // -- Helpers ---------------------------------------------------------------

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKey: Int,
    )

    private fun openAt(dbPath: String): DesktopDatabase =
        DatabaseFactory.openAt(dbPath).also { openDrivers += it.driver }

    /**
     * Writes a database in the v8 shape from the frozen DDL below: user_version 8, one host and one
     * fully populated v8 tunnel row (every column distinct and non-default).
     */
    private fun buildV8Database(fileName: String): String {
        val path = File(dir, fileName).path
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        try {
            FROZEN_V8_DDL.forEach { driver.execute(null, it, 0) }
            driver.execute(null, "PRAGMA user_version = 8", 0)
            driver.execute(
                null,
                "INSERT INTO hosts(id, label, hostname, port, username, authType, credentialId, \"group\", " +
                    "keepAliveSeconds, autoReconnect, terminalTheme, fido2Mode, isFavorite, createdAt, " +
                    "lastConnectedAt, vectorClock, deleted, deletedAt, updatedAt) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                19,
            ) {
                bindString(0, "h1")
                bindString(1, "Host h1")
                bindString(2, "h1.local")
                bindLong(3, 22L)
                bindString(4, "user")
                bindString(5, "PASSWORD")
                bindString(6, "cred-h1")
                bindString(7, null)
                bindLong(8, 30L)
                bindLong(9, 1L)
                bindString(10, "TECHTICAL_DARK")
                bindString(11, null)
                bindLong(12, 0L)
                bindLong(13, SEED_CREATED_AT)
                bindLong(14, null)
                bindString(15, VectorClockCodec.encode(seedClock()))
                bindLong(16, 0L)
                bindLong(17, null)
                bindLong(18, SEED_UPDATED_AT_MS)
            }
            driver.execute(
                null,
                "INSERT INTO tunnels(id, label, hostId, type, localPort, remoteHost, remotePort, autoStart, " +
                    "openBrowserOnConnect, keepAliveAfterBrowserClose, isFavorite, createdAt, vectorClock, " +
                    "deleted, deletedAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                16,
            ) {
                bindString(0, "t1")
                bindString(1, "Grafana")
                bindString(2, "h1")
                bindString(3, "LOCAL_FORWARD")
                bindLong(4, 3000L)
                bindString(5, "grafana.internal")
                bindLong(6, 3001L)
                bindLong(7, 1L)
                bindLong(8, 1L)
                bindLong(9, 0L)
                bindLong(10, 1L)
                bindLong(11, SEED_CREATED_AT)
                bindString(12, VectorClockCodec.encode(seedClock()))
                bindLong(13, 0L)
                bindLong(14, null)
                bindLong(15, SEED_UPDATED_AT_MS)
            }
        } finally {
            driver.close()
        }
        return path
    }

    private fun SqlDriver.userVersion(): Int {
        var version = -1
        executeQuery(null, "PRAGMA user_version", parameters = 0, mapper = { cursor ->
            if (cursor.next().value) version = cursor.getLong(0)?.toInt() ?: -1
            QueryResult.Unit
        })
        return version
    }

    private fun SqlDriver.columns(table: String): List<Column> {
        val columns = mutableListOf<Column>()
        executeQuery(null, "PRAGMA table_info($table)", parameters = 0, mapper = { cursor ->
            while (cursor.next().value) {
                columns += Column(
                    name = checkNotNull(cursor.getString(1)),
                    type = checkNotNull(cursor.getString(2)),
                    notNull = cursor.getLong(3) == 1L,
                    defaultValue = cursor.getString(4),
                    primaryKey = cursor.getLong(5)?.toInt() ?: 0,
                )
            }
            QueryResult.Unit
        })
        return columns
    }

    private companion object {
        /**
         * The `hosts` and `tunnels` tables exactly as schema version 8 defined them. FROZEN: never edit
         * this to follow a later schema, that is what the migrations under test are for. `tunnels` is
         * the only table migration 8.sqm touches, `hosts` is its foreign key target.
         */
        val FROZEN_V8_DDL = listOf(
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
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX idx_tunnels_hostId ON tunnels(hostId)",
        )
    }
}
