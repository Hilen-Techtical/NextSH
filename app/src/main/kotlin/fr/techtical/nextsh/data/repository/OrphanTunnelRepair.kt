// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.db.dao.TunnelDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes the live tunnels whose host is deleted.
 *
 * Deleting a host tombstones its tunnels ([HostRepositoryImpl.delete]), but orphans keep
 * arriving: an Android or Desktop 1.0.x peer does not cascade, a peer may create a tunnel under
 * a host deleted meanwhile, and a sync writes a remote live tunnel under a locally tombstoned
 * host (the tombstone satisfies the foreign key). An orphan shows as "?" in the list, cannot
 * start (its host is not found) and was retried at every autostart.
 *
 * Each orphan is tombstoned with its own ticked clock, so the delete travels with the next push
 * of the sync cycle. A host with a pending HOST conflict is spared (see
 * [TunnelDao.getLiveTunnelsOfDeletedHosts]): choosing "keep remote" later must not bring the
 * host back without its tunnels.
 *
 * A tunnel that runs is not touched here: the row change reaches `TunnelReconciler`, which stops
 * it.
 */
@Singleton
class OrphanTunnelRepair @Inject constructor(
    private val tunnelDao: TunnelDao,
    private val transactionRunner: TransactionRunner,
) {
    // Runs may overlap (start-up, a sync cycle, a conflict resolution): one at a time, so two
    // runs never tick the same clock twice.
    private val mutex = Mutex()

    /**
     * Tombstones every orphan and returns how many. Idempotent: on a clean database it reads and
     * writes nothing. The read and the writes are one transaction, so a row revived between them
     * is not deleted from a stale list.
     */
    suspend fun run(): Int = mutex.withLock {
        transactionRunner.run {
            val orphans = tunnelDao.getLiveTunnelsOfDeletedHosts()
            if (orphans.isNotEmpty()) {
                val now = System.currentTimeMillis()
                orphans.forEach { tunnelDao.tombstone(it, now) }
            }
            orphans.size
        }
    }

    /**
     * [run] for callers that must not fail because of it (start-up, the end of a sync pull): a
     * failure is logged by class name only and counts as zero repaired.
     */
    suspend fun runSafely(): Int = try {
        run().also { count ->
            // Count only: no label, id or host.
            if (count > 0) Timber.i("OrphanTunnelRepair: $count orphan tunnel(s) deleted")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("OrphanTunnelRepair failed: ${e::class.java.simpleName}")
        0
    }
}
