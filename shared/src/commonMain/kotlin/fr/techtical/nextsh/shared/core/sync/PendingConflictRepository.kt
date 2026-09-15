// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.coroutines.flow.Flow

interface PendingConflictRepository {
    suspend fun getAll(): List<PendingConflict>
    fun observeAll(): Flow<List<PendingConflict>>
    fun observeCount(): Flow<Int>
    suspend fun save(conflict: PendingConflict)
    suspend fun deleteById(id: String)
    suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String)
    suspend fun deleteAll()
}
