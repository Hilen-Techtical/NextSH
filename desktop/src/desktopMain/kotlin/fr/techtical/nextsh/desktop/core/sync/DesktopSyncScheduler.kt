// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.sync

import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "DesktopSyncScheduler"

/**
 * Desktop-side [SyncScheduler].
 *
 * Desktop is a **passive** sync participant: it does not initiate connections to Android peers
 * because Android phones do not expose a sync server. The server listens on [LanSyncServer]
 * and all actual data exchange is initiated by the Android client.
 *
 * This scheduler's only active role is to update [syncState] when [LanSyncServer] notifies
 * it that a push was received and applied. The [pendingConflicts] field is driven by
 * a [PendingConflictRepository.observeCount] Flow so the count survives app restarts.
 *
 * @param externalScope optional scope for the conflict-count observer; defaults to a new
 *   [SupervisorJob] + [Dispatchers.Default] scope. Pass a test scope in unit tests.
 */
class DesktopSyncScheduler(
    private val pendingConflictRepository: PendingConflictRepository,
    externalScope: CoroutineScope? = null,
) : SyncScheduler {

    private val scope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _syncState = MutableStateFlow(SyncState(SyncStatus.IDLE, null))
    override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var conflictCountJob: Job? = conflictCountJob()

    /** No-op: Desktop does not initiate syncs. */
    override fun start(intervalMs: Long) = Unit

    /** Cancels the conflict count observer. */
    override fun stop() {
        conflictCountJob?.cancel()
        conflictCountJob = null
    }

    /**
     * No-op: Desktop has no IP/port of Android peers and cannot initiate.
     * Android is always the active side.
     */
    override suspend fun forceSync() = Unit

    /**
     * Called by [LanSyncServer] after a push from [fromDeviceId] has been successfully applied.
     *
     * Updates [syncState] so the settings UI reflects the last received sync time.
     * The [pendingConflicts] count is driven by the DB Flow: no manual increment here.
     *
     * @param fromDeviceId device ID of the Android peer that pushed
     * @param cleanApplied number of entries cleanly merged
     * @param conflicts number of conflicting entries (informational: persisted by DesktopSyncRepository)
     */
    fun onPushReceived(fromDeviceId: String, cleanApplied: Int, conflicts: Int) {
        Logger.d(TAG, "Push received from deviceId=$fromDeviceId cleanApplied=$cleanApplied conflicts=$conflicts")
        _syncState.update { current ->
            current.copy(
                status = SyncStatus.IDLE,
                lastSyncAt = System.currentTimeMillis(),
            )
        }
    }

    private fun conflictCountJob(): Job = scope.launch {
        pendingConflictRepository.observeCount().collect { count ->
            _syncState.update { it.copy(pendingConflicts = count) }
        }
    }
}
