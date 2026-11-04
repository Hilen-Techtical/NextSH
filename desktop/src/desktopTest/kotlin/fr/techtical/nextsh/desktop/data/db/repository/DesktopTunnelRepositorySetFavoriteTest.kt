// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * isFavorite travels in the sync payload, so toggling it is a regular synced edit: it must tick
 * the vector clock and bump updatedAt, otherwise it never wins a LAN sync.
 */
class DesktopTunnelRepositorySetFavoriteTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var repository: DesktopTunnelRepository

    private val deviceId = DeviceIdentity.deviceId()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        repository = DesktopTunnelRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `setFavorite true ticks this device clock, bumps updatedAt and sets the flag`() = runTest {
        val seeded = seed("t1", isFavorite = false)
        val before = System.currentTimeMillis()

        repository.setFavorite("t1", true)

        val entry = entryOf("t1")
        assertEquals(true, entry.payload?.isFavorite)
        assertEquals(true, repository.getById("t1")?.isFavorite)
        assertTrue(entry.clock.dominates(seeded.clock), "clock must dominate the previous one")
        assertEquals(OTHER_DEVICE_COUNTER, entry.clock.entries[OTHER_DEVICE], "other devices counters are kept")
        val tick = assertNotNull(entry.clock.entries[deviceId], "this device must have ticked")
        assertTrue(tick >= before)
        assertTrue(entry.updatedAt >= before && entry.updatedAt > SEED_UPDATED_AT)
    }

    @Test
    fun `successive toggles each dominate the previous clock`() = runTest {
        seed("t1", isFavorite = false)

        repository.setFavorite("t1", true)
        val afterOn = entryOf("t1")
        repository.setFavorite("t1", false)
        val afterOff = entryOf("t1")

        assertEquals(false, afterOff.payload?.isFavorite)
        assertTrue(afterOff.clock.dominates(afterOn.clock), "second toggle must dominate the first even within one millisecond")
        assertTrue(afterOff.updatedAt >= afterOn.updatedAt)
    }

    @Test
    fun `setFavorite with the same value false is a no-op`() = runTest {
        val seeded = seed("t1", isFavorite = false)

        repository.setFavorite("t1", false)

        assertEquals(seeded, entryOf("t1"))
    }

    @Test
    fun `setFavorite with the same value true is a no-op`() = runTest {
        val seeded = seed("t1", isFavorite = true)

        repository.setFavorite("t1", true)

        assertEquals(seeded, entryOf("t1"))
    }

    @Test
    fun `setFavorite on an unknown id does nothing`() = runTest {
        val seeded = seed("t1", isFavorite = false)

        repository.setFavorite("missing", true)

        assertEquals(listOf(seeded), repository.getAllSyncEntries())
    }

    @Test
    fun `setFavorite on a tombstoned tunnel leaves the tombstone untouched`() = runTest {
        seed("t1", isFavorite = false)
        repository.delete("t1")
        val rowsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()
        assertEquals(1L, rowsBefore.single().deleted)

        repository.setFavorite("t1", true)

        assertEquals(rowsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    @Test
    fun `the other device merging our toggled favorite keeps it over its own older entry`() = runTest {
        val otherDeviceEntry = seed("t1", isFavorite = false)

        repository.setFavorite("t1", true)

        // Merge from the other device's point of view: its entry is local, ours is the remote one.
        val toggled = entryOf("t1")
        val merged = assertIs<MergeResult.Clean<TunnelConfig>>(CrdtEngine.merge(otherDeviceEntry, toggled))
        assertEquals(true, merged.entry.payload?.isFavorite)
        assertEquals(toggled.clock, merged.entry.clock)
    }

    @Test
    fun `the setFavorite query alone never touches a tombstoned row`() = runTest {
        seed("t1", isFavorite = false)
        repository.delete("t1")
        val rowsBefore = db.tunnelQueries.selectAllIncludingDeleted().executeAsList()
        assertEquals(1L, rowsBefore.single().deleted)

        db.tunnelQueries.setFavorite(isFavorite = 1L, vectorClock = "{}", updatedAt = 1L, id = "t1")

        assertEquals(rowsBefore, db.tunnelQueries.selectAllIncludingDeleted().executeAsList())
    }

    /** Inserts a tunnel through the sync primitive so that clock and updatedAt are known, old values. */
    private suspend fun seed(id: String, isFavorite: Boolean): SyncEntry<TunnelConfig> {
        // Tunnels reference a host (foreign key): create it once.
        if (hostRepository.getById(HOST_ID) == null) {
            hostRepository.save(
                Host(
                    id = HOST_ID,
                    label = "Host",
                    hostname = "host.local",
                    username = "user",
                    authType = AuthType.PASSWORD,
                    credentialId = "cred",
                ),
            )
        }
        repository.upsertSyncEntry(
            SyncEntry(
                id = id,
                payload = makeTunnel(id, isFavorite),
                clock = VectorClock(mapOf(OTHER_DEVICE to OTHER_DEVICE_COUNTER)),
                updatedAt = SEED_UPDATED_AT,
            ),
        )
        return entryOf(id)
    }

    private suspend fun entryOf(id: String): SyncEntry<TunnelConfig> =
        repository.getAllSyncEntries().single { it.id == id }

    private fun makeTunnel(id: String, isFavorite: Boolean): TunnelConfig = TunnelConfig(
        id = id,
        label = "Tunnel $id",
        hostId = HOST_ID,
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
        isFavorite = isFavorite,
    )

    private companion object {
        const val HOST_ID = "host-1"
        const val OTHER_DEVICE = "other-device"
        const val OTHER_DEVICE_COUNTER = 5L
        const val SEED_UPDATED_AT = 1_000L
    }
}
