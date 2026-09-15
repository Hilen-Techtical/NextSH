// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import timber.log.Timber

private const val TAG = "SyncWorker"

/**
 * WorkManager worker that delegates sync execution to [AndroidSyncScheduler].
 *
 * Hilt cannot inject into [CoroutineWorker] directly without `hilt-work`, so we use
 * the [EntryPoint] pattern: [SyncWorkerEntryPoint] is generated on [SingletonComponent]
 * and accessed via [EntryPointAccessors.fromApplication]. This avoids the extra
 * `hilt-work` dependency while keeping the Worker itself thin and testable.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d(TAG, "doWork: starting sync cycle")
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                SyncWorkerEntryPoint::class.java,
            )
            entryPoint.syncScheduler().forceSync()
            Timber.d(TAG, "doWork: sync cycle completed")
            Result.success()
        } catch (e: Exception) {
            Timber.w(TAG, "doWork, sync cycle failed: ${e.message}")
            // Delegate to WorkManager retry logic (max 3 auto-retries by default).
            // The scheduler itself does not retry above this: LanSyncClient handles its own
            // 3-attempt exponential back-off at the network level.
            Result.retry()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun syncScheduler(): SyncScheduler
}
