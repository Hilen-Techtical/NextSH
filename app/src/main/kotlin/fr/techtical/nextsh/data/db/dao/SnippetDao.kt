// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.techtical.nextsh.data.db.entity.SnippetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SnippetDao {

    @Query("SELECT * FROM snippets WHERE deleted = 0 ORDER BY label ASC")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE (hostId IS NULL OR hostId = :hostId) AND deleted = 0 ORDER BY label ASC")
    fun observeForHost(hostId: String): Flow<List<SnippetEntity>>

    @Query("SELECT DISTINCT category FROM snippets WHERE category IS NOT NULL AND deleted = 0 ORDER BY category ASC")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT * FROM snippets WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): SnippetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snippet: SnippetEntity)

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE snippets SET deleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt, vectorClock = :vectorClock WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long, vectorClock: String)

    // Sync: all rows including soft-deleted.
    @Query("SELECT * FROM snippets ORDER BY label ASC")
    suspend fun getAllIncludingDeleted(): List<SnippetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snippet: SnippetEntity)
}
