// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import javax.inject.Inject

/** A live tunnel row and the live host row it points at, as stored when they were read. */
data class LiveTunnel(val tunnel: TunnelConfig, val host: Host)

/**
 * Answers one question from the database: is this tunnel still there, and is its host?
 *
 * Tunnels and hosts are soft-deleted and a sync can delete them while a tunnel runs or waits for
 * a reconnection. `StartTunnelUseCase` (in `:shared`) re-reads the host but hides nothing from a
 * stale tunnel config, and a tombstoned tunnel with a live host would start as an invisible
 * ghost. Every start in the app layer (manual, autostart, automatic reconnection) goes through
 * here first. `getById` of both repositories hides tombstones, so "found" means "live".
 */
class LiveTunnelLookup @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val hostRepository: HostRepository,
) {

    /**
     * The stored row of [tunnelId] when it is live and so is the host it points at, null
     * otherwise. Start from this row, not from an older copy: an edit made meanwhile applies.
     */
    suspend fun find(tunnelId: String): TunnelConfig? = findLive(tunnelId)?.tunnel

    /**
     * Like [find], with the stored row of the host that was read to check it, for the caller that
     * decides from the host too (the automatic reconnection reads its `autoReconnect` option).
     */
    suspend fun findLive(tunnelId: String): LiveTunnel? {
        val tunnel = tunnelRepository.getById(tunnelId) ?: return null
        val host = hostRepository.getById(tunnel.hostId) ?: return null
        return LiveTunnel(tunnel, host)
    }

    /** True when the host [hostId] is live. */
    suspend fun isHostLive(hostId: String): Boolean = hostRepository.getById(hostId) != null

    /** The stored row of the host [hostId] when it is live, null otherwise: how it signs in decides what a start needs. */
    suspend fun findHost(hostId: String): Host? = hostRepository.getById(hostId)
}
