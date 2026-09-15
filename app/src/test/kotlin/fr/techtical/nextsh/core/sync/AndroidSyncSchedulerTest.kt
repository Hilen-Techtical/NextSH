// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncError
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncResult
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AndroidSyncScheduler].
 *
 * WorkManager is not exercised here (it requires an instrumented test context).
 * We focus on the state machine transitions driven by [forceSync] and the network
 * reconnect watcher, which are pure coroutine logic.
 *
 * The scheduler is constructed with a real [CoroutineScope] backed by
 * [UnconfinedTestDispatcher] so that coroutines run eagerly inside [runTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSyncSchedulerTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private lateinit var syncProtocol: SyncProtocol
    private lateinit var enrolledDeviceRepository: EnrolledDeviceRepository
    private lateinit var networkConnectedFlow: MutableStateFlow<Boolean>
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var scheduler: AndroidSyncScheduler

    /** Fake PendingConflictRepository that returns an always-zero count Flow. */
    private val fakePendingConflictRepository = object : PendingConflictRepository {
        private val countFlow = MutableStateFlow(0)
        override fun observeCount(): Flow<Int> = countFlow
        override suspend fun getAll(): List<PendingConflict> = emptyList()
        override fun observeAll(): Flow<List<PendingConflict>> = MutableStateFlow(emptyList())
        override suspend fun save(conflict: PendingConflict) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) = Unit
        override suspend fun deleteAll() = Unit
    }

    private val desktopDevice = EnrolledDevice(
        deviceId = "desktop-001",
        deviceName = "My Desktop",
        platform = Platform.DESKTOP,
        publicKeyFingerprint = "fp",
        tlsCertFingerprint = "certfp",
        lastSyncAt = null,
        enrolledAt = 0L,
        lastKnownHost = "192.168.1.10",
    )

    @BeforeEach
    fun setUp() {
        syncProtocol = mockk()
        enrolledDeviceRepository = mockk()
        networkConnectedFlow = MutableStateFlow(true)

        networkMonitor = mockk {
            every { isConnected } returns networkConnectedFlow
            every { isOnLanTransport } returns networkConnectedFlow
        }

        // Scheduler with a fake context: WorkManager calls are guarded by try/catch
        // in stop(), so they won't throw even without a real Application context.
        scheduler = AndroidSyncScheduler(
            context = mockk(relaxed = true),
            syncProtocol = syncProtocol,
            enrolledDeviceRepository = enrolledDeviceRepository,
            networkMonitor = networkMonitor,
            pendingConflictRepository = fakePendingConflictRepository,
        )
    }

    // ── Test 1: forceSync success ─────────────────────────────────────────

    @Test
    fun `forceSync success updates lastSyncAt and status IDLE`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Success(
            pushedCount = 3,
            pulledCount = 2,
            conflicts = emptyList(),
            syncedAt = System.currentTimeMillis(),
        )

        scheduler.forceSync()

        val state = scheduler.syncState.value
        assertEquals(SyncStatus.IDLE, state.status)
        assertNotNull(state.lastSyncAt)
        assertEquals(0, state.pendingConflicts)
        assertNull(state.lastError)
    }

    // ── Test 2: forceSync with error sets ERROR status ────────────────────

    @Test
    fun `forceSync on error sets status ERROR with reason`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Error(
            reason = SyncError.NETWORK_TIMEOUT,
            message = "connection timed out",
        )

        scheduler.forceSync()

        val state = scheduler.syncState.value
        assertEquals(SyncStatus.ERROR, state.status)
        assertNotNull(state.lastSyncAt)
        assertNotNull(state.lastError)
        assertTrue(state.lastError!!.contains("My Desktop"))
    }

    // ── Test 3: network reconnect triggers sync ───────────────────────────
    //
    // The scheduler's network watcher runs on Dispatchers.Default (internal scope),
    // which is not controlled by the test dispatcher. We therefore test the state
    // machine logic directly by:
    //   1. Driving the scheduler to OFFLINE via forceSync while offline (unreachable)
    //   2. Verifying OFFLINE is set
    //   3. Calling forceSync when online to confirm the transition back to IDLE
    //
    // This avoids reliance on the internal coroutine timing while still asserting
    // the meaningful state transitions.

    @Test
    fun `offline transport short-circuits to OFFLINE then reconnect syncs`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)

        // Offline: runSync should short-circuit to OFFLINE without touching lastSyncAt.
        networkConnectedFlow.value = false
        scheduler.forceSync()

        val offlineState = scheduler.syncState.value
        assertEquals(SyncStatus.OFFLINE, offlineState.status)
        assertNull(offlineState.lastSyncAt)

        // Back on a LAN-capable transport: sync proceeds and status becomes IDLE.
        networkConnectedFlow.value = true
        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Success(
            pushedCount = 1,
            pulledCount = 1,
            conflicts = emptyList(),
            syncedAt = System.currentTimeMillis(),
        )
        scheduler.forceSync()

        val reconnectedState = scheduler.syncState.value
        assertEquals(SyncStatus.IDLE, reconnectedState.status)
        assertNotNull(reconnectedState.lastSyncAt)
    }

    // ── Test 4: device eligibility filter (C4: discovery replaces the lastKnownHost gate) ──

    @Test
    fun `runSync includes a Desktop device with no lastKnownHost as long as it has a tlsCertFingerprint`() = runTest {
        // lastKnownHost is no longer a gate: LanSyncClient.fullSync now resolves a missing
        // host itself (hostname probe, then LAN discovery), see LanDiscoveryClient.
        val deviceNoHost = desktopDevice.copy(lastKnownHost = null)
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(deviceNoHost)
        coEvery { syncProtocol.fullSync(deviceNoHost) } returns SyncResult.Success(
            pushedCount = 0,
            pulledCount = 0,
            conflicts = emptyList(),
            syncedAt = System.currentTimeMillis(),
        )

        scheduler.forceSync()

        coVerify(exactly = 1) { syncProtocol.fullSync(deviceNoHost) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
    }

    @Test
    fun `runSync excludes a Desktop device without a tlsCertFingerprint`() = runTest {
        // Missing tlsCertFingerprint means no TLS-pinned connection can be attempted at
        // all (not even a discovery-candidate validation probe): re-enrollment is
        // required, so this device is still skipped, unlike a merely-missing host.
        val deviceNoFingerprint = desktopDevice.copy(tlsCertFingerprint = null)
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(deviceNoFingerprint)

        scheduler.forceSync()

        coVerify(exactly = 0) { syncProtocol.fullSync(any()) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
    }

    @Test
    fun `runSync excludes a Desktop device whose tlsCertFingerprint is blank`() = runTest {
        // The filter is isNullOrBlank(), not just null: a blank fingerprint pins nothing, so it
        // would hand PinnedTrustManager an empty expectation instead of skipping the device.
        val deviceBlankFingerprint = desktopDevice.copy(tlsCertFingerprint = "")
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(deviceBlankFingerprint)

        scheduler.forceSync()

        coVerify(exactly = 0) { syncProtocol.fullSync(any()) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
    }
}
