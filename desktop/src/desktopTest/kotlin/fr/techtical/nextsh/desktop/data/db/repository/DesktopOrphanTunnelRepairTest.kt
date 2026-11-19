// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The orphan repair tombstones the LIVE tunnels of tombstoned hosts, each with its own ticked clock, and
 * leaves everything else alone. Runs on an in-memory driver with foreign keys ON, the way the production
 * database behaves: a host tombstone is an UPDATE, so it cascades to nothing and the tunnels stay live.
 */
class DesktopOrphanTunnelRepairTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var repair: DesktopOrphanTunnelRepair
    private lateinit var tunnelRepository: DesktopTunnelRepository

    private val deviceId = DeviceIdentity.deviceId()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        repair = DesktopOrphanTunnelRepair(db)
        tunnelRepository = DesktopTunnelRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun orphanIds(): List<String> =
        db.tunnelQueries.selectLiveOfDeletedHosts().executeAsList().map { it.id }

    // -- The query ---------------------------------------------------------------

    @Test
    fun `the query returns only the live tunnels of deleted hosts`() {
        db.insertHostRow("h-live")
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t-ok", hostId = "h-live")
        db.insertTunnelRow("t-orphan", hostId = "h-gone")
        db.insertTunnelRow("t-orphan-2", hostId = "h-gone")
        db.insertTunnelRow("t-already-gone", hostId = "h-gone")
        db.tunnelQueries.markDeleted(deletedAt = 1L, updatedAt = 1L, vectorClock = "{}", id = "t-already-gone")
        db.tombstoneHostRowOnly("h-gone")

        assertEquals(listOf("t-orphan", "t-orphan-2"), orphanIds())
    }

    @Test
    fun `a host with a pending HOST conflict is protected, other conflicts do not protect it`() {
        db.insertHostRow("h-contested")
        db.insertHostRow("h-other-conflicts")
        db.insertTunnelRow("t-contested", hostId = "h-contested")
        db.insertTunnelRow("t-other", hostId = "h-other-conflicts")
        db.tombstoneHostRowOnly("h-contested")
        db.tombstoneHostRowOnly("h-other-conflicts")
        // A conflict of another kind on the same id, and a HOST conflict on another id: neither protects.
        db.insertPendingConflictRow(SyncableEntityType.TUNNEL, "h-other-conflicts")
        db.insertPendingConflictRow(SyncableEntityType.HOST, "h-nowhere")
        db.insertPendingConflictRow(SyncableEntityType.HOST, "h-contested")

        assertEquals(listOf("t-other"), orphanIds())
    }

    // -- The repair --------------------------------------------------------------

    @Test
    fun `the repair tombstones the orphans with a clock that strictly dominates the previous one`() = runTest {
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t1", hostId = "h-gone")
        db.insertTunnelRow("t2", hostId = "h-gone", isFavorite = true)
        db.tombstoneHostRowOnly("h-gone")
        val liveBefore = tunnelRepository.getAllSyncEntries().associateBy { it.id }
        val before = System.currentTimeMillis()

        val repaired = repair.run()

        assertEquals(2, repaired)
        assertTrue(orphanIds().isEmpty())
        db.tunnelQueries.selectAllIncludingDeleted().executeAsList().forEach { row ->
            assertEquals(1L, row.deleted)
            val deletedAt = assertNotNull(row.deletedAt)
            assertTrue(deletedAt >= before)
            assertTrue(row.updatedAt >= before)
            assertEquals(SEED_CREATED_AT, row.createdAt, "createdAt is local-only and must not move")
        }
        tunnelRepository.getAllSyncEntries().forEach { entry ->
            val previous = liveBefore.getValue(entry.id)
            assertTrue(entry.deleted)
            assertTrue(entry.clock.dominates(previous.clock), "the tombstone must dominate the live version")
            assertEquals(SEED_OTHER_COUNTER, entry.clock.entries[SEED_OTHER_DEVICE])
            assertNotNull(entry.clock.entries[deviceId], "this device must have ticked")
            // What a peer that still holds the live tunnel does with the tombstone it receives.
            val merged = assertIs<MergeResult.Clean<TunnelConfig>>(CrdtEngine.merge(previous, entry))
            assertTrue(merged.entry.deleted)
        }
    }

    @Test
    fun `the repair leaves live tunnels, tombstones, protected hosts and the host rows untouched`() = runTest {
        db.insertHostRow("h-live")
        db.insertHostRow("h-gone")
        db.insertHostRow("h-contested")
        db.insertTunnelRow("t-ok", hostId = "h-live")
        db.insertTunnelRow("t-orphan", hostId = "h-gone")
        db.insertTunnelRow("t-already-gone", hostId = "h-gone")
        db.tunnelQueries.markDeleted(deletedAt = 1L, updatedAt = 1L, vectorClock = "{}", id = "t-already-gone")
        db.insertTunnelRow("t-protected", hostId = "h-contested")
        db.tombstoneHostRowOnly("h-gone")
        db.tombstoneHostRowOnly("h-contested")
        db.insertPendingConflictRow(SyncableEntityType.HOST, "h-contested")
        val untouchedTunnels = listOf("t-ok", "t-already-gone", "t-protected")
            .associateWith { db.tunnelQueries.selectByIdIncludingDeleted(it).executeAsOne() }
        val hostRowsBefore = db.hostQueries.selectAllIncludingDeleted().executeAsList()

        assertEquals(1, repair.run())

        untouchedTunnels.forEach { (id, row) ->
            assertEquals(row, db.tunnelQueries.selectByIdIncludingDeleted(id).executeAsOne(), "$id must not change")
        }
        assertEquals(1L, db.tunnelQueries.selectByIdIncludingDeleted("t-orphan").executeAsOne().deleted)
        assertEquals(hostRowsBefore, db.hostQueries.selectAllIncludingDeleted().executeAsList(), "no host is written")
    }

    @Test
    fun `a database without orphans is not written at all`() = runTest {
        db.insertHostRow("h-live")
        db.insertTunnelRow("t-ok", hostId = "h-live")
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t-gone", hostId = "h-gone")
        // A host deleted the proper way: its tunnel is already a tombstone, nothing is left to repair.
        DesktopHostRepository(db).delete("h-gone")
        val tunnelsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()
        val hostsBefore = db.hostQueries.selectAllIncludingDeleted().executeAsList()

        assertEquals(0, repair.run())

        // Not a single write: this would be sync churn at every launch and after every push.
        assertEquals(tunnelsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
        assertEquals(hostsBefore, db.hostQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `a second run right after a repair is a no-op`() = runTest {
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t1", hostId = "h-gone")
        db.tombstoneHostRowOnly("h-gone")
        assertEquals(1, repair.run())
        val afterFirst = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()

        assertEquals(0, repair.run())

        assertEquals(afterFirst, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `the repair deletes nothing, rows stay in the table to carry the tombstone`() = runTest {
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t1", hostId = "h-gone")
        db.tombstoneHostRowOnly("h-gone")

        repair.run()

        assertEquals(listOf("t1"), db.tunnelQueries.selectAllIncludingDeleted().executeAsList().map { it.id })
        assertEquals(1L, db.hostQueries.selectByIdIncludingDeleted("h-gone").executeAsOne().deleted)
    }

    @Test
    fun `a protected host is repaired as soon as its conflict is gone`() = runTest {
        db.insertHostRow("h-contested")
        db.insertTunnelRow("t1", hostId = "h-contested")
        db.tombstoneHostRowOnly("h-contested")
        db.insertPendingConflictRow(SyncableEntityType.HOST, "h-contested")

        assertEquals(0, repair.run())
        assertNotNull(tunnelRepository.getById("t1"), "the tunnel stays live while the conflict is pending")

        DesktopPendingConflictRepository(db).deleteByEntity(SyncableEntityType.HOST, "h-contested")

        assertEquals(1, repair.run())
        assertEquals(1L, db.tunnelQueries.selectByIdIncludingDeleted("t1").executeAsOne().deleted)
    }

    @Test
    fun `a write failure rolls the whole repair back and is swallowed`() = runTest {
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t1", hostId = "h-gone")
        db.insertTunnelRow("t2", hostId = "h-gone")
        db.tombstoneHostRowOnly("h-gone")
        driver.execute(
            null,
            "CREATE TRIGGER fail_second_tunnel BEFORE UPDATE ON tunnels WHEN OLD.id = 't2' " +
                "BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )

        assertEquals(0, repair.run())

        assertEquals(listOf("t1", "t2"), orphanIds(), "t1 was written before the failure: it must be rolled back")
        assertNotNull(tunnelRepository.getById("t1"))
    }

    // -- The host delete keeps its behaviour through the shared helper ------------

    @Test
    fun `deleting a host still tombstones its tunnels with a ticked clock through the shared helper`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        val live = tunnelRepository.getAllSyncEntries().single()

        DesktopHostRepository(db).delete("h1")

        val tombstone = tunnelRepository.getAllSyncEntries().single()
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.clock.dominates(live.clock))
        assertTrue(orphanIds().isEmpty())
    }
}
