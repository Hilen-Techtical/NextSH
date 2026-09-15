// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.Custom_terminal_themes
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopCustomTerminalThemeRepository(db: NextShDatabase) : CustomTerminalThemeRepository {

    private val q = db.customTerminalThemeQueries

    override fun observeAll(): Flow<List<CustomTerminalTheme>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override suspend fun getById(id: String): CustomTerminalTheme? = withContext(Dispatchers.IO) {
        q.selectById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun save(theme: CustomTerminalTheme) = withContext(Dispatchers.IO) {
        val sanitized = theme.sanitized()
        val now = System.currentTimeMillis()
        val existing = q.selectAllIncludingDeleted().executeAsList().firstOrNull { it.id == sanitized.id }
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.insert(
            id = sanitized.id,
            name = sanitized.name,
            background = sanitized.background.toLong(),
            foreground = sanitized.foreground.toLong(),
            cursor = sanitized.cursor.toLong(),
            selectionBg = sanitized.selectionBg.toLong(),
            ansi = encodeAnsi(sanitized.ansi),
            createdAt = sanitized.createdAt,
            vectorClock = VectorClockCodec.encode(newClock),
            deleted = 0L,
            deletedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectAllIncludingDeleted().executeAsList().firstOrNull { it.id == id }
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.markDeleted(deletedAt = now, updatedAt = now, vectorClock = VectorClockCodec.encode(newClock), id = id)
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<CustomTerminalTheme>> = withContext(Dispatchers.IO) {
        q.selectAllIncludingDeleted().executeAsList().map { row ->
            SyncEntry(
                id = row.id,
                payload = if (row.deleted == 1L) null else toDomain(row),
                clock = VectorClockCodec.decode(row.vectorClock),
                deleted = row.deleted == 1L,
                deletedAt = row.deletedAt,
                updatedAt = row.updatedAt,
            )
        }
    }

    override suspend fun upsertSyncEntry(entry: SyncEntry<CustomTerminalTheme>) = withContext(Dispatchers.IO) {
        val payload = entry.payload
        if (payload == null) {
            val existing = q.selectAllIncludingDeleted().executeAsList().firstOrNull { it.id == entry.id }
            if (existing != null) {
                q.markDeleted(
                    deletedAt = entry.deletedAt ?: System.currentTimeMillis(),
                    updatedAt = entry.updatedAt,
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    id = entry.id,
                )
            }
        } else {
            val sanitized = payload.sanitized()
            q.insert(
                id = sanitized.id,
                name = sanitized.name,
                background = sanitized.background.toLong(),
                foreground = sanitized.foreground.toLong(),
                cursor = sanitized.cursor.toLong(),
                selectionBg = sanitized.selectionBg.toLong(),
                ansi = encodeAnsi(sanitized.ansi),
                createdAt = sanitized.createdAt,
                vectorClock = VectorClockCodec.encode(entry.clock),
                deleted = if (entry.deleted) 1L else 0L,
                deletedAt = entry.deletedAt,
                updatedAt = entry.updatedAt,
            )
        }
    }

    override suspend fun hardDelete(id: String) = withContext(Dispatchers.IO) {
        q.delete(id)
    }

    private fun toDomain(row: Custom_terminal_themes): CustomTerminalTheme = CustomTerminalTheme(
        id = row.id,
        name = row.name,
        background = row.background.toInt(),
        foreground = row.foreground.toInt(),
        cursor = row.cursor.toInt(),
        selectionBg = row.selectionBg.toInt(),
        ansi = decodeAnsi(row.ansi),
        createdAt = row.createdAt,
    )

    companion object {
        /** Joins the 16 ANSI ints into a CSV string (mirrors the Android entity). */
        fun encodeAnsi(ansi: List<Int>): String = ansi.joinToString(",")

        /** Parses CSV back into exactly 16 ints, padding with opaque black as needed. */
        fun decodeAnsi(csv: String): List<Int> {
            val parsed = csv.split(",").mapNotNull { it.trim().toIntOrNull() }
            val padded = parsed.take(CustomTerminalTheme.ANSI_SIZE).toMutableList()
            while (padded.size < CustomTerminalTheme.ANSI_SIZE) padded.add(0xFF000000.toInt())
            return padded
        }
    }
}

/** Guarantees an exactly-16-entry ANSI palette before persistence. */
private fun CustomTerminalTheme.sanitized(): CustomTerminalTheme {
    if (ansi.size == CustomTerminalTheme.ANSI_SIZE) return this
    val fixed = ansi.take(CustomTerminalTheme.ANSI_SIZE).toMutableList()
    while (fixed.size < CustomTerminalTheme.ANSI_SIZE) fixed.add(0xFF000000.toInt())
    return copy(ansi = fixed)
}
