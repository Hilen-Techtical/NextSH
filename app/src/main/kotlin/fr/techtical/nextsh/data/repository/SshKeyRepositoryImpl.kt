// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.SshKeyDao
import fr.techtical.nextsh.data.db.entity.SshKeyEntity
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyRepositoryImpl @Inject constructor(
    private val sshKeyDao: SshKeyDao,
) : SshKeyRepository {

    override fun observeAll(): Flow<List<SshKey>> =
        sshKeyDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): SshKey? =
        sshKeyDao.getById(id)?.toDomain()

    override suspend fun save(key: SshKey) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        sshKeyDao.insert(
            SshKeyEntity.fromDomain(key).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun update(key: SshKey) {
        val now = System.currentTimeMillis()
        val existing = sshKeyDao.getById(key.id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        sshKeyDao.update(
            SshKeyEntity.fromDomain(key).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun delete(id: String) {
        val now = System.currentTimeMillis()
        val existing = sshKeyDao.getById(id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        sshKeyDao.softDelete(id, now, now, VectorClockCodec.encode(newClock))
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> =
        sshKeyDao.getAllIncludingDeleted().map { entity ->
            SyncEntry(
                id = entity.id,
                payload = if (entity.deleted) null else entity.toDomain(),
                clock = VectorClockCodec.decode(entity.vectorClock),
                deleted = entity.deleted,
                deletedAt = entity.deletedAt,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) {
        val payload = entry.payload
        if (payload == null) {
            val existing = sshKeyDao.getAllIncludingDeleted().firstOrNull { it.id == entry.id }
            if (existing != null) {
                sshKeyDao.upsert(
                    existing.copy(
                        deleted = true,
                        deletedAt = entry.deletedAt,
                        updatedAt = entry.updatedAt,
                        vectorClock = VectorClockCodec.encode(entry.clock),
                    )
                )
            }
        } else {
            sshKeyDao.upsert(
                SshKeyEntity.fromDomain(payload).copy(
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    deleted = entry.deleted,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                )
            )
        }
    }

    override suspend fun hardDelete(id: String) {
        sshKeyDao.deleteById(id)
    }
}
