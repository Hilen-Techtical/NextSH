// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncResult
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidSyncScheduler"
private const val WORK_NAME = "nextsh_sync_periodic"
private const val MIN_WORKMANAGER_INTERVAL_MS = 15L * 60 * 1000   // WorkManager minimum

@Singleton
class AndroidSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncProtocol: SyncProtocol,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val networkMonitor: NetworkMonitor,
    private val pendingConflictRepository: PendingConflictRepository,
) : SyncScheduler {

    /**
     * Internal scope isolated with [SupervisorJob] so that a failing coroutine job
     * (network reconnect watcher, short-interval loop) does not cancel siblings.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Running coroutine job for short-interval (<15 min) loop. Null when using WorkManager. */
    private var coroutineLoopJob: Job? = null

    /** Running coroutine job for network reconnect watcher. Always active after [start]. */
    private var networkWatcherJob: Job? = null

    /** Coroutine that keeps [SyncState.pendingConflicts] in sync with the DB count Flow. */
    private var conflictCountJob: Job? = null

    private val _syncState = MutableStateFlow(SyncState(SyncStatus.IDLE, null))
    override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        // Observe the persistent conflict count as soon as the scheduler is created
        // so the badge is accurate even before start() is called (e.g. after app restart).
        conflictCountJob = scope.launch {
            pendingConflictRepository.observeCount().collect { count ->
                _syncState.update { it.copy(pendingConflicts = count) }
            }
        }
    }

    override fun start(intervalMs: Long) {
        stop()

        if (intervalMs >= MIN_WORKMANAGER_INTERVAL_MS) {
            scheduleWorkManager(intervalMs)
        } else {
            scheduleCoroutineLoop(intervalMs)
        }

        startNetworkWatcher()

        // Re-launch conflict count observer (was cancelled by stop())
        conflictCountJob = scope.launch {
            pendingConflictRepository.observeCount().collect { count ->
                _syncState.update { it.copy(pendingConflicts = count) }
            }
        }

        Timber.d(TAG, "Scheduler started (intervalMs=$intervalMs)")
    }

    override fun stop() {
        cancelActiveJobs()
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (_: IllegalStateException) {
            // WorkManager not initialised yet: safe to ignore on first call
        }
        Timber.d(TAG, "Scheduler stopped")
    }

    override suspend fun forceSync() = runSync()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun scheduleWorkManager(intervalMs: Long) {
        // Keep CONNECTED (not UNMETERED) so a VPN over cellular back to the home LAN
        // still triggers a tick. runSync() short-circuits on non-LAN transports.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)

        Timber.d(TAG, "WorkManager periodic work scheduled (intervalMs=$intervalMs)")
    }

    private fun scheduleCoroutineLoop(intervalMs: Long) {
        coroutineLoopJob = scope.launch {
            Timber.d(TAG, "Coroutine loop started (intervalMs=$intervalMs)")
            while (isActive) {
                delay(intervalMs)
                if (networkMonitor.isOnLanTransport.value) {
                    runSync()
                } else {
                    Timber.d(TAG, "Loop tick skipped: not on LAN-capable transport")
                }
            }
        }
    }

    private fun startNetworkWatcher() {
        networkWatcherJob = scope.launch {
            networkMonitor.isOnLanTransport.collect { onLan ->
                if (!onLan) {
                    _syncState.update { it.copy(status = SyncStatus.OFFLINE) }
                    Timber.d(TAG, "LAN transport lost: status set to OFFLINE")
                } else if (_syncState.value.status == SyncStatus.OFFLINE) {
                    _syncState.update { it.copy(status = SyncStatus.IDLE) }
                    Timber.d(TAG, "LAN transport restored: triggering immediate sync")
                    runSync()
                }
            }
        }
    }

    private fun cancelActiveJobs() {
        coroutineLoopJob?.cancel()
        coroutineLoopJob = null
        networkWatcherJob?.cancel()
        networkWatcherJob = null
        conflictCountJob?.cancel()
        conflictCountJob = null
    }

    /**
     * Executes a full push + pull cycle against every enrolled Desktop peer.
     *
     * Errors are aggregated: a single device failure sets [SyncStatus.ERROR] with
     * the last failing device name, but does not interrupt sync with other devices.
     *
     * The scheduler never retries above this level: [LanSyncClient] already handles
     * 3-attempt exponential back-off internally.
     *
     * Conflict counts are no longer accumulated here: the DB Flow maintained by
     * [conflictCountJob] keeps [SyncState.pendingConflicts] current automatically.
     */
    private suspend fun runSync() {
        if (_syncState.value.status == SyncStatus.SYNCING) {
            Timber.d(TAG, "runSync: already syncing, skipping")
            return
        }

        if (!networkMonitor.isOnLanTransport.value) {
            Timber.d(TAG, "runSync: not on LAN transport, staying OFFLINE")
            _syncState.update { it.copy(status = SyncStatus.OFFLINE) }
            return
        }

        _syncState.update { it.copy(status = SyncStatus.SYNCING, lastError = null) }

        val devices = try {
            enrolledDeviceRepository.getAll()
        } catch (e: Exception) {
            Timber.w(TAG, "runSync, failed to load enrolled devices: ${e.message}")
            _syncState.update { it.copy(status = SyncStatus.ERROR, lastError = "Impossible de lire les appareils enrôlés") }
            return
        }

        // A device without a lastKnownHost is no longer skipped here: LanSyncClient.fullSync
        // now resolves a fresh address itself (best-effort hostname probe, then LAN broadcast
        // discovery, see LanDiscoveryClient) when the host is missing or unreachable. Only a
        // missing tlsCertFingerprint still disqualifies a device, since without it no TLS-pinned
        // connection (including the discovery-candidate validation probe) can be attempted at
        // all; that requires re-enrollment (a fresh QR scan), not discovery.
        val desktopDevices = devices
            .filter { it.platform == Platform.DESKTOP }
            .filter { device ->
                val usable = !device.tlsCertFingerprint.isNullOrBlank()
                if (!usable) {
                    Timber.d(
                        TAG,
                        "runSync, skipping device without tlsCertFingerprint (re-enrollment required): ${device.deviceName}",
                    )
                }
                usable
            }
        if (desktopDevices.isEmpty()) {
            Timber.d(TAG, "runSync: no Desktop devices enrolled, nothing to do")
            _syncState.update {
                it.copy(
                    status = SyncStatus.IDLE,
                    lastSyncAt = System.currentTimeMillis(),
                )
            }
            return
        }

        var lastError: String? = null

        for (device in desktopDevices) {
            when (val result = syncProtocol.fullSync(device)) {
                is SyncResult.Success -> {
                    Timber.d(
                        TAG,
                        "runSync: device=${device.deviceName} pushed=${result.pushedCount} pulled=${result.pulledCount} conflicts=${result.conflicts.size}",
                    )
                }
                is SyncResult.Error -> {
                    lastError = "${device.deviceName}: ${result.message ?: result.reason.name}"
                    Timber.w(TAG, "runSync: device=${device.deviceName} error=${result.reason}")
                }
                SyncResult.DeviceUnreachable -> {
                    lastError = "${device.deviceName}: inaccessible"
                    Timber.w(TAG, "runSync: device=${device.deviceName} unreachable")
                }
                SyncResult.AuthFailed -> {
                    lastError = "${device.deviceName}: authentification échouée"
                    Timber.w(TAG, "runSync: device=${device.deviceName} auth failed")
                }
            }
        }

        _syncState.update {
            it.copy(
                status = if (lastError != null) SyncStatus.ERROR else SyncStatus.IDLE,
                lastSyncAt = System.currentTimeMillis(),
                lastError = lastError,
            )
        }
        Timber.d(TAG, "runSync: done, status=${_syncState.value.status}")
    }
}
