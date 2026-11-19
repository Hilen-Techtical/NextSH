// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException as SharedVaultAuthExpiredException
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.core.sync.SyncError
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncResult
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidSyncScheduler"
private const val WORK_NAME = "nextsh_sync_periodic"
private const val MIN_WORKMANAGER_INTERVAL_MS = 15L * 60 * 1000   // WorkManager minimum

/**
 * The sentence for a sync that failed with [reason], one resource per reason with the device
 * name as `%1$s`, except [SyncError.HOST_UNREACHABLE]: a lost address and a device that cannot be
 * reached are one situation for the user, said by the sentence of [SyncResult.DeviceUnreachable].
 * `:shared` carries no text: the reason is all the user ever sees of the failure, never the
 * technical message that comes with it.
 */
@StringRes
internal fun syncErrorLabelRes(reason: SyncError): Int = when (reason) {
    SyncError.DECRYPT_FAILED -> R.string.sync_error_decrypt_failed
    SyncError.HMAC_INVALID -> R.string.sync_error_hmac_invalid
    SyncError.TIMESTAMP_DRIFT -> R.string.sync_error_timestamp_drift
    SyncError.VERSION_MISMATCH -> R.string.sync_error_version_mismatch
    SyncError.NETWORK_TIMEOUT -> R.string.sync_error_network_timeout
    SyncError.HOST_UNREACHABLE -> R.string.sync_error_device_unreachable
    SyncError.SERVER_ERROR -> R.string.sync_error_server_error
    SyncError.UNKNOWN -> R.string.sync_error_unknown
}

