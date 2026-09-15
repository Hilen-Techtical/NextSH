// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.techtical.nextsh.data.db.entity.EnrolledDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EnrolledDeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: EnrolledDeviceEntity)

    @Query("DELETE FROM enrolled_devices WHERE deviceId = :deviceId")
    suspend fun deleteByDeviceId(deviceId: String)

    @Query("SELECT * FROM enrolled_devices ORDER BY enrolledAt DESC")
    fun observeAll(): Flow<List<EnrolledDeviceEntity>>

    @Query("SELECT * FROM enrolled_devices ORDER BY enrolledAt DESC")
    suspend fun getAll(): List<EnrolledDeviceEntity>

    @Query("SELECT * FROM enrolled_devices WHERE deviceId = :deviceId")
    suspend fun getByDeviceId(deviceId: String): EnrolledDeviceEntity?

    @Query("UPDATE enrolled_devices SET lastSyncAt = :timestamp WHERE deviceId = :deviceId")
    suspend fun updateLastSyncAt(deviceId: String, timestamp: Long)

    @Query("UPDATE enrolled_devices SET lastKnownHost = :host WHERE deviceId = :deviceId")
    suspend fun updateLastKnownHost(deviceId: String, host: String)
}
