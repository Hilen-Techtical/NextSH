// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.FakeTransactionRunner
import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.data.db.entity.TunnelEntity
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [OrphanTunnelRepair] with a mocked [TunnelDao]. The SQL that selects the orphans (live tunnel,
 * deleted host, no pending HOST conflict) is not exercised here: no Room database runs in these
 * tests, that query is validated by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrphanTunnelRepairTest {

    private lateinit var tunnelDao: TunnelDao
    private val transactionRunner = FakeTransactionRunner()
    private lateinit var repair: OrphanTunnelRepair

    @BeforeEach
    fun setUp() {
        tunnelDao = mockk()
        repair = OrphanTunnelRepair(tunnelDao, transactionRunner)
    }

    private fun tunnel(id: String, clock: VectorClock = VectorClock.EMPTY) = TunnelEntity(
        id = id,
        label = "Tunnel $id",
        hostId = "host-deleted",
        type = "LOCAL_FORWARD",
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        vectorClock = VectorClockCodec.encode(clock),
        updatedAt = 1_000L,
    )

    @Test
    fun `every orphan is tombstoned with its own ticked clock, inside one transaction`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val clockA = VectorClock(mapOf("desktop-device" to 3L))
        val clockB = VectorClock(mapOf(deviceId to 2L))
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } coAnswers {
            assertTrue(transactionRunner.active, "the orphans are read inside the transaction")
            listOf(tunnel("tunnel-a", clockA), tunnel("tunnel-b", clockB))
        }
        val writesInsideTransaction = mutableListOf<Boolean>()
        val deletedAt = mutableMapOf<String, Long>()
        val updatedAt = mutableMapOf<String, Long>()
        val clockJson = mutableMapOf<String, String>()
        coEvery { tunnelDao.softDelete(any(), any(), any(), any()) } coAnswers {
            writesInsideTransaction += transactionRunner.active
            val id = firstArg<String>()
            deletedAt[id] = secondArg()
            updatedAt[id] = thirdArg()
            clockJson[id] = arg(3)
        }

        val before = System.currentTimeMillis()
        val repaired = repair.run()
        val after = System.currentTimeMillis()

        assertEquals(2, repaired)
        assertEquals(1, transactionRunner.runs)
        assertEquals(listOf(true, true), writesInsideTransaction)
        assertEquals(setOf("tunnel-a", "tunnel-b"), clockJson.keys)
        for ((id, previous) in mapOf("tunnel-a" to clockA, "tunnel-b" to clockB)) {
            assertTrue(deletedAt.getValue(id) in before..after, "$id deletedAt")
            assertEquals(deletedAt.getValue(id), updatedAt.getValue(id), "$id updatedAt")
            val newClock = VectorClockCodec.decode(clockJson.getValue(id))
            assertTrue(newClock.dominates(previous), "$id clock must dominate its own previous clock")
        }
        // Each row keeps its own history: the clock of tunnel-b has no trace of tunnel-a's.
        val clockOfB = VectorClockCodec.decode(clockJson.getValue("tunnel-b"))
        assertFalse(clockOfB.entries.containsKey("desktop-device"))
    }

    @Test
    fun `a clean database is left untouched`() = runTest {
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } returns emptyList()

        assertEquals(0, repair.run())

        coVerify(exactly = 0) { tunnelDao.softDelete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tunnelDao.upsert(any()) }
        coVerify(exactly = 0) { tunnelDao.update(any()) }
    }

    @Test
    fun `a second run finds nothing left to delete`() = runTest {
        var rows = listOf(tunnel("tunnel-a"))
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } answers { rows }
        coEvery { tunnelDao.softDelete(any(), any(), any(), any()) } answers { rows = emptyList() }

        assertEquals(1, repair.run())
        assertEquals(0, repair.run())

        coVerify(exactly = 1) { tunnelDao.softDelete(any(), any(), any(), any()) }
    }

    @Test
    fun `two overlapping runs are serialized`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        var reads = 0
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } coAnswers {
            reads++
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                // The first run waits here, holding the lock; the second must wait behind it.
                if (reads == 1) gate.await()
                emptyList()
            } finally {
                inFlight--
            }
        }

        val first = async { repair.run() }
        val second = async { repair.run() }
        runCurrent()
        assertEquals(1, reads, "the second run must not read while the first holds the lock")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, first.await())
        assertEquals(0, second.await())
        assertEquals(2, reads)
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `runSafely returns the number of repaired tunnels`() = runTest {
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } returns listOf(tunnel("tunnel-a"), tunnel("tunnel-b"))
        coEvery { tunnelDao.softDelete(any(), any(), any(), any()) } returns Unit

        assertEquals(2, repair.runSafely())
    }

    @Test
    fun `runSafely swallows a failure and reports nothing repaired`() = runTest {
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } throws IllegalStateException("disk full")

        assertEquals(0, repair.runSafely())
    }

    @Test
    fun `runSafely lets a cancellation through`() = runTest {
        coEvery { tunnelDao.getLiveTunnelsOfDeletedHosts() } throws CancellationException("cancelled")

        var rethrown = false
        try {
            repair.runSafely()
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown, "a cancelled repair must not be mistaken for a failure")
    }
}