@Singleton
class AndroidSyncScheduler internal constructor(
    private val context: Context,
    private val syncProtocol: SyncProtocol,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val networkMonitor: NetworkMonitor,
    private val pendingConflictRepository: PendingConflictRepository,
    private val vaultManager: VaultManager,
    dispatcher: CoroutineDispatcher,
    /** The wipe of the vault: while the app is closed nothing syncs and nothing is scheduled, see [runSync]. */
    private val wipeGate: VaultWipeGate = VaultWipeGate(),
) : SyncScheduler {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        syncProtocol: SyncProtocol,
        enrolledDeviceRepository: EnrolledDeviceRepository,
        networkMonitor: NetworkMonitor,
        pendingConflictRepository: PendingConflictRepository,
        vaultManager: VaultManager,
        wipeGate: VaultWipeGate,
    ) : this(
        context,
        syncProtocol,
        enrolledDeviceRepository,
        networkMonitor,
        pendingConflictRepository,
        vaultManager,
        Dispatchers.Default,
        wipeGate,
    )

    /**
     * Internal scope isolated with [SupervisorJob] so that a failing coroutine job
     * (network reconnect watcher, short-interval loop) does not cancel siblings.
     *
     * The handler is a last-resort net: an exception escaping a coroutine of this scope would
     * otherwise reach the default uncaught handler and kill the whole process (tunnels and
     * terminal sessions included). Only the exception class name is logged.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            Timber.tag(TAG).w("Sync scope: uncaught exception (${errorClass(e)})")
        },
    )

    /** The syncs asked by [forceSync] and [syncInBackground] that are still running, see [stopAndAwait]. */
    private val runningSyncs: MutableSet<Deferred<Unit>> = ConcurrentHashMap.newKeySet()

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
        // The wipe of the vault has the app closed: nothing is scheduled (the sync switch is turned off by
        // the wipe, and a start that raced it must not leave a loop or a periodic work behind).
        if (wipeGate.isClosed()) {
            Timber.tag(TAG).d("Scheduler not started: the app is being wiped")
            return
        }

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

        Timber.tag(TAG).d("Scheduler started (intervalMs=$intervalMs)")
    }

    override fun stop() {
        cancelActiveJobs()
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (_: IllegalStateException) {
            // WorkManager not initialised yet: safe to ignore on first call
        }
        Timber.tag(TAG).d("Scheduler stopped")
    }

    /**
     * Runs a full sync and waits for it, but inside this scheduler's own scope: cancelling
     * the caller (a ViewModel cleared when its screen is popped, a stopped worker) stops
     * the wait, never the sync. A forced sync is requested right before leaving the
     * conflict screen, and cutting it between the pull and the push would drop the
     * resolution that was just written.
     *
     * Requested from the interface: may open the vault (inside the authentication window).
     */
    override suspend fun forceSync() {
        inFlight(background = false).await()
    }

    /**
     * Sync triggered without the user (WorkManager worker): never opens the vault, see [runSync].
     * Same own-scope execution as [forceSync].
     */
    suspend fun syncInBackground() {
        inFlight(background = true).await()
    }

    /**
     * Runs a sync in the scope of the scheduler and remembers it while it runs, so that [stopAndAwait]
     * can cancel it: [stop] alone does not, these syncs are not children of the loop or of the watcher.
     */
    private fun inFlight(background: Boolean): Deferred<Unit> {
        val job = scope.async { runSync(background = background) }
        runningSyncs += job
        job.invokeOnCompletion { runningSyncs -= job }
        return job
    }

    /**
     * Stops the scheduler like [stop], then cancels the syncs in flight (the ones asked by [forceSync] and
     * [syncInBackground] too) and waits, at most [timeoutMs], for the status to leave SYNCING. The first
     * step of the closing of the "Wipe the vault" action: no pull may write the database after the erase.
     *
     * Cancelling a request that blocks in the network may not interrupt it, hence the bound; what the wait
     * cannot end is refused later by [runSync] and by `AndroidSyncRepository.applyRemoteBundle`, which
     * look at the closed gate. Returns false when the sync did not end in time.
     */
    suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        stop()
        val running = runningSyncs.toList()
        running.forEach { it.cancel() }
        return withTimeoutOrNull(timeoutMs) {
            running.forEach { it.join() }
            _syncState.first { it.status != SyncStatus.SYNCING }
            true
        } ?: false
    }

    /**
     * After the tables were erased ("Wipe the vault"): the state of the sync is the one of a new install
     * (idle, no last sync, no error, no conflict), and the observer of the conflict count, which
     * [stopAndAwait] cancelled with the rest, runs again: without it the count would stay frozen on the
     * erased vault until the process dies.
     */
    fun resetAfterWipe() {
        _syncState.value = SyncState(SyncStatus.IDLE, null)
        conflictCountJob?.cancel()
        conflictCountJob = scope.launch {
            pendingConflictRepository.observeCount().collect { count ->
                _syncState.update { it.copy(pendingConflicts = count) }
            }
        }
    }

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

        Timber.tag(TAG).d("WorkManager periodic work scheduled (intervalMs=$intervalMs)")
    }

    private fun scheduleCoroutineLoop(intervalMs: Long) {
        coroutineLoopJob = scope.launch {
            Timber.tag(TAG).d("Coroutine loop started (intervalMs=$intervalMs)")
            while (isActive) {
                delay(intervalMs)
                if (networkMonitor.isOnLanTransport.value) {
                    runSync(background = true)
                } else {
                    Timber.tag(TAG).d("Loop tick skipped: not on LAN-capable transport")
                }
            }
        }
    }

    private fun startNetworkWatcher() {
        networkWatcherJob = scope.launch {
            networkMonitor.isOnLanTransport.collect { onLan ->
                if (!onLan) {
                    _syncState.update { it.copy(status = SyncStatus.OFFLINE) }
                    Timber.tag(TAG).d("LAN transport lost: status set to OFFLINE")
                } else if (_syncState.value.status == SyncStatus.OFFLINE) {
                    _syncState.update { it.copy(status = SyncStatus.IDLE) }
                    Timber.tag(TAG).d("LAN transport restored: triggering immediate sync")
                    runSync(background = true)
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
    internal suspend fun runSync(background: Boolean) {
        // The wipe of the vault has the app closed, a pass that was already asked for included: it would
        // pull into a database that is being erased and push what is about to be gone.
        if (wipeGate.isClosed()) {
            Timber.tag(TAG).d("runSync: skipped, the app is being wiped")
            return
        }

        // Background triggers (loop, network watcher, worker) must never open the vault: past the
        // 300 s authentication window the opening throws, and nobody is there to authenticate.
        // The status is left untouched and no text is raised: nothing went wrong.
        if (background && !vaultManager.isOpen()) {
            Timber.tag(TAG).d("runSync: background trigger skipped, vault not open")
            return
        }

        if (_syncState.value.status == SyncStatus.SYNCING) {
            Timber.tag(TAG).d("runSync: already syncing, skipping")
            return
        }

        if (!networkMonitor.isOnLanTransport.value) {
            Timber.tag(TAG).d("runSync: not on LAN transport, staying OFFLINE")
            _syncState.update { it.copy(status = SyncStatus.OFFLINE) }
            return
        }

        if (!tryBeginSync()) {
            Timber.tag(TAG).d("runSync: already syncing, skipping")
            return
        }

        // SYNCING is owned by this call. If it ends without reaching its own final status
        // (cancelled by stop() or a stopped worker, or an unexpected exception), SYNCING
        // must not stay behind: every later runSync would then skip as already syncing,
        // until a network transition or a restart.
        var completed = false
        try {
            syncAllDevices()
            completed = true
        } finally {
            if (!completed) {
                _syncState.update {
                    if (it.status == SyncStatus.SYNCING) it.copy(status = SyncStatus.IDLE) else it
                }
                Timber.tag(TAG).d("runSync: interrupted before the end, status reset")
            }
        }
    }

    /**
     * Atomically moves the status to SYNCING. False when another sync holds it, so two
     * syncs racing on different threads cannot both start.
     */
    private fun tryBeginSync(): Boolean {
        while (true) {
            val current = _syncState.value
            if (current.status == SyncStatus.SYNCING) return false
            if (_syncState.compareAndSet(current, current.copy(status = SyncStatus.SYNCING, lastError = null))) {
                return true
            }
        }
    }

    private suspend fun syncAllDevices() {
        val devices = try {
            enrolledDeviceRepository.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("runSync, failed to load enrolled devices (${errorClass(e)})")
            val message = context.getString(R.string.sync_error_load_devices)
            _syncState.update { it.copy(status = SyncStatus.ERROR, lastError = message) }
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
                    Timber.tag(TAG).d(
                        "runSync, skipping device without tlsCertFingerprint (re-enrollment required) device=${LogId.of(device.deviceId)}",
                    )
                }
                usable
            }
        if (desktopDevices.isEmpty()) {
            Timber.tag(TAG).d("runSync: no Desktop devices enrolled, nothing to do")
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
            val result = try {
                syncProtocol.fullSync(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The shared exception is the one LanSyncClient sees (the vault adapter translates
                // the app's); the app's one is kept as a net in case a path reads the vault directly.
                if (e is SharedVaultAuthExpiredException || e is VaultAuthExpiredException) {
                    // The vault cannot be read: every remaining device would fail the same way.
                    Timber.tag(TAG).w("runSync: vault authentication expired, stopping the device loop")
                    val message = context.getString(R.string.sync_error_vault_locked)
                    _syncState.update {
                        it.copy(
                            status = SyncStatus.ERROR,
                            lastSyncAt = System.currentTimeMillis(),
                            lastError = message,
                        )
                    }
                    return
                }
                // Class name only: the message may carry an address or a secret-adjacent detail.
                Timber.tag(TAG).w("runSync: device=${LogId.of(device.deviceId)} fullSync failed (${errorClass(e)})")
                lastError = context.getString(syncErrorLabelRes(SyncError.UNKNOWN), device.deviceName)
                continue
            }
            when (result) {
                is SyncResult.Success -> {
                    Timber.tag(TAG).d(
                        "runSync: device=${LogId.of(device.deviceId)} pushed=${result.pushedCount} pulled=${result.pulledCount} conflicts=${result.conflicts.size}",
                    )
                }
                is SyncResult.Error -> {
                    // From the reason only: result.message is a developer text from LanSyncClient
                    // (it can quote an address or an exception message), never shown to the user.
                    lastError = context.getString(syncErrorLabelRes(result.reason), device.deviceName)
                    Timber.tag(TAG).w("runSync: device=${LogId.of(device.deviceId)} error=${result.reason}")
                }
                SyncResult.DeviceUnreachable -> {
                    lastError = context.getString(R.string.sync_error_device_unreachable, device.deviceName)
                    Timber.tag(TAG).w("runSync: device=${LogId.of(device.deviceId)} unreachable")
                }
                SyncResult.AuthFailed -> {
                    lastError = context.getString(R.string.sync_error_device_auth_failed, device.deviceName)
                    Timber.tag(TAG).w("runSync: device=${LogId.of(device.deviceId)} auth failed")
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
        Timber.tag(TAG).d("runSync: done, status=${_syncState.value.status}")
    }
}
