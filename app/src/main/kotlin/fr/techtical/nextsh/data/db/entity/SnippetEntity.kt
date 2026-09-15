// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.techtical.nextsh.domain.model.Snippet

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey val id: String,
    val label: String,
    val command: String,
    val category: String?,
    val hostId: String?,
    val createdAt: Long,
    val vectorClock: String = "{}",
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    fun toDomain(): Snippet = Snippet(id, label, command, category, hostId, createdAt)

    companion object {
        fun fromDomain(s: Snippet) = SnippetEntity(s.id, s.label, s.command, s.category, s.hostId, s.createdAt)
    }
}
