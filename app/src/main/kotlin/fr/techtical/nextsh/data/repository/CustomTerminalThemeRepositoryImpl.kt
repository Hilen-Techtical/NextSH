// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.CustomTerminalThemeDao
import fr.techtical.nextsh.data.db.entity.CustomTerminalThemeEntity
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomTerminalThemeRepositoryImpl @Inject constructor(
    private val dao: CustomTerminalThemeDao,
) : CustomTerminalThemeRepository {

    override fun observeAll(): Flow<List<CustomTerminalTheme>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): CustomTerminalTheme? =
        dao.getById(id)?.toDomain()

    override suspend fun save(theme: CustomTerminalTheme) {
        val now = System.currentTimeMillis()
        val existing = dao.getById(theme.id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        dao.insert(
            CustomTerminalThemeEntity.fromDomain(theme.sanitized()).copy(
                vectorClock = VectorClockCodec.encode(newClock),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun delete(id: String) {
        val now = System.currentTimeMillis()
        val existing = dao.getById(id)
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        dao.softDelete(id, now, now, VectorClockCodec.encode(newClock))
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<CustomTerminalTheme>> =
        dao.getAllIncludingDeleted().map { entity ->
            SyncEntry(
                id = entity.id,
                payload = if (entity.deleted) null else entity.toDomain(),
                clock = VectorClockCodec.decode(entity.vectorClock),
                deleted = entity.deleted,
                deletedAt = entity.deletedAt,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun upsertSyncEntry(entry: SyncEntry<CustomTerminalTheme>) {
        val payload = entry.payload
        if (payload == null) {
            val existing = dao.getAllIncludingDeleted().firstOrNull { it.id == entry.id }
            if (existing != null) {
                dao.upsert(
                    existing.copy(
                        deleted = true,
                        deletedAt = entry.deletedAt,
                        updatedAt = entry.updatedAt,
                        vectorClock = VectorClockCodec.encode(entry.clock),
                    )
                )
            }
        } else {
            dao.upsert(
                CustomTerminalThemeEntity.fromDomain(payload.sanitized()).copy(
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    deleted = entry.deleted,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                )
            )
        }
    }

    override suspend fun hardDelete(id: String) {
        dao.deleteById(id)
    }
}

/**
 * Guarantees the ANSI palette has exactly 16 entries before persistence, so the
 * renderer can never index out of bounds, including data arriving via sync from
 * a misbehaving peer. Short lists are padded with opaque black; overlong lists
 * are truncated.
 */
private fun CustomTerminalTheme.sanitized(): CustomTerminalTheme {
    if (ansi.size == CustomTerminalTheme.ANSI_SIZE) return this
    val fixed = ansi.take(CustomTerminalTheme.ANSI_SIZE).toMutableList()
    while (fixed.size < CustomTerminalTheme.ANSI_SIZE) fixed.add(0xFF000000.toInt())
    return copy(ansi = fixed)
}
