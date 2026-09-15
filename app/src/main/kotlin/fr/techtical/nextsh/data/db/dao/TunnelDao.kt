// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.techtical.nextsh.data.db.entity.TunnelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TunnelDao {

    @Query("SELECT * FROM tunnels WHERE deleted = 0 ORDER BY label ASC")
    fun observeAll(): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE hostId = :hostId AND deleted = 0 ORDER BY label ASC")
    fun observeByHost(hostId: String): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE autoStart = 1 AND deleted = 0")
    suspend fun getAutoStartTunnels(): List<TunnelEntity>

    @Query("SELECT * FROM tunnels WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): TunnelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tunnel: TunnelEntity)

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

    @Query("UPDATE tunnels SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    // Sync: all rows including soft-deleted.
    @Query("SELECT * FROM tunnels ORDER BY label ASC")
    suspend fun getAllIncludingDeleted(): List<TunnelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tunnel: TunnelEntity)
}
