// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import fr.techtical.nextsh.data.db.entity.SshKeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {

    @Query("SELECT * FROM ssh_keys WHERE deleted = 0 ORDER BY label ASC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): SshKeyEntity?

    @Query("SELECT * FROM ssh_keys WHERE keystoreAlias = :alias AND deleted = 0")
    suspend fun getByKeystoreAlias(alias: String): SshKeyEntity?

    // One row by id, tombstones included (getById hides them).
    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): SshKeyEntity?

    @Update
    suspend fun update(key: SshKeyEntity)

    @Delete
    suspend fun delete(key: SshKeyEntity)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE ssh_keys SET deleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt, vectorClock = :vectorClock WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long, updatedAt: Long, vectorClock: String)

    // Sync: all rows including soft-deleted.
    @Query("SELECT * FROM ssh_keys ORDER BY label ASC")
    suspend fun getAllIncludingDeleted(): List<SshKeyEntity>

    // Insert, or UPDATE in place on primary-key conflict (see HostDao.upsert). No REPLACE insert on this DAO on purpose.
    @Upsert
    suspend fun upsert(key: SshKeyEntity)
}
