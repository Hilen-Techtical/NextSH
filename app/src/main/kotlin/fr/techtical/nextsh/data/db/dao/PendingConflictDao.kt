// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.techtical.nextsh.data.db.entity.PendingConflictEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingConflictDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conflict: PendingConflictEntity)

    @Query("SELECT * FROM pending_conflicts ORDER BY detectedAt DESC")
    suspend fun getAll(): List<PendingConflictEntity>

    @Query("SELECT * FROM pending_conflicts ORDER BY detectedAt DESC")
    fun observeAll(): Flow<List<PendingConflictEntity>>

    @Query("SELECT COUNT(*) FROM pending_conflicts")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_conflicts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_conflicts WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: String)

    @Query("DELETE FROM pending_conflicts")
    suspend fun deleteAll()
}
