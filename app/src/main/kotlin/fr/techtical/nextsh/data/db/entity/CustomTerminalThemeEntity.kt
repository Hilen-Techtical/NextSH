// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.techtical.nextsh.domain.model.CustomTerminalTheme

/**
 * Room persistence for a user-defined terminal theme. CRDT-synced like
 * [SnippetEntity], carrying the same four sync columns.
 *
 * [ansi] is stored as a CSV of exactly 16 signed ARGB ints. Conversion is
 * defensive: a malformed/short list is padded with black so the renderer can
 * never index out of bounds.
 */
@Entity(tableName = "custom_terminal_themes")
data class CustomTerminalThemeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selectionBg: Int,
    val ansi: String,
    val createdAt: Long,
    val vectorClock: String = "{}",
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    fun toDomain(): CustomTerminalTheme = CustomTerminalTheme(
        id = id,
        name = name,
        background = background,
        foreground = foreground,
        cursor = cursor,
        selectionBg = selectionBg,
        ansi = decodeAnsi(ansi),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(t: CustomTerminalTheme) = CustomTerminalThemeEntity(
            id = t.id,
            name = t.name,
            background = t.background,
            foreground = t.foreground,
            cursor = t.cursor,
            selectionBg = t.selectionBg,
            ansi = encodeAnsi(t.ansi),
            createdAt = t.createdAt,
        )

        /** Joins the 16 ANSI ints into a CSV string. */
        fun encodeAnsi(ansi: List<Int>): String = ansi.joinToString(",")

        /**
         * Parses the CSV back into exactly 16 ints. Pads with 0xFF000000 (opaque
         * black) and truncates so callers always receive a 16-entry list.
         */
        fun decodeAnsi(csv: String): List<Int> {
            val parsed = csv.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
            val padded = parsed.take(CustomTerminalTheme.ANSI_SIZE).toMutableList()
            while (padded.size < CustomTerminalTheme.ANSI_SIZE) padded.add(0xFF000000.toInt())
            return padded
        }
    }
}
