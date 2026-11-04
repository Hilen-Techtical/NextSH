// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A tunnel sync write must update the row in place (INSERT OR REPLACE resets createdAt) and insert only
 * when the row is unknown. Foreign keys are ON, as in the host tests.
 */
class DesktopTunnelRepositorySyncUpsertTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var repository: DesktopTunnelRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        repository = DesktopTunnelRepository(db)
        db.insertHostRow("h1")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `syncing an existing tunnel keeps its createdAt and writes every synced column`() = runTest {
        db.insertHostRow("h2")
        db.insertTunnelRow("t1", hostId = "h1")
        val remote = TunnelConfig(
            id = "t1",
            label = "Remote label",
            hostId = "h2",
            type = TunnelType.DYNAMIC_SOCKS5,
            localPort = 1080,
            remoteHost = "remote.internal",
            remotePort = 3128,
            autoStart = true,
            openBrowserOnConnect = true,
            keepAliveAfterBrowserClose = false,
            isFavorite = true,
        )

        repository.upsertSyncEntry(
            SyncEntry(id = "t1", payload = remote, clock = clockOf(6L), updatedAt = REMOTE_UPDATED_AT),
        )

        val row = db.tunnelQueries.selectAllIncludingDeleted().executeAsList().single()
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(VectorClockCodec.encode(clockOf(6L)), row.vectorClock)
        assertEquals(REMOTE_UPDATED_AT, row.updatedAt)
        assertEquals(remote, repository.getById("t1"))
    }

    @Test
    fun `a tombstone entry for an unknown id creates no row`() = runTest {
        repository.upsertSyncEntry(
            SyncEntry(
                id = "ghost",
                payload = null,
                clock = clockOf(6L),
                deleted = true,
                deletedAt = REMOTE_UPDATED_AT,
                updatedAt = REMOTE_UPDATED_AT,
            ),
        )

        assertEquals(emptyList(), db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `an unknown id is inserted`() = runTest {
        val entry = SyncEntry(
            id = "new",
            payload = TunnelConfig(
                id = "new",
                label = "Brand new",
                hostId = "h1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = 9000,
                remoteHost = "localhost",
                remotePort = 90,
            ),
            clock = clockOf(6L),
            updatedAt = REMOTE_UPDATED_AT,
        )

        repository.upsertSyncEntry(entry)

        assertEquals(entry, repository.getAllSyncEntries().single())
    }

    @Test
    fun `a tombstoned tunnel receiving a live entry is revived in place`() = runTest {
        db.insertTunnelRow("t1", hostId = "h1")
        repository.delete("t1")
        assertEquals(1L, db.tunnelQueries.selectAllIncludingDeleted().executeAsList().single().deleted)

        repository.upsertSyncEntry(
            SyncEntry(
                id = "t1",
                payload = TunnelConfig(
                    id = "t1",
                    label = "Back from the dead",
                    hostId = "h1",
                    type = TunnelType.LOCAL_FORWARD,
                    localPort = 8080,
                    remoteHost = "localhost",
                    remotePort = 80,
                ),
                clock = clockOf(6L),
                updatedAt = REMOTE_UPDATED_AT,
            ),
        )

        val row = db.tunnelQueries.selectAllIncludingDeleted().executeAsList().single()
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals("Back from the dead", assertNotNull(repository.getById("t1")).label)
    }

    @Test
    fun `a tunnel tombstone entry marks the row deleted and keeps its createdAt`() = runTest {
        db.insertTunnelRow("t1", hostId = "h1")

        repository.upsertSyncEntry(
            SyncEntry(
                id = "t1",
                payload = null,
                clock = clockOf(6L),
                deleted = true,
                deletedAt = REMOTE_UPDATED_AT,
                updatedAt = REMOTE_UPDATED_AT,
            ),
        )

        val row = db.tunnelQueries.selectAllIncludingDeleted().executeAsList().single()
        assertEquals(1L, row.deleted)
        assertEquals(REMOTE_UPDATED_AT, row.deletedAt)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertNull(repository.getById("t1"))
    }

    private fun clockOf(counter: Long): VectorClock = VectorClock(mapOf(SEED_OTHER_DEVICE to counter))

    private companion object {
        const val REMOTE_UPDATED_AT = 5_000L
    }
}
