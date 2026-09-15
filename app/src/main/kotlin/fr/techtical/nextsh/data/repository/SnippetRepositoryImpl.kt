// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.SnippetDao
import fr.techtical.nextsh.data.db.entity.SnippetEntity
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnippetRepositoryImpl @Inject constructor(
    private val snippetDao: SnippetDao,
) : SnippetRepository {

    override fun observeAll(): Flow<List<Snippet>> =
        snippetDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeForHost(hostId: String): Flow<List<Snippet>> =
        snippetDao.observeForHost(hostId).map { entities -> entities.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<String>> =
        snippetDao.observeCategories()

    override suspend fun getById(id: String): Snippet? =
        snippetDao.getById(id)?.toDomain()

    override suspend fun save(snippet: Snippet) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        snippetDao.insert(
            SnippetEntity.fromDomain(snippet).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun update(snippet: Snippet) {
        val now = System.currentTimeMillis()
        val existing = snippetDao.getById(snippet.id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        snippetDao.update(
            SnippetEntity.fromDomain(snippet).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun delete(id: String) {
        val now = System.currentTimeMillis()
        val existing = snippetDao.getById(id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        snippetDao.softDelete(id, now, now, VectorClockCodec.encode(newClock))
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<Snippet>> =
        snippetDao.getAllIncludingDeleted().map { entity ->
            SyncEntry(
                id = entity.id,
                payload = if (entity.deleted) null else entity.toDomain(),
                clock = VectorClockCodec.decode(entity.vectorClock),
                deleted = entity.deleted,
                deletedAt = entity.deletedAt,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun upsertSyncEntry(entry: SyncEntry<Snippet>) {
        val payload = entry.payload
        if (payload == null) {
            val existing = snippetDao.getAllIncludingDeleted().firstOrNull { it.id == entry.id }
            if (existing != null) {
                snippetDao.upsert(
                    existing.copy(
                        deleted = true,
                        deletedAt = entry.deletedAt,
                        updatedAt = entry.updatedAt,
                        vectorClock = VectorClockCodec.encode(entry.clock),
                    )
                )
            }
        } else {
            snippetDao.upsert(
                SnippetEntity.fromDomain(payload).copy(
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    deleted = entry.deleted,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                )
            )
        }
    }

    override suspend fun hardDelete(id: String) {
        snippetDao.deleteById(id)
    }
}
