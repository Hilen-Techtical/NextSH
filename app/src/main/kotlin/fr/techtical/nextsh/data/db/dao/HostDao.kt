// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import fr.techtical.nextsh.data.db.entity.HostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts WHERE deleted = 0 ORDER BY label ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE `group` = :group AND deleted = 0 ORDER BY label ASC")
    fun observeByGroup(group: String): Flow<List<HostEntity>>

    @Query("SELECT DISTINCT `group` FROM hosts WHERE `group` IS NOT NULL AND deleted = 0 ORDER BY `group` ASC")
    fun observeGroups(): Flow<List<String>>

    @Query("SELECT * FROM hosts WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): HostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: HostEntity)

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE hosts SET deleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt, vectorClock = :vectorClock WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long, vectorClock: String)

    @Query("UPDATE hosts SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM hosts WHERE deleted = 0")
    suspend fun count(): Int

    @Query("SELECT * FROM hosts WHERE isFavorite = 1 AND deleted = 0 ORDER BY label ASC")
    fun observeFavorites(): Flow<List<HostEntity>>

    // The favorite flag is part of the synced payload: the clock and updatedAt must move with it.
    @Query("UPDATE hosts SET isFavorite = :isFavorite, vectorClock = :vectorClock, updatedAt = :updatedAt WHERE id = :id AND deleted = 0")
    suspend fun updateFavorite(id: String, isFavorite: Boolean, vectorClock: String, updatedAt: Long)

    // Sync: all rows including soft-deleted.
    @Query("SELECT * FROM hosts ORDER BY label ASC")
    suspend fun getAllIncludingDeleted(): List<HostEntity>

    // Sync: one row by id, tombstones included (getById hides them).
    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): HostEntity?

    /**
     * Insert, or UPDATE in place on primary-key conflict. Never REPLACE a host that may exist:
     * REPLACE is delete + insert and tunnels.hostId is a CASCADE foreign key, so its tunnels
     * would be wiped. [insert] is REPLACE: only safe for a new id.
     */
    @Upsert
    suspend fun upsert(host: HostEntity)
}
