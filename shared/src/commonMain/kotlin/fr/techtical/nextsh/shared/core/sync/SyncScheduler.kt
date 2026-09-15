// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Schedules periodic synchronisation with enrolled peers.
 *
 * Android is the active side: it initiates pull + push on a configurable interval
 * and on network reconnect. Desktop is passive: it only receives pushes via the
 * Ktor server and updates [syncState] when a push arrives.
 */
interface SyncScheduler {
    /**
     * Starts the scheduler with the given [intervalMs].
     *
     * On Android, intervals >= 15 min are delegated to WorkManager periodic work.
     * Shorter intervals use a coroutine loop (WorkManager minimum is 15 min).
     *
     * On Desktop this is a no-op: the server is passive.
     */
    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS)

    /** Cancels any running periodic work and coroutine jobs. */
    fun stop()

    /**
     * Triggers an immediate full sync on all enrolled Desktop peers.
     * Android only, Desktop does not initiate.
     */
    suspend fun forceSync()

    /** Live sync status stream. */
    val syncState: StateFlow<SyncState>

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 15L * 60 * 1000   // 15 min
    }
}

data class SyncState(
    val status: SyncStatus,
    val lastSyncAt: Long?,
    val pendingConflicts: Int = 0,
    val lastError: String? = null,
)

enum class SyncStatus { IDLE, SYNCING, ERROR, OFFLINE }

/**
 * Sync interval presets exposed in the settings UI.
 * Values < 15 min bypass WorkManager periodic (which enforces a 15-min minimum)
 * and use a plain coroutine loop instead.
 */
enum class SyncInterval(val minutes: Int, val displayLabel: String) {
    ONE_MIN(1, "1 min"),
    FIVE_MIN(5, "5 min"),
    FIFTEEN_MIN(15, "15 min"),
    THIRTY_MIN(30, "30 min");

    val intervalMs: Long get() = minutes.toLong() * 60 * 1000

    companion object {
        val DEFAULT = FIFTEEN_MIN

        fun fromMinutes(minutes: Int): SyncInterval =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}
