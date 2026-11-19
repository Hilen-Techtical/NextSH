// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.FakeTransactionRunner
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

    private val transactionRunner = FakeTransactionRunner()

    private lateinit var repository: TunnelRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = TunnelRepositoryImpl(tunnelDao, transactionRunner)
    }

    private fun entity(
        isFavorite: Boolean,
        clock: VectorClock = VectorClock.EMPTY,
        updatedAt: Long = 1_000L,
        createdAt: Long = 111L,
        deleted: Boolean = false,
        deletedAt: Long? = null,
        browserPath: String = "/",
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
        browserPath = browserPath,
    )

    private fun remoteTunnel(isFavorite: Boolean = true, browserPath: String = "/") = TunnelConfig(
        id = "tunnel-1",
        label = "Web (remote)",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 9090,
        remoteHost = "intranet",
        remotePort = 443,
        isFavorite = isFavorite,
        browserPath = browserPath,
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
        val saved = written.captured
        assertEquals("Web (remote)", saved.label)
        assertEquals(9090, saved.localPort)
        assertTrue(saved.isFavorite)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertEquals(5_000L, saved.updatedAt)
        assertFalse(saved.deleted)
        assertEquals(111L, saved.createdAt)
    }

    // The start path of the in-app browser travels with the tunnel on every write path.

    @Test
    fun `upsertSyncEntry writes the start path of the synced tunnel`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns entity(isFavorite = false, browserPath = "/")
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(
                id = "tunnel-1",
                payload = remoteTunnel(browserPath = "/admin?tab=2"),
                clock = remoteClock,
                updatedAt = 5_000L,
            )
        )

        assertEquals("/admin?tab=2", written.captured.browserPath)
    }

    @Test
    fun `upsertSyncEntry tombstone keeps the stored start path`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns
            entity(isFavorite = false, browserPath = "/admin")
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(id = "tunnel-1", payload = null, clock = remoteClock, deleted = true, deletedAt = 4_000L, updatedAt = 4_000L)
        )

        assertEquals("/admin", written.captured.browserPath)
    }

    @Test
    fun `getAllSyncEntries sends the start path in the payload`() = runTest {
        coEvery { tunnelDao.getAllIncludingDeleted() } returns listOf(entity(isFavorite = false, browserPath = "/admin?tab=2"))

        val entries = repository.getAllSyncEntries()

        assertEquals("/admin?tab=2", entries.single().payload?.browserPath)
    }

    @Test
    fun `save persists the start path`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns null
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.save(remoteTunnel(browserPath = "/admin"))

        assertEquals("/admin", written.captured.browserPath)
    }

    @Test
    fun `update persists the start path`() = runTest {
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = false, browserPath = "/")
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.update(capture(written)) } returns Unit

        repository.update(remoteTunnel(browserPath = "/admin"))

        assertEquals("/admin", written.captured.browserPath)
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
    }

    // save: an existing id (a backup restore) is updated in place, never REPLACEd; a new id is
    // inserted with a fresh clock.

    @Test
    fun `save of a new id goes through upsert with a fresh clock, never insert`() = runTest {
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns null
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.save(remoteTunnel(isFavorite = false))
        val after = System.currentTimeMillis()

        coVerify(exactly = 1) { tunnelDao.upsert(any()) }
        val saved = written.captured
        assertEquals("Web (remote)", saved.label)
        assertEquals(9090, saved.localPort)
        assertTrue(saved.updatedAt in before..after)
        assertTrue(saved.createdAt in before..after)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        val clock = VectorClockCodec.decode(saved.vectorClock)
        assertEquals(setOf(DeviceIdentity.deviceId()), clock.entries.keys)
        assertEquals(saved.updatedAt, clock.entries[DeviceIdentity.deviceId()])
    }

    @Test
    fun `save over an existing tunnel updates in place, keeps createdAt, ticks the stored clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns
            entity(isFavorite = false, clock = previous, createdAt = 111L)
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.save(remoteTunnel(isFavorite = true, browserPath = "/admin"))
        val after = System.currentTimeMillis()

        coVerify(exactly = 0) { tunnelDao.deleteById(any()) }
        coVerify(exactly = 0) { tunnelDao.delete(any()) }
        val saved = written.captured
        assertEquals("Web (remote)", saved.label)
        assertEquals(9090, saved.localPort)
        assertEquals("intranet", saved.remoteHost)
        assertEquals("/admin", saved.browserPath)
        assertTrue(saved.isFavorite)
        assertEquals(111L, saved.createdAt)
        assertTrue(saved.updatedAt in before..after)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `save over a tombstoned tunnel revives it in place and ticks the tombstone clock`() = runTest {
        val previous = VectorClock(mapOf("desktop-device" to 9L))
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns
            entity(isFavorite = false, clock = previous, createdAt = 111L, deleted = true, deletedAt = 900L)
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.save(remoteTunnel())

        val saved = written.captured
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        assertEquals(111L, saved.createdAt)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous), "the revive must win over the tombstone on the peers")
        assertEquals(9L, newClock.entries["desktop-device"])
    }

    // update: an edit keeps the local-only createdAt and never revives a deleted tunnel.

    @Test
    fun `update keeps createdAt and ticks the stored clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = false, clock = previous, createdAt = 111L)
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.update(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.update(remoteTunnel(isFavorite = true))
        val after = System.currentTimeMillis()

        val saved = written.captured
        assertEquals("Web (remote)", saved.label)
        assertEquals(9090, saved.localPort)
        assertTrue(saved.isFavorite)
        assertEquals(111L, saved.createdAt)
        assertTrue(saved.updatedAt in before..after)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
        coVerify(exactly = 0) { tunnelDao.upsert(any()) }
    }

    @Test
    fun `update of a deleted or unknown tunnel writes nothing`() = runTest {
        // TunnelDao.getById filters deleted = 0: a tombstone and an unknown id both come back null.
        coEvery { tunnelDao.getById("tunnel-1") } returns null

        repository.update(remoteTunnel())

        coVerify(exactly = 0) { tunnelDao.update(any()) }
        coVerify(exactly = 0) { tunnelDao.upsert(any()) }
    }

    // delete: a tunnel already deleted (or unknown) keeps its tombstone untouched.

    @Test
    fun `delete tombstones a live tunnel with a ticked clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { tunnelDao.getById("tunnel-1") } returns entity(isFavorite = false, clock = previous)
        val deletedAt = slot<Long>()
        val updatedAt = slot<Long>()
        val clockJson = slot<String>()
        coEvery {
            tunnelDao.softDelete("tunnel-1", capture(deletedAt), capture(updatedAt), capture(clockJson))
        } returns Unit

        val before = System.currentTimeMillis()
        repository.delete("tunnel-1")
        val after = System.currentTimeMillis()

        assertTrue(deletedAt.captured in before..after)
        assertEquals(deletedAt.captured, updatedAt.captured)
        val newClock = VectorClockCodec.decode(clockJson.captured)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `delete of an unknown or already deleted tunnel writes nothing`() = runTest {
        // TunnelDao.getById filters deleted = 0: a tombstone and an unknown id both come back null.
        coEvery { tunnelDao.getById("gone") } returns null

        repository.delete("gone")

        coVerify(exactly = 0) { tunnelDao.softDelete(any(), any(), any(), any()) }
    }

    // save: restoring a tunnel that is already stored with the same content is a no-op.

    @Test
    fun `save of a live tunnel that already holds this content writes nothing`() = runTest {
        val same = remoteTunnel(isFavorite = true)
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns TunnelEntity.fromDomain(same).copy(
            createdAt = 111L,
            vectorClock = VectorClockCodec.encode(remoteClock),
            updatedAt = 1_000L,
        )

        repository.save(same)

        coVerify(exactly = 0) { tunnelDao.upsert(any()) }
    }

    @Test
    fun `save of a live tunnel that differs by one field writes`() = runTest {
        val same = remoteTunnel(isFavorite = true)
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns
            TunnelEntity.fromDomain(same.copy(remotePort = 8443)).copy(vectorClock = VectorClockCodec.encode(remoteClock))
        coEvery { tunnelDao.upsert(any()) } returns Unit

        repository.save(same)

        coVerify(exactly = 1) { tunnelDao.upsert(match { it.remotePort == 443 }) }
    }

    @Test
    fun `save of a tombstoned tunnel with the same content still revives it`() = runTest {
        val same = remoteTunnel()
        coEvery { tunnelDao.getByIdIncludingDeleted("tunnel-1") } returns TunnelEntity.fromDomain(same).copy(
            vectorClock = VectorClockCodec.encode(remoteClock),
            deleted = true,
            deletedAt = 900L,
        )
        val written = slot<TunnelEntity>()
        coEvery { tunnelDao.upsert(capture(written)) } returns Unit

        repository.save(same)

        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertTrue(VectorClockCodec.decode(written.captured.vectorClock).dominates(remoteClock))
    }

    @Test
    fun `update reads and writes inside one transaction`() = runTest {
        val insideTransaction = mutableListOf<Boolean>()
        coEvery { tunnelDao.getById("tunnel-1") } coAnswers {
            insideTransaction += transactionRunner.active
            entity(isFavorite = false)
        }
        coEvery { tunnelDao.update(any()) } coAnswers { insideTransaction += transactionRunner.active }

        repository.update(remoteTunnel())

        // A sync tombstone landing between the read and the write would otherwise be overwritten.
        assertEquals(1, transactionRunner.runs)
        assertEquals(listOf(true, true), insideTransaction)
    }

    @Test
    fun `update of a deleted tunnel still runs its check inside the transaction`() = runTest {
        coEvery { tunnelDao.getById("tunnel-1") } coAnswers {
            assertTrue(transactionRunner.active)
            null
        }

        repository.update(remoteTunnel())

        assertEquals(1, transactionRunner.runs)
        coVerify(exactly = 0) { tunnelDao.update(any()) }
    }
}
