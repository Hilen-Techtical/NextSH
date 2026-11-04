// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.data.db.entity.TunnelEntity
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class TunnelRepositoryImplTest {

    @MockK
    private lateinit var tunnelDao: TunnelDao

    private lateinit var repository: TunnelRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = TunnelRepositoryImpl(tunnelDao)
    }

    private fun entity(
        isFavorite: Boolean,
        clock: VectorClock = VectorClock.EMPTY,
        updatedAt: Long = 1_000L,
        createdAt: Long = 111L,
        deleted: Boolean = false,
        deletedAt: Long? = null,
    ) = TunnelEntity(
        id = "tunnel-1",
        label = "Web",
        hostId = "host-1",
        type = "LOCAL_FORWARD",
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
        isFavorite = isFavorite,
        createdAt = createdAt,
        vectorClock = VectorClockCodec.encode(clock),
        deleted = deleted,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

    private fun remoteTunnel(isFavorite: Boolean = true) = TunnelConfig(
        id = "tunnel-1",
        label = "Web (remote)",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 9090,
        remoteHost = "intranet",
        remotePort = 443,
        isFavorite = isFavorite,
    )

    private val remoteClock = VectorClock(mapOf("desktop-device" to 9L))

    @Test
    fun `setFavorite writes the flag, a ticked clock for this device and updatedAt`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val previous = VectorClock(mapOf(deviceId to 5L, "other-device" to 7L))
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = false, clock = previous)
        val flag = slot<Boolean>()
        val clockJson = slot<String>()
        val updatedAt = slot<Long>()
        coEvery {
            tunnelDao.updateFavorite("tunnel-1", capture(flag), capture(clockJson), capture(updatedAt))
        } returns Unit

        val before = System.currentTimeMillis()
        repository.setFavorite("tunnel-1", true)
        val after = System.currentTimeMillis()

        assertEquals(true, flag.captured)
        assertTrue(updatedAt.captured in before..after)
        val newClock = VectorClockCodec.decode(clockJson.captured)
        assertTrue(newClock.dominates(previous))
        assertEquals(updatedAt.captured, newClock.entries[deviceId])
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `setFavorite to false also ticks the clock`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val previous = VectorClock(mapOf(deviceId to 5L))
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = true, clock = previous)
        val flag = slot<Boolean>()
        val clockJson = slot<String>()
        coEvery {
            tunnelDao.updateFavorite("tunnel-1", capture(flag), capture(clockJson), any())
        } returns Unit

        repository.setFavorite("tunnel-1", false)

        assertEquals(false, flag.captured)
        assertTrue(VectorClockCodec.decode(clockJson.captured).dominates(previous))
    }

    @Test
    fun `setFavorite still dominates when the stored counter is ahead of the wall clock`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val ahead = System.currentTimeMillis() + 10_000_000L
        val previous = VectorClock(mapOf(deviceId to ahead))
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = false, clock = previous)
        val clockJson = slot<String>()
        coEvery {
            tunnelDao.updateFavorite("tunnel-1", any(), capture(clockJson), any())
        } returns Unit

        repository.setFavorite("tunnel-1", true)

        val newClock = VectorClockCodec.decode(clockJson.captured)
        assertTrue(newClock.dominates(previous))
        assertEquals(ahead + 1, newClock.entries[deviceId])
    }

    @Test
    fun `setFavorite with the stored value is a no-op`() = runTest {
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = true)

        repository.setFavorite("tunnel-1", true)

        coVerify(exactly = 0) { tunnelDao.updateFavorite(any(), any(), any(), any()) }
    }

    @Test
    fun `setFavorite with the stored value false is a no-op`() = runTest {
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = false)

        repository.setFavorite("tunnel-1", false)

        coVerify(exactly = 0) { tunnelDao.updateFavorite(any(), any(), any(), any()) }
    }

    @Test
    fun `setFavorite on a missing or tombstoned row is a no-op`() = runTest {
        // TunnelDao.getById filters deleted = 0, so a tombstone and an unknown id both come back null.
        coEvery { tunnelDao.getById("gone") } returns null

        repository.setFavorite("gone", true)

        coVerify(exactly = 0) { tunnelDao.updateFavorite(any(), any(), any(), any()) }
    }

    // upsertSyncEntry: a sync write updates in place and keeps the local-only createdAt.

    @Test
    fun `upsertSyncEntry live entry goes through upsert, never insert, and keeps createdAt`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns entity(isFavorite = false, createdAt = 111L)
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(id = "tunnel-1", payload = remoteTunnel(isFavorite = true), clock = remoteClock, updatedAt = 5_000L)
        )

        coVerify(exactly = 1) { tunnelDao.upsert(any()) }
        coVerify(exactly = 0) { tunnelDao.insert(any()) }
        val saved = written.captured
        assertEquals("Web (remote)", saved.label)
        assertEquals(9090, saved.localPort)
        assertTrue(saved.isFavorite)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertEquals(5_000L, saved.updatedAt)
        assertFalse(saved.deleted)
        assertEquals(111L, saved.createdAt)
    }

    @Test
    fun `upsertSyncEntry live entry over a local tombstone writes deleted false and keeps createdAt`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns
            entity(isFavorite = false, createdAt = 111L, deleted = true, deletedAt = 900L)
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(id = "tunnel-1", payload = remoteTunnel(), clock = remoteClock, updatedAt = 5_000L)
        )

        coVerify(exactly = 0) { tunnelDao.insert(any()) }
        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertEquals(111L, written.captured.createdAt)
    }

    @Test
    fun `upsertSyncEntry for an unknown id goes through upsert, never insert`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns null
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.upsertSyncEntry(
            SyncEntry(id = "tunnel-1", payload = remoteTunnel(), clock = remoteClock, updatedAt = 5_000L)
        )
        val after = System.currentTimeMillis()

        coVerify(exactly = 1) { tunnelDao.upsert(any()) }
        coVerify(exactly = 0) { tunnelDao.insert(any()) }
        val saved = written.captured
        assertEquals("tunnel-1", saved.id)
        assertEquals(5_000L, saved.updatedAt)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertTrue(saved.createdAt in before..after)
    }

    @Test
    fun `upsertSyncEntry tombstone goes through upsert, never insert, and only changes the sync columns`() = runTest {
        val existing = entity(isFavorite = true, createdAt = 111L)
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns existing
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(
                id = "tunnel-1",
                payload = null,
                clock = remoteClock,
                deleted = true,
                deletedAt = 4_000L,
                updatedAt = 4_000L,
            )
        )

        coVerify(exactly = 1) { tunnelDao.upsert(any()) }
        coVerify(exactly = 0) { tunnelDao.insert(any()) }
        assertEquals(
            existing.copy(
                deleted = true,
                deletedAt = 4_000L,
                updatedAt = 4_000L,
                vectorClock = VectorClockCodec.encode(remoteClock),
            ),
            written.captured,
        )
    }

    @Test
    fun `upsertSyncEntry tombstone for an unknown id writes nothing`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns null

        repository.upsertSyncEntry(
            SyncEntry(id = "tunnel-1", payload = null, clock = remoteClock, deleted = true, deletedAt = 4_000L, updatedAt = 4_000L)
        )

        coVerify(exactly = 0) { tunnelDao.upsert(any()) }
        coVerify(exactly = 0) { tunnelDao.insert(any()) }
    }
}
