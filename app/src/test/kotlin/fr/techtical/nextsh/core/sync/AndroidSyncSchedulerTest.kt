// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import android.content.Context
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncError
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncResult
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.testutil.StringResourceFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale

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
    private lateinit var context: Context
    private lateinit var vaultManager: VaultManager

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

    /**
     * A relaxed `Context` whose `getString` reads the real `values/strings.xml` (French, the
     * default language) and formats it the way Android does. The varargs reach the answer as an
     * array or already spread: both are flattened.
     */
    private fun frenchContext(): Context {
        val strings = StringResourceFile("values/strings.xml").strings
        fun nameOf(id: Int): String =
            R.string::class.java.fields.first { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }.name
        return mockk<Context>(relaxed = true) {
            every { getString(any<Int>(), *anyVararg()) } answers {
                val template = StringResourceFile.unescape(strings.getValue(nameOf(firstArg())))
                val formatArgs = args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
                String.format(Locale.ROOT, template, *formatArgs.toTypedArray())
            }
            every { getString(any<Int>()) } answers {
                StringResourceFile.unescape(strings.getValue(nameOf(firstArg())))
            }
        }
    }

    @BeforeEach
    fun setUp() {
        syncProtocol = mockk()
        enrolledDeviceRepository = mockk()
        networkConnectedFlow = MutableStateFlow(true)

        networkMonitor = mockk {
            every { isConnected } returns networkConnectedFlow
            every { isOnLanTransport } returns networkConnectedFlow
        }

        context = frenchContext()
        vaultManager = mockk { every { isOpen() } returns true }

        // Scheduler with a fake context: WorkManager calls are guarded by try/catch
        // in stop(), so they won't throw even without a real Application context. It says
        // the real French texts of the app, so that the assertions read what the user reads.
        scheduler = AndroidSyncScheduler(
            context = context,
            syncProtocol = syncProtocol,
            enrolledDeviceRepository = enrolledDeviceRepository,
            networkMonitor = networkMonitor,
            pendingConflictRepository = fakePendingConflictRepository,
            vaultManager = vaultManager,
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    /** The French text of [name] from the real resources, formatted with [args]. */
    private fun frenchText(name: String, vararg args: Any): String =
        String.format(
            Locale.ROOT,
            StringResourceFile.unescape(StringResourceFile("values/strings.xml").strings.getValue(name)),
            *args,
        )

    // ── Crash fix: failures of fullSync never escape ──────────────────────

    @Test
    fun `fullSync throwing the vault exception sets ERROR with the vault text and stops the loop`() = runTest {
        val second = desktopDevice.copy(deviceId = "desktop-002", deviceName = "Second")
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice, second)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws VaultAuthExpiredException()

        scheduler.forceSync()

        val state = scheduler.syncState.value
        assertEquals(SyncStatus.ERROR, state.status)
        assertEquals(frenchText("sync_error_vault_locked"), state.lastError)
        coVerify(exactly = 0) { syncProtocol.fullSync(second) }
    }

    @Test
    fun `fullSync throwing a RuntimeException sets ERROR and releases SYNCING`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws IllegalStateException("secret detail")

        scheduler.forceSync()

        val state = scheduler.syncState.value
        assertEquals(SyncStatus.ERROR, state.status)
        assertEquals(frenchText("sync_error_unknown", "My Desktop"), state.lastError)
        assertFalse(state.lastError!!.contains("secret detail"))
    }

    @Test
    fun `a failing device does not prevent the next device from syncing`() = runTest {
        val second = desktopDevice.copy(deviceId = "desktop-002", deviceName = "Second")
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice, second)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws IllegalStateException()
        coEvery { syncProtocol.fullSync(second) } returns SyncResult.Success(0, 0, emptyList(), 1L)

        scheduler.forceSync()

        coVerify(exactly = 1) { syncProtocol.fullSync(second) }
    }

    @Test
    fun `cancellation of fullSync is propagated and SYNCING is released`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws CancellationException("cancelled")

        assertThrows<CancellationException> { scheduler.forceSync() }

        assertTrue(scheduler.syncState.value.status != SyncStatus.SYNCING)
    }

    // ── Crash fix: background triggers never open the vault ───────────────

    @Test
    fun `background sync with a closed vault returns at once without touching the status`() = runTest {
        every { vaultManager.isOpen() } returns false
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)

        scheduler.syncInBackground()

        coVerify(exactly = 0) { syncProtocol.fullSync(any()) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
        assertNull(scheduler.syncState.value.lastError)
    }

    @Test
    fun `background sync with an open vault runs the cycle`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Success(0, 0, emptyList(), 1L)

        scheduler.syncInBackground()

        coVerify(exactly = 1) { syncProtocol.fullSync(desktopDevice) }
    }

    @Test
    fun `forceSync from the UI does not depend on isOpen`() = runTest {
        every { vaultManager.isOpen() } returns false
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Success(0, 0, emptyList(), 1L)

        scheduler.forceSync()

        coVerify(exactly = 1) { syncProtocol.fullSync(desktopDevice) }
    }

    @Test
    fun `network watcher never syncs while the vault is closed`() = runTest {
        every { vaultManager.isOpen() } returns false
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)

        scheduler.start(1_000L)
        networkConnectedFlow.value = false
        networkConnectedFlow.value = true
        scheduler.stop()

        coVerify(exactly = 0) { syncProtocol.fullSync(any()) }
    }

    @Test
    fun `network watcher survives a failed sync and syncs again on the next transition`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws IllegalStateException() andThen
            SyncResult.Success(0, 0, emptyList(), 1L)

        scheduler.start(1_000L)
        networkConnectedFlow.value = false
        networkConnectedFlow.value = true
        networkConnectedFlow.value = false
        networkConnectedFlow.value = true
        scheduler.stop()

        coVerify(exactly = 2) { syncProtocol.fullSync(desktopDevice) }
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
        // The sentence comes from the reason, in the language of the app.
        assertEquals("My Desktop ne répond pas", state.lastError)
    }

    // ── Last error: a resource per reason, never the technical message ────

    @Test
    fun `the last error never shows the technical message nor the name of the reason`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)

        val sentenceOfStatus = StringResourceFile("values/strings.xml").strings.getValue("sync_status_error")
        for (reason in SyncError.entries) {
            // A developer text of the kind LanSyncClient builds: an address, an exception message.
            val technical = "Host unreachable (192.168.1.10:47731) java.net.ConnectException: refused"
            coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Error(reason, technical)

            scheduler.forceSync()

            val text = scheduler.syncState.value.lastError!!
            assertTrue(text.contains("My Desktop"), "$reason does not name the device: $text")
            assertFalse(text.contains(technical), "$reason shows the technical message: $text")
            assertFalse(text.contains("192.168"), "$reason shows an address: $text")
            assertFalse(text.contains(reason.name), "$reason shows the name of the enum: $text")
            assertFalse(text.contains("%"), "$reason left a placeholder: $text")
            // Shown after "Erreur : ", the sentence adds no second colon (it used to read
            // "Erreur : PC : inaccessible").
            val shown = StringResourceFile.unescape(sentenceOfStatus).replace("%1\$s", text)
            assertEquals(1, shown.count { it == ':' }, "$reason: $shown")
        }
    }

    @Test
    fun `an error without a message still gets its sentence`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Error(SyncError.SERVER_ERROR)

        scheduler.forceSync()

        assertEquals("My Desktop a rencontré une erreur. Réessayez.", scheduler.syncState.value.lastError)
    }

    @Test
    fun `an unreachable device and a failed authentication are said in French with the device name`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)

        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.DeviceUnreachable
        scheduler.forceSync()
        assertEquals(
            "My Desktop injoignable. Vérifiez que le Desktop est ouvert et sur le même réseau.",
            scheduler.syncState.value.lastError,
        )

        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.AuthFailed
        scheduler.forceSync()
        assertEquals(
            "My Desktop a refusé la connexion. Vérifiez l'heure des deux appareils, sinon ré-enrôlez-le.",
            scheduler.syncState.value.lastError,
        )
        assertEquals(SyncStatus.ERROR, scheduler.syncState.value.status)
    }

    @Test
    fun `a lost address says the same sentence as a device that cannot be reached`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)

        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.DeviceUnreachable
        scheduler.forceSync()
        val unreachable = scheduler.syncState.value.lastError

        coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Error(SyncError.HOST_UNREACHABLE, "Host unreachable")
        scheduler.forceSync()

        assertNotNull(unreachable)
        assertEquals(unreachable, scheduler.syncState.value.lastError)
    }

    @Test
    fun `the other reasons are sentences that name the device and say what to do when there is something to do`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        val expected = mapOf(
            SyncError.DECRYPT_FAILED to "données illisibles reçues de My Desktop",
            SyncError.TIMESTAMP_DRIFT to "la date ou l'heure diffère entre My Desktop et cet appareil. " +
                "Vérifiez la date et l'heure des deux appareils.",
            SyncError.VERSION_MISMATCH to "la version de NextSH de My Desktop n'est pas compatible avec celle de cet appareil. " +
                "Mettez NextSH à jour sur les deux appareils.",
            SyncError.UNKNOWN to "échec de la synchronisation avec My Desktop",
        )

        expected.forEach { (reason, sentence) ->
            coEvery { syncProtocol.fullSync(desktopDevice) } returns SyncResult.Error(reason)
            scheduler.forceSync()
            assertEquals(sentence, scheduler.syncState.value.lastError, reason.name)
        }
    }

    @Test
    fun `an enrolled device list that cannot be read is said from the resources`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } throws IllegalStateException("database is locked")

        scheduler.forceSync()

        val state = scheduler.syncState.value
        assertEquals(SyncStatus.ERROR, state.status)
        assertEquals("impossible de lire les appareils enrôlés. Redémarrez NextSH et réessayez.", state.lastError)
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

    // ── Cancellation: status never stays stuck, forced sync outlives its caller ──

    private fun successResult() = SyncResult.Success(
        pushedCount = 1,
        pulledCount = 1,
        conflicts = emptyList(),
        syncedAt = System.currentTimeMillis(),
    )

    /** Waits (real time: the scheduler runs on Dispatchers.Default) until [predicate] holds. */
    private suspend fun awaitState(predicate: (SyncState) -> Boolean): SyncState =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { scheduler.syncState.first(predicate) }
        }

    private suspend fun awaitStarted(started: CompletableDeferred<Unit>) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }

    /**
     * stop() cancels the loop job running a sync, a stopped worker cancels its own: the
     * CancellationException used to leave SYNCING behind, so every later sync (periodic,
     * WorkManager, Settings sync now) was skipped as already syncing.
     */
    @Test
    fun `cancelling a running sync resets the status instead of leaving SYNCING`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        val started = CompletableDeferred<Unit>()
        coEvery { syncProtocol.fullSync(desktopDevice) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }

        val running = launch(Dispatchers.Default) { scheduler.runSync(background = false) }
        awaitStarted(started)
        assertEquals(SyncStatus.SYNCING, scheduler.syncState.value.status)

        running.cancelAndJoin()

        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
        assertNull(scheduler.syncState.value.lastError)

        // The next sync is not skipped as "already syncing".
        coEvery { syncProtocol.fullSync(desktopDevice) } returns successResult()
        scheduler.forceSync()

        coVerify(exactly = 2) { syncProtocol.fullSync(desktopDevice) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
        assertNotNull(scheduler.syncState.value.lastSyncAt)
    }

    @Test
    fun `an unexpected failure during a sync does not leave SYNCING either`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws IllegalStateException("boom")

        // The failure no longer escapes (it killed the process from a background trigger): it is
        // reported as the error of the device, and SYNCING is released.
        scheduler.forceSync()

        assertEquals(SyncStatus.ERROR, scheduler.syncState.value.status)
        assertEquals(frenchText("sync_error_unknown", "My Desktop"), scheduler.syncState.value.lastError)
    }

    @Test
    fun `the shared vault exception sets the vault text too and stops the loop`() = runTest {
        val second = desktopDevice.copy(deviceId = "desktop-002", deviceName = "Second")
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice, second)
        coEvery { syncProtocol.fullSync(desktopDevice) } throws
            fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException()

        scheduler.forceSync()

        assertEquals(SyncStatus.ERROR, scheduler.syncState.value.status)
        assertEquals(frenchText("sync_error_vault_locked"), scheduler.syncState.value.lastError)
        coVerify(exactly = 0) { syncProtocol.fullSync(second) }
    }

    /**
     * The conflict screen requests a forced sync right before it is popped, which clears its
     * ViewModel and cancels viewModelScope. The sync must still reach its end (pull then
     * push of the new clock).
     */
    @Test
    fun `a forced sync still completes when its caller is cancelled`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { syncProtocol.fullSync(desktopDevice) } coAnswers {
            started.complete(Unit)
            release.await()
            successResult()
        }

        val caller = launch { scheduler.forceSync() }
        awaitStarted(started)
        caller.cancelAndJoin()

        // The caller is gone, the sync is still running.
        assertEquals(SyncStatus.SYNCING, scheduler.syncState.value.status)

        release.complete(Unit)
        val done = awaitState { it.status == SyncStatus.IDLE && it.lastSyncAt != null }

        assertNull(done.lastError)
        coVerify(exactly = 1) { syncProtocol.fullSync(desktopDevice) }
    }

    @Test
    fun `a sync requested while another one runs is skipped`() = runTest {
        coEvery { enrolledDeviceRepository.getAll() } returns listOf(desktopDevice)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { syncProtocol.fullSync(desktopDevice) } coAnswers {
            started.complete(Unit)
            release.await()
            successResult()
        }

        val first = launch { scheduler.forceSync() }
        awaitStarted(started)

        // Skipped right away, without waiting for the first one.
        scheduler.forceSync()
        assertEquals(SyncStatus.SYNCING, scheduler.syncState.value.status)

        release.complete(Unit)
        first.join()

        coVerify(exactly = 1) { syncProtocol.fullSync(desktopDevice) }
        assertEquals(SyncStatus.IDLE, scheduler.syncState.value.status)
    }
}
