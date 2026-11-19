// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DesktopOrphanTunnelRepair"

/**
 * Tombstones the LIVE tunnels whose host is tombstoned ("orphans").
 *
 * Foreign keys are enforced, and a tombstone is an UPDATE, so nothing cascades: [DesktopHostRepository.delete]
 * tombstones the tunnels of the host itself, but orphans keep arriving by other roads. An Android 1.0.x peer
 * deletes a host without its tunnels, another device creates a tunnel on a host this one has already
 * deleted, and a sync bundle may carry a live tunnel for a host that is tombstoned here. Left alone, an
 * orphan shows up in the list with an unknown host and fails to start at every launch.
 *
 * Each orphan gets its own ticked clock (see [tombstoneTunnels]), so the deletion reaches the peers. It does
 * not go through [DesktopHostRepository.delete], which would tick the host tombstone again at every run.
 * A host with a pending HOST conflict is skipped (see `selectLiveOfDeletedHosts`).
 *
 * Idempotent: a database without orphans is not written at all, so running it on every launch and after
 * every sync creates no churn.
 */
class DesktopOrphanTunnelRepair(private val db: NextShDatabase) {

    private val tunnelQ = db.tunnelQueries

    /**
     * Runs the repair in one transaction and returns how many tunnels it tombstoned. A failure is logged
     * (exception class only) and counts as 0: the repair is a best-effort cleanup, it must never break the
     * caller (launch, sync apply, conflict resolution).
     */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        try {
            // Like DesktopHostRepository.delete: the device id and the time are read before the transaction,
            // which only holds the reads and the writes.
            val deviceId = DeviceIdentity.deviceId()
            val now = System.currentTimeMillis()
            val repaired = db.transactionWithResult {
                // Read inside the transaction: the rows seen are the rows tombstoned.
                val orphans = tunnelQ.selectLiveOfDeletedHosts().executeAsList()
                if (orphans.isNotEmpty()) tunnelQ.tombstoneTunnels(orphans, deviceId, now)
                orphans.size
            }
            // Count only: labels, hostnames and ids stay out of the logs.
            if (repaired > 0) Logger.w(TAG, "$repaired tunnel(s) of deleted hosts removed")
            repaired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "repair failed: ${errorClass(e)}")
            0
        }
    }
}
