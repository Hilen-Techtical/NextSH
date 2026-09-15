// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.desktop.db.Snippets
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopSnippetRepository(db: NextShDatabase) : SnippetRepository {

    private val q = db.snippetQueries

    override fun observeAll(): Flow<List<Snippet>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override fun observeForHost(hostId: String): Flow<List<Snippet>> =
        q.selectForHost(hostId).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override fun observeCategories(): Flow<List<String>> =
        q.selectCategories().asFlow().mapToList(Dispatchers.IO)

    override suspend fun getById(id: String): Snippet? = withContext(Dispatchers.IO) {
        q.selectById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun save(snippet: Snippet) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        q.insert(
            id = snippet.id,
            label = snippet.label,
            command = snippet.command,
            category = snippet.category,
            hostId = snippet.hostId,
            createdAt = snippet.createdAt,
            vectorClock = VectorClockCodec.encode(newClock),
            deleted = 0L,
            deletedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun update(snippet: Snippet) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectById(snippet.id).executeAsOneOrNull()
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.update(
            label = snippet.label,
            command = snippet.command,
            category = snippet.category,
            hostId = snippet.hostId,
            vectorClock = VectorClockCodec.encode(newClock),
            updatedAt = now,
            id = snippet.id,
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

    override suspend fun getAllSyncEntries(): List<SyncEntry<Snippet>> = withContext(Dispatchers.IO) {
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

    override suspend fun upsertSyncEntry(entry: SyncEntry<Snippet>) = withContext(Dispatchers.IO) {
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
            q.insert(
                id = payload.id,
                label = payload.label,
                command = payload.command,
                category = payload.category,
                hostId = payload.hostId,
                createdAt = payload.createdAt,
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

    private fun toDomain(row: Snippets): Snippet = Snippet(
        id = row.id,
        label = row.label,
        command = row.command,
        category = row.category,
        hostId = row.hostId,
        createdAt = row.createdAt,
    )
}
