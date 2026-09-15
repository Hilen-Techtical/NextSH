// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.desktop.db.Tunnels
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopTunnelRepository(db: NextShDatabase) : TunnelRepository {

    private val q = db.tunnelQueries

    override fun observeAll(): Flow<List<TunnelConfig>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> =
        q.selectByHost(hostId).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override suspend fun getById(id: String): TunnelConfig? = withContext(Dispatchers.IO) {
        q.selectById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun getAutoStartTunnels(): List<TunnelConfig> = withContext(Dispatchers.IO) {
        q.selectAutoStart().executeAsList().map(::toDomain)
    }

    override suspend fun save(config: TunnelConfig) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        q.insert(
            id = config.id,
            label = config.label,
            hostId = config.hostId,
            type = config.type.name,
            localPort = config.localPort.toLong(),
            remoteHost = config.remoteHost,
            remotePort = config.remotePort.toLong(),
            autoStart = if (config.autoStart) 1L else 0L,
            openBrowserOnConnect = if (config.openBrowserOnConnect) 1L else 0L,
            keepAliveAfterBrowserClose = if (config.keepAliveAfterBrowserClose) 1L else 0L,
            isFavorite = if (config.isFavorite) 1L else 0L,
            createdAt = now,
            vectorClock = VectorClockCodec.encode(newClock),
            deleted = 0L,
            deletedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun update(config: TunnelConfig) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectById(config.id).executeAsOneOrNull()
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.update(
            label = config.label,
            hostId = config.hostId,
            type = config.type.name,
            localPort = config.localPort.toLong(),
            remoteHost = config.remoteHost,
            remotePort = config.remotePort.toLong(),
            autoStart = if (config.autoStart) 1L else 0L,
            openBrowserOnConnect = if (config.openBrowserOnConnect) 1L else 0L,
            keepAliveAfterBrowserClose = if (config.keepAliveAfterBrowserClose) 1L else 0L,
            isFavorite = if (config.isFavorite) 1L else 0L,
            vectorClock = VectorClockCodec.encode(newClock),
            updatedAt = now,
            id = config.id,
        )
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectAllIncludingDeleted().executeAsList().firstOrNull { it.id == id }
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.markDeleted(deletedAt = now, updatedAt = now, vectorClock = VectorClockCodec.encode(newClock), id = id)
    }

    override fun observeFavorites(): Flow<List<TunnelConfig>> =
        q.selectFavorites().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        q.setFavorite(if (isFavorite) 1L else 0L, id)
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = withContext(Dispatchers.IO) {
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

    override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = withContext(Dispatchers.IO) {
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
                hostId = payload.hostId,
                type = payload.type.name,
                localPort = payload.localPort.toLong(),
                remoteHost = payload.remoteHost,
                remotePort = payload.remotePort.toLong(),
                autoStart = if (payload.autoStart) 1L else 0L,
                openBrowserOnConnect = if (payload.openBrowserOnConnect) 1L else 0L,
                keepAliveAfterBrowserClose = if (payload.keepAliveAfterBrowserClose) 1L else 0L,
                isFavorite = if (payload.isFavorite) 1L else 0L,
                createdAt = System.currentTimeMillis(),
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

    private fun toDomain(row: Tunnels): TunnelConfig = TunnelConfig(
        id = row.id,
        label = row.label,
        hostId = row.hostId,
        type = TunnelType.valueOf(row.type),
        localPort = row.localPort.toInt(),
        remoteHost = row.remoteHost,
        remotePort = row.remotePort.toInt(),
        autoStart = row.autoStart == 1L,
        openBrowserOnConnect = row.openBrowserOnConnect == 1L,
        keepAliveAfterBrowserClose = row.keepAliveAfterBrowserClose == 1L,
        isFavorite = row.isFavorite == 1L,
    )
}
