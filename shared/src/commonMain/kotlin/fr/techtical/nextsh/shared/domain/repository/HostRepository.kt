// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.repository

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.Host
import kotlinx.coroutines.flow.Flow

interface HostRepository {
    fun observeAll(): Flow<List<Host>>
    fun observeByGroup(group: String): Flow<List<Host>>
    fun observeGroups(): Flow<List<String>>
    suspend fun getById(id: String): Host?
    suspend fun save(host: Host)
    suspend fun update(host: Host)
    suspend fun delete(id: String)
    suspend fun updateLastConnected(id: String)
    fun observeFavorites(): Flow<List<Host>>
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    // Sync primitives: used by SyncRepository; not called from UI.
    suspend fun getAllSyncEntries(): List<SyncEntry<Host>>
    suspend fun upsertSyncEntry(entry: SyncEntry<Host>)
    suspend fun hardDelete(id: String)
}
