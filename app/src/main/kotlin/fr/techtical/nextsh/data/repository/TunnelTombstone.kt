// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.data.db.entity.TunnelEntity

/**
 * Soft-deletes [tunnel] (deleted, deletedAt and updatedAt at [now]) and ticks its own vector
 * clock, so the delete wins on the peers instead of looking concurrent to them.
 *
 * The one place that tombstones a tunnel row outside the single-tunnel delete: the cascade of
 * [HostRepositoryImpl.delete] and [OrphanTunnelRepair] share it. Call it inside a transaction
 * when several rows are written together.
 */
internal suspend fun TunnelDao.tombstone(tunnel: TunnelEntity, now: Long) {
    softDelete(tunnel.id, now, now, tickedClockJson(tunnel.vectorClock, now))
}
