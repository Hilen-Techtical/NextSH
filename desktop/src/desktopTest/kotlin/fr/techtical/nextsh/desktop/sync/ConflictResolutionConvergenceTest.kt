// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopCustomTerminalThemeRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopPendingConflictRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSnippetRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.data.db.repository.enableForeignKeys
import fr.techtical.nextsh.desktop.data.db.repository.insertHostRow
import fr.techtical.nextsh.desktop.data.db.repository.insertTunnelRow
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two-round convergence of "keep local" through the real [DesktopSyncRepository] and the real
 * SQLDelight repositories of two in-memory databases (this device and its peer).
 *
 * Before the fix, "keep local" only deleted the pending marker: the two vector clocks stayed
 * concurrent and the next sync recreated the conflict on both devices. The peer is a second
 * Desktop database here, which runs the same shared CRDT merge as the Android peer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolutionConvergenceTest {

    private class Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: NextShDatabase
        val hostRepo: DesktopHostRepository
        val tunnelRepo: DesktopTunnelRepository
        val sshKeyRepo: DesktopSshKeyRepository
        val snippetRepo: DesktopSnippetRepository
        val themeRepo: DesktopCustomTerminalThemeRepository
        val pendingRepo: DesktopPendingConflictRepository
        val sync: DesktopSyncRepository

        init {
            driver.enableForeignKeys()
            NextShDatabase.Schema.create(driver)
            db = NextShDatabase(driver)
            hostRepo = DesktopHostRepository(db)
            tunnelRepo = DesktopTunnelRepository(db)
            sshKeyRepo = DesktopSshKeyRepository(db)
            snippetRepo = DesktopSnippetRepository(db)
            themeRepo = DesktopCustomTerminalThemeRepository(db)
            pendingRepo = DesktopPendingConflictRepository(db)
            sync = DesktopSyncRepository(hostRepo, tunnelRepo, sshKeyRepo, snippetRepo, themeRepo, pendingRepo)
        }
    }

    private class NoopSyncScheduler : SyncScheduler {
        override val syncState: StateFlow<SyncState> =
            MutableStateFlow(SyncState(SyncStatus.IDLE, null)).asStateFlow()
        override fun start(intervalMs: Long) = Unit
        override fun stop() = Unit
        override suspend fun forceSync() = Unit
    }

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = Unit
    }

    private val thisDevice = Device()
    private val peer = Device()

    @AfterTest
    fun tearDown() {
        thisDevice.driver.close()
        peer.driver.close()
    }

    private fun TestScope.viewModelOfThisDevice() = ConflictResolutionViewModel(
        pendingConflictRepository = thisDevice.pendingRepo,
        hostRepository = thisDevice.hostRepo,
        tunnelRepository = thisDevice.tunnelRepo,
        sshKeyRepository = thisDevice.sshKeyRepo,
        snippetRepository = thisDevice.snippetRepo,
        customThemeRepository = thisDevice.themeRepo,
        syncScheduler = NoopSyncScheduler(),
        appScope = TestAppScope(backgroundScope),
        credentialSyncRepository = mockk(relaxed = true),
        enrolledDeviceRepository = mockk(relaxed = true),
        secretStore = mockk(relaxed = true),
        deviceIdProvider = { THIS_DEVICE },
        clock = { NOW },
    )

    @Test
    fun `keeping the local edit stops the conflict on both devices`() = runTest {
        // Both devices edited host h1 from the same ancestor: concurrent clocks, different labels.
        thisDevice.db.insertHostRow("h1", label = "Edited here", clock = clockOf(THIS_DEVICE to 6L))
        peer.db.insertHostRow("h1", label = "Edited on the peer", clock = clockOf(PEER_DEVICE to 7L))
        // A tunnel of h1 both devices agree on (foreign keys are on): it must come out untouched.
        thisDevice.db.insertTunnelRow("t1", hostId = "h1")
        peer.db.insertTunnelRow("t1", hostId = "h1")
        val tunnelBefore = thisDevice.tunnelRepo.getAllSyncEntries().single()

        // The peer pushes: the conflict is detected here.
        val detected = thisDevice.sync.applyRemoteBundle(peer.sync.getAllLocalAsBundle())
        assertEquals(1, detected.conflicts.size)
        val conflict = thisDevice.pendingRepo.getAll().single()

        val viewModel = viewModelOfThisDevice()
        viewModel.keepLocal(conflict)
        assertEquals(ApplyOutcome(1, 0), viewModel.applyAll())
        assertTrue(thisDevice.pendingRepo.getAll().isEmpty())
        assertEquals(
            clockOf(THIS_DEVICE to NOW, PEER_DEVICE to 7L),
            thisDevice.hostRepo.getAllSyncEntries().single().clock,
            "the clock merges both versions and ticks this device",
        )
        assertEquals(tunnelBefore, thisDevice.tunnelRepo.getAllSyncEntries().single(), "keeping the host must not touch its tunnel")

        // The peer pushes the same version again before it received ours: no new conflict here.
        val secondPush = thisDevice.sync.applyRemoteBundle(peer.sync.getAllLocalAsBundle())
        assertTrue(secondPush.conflicts.isEmpty(), "the same remote version must not conflict again")
        assertTrue(thisDevice.pendingRepo.getAll().isEmpty())
        assertEquals("Edited here", thisDevice.hostRepo.getById("h1")?.label)

        // The peer pulls our answer: a clean win there, no conflict, and it now holds our version.
        val peerPull = peer.sync.applyRemoteBundle(thisDevice.sync.getAllLocalAsBundle())
        assertTrue(peerPull.conflicts.isEmpty(), "the peer must apply the kept version without conflict")
        assertTrue(peer.pendingRepo.getAll().isEmpty())
        assertEquals("Edited here", peer.hostRepo.getById("h1")?.label)

        // Third round: both sides agree.
        val thirdPush = thisDevice.sync.applyRemoteBundle(peer.sync.getAllLocalAsBundle())
        assertTrue(thirdPush.conflicts.isEmpty())
        assertEquals(
            thisDevice.hostRepo.getAllSyncEntries().single().clock,
            peer.hostRepo.getAllSyncEntries().single().clock,
        )
        // The tunnel survived the whole round trip on both devices, with its sync metadata intact.
        assertEquals(tunnelBefore, thisDevice.tunnelRepo.getAllSyncEntries().single())
        assertEquals(tunnelBefore, peer.tunnelRepo.getAllSyncEntries().single())
        assertEquals(false, thisDevice.tunnelRepo.getAllSyncEntries().single().deleted)
    }

    @Test
    fun `keeping a local deletion keeps the tombstone and stops the conflict on both devices`() = runTest {
        // Deterministic seed: the local deletion carries a known clock (no wall clock, no real device id).
        thisDevice.db.insertHostRow("h1", label = "Deleted here", clock = clockOf(THIS_DEVICE to 6L))
        thisDevice.db.hostQueries.markDeleted(
            deletedAt = 1_500L,
            updatedAt = 1_500L,
            vectorClock = VectorClockCodec.encode(clockOf(THIS_DEVICE to 7L)),
            id = "h1",
        )
        peer.db.insertHostRow("h1", label = "Edited on the peer", clock = clockOf(PEER_DEVICE to 7L))

        val detected = thisDevice.sync.applyRemoteBundle(peer.sync.getAllLocalAsBundle())
        assertEquals(1, detected.conflicts.size)
        val conflict = thisDevice.pendingRepo.getAll().single()

        val viewModel = viewModelOfThisDevice()
        viewModel.keepLocal(conflict)
        assertEquals(ApplyOutcome(1, 0), viewModel.applyAll())

        val tombstone = thisDevice.hostRepo.getAllSyncEntries().single()
        assertTrue(tombstone.deleted, "the local deletion must stay a tombstone")
        assertEquals(NOW, tombstone.updatedAt)
        assertEquals(clockOf(THIS_DEVICE to NOW, PEER_DEVICE to 7L), tombstone.clock)

        val secondPush = thisDevice.sync.applyRemoteBundle(peer.sync.getAllLocalAsBundle())
        assertTrue(secondPush.conflicts.isEmpty())
        assertTrue(thisDevice.hostRepo.getAllSyncEntries().single().deleted)

        val peerPull = peer.sync.applyRemoteBundle(thisDevice.sync.getAllLocalAsBundle())
        assertTrue(peerPull.conflicts.isEmpty())
        assertTrue(peer.hostRepo.getAllSyncEntries().single().deleted, "the peer must receive the deletion")
        assertEquals(null, peer.hostRepo.getById("h1"))
    }

    private fun clockOf(vararg entries: Pair<String, Long>) =
        VectorClock(mapOf(ANCESTOR_DEVICE to 5L) + entries.toMap())

    private companion object {
        const val ANCESTOR_DEVICE = "ancestor-device"
        const val THIS_DEVICE = "this-desktop"
        const val PEER_DEVICE = "peer-android"
        const val NOW = 9_000_000L
    }
}
