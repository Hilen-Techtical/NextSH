// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_conflicts",
    indices = [Index(value = ["entityType", "entityId"])],
)
data class PendingConflictEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val localJson: String,
    val remoteJson: String,
    val detectedAt: Long,
)
