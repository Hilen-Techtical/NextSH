// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import fr.techtical.nextsh.data.db.entity.TunnelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TunnelDao {

    @Query("SELECT * FROM tunnels WHERE deleted = 0 ORDER BY label ASC")
    fun observeAll(): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE hostId = :hostId AND deleted = 0 ORDER BY label ASC")
    fun observeByHost(hostId: String): Flow<List<TunnelEntity>>

    // Only tunnels whose host is live: a tunnel under a deleted host can never start (the host
    // is not found), so autostart would only retry it at every list opening.
    @Query(
        "SELECT t.* FROM tunnels t INNER JOIN hosts h ON h.id = t.hostId " +
            "WHERE t.autoStart = 1 AND t.deleted = 0 AND h.deleted = 0"
    )
    suspend fun getAutoStartTunnels(): List<TunnelEntity>

    @Query("SELECT * FROM tunnels WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): TunnelEntity?

    // Live tunnels of one host: deleting the host tombstones them too.
    @Query("SELECT * FROM tunnels WHERE hostId = :hostId AND deleted = 0")
    suspend fun getByHostId(hostId: String): List<TunnelEntity>

    // Live tunnels that go through a live host connecting with the key [keyId] (read only, for the
    // warning shown before the key is deleted). Same host filter as HostDao.getLabelsUsingKey.
    @Query(
        "SELECT COUNT(*) FROM tunnels t INNER JOIN hosts h ON h.id = t.hostId " +
            "WHERE t.deleted = 0 AND h.deleted = 0 AND h.credentialId = :keyId " +
            "AND h.authType IN (:authTypes)"
    )
    suspend fun countLiveTunnelsOfHostsUsingKey(keyId: String, authTypes: List<String>): Int

    // Live tunnels whose host is tombstoned: orphans, left by a peer that does not cascade or by
    // a tunnel created concurrently under a host deleted elsewhere. A host with a pending HOST
    // conflict is left out: if the user keeps the remote version the host comes back, and its
    // tunnels must still be there. pending_conflicts.entityType holds the SyncableEntityType
    // name (see PendingConflictRepositoryImpl), so 'HOST'.
    @Query(
        "SELECT t.* FROM tunnels t INNER JOIN hosts h ON h.id = t.hostId " +
            "WHERE t.deleted = 0 AND h.deleted = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM pending_conflicts p " +
            "WHERE p.entityType = 'HOST' AND p.entityId = h.id)"
    )
    suspend fun getLiveTunnelsOfDeletedHosts(): List<TunnelEntity>

    @Update
    suspend fun update(tunnel: TunnelEntity)

    @Delete
    suspend fun delete(tunnel: TunnelEntity)

    @Query("DELETE FROM tunnels WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tunnels SET deleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt, vectorClock = :vectorClock WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long, vectorClock: String)

    @Query("SELECT * FROM tunnels WHERE isFavorite = 1 AND deleted = 0 ORDER BY label ASC")
    fun observeFavorites(): Flow<List<TunnelEntity>>

    // The favorite flag is part of the synced payload: the clock and updatedAt must move with it.
    @Query("UPDATE tunnels SET isFavorite = :isFavorite, vectorClock = :vectorClock, updatedAt = :updatedAt WHERE id = :id AND deleted = 0")
    suspend fun updateFavorite(id: String, isFavorite: Boolean, vectorClock: String, updatedAt: Long)

    // Sync: all rows including soft-deleted.
    @Query("SELECT * FROM tunnels ORDER BY label ASC")
    suspend fun getAllIncludingDeleted(): List<TunnelEntity>

    // Sync: one row by id, tombstones included (getById hides them).
    @Query("SELECT * FROM tunnels WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): TunnelEntity?

    // Insert, or UPDATE in place on primary-key conflict (see HostDao.upsert). No REPLACE insert on this DAO on purpose.
    @Upsert
    suspend fun upsert(tunnel: TunnelEntity)
}
