// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.service.DeletedTunnelNotifier
import fr.techtical.nextsh.shared.domain.model.SshResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stops tunnels whose row or host was deleted behind their back (a sync, a conflict resolution,
 * the orphan repair), by [TunnelReconciler] and by the reconnection loop, and tells the user.
 *
 * The one place that does it, so that every such stop is treated the same way. A local delete
 * from the UI never comes here: it stops the tunnel before it tombstones the row, and the user
 * who just deleted it needs no notification.
 */
@Singleton
class DeletedTunnelStopper @Inject constructor(
    private val tunnelManager: SshTunnelManager,
    private val notifier: DeletedTunnelNotifier,
) {

    /**
     * Stops each of [tunnels] that is still known to the manager and returns how many really
     * stopped. `stopTunnel` also removes the state of a tunnel in error, which lets the
     * foreground service end when nothing is left. A tunnel already gone (another path stopped
     * it first) or whose teardown failed is not counted, and not announced.
     *
     * The tunnels stopped by one call are announced together, in one notification, but only
     * those that were really running when stopped: ACTIVE or RECONNECTING. One that was STARTING
     * never reached ACTIVE, so the user never saw it run, and the deletion most likely comes
     * from this device (a local delete racing a connection), where "deleted on another device"
     * would be wrong and a second announcement. One in ERROR is already shown as failed and
     * would disappear from the list anyway. Both are stopped silently.
     */
    suspend fun stop(tunnels: List<TunnelConfig>): Int {
        var stopped = 0
        var failed = 0
        val announced = mutableListOf<String>()
        for (config in tunnels) {
            // Read before the stop: stopping removes the state.
            val status = tunnelManager.getTunnelState(config.id)?.status
            val wasRunning = status == TunnelStatus.ACTIVE || status == TunnelStatus.RECONNECTING
            if (tunnelManager.stopTunnel(config.id) is SshResult.Success) {
                stopped++
                if (wasRunning) announced += config.label
            } else {
                failed++
            }
        }
        // Counts only: no label, id or host.
        if (failed > 0) Timber.w("DeletedTunnelStopper: $failed stop(s) failed")
        if (stopped == 0) return 0

        Timber.i("DeletedTunnelStopper: stopped $stopped tunnel(s) deleted elsewhere")
        if (announced.isNotEmpty()) {
            try {
                notifier.notifyStopped(announced)
            } catch (e: Exception) {
                // The tunnels are stopped, which is what matters: a notification that cannot be
                // posted must not turn into a failure of the caller.
                Timber.w("DeletedTunnelStopper: notification failed (${e::class.java.simpleName})")
            }
        }
        return stopped
    }
}
