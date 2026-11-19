// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import fr.techtical.nextsh.desktop.db.TunnelQueries
import fr.techtical.nextsh.desktop.db.Tunnels
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec

/**
 * Tombstones every row of [tunnels]: deleted = 1 and a clock that is the row's own clock ticked for
 * [deviceId], so that the deletion wins over the live version on the peers.
 *
 * Not a suspend function on purpose: it is meant to run inside a caller's transaction, and a SQLDelight
 * transaction is bound to the thread that opened it. Shared by [DesktopHostRepository.delete] (the tunnels
 * of the host being deleted) and [DesktopOrphanTunnelRepair] (the live tunnels of an already deleted host).
 */
internal fun TunnelQueries.tombstoneTunnels(tunnels: List<Tunnels>, deviceId: String, now: Long) {
    tunnels.forEach { tunnel ->
        val tunnelClock = VectorClockCodec.decode(tunnel.vectorClock).tick(deviceId, now)
        markDeleted(
            deletedAt = now,
            updatedAt = now,
            vectorClock = VectorClockCodec.encode(tunnelClock),
            id = tunnel.id,
        )
    }
}
