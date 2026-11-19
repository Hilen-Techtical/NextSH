// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.dao.TunnelDao
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
    private val tunnelDao: TunnelDao,
    private val transactionRunner: TransactionRunner,
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
        // Never delete-and-reinsert (HostDao has no REPLACE insert): tunnels.hostId is a CASCADE
        // foreign key, so replacing a host row that already exists would wipe its tunnels. An
        // existing id (a backup restore over the same host, tombstone included) is updated in
        // place: the local-only columns stay, the row is revived, and its clock is ticked from
        // the stored one so the saved version wins on the peers. A new id gets a fresh clock.
        val now = System.currentTimeMillis()
        val existing = hostDao.getByIdIncludingDeleted(host.id)
        // A live row that already holds exactly this content is left alone: ticking its clock
        // for nothing would make every peer pull the row again, and could raise false conflicts.
        if (existing != null && !existing.deleted && existing.toDomain() == host) return
        val fresh = HostEntity.fromDomain(host)
        hostDao.upsert(
            fresh.copy(
                createdAt = existing?.createdAt ?: fresh.createdAt,
                lastConnectedAt = existing?.lastConnectedAt,
                vectorClock = tickedClockJson(existing?.vectorClock ?: "{}", now),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun update(host: Host) {
        // getById hides tombstones: an edit never revives a host deleted meanwhile (by a sync,
        // for example) and never creates a missing one, it writes nothing. The caller tells the
        // user (HostViewModel re-reads the host before calling this). The read and the write
        // are one transaction: a sync tombstone cannot land between them and be overwritten.
        transactionRunner.run {
            val existing = hostDao.getById(host.id) ?: return@run
            val now = System.currentTimeMillis()
            hostDao.update(
                HostEntity.fromDomain(host).copy(
                    // fromDomain knows nothing of the local-only columns: carry them over,
                    // otherwise every edit resets createdAt to now and forgets the last connection.
                    createdAt = existing.createdAt,
                    lastConnectedAt = existing.lastConnectedAt,
                    vectorClock = tickedClockJson(existing.vectorClock, now),
                    updatedAt = now,
                    deleted = false,
                    deletedAt = null,
                )
            )
        }
    }

    override suspend fun delete(id: String) {
        // The host and its live tunnels are tombstoned together, in one transaction, because
        // the delete dialog promises it and a tunnel left live would point at a deleted host:
        // it would travel in sync bundles and backups and fail the foreign key on a peer that
        // never saw the host. Each row gets its own ticked clock so the deletes win on peers.
        transactionRunner.run {
            val now = System.currentTimeMillis()
            // getById hides tombstones: a host already deleted (or unknown) keeps its tombstone
            // untouched instead of having its clock overwritten by one ticked from "{}".
            val existing = hostDao.getById(id)
            if (existing != null) {
                hostDao.softDelete(id, now, now, tickedClockJson(existing.vectorClock, now))
            }
            tunnelDao.getByHostId(id).forEach { tunnel -> tunnelDao.tombstone(tunnel, now) }
        }
    }

    override suspend fun updateLastConnected(id: String) {
        hostDao.updateLastConnected(id, System.currentTimeMillis())
    }

    override fun observeFavorites(): Flow<List<Host>> =
        hostDao.observeFavorites().map { list -> list.map { it.toDomain() } }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) {
        // isFavorite is synced: an un-ticked write never wins a sync, so a favorite change is a
        // regular edit. getById ignores tombstones, a deleted row stays untouched.
        val existing = hostDao.getById(id) ?: return
        if (existing.isFavorite == isFavorite) return
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode(existing.vectorClock).tick(DeviceIdentity.deviceId(), now)
        hostDao.updateFavorite(id, isFavorite, VectorClockCodec.encode(newClock), now)
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
        // Update in place (see HostDao.upsert): REPLACE would cascade-delete the tunnels.
        // Local-only columns are read, tombstones included, and kept.
        val existing = hostDao.getByIdIncludingDeleted(entry.id)
        val payload = entry.payload
        if (payload == null) {
            // Propagating a remote soft-delete: keep metadata, null payload.
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
            val fresh = HostEntity.fromDomain(payload)
            hostDao.upsert(
                fresh.copy(
                    createdAt = existing?.createdAt ?: fresh.createdAt,
                    lastConnectedAt = existing?.lastConnectedAt,
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
