// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.repository

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import kotlinx.coroutines.flow.Flow

/**
 * Repository for user-defined terminal themes. Mirrors [SnippetRepository]:
 * standard CRUD plus the CRDT sync primitives consumed by the SyncRepository.
 */
interface CustomTerminalThemeRepository {
    fun observeAll(): Flow<List<CustomTerminalTheme>>
    suspend fun getById(id: String): CustomTerminalTheme?
    suspend fun save(theme: CustomTerminalTheme)
    suspend fun delete(id: String)

    // Sync primitives: used by SyncRepository; not called from UI.
    suspend fun getAllSyncEntries(): List<SyncEntry<CustomTerminalTheme>>
    suspend fun upsertSyncEntry(entry: SyncEntry<CustomTerminalTheme>)
    suspend fun hardDelete(id: String)
}
