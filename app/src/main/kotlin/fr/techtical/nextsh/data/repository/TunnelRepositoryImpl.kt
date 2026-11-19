// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.data.db.entity.TunnelEntity
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunnelRepositoryImpl @Inject constructor(
    private val tunnelDao: TunnelDao,
    private val transactionRunner: TransactionRunner,
) : TunnelRepository {

    override fun observeAll(): Flow<List<TunnelConfig>> =
        tunnelDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> =
        tunnelDao.observeByHost(hostId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): TunnelConfig? =
        tunnelDao.getById(id)?.toDomain()

    override suspend fun getAutoStartTunnels(): List<TunnelConfig> =
        tunnelDao.getAutoStartTunnels().map { it.toDomain() }

    override suspend fun save(config: TunnelConfig) {
        // Same rule as HostRepositoryImpl.save: an existing id (tombstone included) is updated
        // in place, keeps its createdAt, is revived and gets a clock ticked from the stored one;
        // a new id gets a fresh clock. TunnelDao has no REPLACE insert.
        val now = System.currentTimeMillis()
        val existing = tunnelDao.getByIdIncludingDeleted(config.id)
        // A live row that already holds exactly this content is left alone (no clock tick for
        // nothing: it would make every peer pull the row again and could raise false conflicts).
        if (existing != null && !existing.deleted && existing.toDomain() == config) return
        val fresh = TunnelEntity.fromDomain(config)
        tunnelDao.upsert(
            fresh.copy(
                createdAt = existing?.createdAt ?: fresh.createdAt,
                vectorClock = tickedClockJson(existing?.vectorClock ?: "{}", now),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun update(config: TunnelConfig) {
        // getById hides tombstones: an edit never revives a tunnel deleted meanwhile and never
        // creates a missing one, it writes nothing (TunnelViewModel re-reads the tunnel first).
        // The read and the write are one transaction: a sync tombstone cannot land between them
        // and be overwritten.
        transactionRunner.run {
            val existing = tunnelDao.getById(config.id) ?: return@run
            val now = System.currentTimeMillis()
            tunnelDao.update(
                TunnelEntity.fromDomain(config).copy(
                    // createdAt is local-only (not in the domain model): carry it over.
                    createdAt = existing.createdAt,
                    vectorClock = tickedClockJson(existing.vectorClock, now),
                    updatedAt = now,
                    deleted = false,
                    deletedAt = null,
                )
            )
        }
    }

    override suspend fun delete(id: String) {
        // getById hides tombstones: a tunnel already deleted (or unknown) keeps its tombstone
        // untouched instead of having its clock overwritten by one ticked from "{}", which would
        // look concurrent to every peer.
        val existing = tunnelDao.getById(id) ?: return
        val now = System.currentTimeMillis()
        tunnelDao.softDelete(id, now, now, tickedClockJson(existing.vectorClock, now))
    }

    override fun observeFavorites(): Flow<List<TunnelConfig>> =
        tunnelDao.observeFavorites().map { list -> list.map { it.toDomain() } }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        // isFavorite is synced: an un-ticked write never wins a sync, so a favorite change is a
        // regular edit. getById ignores tombstones, a deleted row stays untouched.
        val existing = tunnelDao.getById(id) ?: return
        if (existing.isFavorite == isFavorite) return
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode(existing.vectorClock).tick(DeviceIdentity.deviceId(), now)
        tunnelDao.updateFavorite(id, isFavorite, VectorClockCodec.encode(newClock), now)
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> =
        tunnelDao.getAllIncludingDeleted().map { entity ->
            SyncEntry(
                id = entity.id,
                payload = if (entity.deleted) null else entity.toDomain(),
                clock = VectorClockCodec.decode(entity.vectorClock),
                deleted = entity.deleted,
                deletedAt = entity.deletedAt,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) {
        // Update in place (see HostDao.upsert, same rule). The local-only createdAt is read,
        // tombstones included, and kept.
        val existing = tunnelDao.getByIdIncludingDeleted(entry.id)
        val payload = entry.payload
        if (payload == null) {
            if (existing != null) {
                tunnelDao.upsert(
                    existing.copy(
                        deleted = true,
                        deletedAt = entry.deletedAt,
                        updatedAt = entry.updatedAt,
                        vectorClock = VectorClockCodec.encode(entry.clock),
                    )
                )
            }
        } else {
            val fresh = TunnelEntity.fromDomain(payload)
            tunnelDao.upsert(
                fresh.copy(
                    createdAt = existing?.createdAt ?: fresh.createdAt,
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    deleted = entry.deleted,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                )
            )
        }
    }

    override suspend fun hardDelete(id: String) {
        tunnelDao.deleteById(id)
    }
}
