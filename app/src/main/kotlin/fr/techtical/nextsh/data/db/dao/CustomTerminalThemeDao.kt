// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.techtical.nextsh.data.db.entity.CustomTerminalThemeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomTerminalThemeDao {

    @Query("SELECT * FROM custom_terminal_themes WHERE deleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<CustomTerminalThemeEntity>>

    @Query("SELECT * FROM custom_terminal_themes WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): CustomTerminalThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(theme: CustomTerminalThemeEntity)

    @Query("DELETE FROM custom_terminal_themes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE custom_terminal_themes SET deleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt, vectorClock = :vectorClock WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long, vectorClock: String)

    // Sync: all rows including soft-deleted.
    @Query("SELECT * FROM custom_terminal_themes ORDER BY name ASC")
    suspend fun getAllIncludingDeleted(): List<CustomTerminalThemeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(theme: CustomTerminalThemeEntity)
}
