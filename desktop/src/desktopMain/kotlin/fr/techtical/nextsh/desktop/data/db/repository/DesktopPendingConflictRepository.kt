// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.desktop.db.Pending_conflicts
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopPendingConflictRepository(private val db: NextShDatabase) : PendingConflictRepository {

    private val q = db.pendingConflictQueries

    override suspend fun getAll(): List<PendingConflict> = withContext(Dispatchers.IO) {
        q.selectAll().executeAsList().map(::toModel)
    }

    override fun observeAll(): Flow<List<PendingConflict>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toModel) }

    override fun observeCount(): Flow<Int> =
        q.selectCount().asFlow().mapToOne(Dispatchers.IO).map { it.toInt() }

    override suspend fun save(conflict: PendingConflict) = withContext(Dispatchers.IO) {
        q.upsert(
            id = conflict.id,
            entityType = conflict.entityType.name,
            entityId = conflict.entityId,
            localJson = conflict.localJson,
            remoteJson = conflict.remoteJson,
            detectedAt = conflict.detectedAt,
        )
    }

    override suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        q.deleteById(id)
    }

    override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) =
        withContext(Dispatchers.IO) {
            q.deleteByEntity(entityType.name, entityId)
        }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        q.deleteAll()
    }

    private fun toModel(row: Pending_conflicts): PendingConflict = PendingConflict(
        id = row.id,
        entityType = SyncableEntityType.valueOf(row.entityType),
        entityId = row.entityId,
        localJson = row.localJson,
        remoteJson = row.remoteJson,
        detectedAt = row.detectedAt,
    )
}
