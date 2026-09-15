// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.repository

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.Snippet
import kotlinx.coroutines.flow.Flow

interface SnippetRepository {
    fun observeAll(): Flow<List<Snippet>>
    fun observeForHost(hostId: String): Flow<List<Snippet>>
    fun observeCategories(): Flow<List<String>>
    suspend fun getById(id: String): Snippet?
    suspend fun save(snippet: Snippet)
    suspend fun update(snippet: Snippet)
    suspend fun delete(id: String)

    // Sync primitives: used by SyncRepository; not called from UI.
    suspend fun getAllSyncEntries(): List<SyncEntry<Snippet>>
    suspend fun upsertSyncEntry(entry: SyncEntry<Snippet>)
    suspend fun hardDelete(id: String)
}
