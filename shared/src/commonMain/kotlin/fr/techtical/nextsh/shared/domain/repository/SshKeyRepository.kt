// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.repository

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.SshKey
import kotlinx.coroutines.flow.Flow

interface SshKeyRepository {
    fun observeAll(): Flow<List<SshKey>>
    suspend fun getById(id: String): SshKey?
    suspend fun save(key: SshKey)
    suspend fun update(key: SshKey)
    suspend fun delete(id: String)

    // Sync primitives: used by SyncRepository; not called from UI.
    suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>>
    suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>)
    suspend fun hardDelete(id: String)
}
