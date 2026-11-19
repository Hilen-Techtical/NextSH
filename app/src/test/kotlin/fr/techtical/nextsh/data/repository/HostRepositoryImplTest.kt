// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.FakeTransactionRunner
import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.data.db.entity.HostEntity
import fr.techtical.nextsh.data.db.entity.TunnelEntity
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
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
class HostRepositoryImplTest {

    @MockK
    private lateinit var hostDao: HostDao

    @MockK
    private lateinit var tunnelDao: TunnelDao

    private val transactionRunner = FakeTransactionRunner()

    private lateinit var repository: HostRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = HostRepositoryImpl(hostDao, tunnelDao, transactionRunner)
    }

    private fun entity(
        isFavorite: Boolean,
        clock: VectorClock = VectorClock.EMPTY,
        updatedAt: Long = 1_000L,
        createdAt: Long = 111L,
        lastConnectedAt: Long? = 222L,
        deleted: Boolean = false,
        deletedAt: Long? = null,
    ) = HostEntity(
        id = "host-1",
        label = "Prod",
        hostname = "prod.example.org",
        username = "root",
        authType = "PASSWORD",
        credentialId = "cred-1",
        isFavorite = isFavorite,
        createdAt = createdAt,
        lastConnectedAt = lastConnectedAt,
        vectorClock = VectorClockCodec.encode(clock),
        deleted = deleted,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

    private fun remoteHost(isFavorite: Boolean = true) = Host(
        id = "host-1",
        label = "Prod (remote)",
        hostname = "remote.example.org",
        port = 2222,
        username = "admin",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
        isFavorite = isFavorite,
    )

    private val remoteClock = VectorClock(mapOf("desktop-device" to 9L))

    @Test
    fun `setFavorite writes the flag, a ticked clock for this device and updatedAt`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val previous = VectorClock(mapOf(deviceId to 5L, "other-device" to 7L))
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = false, clock = previous)
        val flag = slot<Boolean>()
        val clockJson = slot<String>()
        val updatedAt = slot<Long>()
        coEvery {
            hostDao.updateFavorite("host-1", capture(flag), capture(clockJson), capture(updatedAt))
        } returns Unit

        val before = System.currentTimeMillis()
        repository.setFavorite("host-1", true)
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
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = true, clock = previous)
        val flag = slot<Boolean>()
        val clockJson = slot<String>()
        coEvery {
            hostDao.updateFavorite("host-1", capture(flag), capture(clockJson), any())
        } returns Unit

        repository.setFavorite("host-1", false)

        assertEquals(false, flag.captured)
        assertTrue(VectorClockCodec.decode(clockJson.captured).dominates(previous))
    }

    @Test
    fun `setFavorite still dominates when the stored counter is ahead of the wall clock`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val ahead = System.currentTimeMillis() + 10_000_000L
        val previous = VectorClock(mapOf(deviceId to ahead))
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = false, clock = previous)
        val clockJson = slot<String>()
        coEvery {
            hostDao.updateFavorite("host-1", any(), capture(clockJson), any())
        } returns Unit

        repository.setFavorite("host-1", true)

        val newClock = VectorClockCodec.decode(clockJson.captured)
        assertTrue(newClock.dominates(previous))
        assertEquals(ahead + 1, newClock.entries[deviceId])
    }

    @Test
    fun `setFavorite with the stored value is a no-op`() = runTest {
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = true)

        repository.setFavorite("host-1", true)

        coVerify(exactly = 0) { hostDao.updateFavorite(any(), any(), any(), any()) }
    }

    @Test
    fun `setFavorite with the stored value false is a no-op`() = runTest {
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = false)

        repository.setFavorite("host-1", false)

        coVerify(exactly = 0) { hostDao.updateFavorite(any(), any(), any(), any()) }
    }

    @Test
    fun `setFavorite on a missing or tombstoned row is a no-op`() = runTest {
        // HostDao.getById filters deleted = 0, so a tombstone and an unknown id both come back null.
        coEvery { hostDao.getById("gone") } returns null

        repository.setFavorite("gone", true)

        coVerify(exactly = 0) { hostDao.updateFavorite(any(), any(), any(), any()) }
    }

    // upsertSyncEntry: a sync write must never go through the REPLACE insert (delete + insert),
    // because tunnels.hostId is a CASCADE foreign key and the host's tunnels would be wiped.

    @Test
    fun `upsertSyncEntry live entry goes through upsert, never insert, and keeps local-only columns`() = runTest {
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns
            entity(isFavorite = false, createdAt = 111L, lastConnectedAt = 222L)
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(id = "host-1", payload = remoteHost(isFavorite = true), clock = remoteClock, updatedAt = 5_000L)
        )

        coVerify(exactly = 1) { hostDao.upsert(any()) }
        val saved = written.captured
        assertEquals("Prod (remote)", saved.label)
        assertEquals("remote.example.org", saved.hostname)
        assertEquals(2222, saved.port)
        assertTrue(saved.isFavorite)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertEquals(5_000L, saved.updatedAt)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        assertEquals(111L, saved.createdAt)
        assertEquals(222L, saved.lastConnectedAt)
    }

    @Test
    fun `upsertSyncEntry live entry over a local tombstone writes deleted false and keeps local-only columns`() = runTest {
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns
            entity(isFavorite = false, createdAt = 111L, lastConnectedAt = 222L, deleted = true, deletedAt = 900L)
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(id = "host-1", payload = remoteHost(), clock = remoteClock, updatedAt = 5_000L)
        )

        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertEquals(111L, written.captured.createdAt)
        assertEquals(222L, written.captured.lastConnectedAt)
    }

    @Test
    fun `upsertSyncEntry for an unknown id goes through upsert, never insert`() = runTest {
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns null
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.upsertSyncEntry(
            SyncEntry(id = "host-1", payload = remoteHost(), clock = remoteClock, updatedAt = 5_000L)
        )
        val after = System.currentTimeMillis()

        coVerify(exactly = 1) { hostDao.upsert(any()) }
        val saved = written.captured
        assertEquals("host-1", saved.id)
        assertEquals(5_000L, saved.updatedAt)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertTrue(saved.createdAt in before..after)
        assertNull(saved.lastConnectedAt)
    }

    @Test
    fun `upsertSyncEntry tombstone goes through upsert, never insert, and only changes the sync columns`() = runTest {
        val existing = entity(isFavorite = true, createdAt = 111L, lastConnectedAt = 222L)
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns existing
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(
                id = "host-1",
                payload = null,
                clock = remoteClock,
                deleted = true,
                deletedAt = 4_000L,
                updatedAt = 4_000L,
            )
        )

        coVerify(exactly = 1) { hostDao.upsert(any()) }
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
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns null

        repository.upsertSyncEntry(
            SyncEntry(id = "host-1", payload = null, clock = remoteClock, deleted = true, deletedAt = 4_000L, updatedAt = 4_000L)
        )

        coVerify(exactly = 0) { hostDao.upsert(any()) }
    }

    // delete: the host and its live tunnels are tombstoned together, in one transaction.

    private fun tunnelEntity(
        id: String,
        clock: VectorClock = VectorClock.EMPTY,
        hostId: String = "host-1",
    ) = TunnelEntity(
        id = id,
        label = "Tunnel $id",
        hostId = hostId,
        type = "LOCAL_FORWARD",
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
        vectorClock = VectorClockCodec.encode(clock),
        updatedAt = 1_000L,
    )

    @Test
    fun `delete tombstones the host and every live tunnel with their own ticked clock, in one transaction`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val hostClock = VectorClock(mapOf(deviceId to 5L, "other-device" to 7L))
        val clockA = VectorClock(mapOf("desktop-device" to 3L))
        val clockB = VectorClock(mapOf(deviceId to 2L))
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = false, clock = hostClock)
        coEvery { tunnelDao.getByHostId("host-1") } returns
            listOf(tunnelEntity("tunnel-a", clockA), tunnelEntity("tunnel-b", clockB))

        val writesInsideTransaction = mutableListOf<Boolean>()
        val hostDeletedAt = slot<Long>()
        val hostUpdatedAt = slot<Long>()
        val hostClockJson = slot<String>()
        coEvery {
            hostDao.softDelete("host-1", capture(hostDeletedAt), capture(hostUpdatedAt), capture(hostClockJson))
        } coAnswers {
            writesInsideTransaction += transactionRunner.active
        }
        val tunnelDeletedAt = mutableMapOf<String, Long>()
        val tunnelUpdatedAt = mutableMapOf<String, Long>()
        val tunnelClockJson = mutableMapOf<String, String>()
        coEvery { tunnelDao.softDelete(any(), any(), any(), any()) } coAnswers {
            writesInsideTransaction += transactionRunner.active
            val id = firstArg<String>()
            tunnelDeletedAt[id] = secondArg()
            tunnelUpdatedAt[id] = thirdArg()
            tunnelClockJson[id] = arg(3)
        }

        val before = System.currentTimeMillis()
        repository.delete("host-1")
        val after = System.currentTimeMillis()

        assertEquals(1, transactionRunner.runs)
        assertEquals(listOf(true, true, true), writesInsideTransaction)

        assertTrue(hostDeletedAt.captured in before..after)
        assertEquals(hostDeletedAt.captured, hostUpdatedAt.captured)
        val newHostClock = VectorClockCodec.decode(hostClockJson.captured)
        assertTrue(newHostClock.dominates(hostClock))
        assertEquals(7L, newHostClock.entries["other-device"])

        assertEquals(setOf("tunnel-a", "tunnel-b"), tunnelClockJson.keys)
        val expectedPrevious = mapOf("tunnel-a" to clockA, "tunnel-b" to clockB)
        for ((id, previous) in expectedPrevious) {
            assertEquals(hostDeletedAt.captured, tunnelDeletedAt.getValue(id), "$id deletedAt")
            assertEquals(tunnelDeletedAt.getValue(id), tunnelUpdatedAt.getValue(id), "$id updatedAt")
            val newClock = VectorClockCodec.decode(tunnelClockJson.getValue(id))
            assertTrue(newClock.dominates(previous), "$id clock must dominate its previous clock")
        }
        // Each tunnel keeps its own history: tunnel-a's clock is not the host clock.
        val clockOfA = VectorClockCodec.decode(tunnelClockJson.getValue("tunnel-a"))
        assertEquals(3L, clockOfA.entries["desktop-device"])
        assertFalse(clockOfA.entries.containsKey("other-device"))
    }

    @Test
    fun `delete only looks at the tunnels of the deleted host`() = runTest {
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = false)
        coEvery { tunnelDao.getByHostId("host-1") } returns emptyList()
        coEvery { hostDao.softDelete(any(), any(), any(), any()) } returns Unit

        repository.delete("host-1")

        coVerify(exactly = 1) { hostDao.softDelete("host-1", any(), any(), any()) }
        coVerify(exactly = 1) { tunnelDao.getByHostId("host-1") }
        coVerify(exactly = 0) { tunnelDao.softDelete(any(), any(), any(), any()) }
    }

    @Test
    fun `delete of an unknown or already deleted host leaves its tombstone untouched`() = runTest {
        // HostDao.getById filters deleted = 0: a tombstone and an unknown id both come back null.
        coEvery { hostDao.getById("gone") } returns null
        coEvery { tunnelDao.getByHostId("gone") } returns emptyList()

        repository.delete("gone")

        coVerify(exactly = 0) { hostDao.softDelete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tunnelDao.softDelete(any(), any(), any(), any()) }
    }

    @Test
    fun `delete never hard-deletes, the host row and its tunnels stay as tombstones`() = runTest {
        coEvery { hostDao.getById("host-1") } returns entity(isFavorite = false)
        coEvery { tunnelDao.getByHostId("host-1") } returns listOf(tunnelEntity("tunnel-a"))
        coEvery { hostDao.softDelete(any(), any(), any(), any()) } returns Unit
        coEvery { tunnelDao.softDelete(any(), any(), any(), any()) } returns Unit

        repository.delete("host-1")

        coVerify(exactly = 0) { hostDao.deleteById(any()) }
        coVerify(exactly = 0) { hostDao.delete(any()) }
        coVerify(exactly = 0) { tunnelDao.deleteById(any()) }
        coVerify(exactly = 0) { tunnelDao.delete(any()) }
    }

    // save: an existing id (a backup restore) is updated in place, never REPLACEd, because
    // REPLACE would cascade-delete the host's tunnels; a new id is inserted with a fresh clock.

    @Test
    fun `save of a new id goes through upsert with a fresh clock, never insert`() = runTest {
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns null
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.save(remoteHost(isFavorite = false))
        val after = System.currentTimeMillis()

        coVerify(exactly = 1) { hostDao.upsert(any()) }
        val saved = written.captured
        assertEquals("Prod (remote)", saved.label)
        assertEquals(2222, saved.port)
        assertTrue(saved.updatedAt in before..after)
        assertTrue(saved.createdAt in before..after)
        assertNull(saved.lastConnectedAt)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        val clock = VectorClockCodec.decode(saved.vectorClock)
        assertEquals(setOf(DeviceIdentity.deviceId()), clock.entries.keys)
        assertEquals(saved.updatedAt, clock.entries[DeviceIdentity.deviceId()])
    }

    @Test
    fun `save over an existing host updates in place, keeps createdAt and lastConnectedAt, ticks the stored clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns
            entity(isFavorite = false, clock = previous, createdAt = 111L, lastConnectedAt = 222L)
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.save(remoteHost(isFavorite = true))
        val after = System.currentTimeMillis()

        coVerify(exactly = 0) { hostDao.deleteById(any()) }
        coVerify(exactly = 0) { hostDao.delete(any()) }
        val saved = written.captured
        // The backup version wins on the content...
        assertEquals("Prod (remote)", saved.label)
        assertEquals("remote.example.org", saved.hostname)
        assertEquals(2222, saved.port)
        assertTrue(saved.isFavorite)
        // ...the local-only columns stay...
        assertEquals(111L, saved.createdAt)
        assertEquals(222L, saved.lastConnectedAt)
        // ...and the clock moves forward from the stored one, so the peers take this version.
        assertTrue(saved.updatedAt in before..after)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `save over a tombstoned host revives it in place and ticks the tombstone clock`() = runTest {
        val previous = VectorClock(mapOf("desktop-device" to 9L))
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns
            entity(
                isFavorite = false,
                clock = previous,
                createdAt = 111L,
                lastConnectedAt = 222L,
                deleted = true,
                deletedAt = 900L,
            )
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        repository.save(remoteHost())

        val saved = written.captured
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        assertEquals(111L, saved.createdAt)
        assertEquals(222L, saved.lastConnectedAt)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous), "the revive must win over the tombstone on the peers")
        assertEquals(9L, newClock.entries["desktop-device"])
    }

    // update: an edit keeps the local-only columns and never revives a deleted host.

    @Test
    fun `update keeps createdAt and lastConnectedAt and ticks the stored clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { hostDao.getById("host-1") } returns
            entity(isFavorite = false, clock = previous, createdAt = 111L, lastConnectedAt = 222L)
        val written = slot<HostEntity>()
        coEvery { hostDao.update(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.update(remoteHost(isFavorite = true))
        val after = System.currentTimeMillis()

        val saved = written.captured
        assertEquals("Prod (remote)", saved.label)
        assertEquals(2222, saved.port)
        assertTrue(saved.isFavorite)
        assertEquals(111L, saved.createdAt)
        assertEquals(222L, saved.lastConnectedAt)
        assertTrue(saved.updatedAt in before..after)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
        coVerify(exactly = 0) { hostDao.upsert(any()) }
    }

    @Test
    fun `update of a deleted or unknown host writes nothing`() = runTest {
        // HostDao.getById filters deleted = 0: a tombstone and an unknown id both come back null.
        coEvery { hostDao.getById("host-1") } returns null

        repository.update(remoteHost())

        coVerify(exactly = 0) { hostDao.update(any()) }
        coVerify(exactly = 0) { hostDao.upsert(any()) }
    }

    // save: restoring a host that is already stored with the same content is a no-op.

    @Test
    fun `save of a live host that already holds this content writes nothing`() = runTest {
        val same = remoteHost(isFavorite = true)
        // Only the local-only columns and the sync columns differ from the domain model.
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns HostEntity.fromDomain(same).copy(
            createdAt = 111L,
            lastConnectedAt = 222L,
            vectorClock = VectorClockCodec.encode(remoteClock),
            updatedAt = 1_000L,
        )

        repository.save(same)

        coVerify(exactly = 0) { hostDao.upsert(any()) }
    }

    @Test
    fun `save of a live host that differs by one field writes`() = runTest {
        val same = remoteHost(isFavorite = true)
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns
            HostEntity.fromDomain(same.copy(port = 2200)).copy(vectorClock = VectorClockCodec.encode(remoteClock))
        coEvery { hostDao.upsert(any()) } returns Unit

        repository.save(same)

        coVerify(exactly = 1) { hostDao.upsert(match { it.port == 2222 }) }
    }

    @Test
    fun `save of a tombstoned host with the same content still revives it`() = runTest {
        val same = remoteHost()
        coEvery { hostDao.getByIdIncludingDeleted("host-1") } returns HostEntity.fromDomain(same).copy(
            vectorClock = VectorClockCodec.encode(remoteClock),
            deleted = true,
            deletedAt = 900L,
        )
        val written = slot<HostEntity>()
        coEvery { hostDao.upsert(capture(written)) } returns Unit

        repository.save(same)

        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertTrue(VectorClockCodec.decode(written.captured.vectorClock).dominates(remoteClock))
    }

    @Test
    fun `update reads and writes inside one transaction`() = runTest {
        val insideTransaction = mutableListOf<Boolean>()
        coEvery { hostDao.getById("host-1") } coAnswers {
            insideTransaction += transactionRunner.active
            entity(isFavorite = false)
        }
        coEvery { hostDao.update(any()) } coAnswers { insideTransaction += transactionRunner.active }

        repository.update(remoteHost())

        // A sync tombstone landing between the read and the write would otherwise be overwritten.
        assertEquals(1, transactionRunner.runs)
        assertEquals(listOf(true, true), insideTransaction)
    }

    @Test
    fun `update of a deleted host still runs its check inside the transaction`() = runTest {
        coEvery { hostDao.getById("host-1") } coAnswers {
            assertTrue(transactionRunner.active)
            null
        }

        repository.update(remoteHost())

        assertEquals(1, transactionRunner.runs)
        coVerify(exactly = 0) { hostDao.update(any()) }
    }
}
