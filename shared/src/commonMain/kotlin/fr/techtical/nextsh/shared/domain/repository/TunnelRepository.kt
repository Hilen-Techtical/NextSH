// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.repository

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.coroutines.flow.Flow

interface TunnelRepository {
    fun observeAll(): Flow<List<TunnelConfig>>
    fun observeByHost(hostId: String): Flow<List<TunnelConfig>>
    suspend fun getById(id: String): TunnelConfig?
    suspend fun getAutoStartTunnels(): List<TunnelConfig>
    suspend fun save(config: TunnelConfig)
    suspend fun update(config: TunnelConfig)
    suspend fun delete(id: String)
    fun observeFavorites(): Flow<List<TunnelConfig>>
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    // Sync primitives: used by SyncRepository; not called from UI.
    suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>>
    suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>)
    suspend fun hardDelete(id: String)
}
