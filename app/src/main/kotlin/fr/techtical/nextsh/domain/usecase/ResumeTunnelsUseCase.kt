// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.SshResult
import timber.log.Timber
import javax.inject.Inject

/**
 * Restarts the tunnels that stopped reconnecting because their host needed the user in front of the
 * phone while the app was not on screen (see [ReconnectTunnelsUseCase]), once the user came back
 * through the notification and the vault is unlocked. The app is on screen now, so the key sheet or
 * the fingerprint dialog has somebody to answer it.
 *
 * Only the tunnels still waiting are restarted: in error, which is what the reconnection left them
 * in (and what a start lifts the hold of). One that the user started again by hand meanwhile runs
 * and is left alone; one that was stopped by hand is gone from the manager and does not come back
 * behind the user's back; one that was deleted elsewhere is skipped. A notification can be tapped
 * long after it was posted, so what the tunnels are now decides, not what they were.
 *
 * The start itself is the one of the auto-start ([AutoStartTunnelsUseCase.startTunnels]): one after
 * the other, and a non-retryable failure of a host (the key sheet cancelled, a refused key) is the
 * answer of the other tunnels of that host too, so the user is asked once per host. The once-per-
 * process gate of the auto-start does not apply: this is a request of the user, not a start-up
 * feature.
 */
class ResumeTunnelsUseCase @Inject constructor(
    private val tunnelManager: SshTunnelManager,
    private val liveTunnels: LiveTunnelLookup,
    private val starter: AutoStartTunnelsUseCase,
) {
    /** The result per tunnel started, in order; the ones left alone have none. */
    suspend operator fun invoke(tunnelIds: List<String>): List<Pair<String, SshResult<Unit>>> {
        val waiting = tunnelIds.distinct().mapNotNull { id ->
            // The manager's state, not a copy of it: only a tunnel still in error is waiting.
            if (tunnelManager.getTunnelState(id)?.status != TunnelStatus.ERROR) return@mapNotNull null
            liveTunnels.find(id)
        }
        // Counts only, no id or label.
        Timber.i("Reprise de ${waiting.size} tunnel(s) sur ${tunnelIds.size} en attente")
        return starter.startTunnels(waiting)
    }
}
