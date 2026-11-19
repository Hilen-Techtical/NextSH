// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting a host tombstones its live tunnels too, in one transaction, each with its own ticked clock so
 * that the deletion reaches the peers. Foreign keys are ON: a tombstone is an UPDATE, so no cascade fires
 * and the tunnel rows must stay in the table to carry the deletion.
 */
class DesktopHostRepositoryDeleteTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository

    private val deviceId = DeviceIdentity.deviceId()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        tunnelRepository = DesktopTunnelRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `deleting a host tombstones the host and all its live tunnels`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        db.insertTunnelRow("t2", hostId = "h1", isFavorite = true)
        val before = System.currentTimeMillis()

        hostRepository.delete("h1")

        assertNull(hostRepository.getById("h1"))
        assertTrue(db.tunnelQueries.selectByHost("h1").executeAsList().isEmpty())
        assertNull(tunnelRepository.getById("t1"))
        assertNull(tunnelRepository.getById("t2"))
        // The rows are still there: tombstones travel in sync bundles.
        val tunnelRows = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()
        assertEquals(setOf("t1", "t2"), tunnelRows.map { it.id }.toSet())
        tunnelRows.forEach { row ->
            assertEquals(1L, row.deleted)
            val deletedAt = assertNotNull(row.deletedAt)
            assertTrue(deletedAt >= before)
            assertTrue(row.updatedAt >= before)
            assertEquals(SEED_CREATED_AT, row.createdAt, "createdAt is local-only and must not move")
        }
        val hostRow = db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne()
        assertEquals(1L, hostRow.deleted)
    }

    @Test
    fun `each tunnel tombstone ticks that tunnel own clock and keeps the other device counters`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        val seeded = tunnelRepository.getAllSyncEntries().single()

        hostRepository.delete("h1")

        val entry = tunnelRepository.getAllSyncEntries().single()
        assertTrue(entry.deleted)
        assertNull(entry.payload)
        assertTrue(entry.clock.dominates(seeded.clock), "the tombstone must dominate the previous clock")
        assertEquals(SEED_OTHER_COUNTER, entry.clock.entries[SEED_OTHER_DEVICE])
        assertNotNull(entry.clock.entries[deviceId], "this device must have ticked")
    }

    @Test
    fun `the other device applies the tunnel tombstone instead of keeping its live tunnel`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        val peerLiveTunnel = tunnelRepository.getAllSyncEntries().single()

        hostRepository.delete("h1")

        val tombstone = tunnelRepository.getAllSyncEntries().single()
        val merged = assertIs<MergeResult.Clean<TunnelConfig>>(CrdtEngine.merge(peerLiveTunnel, tombstone))
        assertTrue(merged.entry.deleted)
    }

    @Test
    fun `tunnels of another host and already deleted tunnels are left alone`() = runTest {
        db.insertHostRow("h1")
        db.insertHostRow("h2")
        db.insertTunnelRow("other", hostId = "h2")
        db.insertTunnelRow("gone", hostId = "h1")
        tunnelRepository.delete("gone")
        db.insertTunnelRow("live", hostId = "h1")
        val otherBefore = db.tunnelQueries.selectByIdIncludingDeleted("other").executeAsOne()
        val goneBefore = db.tunnelQueries.selectByIdIncludingDeleted("gone").executeAsOne()

        hostRepository.delete("h1")

        assertEquals(otherBefore, db.tunnelQueries.selectByIdIncludingDeleted("other").executeAsOne())
        assertEquals(goneBefore, db.tunnelQueries.selectByIdIncludingDeleted("gone").executeAsOne())
        assertEquals(1L, db.tunnelQueries.selectByIdIncludingDeleted("live").executeAsOne().deleted)
        assertNotNull(hostRepository.getById("h2"))
        assertNotNull(tunnelRepository.getById("other"))
    }

    @Test
    fun `deleting a host without tunnels only tombstones the host`() = runTest {
        db.insertHostRow("h1")

        hostRepository.delete("h1")

        assertNull(hostRepository.getById("h1"))
        assertEquals(1L, db.hostQueries.selectByIdIncludingDeleted("h1").executeAsOne().deleted)
        assertTrue(db.tunnelQueries.selectAllIncludingDeleted().executeAsList().isEmpty())
    }

    @Test
    fun `host and tunnel tombstones are one transaction, a failure on a tunnel keeps the host alive`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        driver.execute(
            null,
            "CREATE TRIGGER fail_tunnel_update BEFORE UPDATE ON tunnels BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
            0,
        )

        assertFailsWith<Exception> { hostRepository.delete("h1") }

        assertNotNull(hostRepository.getById("h1"), "the host tombstone must be rolled back")
        assertNotNull(tunnelRepository.getById("t1"))
    }
}
