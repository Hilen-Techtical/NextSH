// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "enrolled_devices")
data class EnrolledDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val platform: String,
    val publicKeyFingerprint: String,
    val tlsCertFingerprint: String?,
    val lastSyncAt: Long?,
    val enrolledAt: Long,
    val lastKnownHost: String? = null,
)
