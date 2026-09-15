// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.entity.HostEntity
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostRepositoryImpl @Inject constructor(
    private val hostDao: HostDao,
) : HostRepository {

    override fun observeAll(): Flow<List<Host>> =
        hostDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeByGroup(group: String): Flow<List<Host>> =
        hostDao.observeByGroup(group).map { entities -> entities.map { it.toDomain() } }

    override fun observeGroups(): Flow<List<String>> =
        hostDao.observeGroups()

    override suspend fun getById(id: String): Host? =
        hostDao.getById(id)?.toDomain()

    override suspend fun save(host: Host) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        hostDao.insert(
            HostEntity.fromDomain(host).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun update(host: Host) {
        val now = System.currentTimeMillis()
        val existing = hostDao.getById(host.id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        hostDao.update(
            HostEntity.fromDomain(host).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun delete(id: String) {
        val now = System.currentTimeMillis()
        val existing = hostDao.getById(id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        hostDao.softDelete(id, now, now, VectorClockCodec.encode(newClock))
    }

    override suspend fun updateLastConnected(id: String) {
        hostDao.updateLastConnected(id, System.currentTimeMillis())
    }

    override fun observeFavorites(): Flow<List<Host>> =
        hostDao.observeFavorites().map { list -> list.map { it.toDomain() } }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        hostDao.updateFavorite(id, isFavorite)
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> =
        hostDao.getAllIncludingDeleted().map { entity ->
            SyncEntry(
                id = entity.id,
                payload = if (entity.deleted) null else entity.toDomain(),
                clock = VectorClockCodec.decode(entity.vectorClock),
                deleted = entity.deleted,
                deletedAt = entity.deletedAt,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) {
        val payload = entry.payload
        if (payload == null) {
            // Propagating a remote soft-delete: keep metadata, null payload.
            val existing = hostDao.getAllIncludingDeleted().firstOrNull { it.id == entry.id }
            if (existing != null) {
                hostDao.upsert(
                    existing.copy(
                        deleted = true,
                        deletedAt = entry.deletedAt,
                        updatedAt = entry.updatedAt,
                        vectorClock = VectorClockCodec.encode(entry.clock),
                    )
                )
            }
        } else {
            hostDao.upsert(
                HostEntity.fromDomain(payload).copy(
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    deleted = entry.deleted,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                )
            )
        }
    }

    override suspend fun hardDelete(id: String) {
        hostDao.deleteById(id)
    }
}
