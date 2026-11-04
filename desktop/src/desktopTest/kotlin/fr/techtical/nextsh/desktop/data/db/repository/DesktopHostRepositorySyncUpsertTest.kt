// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A host sync write must update the row in place: INSERT OR REPLACE cascades to its tunnels and resets
 * createdAt and lastConnectedAt. Foreign keys are ON here so the cascade is observable.
 */
class DesktopHostRepositorySyncUpsertTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var tunnelRepository: DesktopTunnelRepository

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
    fun `syncing an existing host keeps its tunnels`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1", isFavorite = true)
        db.insertTunnelRow("t2", hostId = "h1")
        val tunnelEntriesBefore = tunnelRepository.getAllSyncEntries()
        val tunnelRowsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()
        assertEquals(2, tunnelRowsBefore.size)

        hostRepository.upsertSyncEntry(liveEntry("h1", label = "Renamed remotely", counter = 6L))

        assertEquals("Renamed remotely", hostRepository.getById("h1")?.label)
        assertEquals(tunnelRowsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
        assertEquals(tunnelEntriesBefore, tunnelRepository.getAllSyncEntries())
    }

    @Test
    fun `syncing the very same host entry keeps its tunnels`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1", isFavorite = true)
        val sameEntry = hostRepository.getAllSyncEntries().single()
        val tunnelRowsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()

        hostRepository.upsertSyncEntry(sameEntry)

        assertEquals(tunnelRowsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `syncing an existing host keeps the local-only createdAt and lastConnectedAt`() = runTest {
        db.insertHostRow("h1")

        hostRepository.upsertSyncEntry(liveEntry("h1", label = "Renamed remotely", counter = 6L))

        val row = db.hostQueries.selectAllIncludingDeleted().executeAsList().single()
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(SEED_LAST_CONNECTED_AT, row.lastConnectedAt)
        assertEquals("Renamed remotely", row.label)
        assertEquals(VectorClockCodec.encode(clockOf(6L)), row.vectorClock)
        assertEquals(REMOTE_UPDATED_AT, row.updatedAt)
    }

    @Test
    fun `syncing an existing host writes every synced column`() = runTest {
        db.insertHostRow("h1")
        val remote = Host(
            id = "h1",
            label = "Remote label",
            hostname = "remote.example.org",
            port = 2222,
            username = "remote-user",
            authType = AuthType.FIDO2,
            credentialId = "remote-cred",
            group = "Remote group",
            keepAliveSeconds = 90,
            autoReconnect = false,
            terminalTheme = "OTHER_THEME",
            fido2Mode = Fido2Mode.PASSKEY,
            isFavorite = true,
        )
        assertNull(db.hostQueries.selectAllIncludingDeleted().executeAsList().single().fido2Mode)

        hostRepository.upsertSyncEntry(
            SyncEntry(id = "h1", payload = remote, clock = clockOf(6L), updatedAt = REMOTE_UPDATED_AT),
        )

        assertEquals(remote, hostRepository.getById("h1"))
    }

    @Test
    fun `a tombstone entry for an unknown id creates no row`() = runTest {
        hostRepository.upsertSyncEntry(
            SyncEntry(
                id = "ghost",
                payload = null,
                clock = clockOf(6L),
                deleted = true,
                deletedAt = REMOTE_UPDATED_AT,
                updatedAt = REMOTE_UPDATED_AT,
            ),
        )

        assertEquals(emptyList(), db.hostQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `an unknown id is inserted`() = runTest {
        val entry = liveEntry("new", label = "Brand new", counter = 6L)

        hostRepository.upsertSyncEntry(entry)

        val stored = hostRepository.getAllSyncEntries().single()
        assertEquals(entry, stored)
        assertNull(db.hostQueries.selectAllIncludingDeleted().executeAsList().single().lastConnectedAt)
    }

    @Test
    fun `a tombstoned host receiving a live entry is revived in place`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1")
        hostRepository.delete("h1")
        val tunnelRowsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()
        assertEquals(1L, db.hostQueries.selectAllIncludingDeleted().executeAsList().single().deleted)

        hostRepository.upsertSyncEntry(liveEntry("h1", label = "Back from the dead", counter = 6L))

        val row = db.hostQueries.selectAllIncludingDeleted().executeAsList().single()
        assertEquals(0L, row.deleted)
        assertNull(row.deletedAt)
        assertEquals(SEED_CREATED_AT, row.createdAt)
        assertEquals(SEED_LAST_CONNECTED_AT, row.lastConnectedAt)
        assertEquals("Back from the dead", assertNotNull(hostRepository.getById("h1")).label)
        assertEquals(tunnelRowsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `a host tombstone entry does not delete its tunnels physically`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t1", hostId = "h1", isFavorite = true)
        val tunnelRowsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()

        hostRepository.upsertSyncEntry(
            SyncEntry(
                id = "h1",
                payload = null,
                clock = clockOf(6L),
                deleted = true,
                deletedAt = REMOTE_UPDATED_AT,
                updatedAt = REMOTE_UPDATED_AT,
            ),
        )

        val hostRow = db.hostQueries.selectAllIncludingDeleted().executeAsList().single()
        assertEquals(1L, hostRow.deleted)
        assertEquals(SEED_CREATED_AT, hostRow.createdAt)
        assertEquals(tunnelRowsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    private fun clockOf(counter: Long): VectorClock = VectorClock(mapOf(SEED_OTHER_DEVICE to counter))

    private fun liveEntry(id: String, label: String, counter: Long): SyncEntry<Host> = SyncEntry(
        id = id,
        payload = Host(
            id = id,
            label = label,
            hostname = "$id.local",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-$id",
        ),
        clock = clockOf(counter),
        updatedAt = REMOTE_UPDATED_AT,
    )

    private companion object {
        const val REMOTE_UPDATED_AT = 5_000L
    }
}
