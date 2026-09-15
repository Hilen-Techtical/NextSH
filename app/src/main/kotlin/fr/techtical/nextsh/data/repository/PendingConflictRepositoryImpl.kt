// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.PendingConflictDao
import fr.techtical.nextsh.data.db.entity.PendingConflictEntity
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingConflictRepositoryImpl @Inject constructor(
    private val dao: PendingConflictDao,
) : PendingConflictRepository {

    override suspend fun getAll(): List<PendingConflict> =
        dao.getAll().map(::toModel)

    override fun observeAll(): Flow<List<PendingConflict>> =
        dao.observeAll().map { entities -> entities.map(::toModel) }

    override fun observeCount(): Flow<Int> =
        dao.observeCount()

    override suspend fun save(conflict: PendingConflict) {
        dao.insert(toEntity(conflict))
    }

    override suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) {
        dao.deleteByEntity(entityType.name, entityId)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    private fun toModel(entity: PendingConflictEntity): PendingConflict = PendingConflict(
        id = entity.id,
        entityType = SyncableEntityType.valueOf(entity.entityType),
        entityId = entity.entityId,
        localJson = entity.localJson,
        remoteJson = entity.remoteJson,
        detectedAt = entity.detectedAt,
    )

    private fun toEntity(conflict: PendingConflict): PendingConflictEntity = PendingConflictEntity(
        id = conflict.id,
        entityType = conflict.entityType.name,
        entityId = conflict.entityId,
        localJson = conflict.localJson,
        remoteJson = conflict.remoteJson,
        detectedAt = conflict.detectedAt,
    )
}
