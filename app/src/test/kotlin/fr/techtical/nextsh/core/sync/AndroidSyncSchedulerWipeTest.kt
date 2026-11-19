// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import android.content.Context
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sync during the "Wipe the vault" action: the closing waits for the passes in flight (the ones asked
 * by `forceSync` and `syncInBackground` too, which `stop()` does not cancel), and a closed app syncs
 * nothing and schedules nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSyncSchedulerWipeTest {

    private val gate = VaultWipeGate()
    private val syncProtocol = mockk<SyncProtocol>()
    private val enrolledDevices = mockk<EnrolledDeviceRepository>()
    private val networkFlow = MutableStateFlow(true)
    private val network = mockk<NetworkMonitor> {
        every { isConnected } returns networkFlow
        every { isOnLanTransport } returns networkFlow
    }
    private val vault = mockk<VaultManager> { every { isOpen() } returns true }
    private val context = mockk<Context>(relaxed = true)

    private val conflicts = object : PendingConflictRepository {
        val count = MutableStateFlow(0)
        override fun observeCount(): Flow<Int> = count
        override suspend fun getAll(): List<PendingConflict> = emptyList()
        override fun observeAll(): Flow<List<PendingConflict>> = MutableStateFlow(emptyList())
        override suspend fun save(conflict: PendingConflict) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    private val device = EnrolledDevice(
        deviceId = "desktop-001",
        deviceName = "My Desktop",
        platform = Platform.DESKTOP,
        publicKeyFingerprint = "fp",
        tlsCertFingerprint = "certfp",
        lastSyncAt = null,
        enrolledAt = 0L,
        lastKnownHost = "192.168.1.10",
    )

    private fun scheduler() = AndroidSyncScheduler(
        context = context,
        syncProtocol = syncProtocol,
        enrolledDeviceRepository = enrolledDevices,
        networkMonitor = network,
        pendingConflictRepository = conflicts,
        vaultManager = vault,
        dispatcher = UnconfinedTestDispatcher(),
        wipeGate = gate,
    )

    // ── A closed app syncs nothing ───────────────────────────────────────────

    @Test
    fun `a pass asked while the app is closed does nothing, whatever the state of the wipe`() = runTest {
        val scheduler = scheduler()
        for (state in listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)) {
            gate.set(state)

            scheduler.runSync(background = false)
            scheduler.runSync(background = true)
        }

        coVerify(exactly = 0) { enrolledDevices.getAll() }
        coVerify(exactly = 0) { syncProtocol.fullSync(any()) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status, "untouched: nothing went wrong")
    }

    @Test
    fun `a pass asked once the app is open again syncs`() = runTest {
        coEvery { enrolledDevices.getAll() } returns emptyList()
        val scheduler = scheduler()
        gate.set(VaultWipeState.DONE)
        gate.acknowledgeDone()

        scheduler.runSync(background = false)

        coVerify(exactly = 1) { enrolledDevices.getAll() }
    }

    @Test
    fun `the scheduler does not start while the app is closed`() = runTest {
        val scheduler = scheduler()
        gate.set(VaultWipeState.RUNNING)

        scheduler.start(60_000L)

        // Neither the loop nor the watcher of the network: nothing is left behind that a wipe would not see.
        verify(exactly = 0) { network.isOnLanTransport }
    }

    // ── The closing waits for the passes in flight ──────────────────────────

    @Test
    fun `stopAndAwait cancels a pass in flight that forceSync asked for, and returns when the sync has left SYNCING`() = runTest {
        coEvery { enrolledDevices.getAll() } returns listOf(device)
        val started = CompletableDeferred<Unit>()
        coEvery { syncProtocol.fullSync(any()) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val scheduler = scheduler()
        val forced = launch(UnconfinedTestDispatcher(testScheduler)) { runCatching { scheduler.forceSync() } }
        started.await()
        assertEquals(SyncStatus.SYNCING, scheduler.syncState.value.status)

        val ended = scheduler.stopAndAwait(5_000L)

        assertTrue(ended)
        assertFalse(scheduler.syncState.value.status == SyncStatus.SYNCING)
        forced.cancel()
    }

    @Test
    fun `stopAndAwait cancels a pass that syncInBackground asked for`() = runTest {
        coEvery { enrolledDevices.getAll() } returns listOf(device)
        val started = CompletableDeferred<Unit>()
        coEvery { syncProtocol.fullSync(any()) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val scheduler = scheduler()
        val background = launch(UnconfinedTestDispatcher(testScheduler)) { runCatching { scheduler.syncInBackground() } }
        started.await()

        val ended = scheduler.stopAndAwait(5_000L)

        assertTrue(ended)
        assertFalse(scheduler.syncState.value.status == SyncStatus.SYNCING)
        background.cancel()
    }

    @Test
    fun `stopAndAwait gives up after its bound when a pass does not end, and says so`() = runTest {
        coEvery { enrolledDevices.getAll() } returns listOf(device)
        val started = CompletableDeferred<Unit>()
        // A blocking request that a cancellation does not interrupt: it ends only when the test says so.
        val release = CompletableDeferred<Unit>()
        coEvery { syncProtocol.fullSync(any()) } coAnswers {
            started.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            fr.techtical.nextsh.shared.core.sync.SyncResult.DeviceUnreachable
        }
        val scheduler = scheduler()
        val forced = launch(UnconfinedTestDispatcher(testScheduler)) { runCatching { scheduler.forceSync() } }
        started.await()

        val ended = scheduler.stopAndAwait(1_000L)

        assertFalse(ended, "the bound held: what the wait cannot end is refused later by the gate")
        release.complete(Unit)
        forced.cancel()
    }

    @Test
    fun `stopAndAwait with nothing in flight returns true at once`() = runTest {
        assertTrue(scheduler().stopAndAwait(1_000L))
    }

    // ── After the tables are erased ─────────────────────────────────────────

    @Test
    fun `after the wipe the sync state is a new one and the conflict count follows the database again`() = runTest {
        val scheduler = scheduler()
        conflicts.count.value = 3
        assertEquals(3, scheduler.syncState.value.pendingConflicts)
        scheduler.stopAndAwait(1_000L) // cancels the observer of the count with the rest
        conflicts.count.value = 0 // the tables are erased
        assertEquals(3, scheduler.syncState.value.pendingConflicts, "frozen on the erased vault")

        scheduler.resetAfterWipe()

        assertEquals(0, scheduler.syncState.value.pendingConflicts)
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
        assertEquals(null, scheduler.syncState.value.lastSyncAt)
        assertEquals(null, scheduler.syncState.value.lastError)
        // The observer runs again: a conflict of the new vault is counted.
        conflicts.count.value = 2
        assertEquals(2, scheduler.syncState.value.pendingConflicts)
    }
}
