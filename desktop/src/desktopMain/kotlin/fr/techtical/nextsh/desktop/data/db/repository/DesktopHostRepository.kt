// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.Hosts
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopHostRepository(db: NextShDatabase) : HostRepository {

    private val q = db.hostQueries

    override fun observeAll(): Flow<List<Host>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override fun observeByGroup(group: String): Flow<List<Host>> =
        q.selectByGroup(group).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override fun observeGroups(): Flow<List<String>> =
        q.selectGroups().asFlow().mapToList(Dispatchers.IO)

    override suspend fun getById(id: String): Host? = withContext(Dispatchers.IO) {
        q.selectById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun save(host: Host) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        q.insert(
            id = host.id,
            label = host.label,
            hostname = host.hostname,
            port = host.port.toLong(),
            username = host.username,
            authType = host.authType.name,
            credentialId = host.credentialId,
            group = host.group,
            keepAliveSeconds = host.keepAliveSeconds.toLong(),
            autoReconnect = if (host.autoReconnect) 1L else 0L,
            terminalTheme = host.terminalTheme,
            fido2Mode = host.fido2Mode?.name,
            isFavorite = if (host.isFavorite) 1L else 0L,
            createdAt = now,
            lastConnectedAt = null,
            vectorClock = VectorClockCodec.encode(newClock),
            deleted = 0L,
            deletedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun update(host: Host) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectById(host.id).executeAsOneOrNull()
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.update(
            label = host.label,
            hostname = host.hostname,
            port = host.port.toLong(),
            username = host.username,
            authType = host.authType.name,
            credentialId = host.credentialId,
            group = host.group,
            keepAliveSeconds = host.keepAliveSeconds.toLong(),
            autoReconnect = if (host.autoReconnect) 1L else 0L,
            terminalTheme = host.terminalTheme,
            fido2Mode = host.fido2Mode?.name,
            isFavorite = if (host.isFavorite) 1L else 0L,
            vectorClock = VectorClockCodec.encode(newClock),
            updatedAt = now,
            id = host.id,
        )
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectAllIncludingDeleted().executeAsList().firstOrNull { it.id == id }
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.markDeleted(deletedAt = now, updatedAt = now, vectorClock = VectorClockCodec.encode(newClock), id = id)
    }

    override suspend fun updateLastConnected(id: String) = withContext(Dispatchers.IO) {
        q.updateLastConnected(System.currentTimeMillis(), id)
    }

    override fun observeFavorites(): Flow<List<Host>> =
        q.selectFavorites().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        q.setFavorite(if (isFavorite) 1L else 0L, id)
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = withContext(Dispatchers.IO) {
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

    override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = withContext(Dispatchers.IO) {
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
                hostname = payload.hostname,
                port = payload.port.toLong(),
                username = payload.username,
                authType = payload.authType.name,
                credentialId = payload.credentialId,
                group = payload.group,
                keepAliveSeconds = payload.keepAliveSeconds.toLong(),
                autoReconnect = if (payload.autoReconnect) 1L else 0L,
                terminalTheme = payload.terminalTheme,
                fido2Mode = payload.fido2Mode?.name,
                isFavorite = if (payload.isFavorite) 1L else 0L,
                createdAt = System.currentTimeMillis(),
                lastConnectedAt = null,
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

    private fun toDomain(row: Hosts): Host = Host(
        id = row.id,
        label = row.label,
        hostname = row.hostname,
        port = row.port.toInt(),
        username = row.username,
        authType = AuthType.valueOf(row.authType),
        credentialId = row.credentialId,
        group = row.group,
        keepAliveSeconds = row.keepAliveSeconds.toInt(),
        autoReconnect = row.autoReconnect == 1L,
        terminalTheme = row.terminalTheme,
        fido2Mode = row.fido2Mode?.let { Fido2Mode.valueOf(it) },
        isFavorite = row.isFavorite == 1L,
    )
}
