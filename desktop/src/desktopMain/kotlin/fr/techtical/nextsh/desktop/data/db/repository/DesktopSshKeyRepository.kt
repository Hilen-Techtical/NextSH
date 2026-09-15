// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.desktop.db.Ssh_keys
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopSshKeyRepository(db: NextShDatabase) : SshKeyRepository {

    private val q = db.sshKeyQueries

    override fun observeAll(): Flow<List<SshKey>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toDomain) }

    override suspend fun getById(id: String): SshKey? = withContext(Dispatchers.IO) {
        q.selectById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun save(key: SshKey) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val newClock = VectorClockCodec.decode("{}").tick(DeviceIdentity.deviceId(), now)
        q.insert(
            id = key.id,
            label = key.label,
            keyType = key.keyType.name,
            publicKey = key.publicKey,
            isBiometric = if (key.isBiometric) 1L else 0L,
            keystoreAlias = key.keystoreAlias,
            fido2CredentialId = key.fido2CredentialId,
            fido2RpId = key.fido2RpId,
            createdAt = now,
            vectorClock = VectorClockCodec.encode(newClock),
            deleted = 0L,
            deletedAt = null,
            updatedAt = now,
        )
    }

    override suspend fun update(key: SshKey) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = q.selectById(key.id).executeAsOneOrNull()
        val prevClock = existing?.vectorClock ?: "{}"
        val newClock = VectorClockCodec.decode(prevClock).tick(DeviceIdentity.deviceId(), now)
        q.update(
            label = key.label,
            keyType = key.keyType.name,
            publicKey = key.publicKey,
            isBiometric = if (key.isBiometric) 1L else 0L,
            keystoreAlias = key.keystoreAlias,
            fido2CredentialId = key.fido2CredentialId,
            fido2RpId = key.fido2RpId,
            vectorClock = VectorClockCodec.encode(newClock),
            updatedAt = now,
            id = key.id,
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

    override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> = withContext(Dispatchers.IO) {
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

    override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) = withContext(Dispatchers.IO) {
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
                keyType = payload.keyType.name,
                publicKey = payload.publicKey,
                isBiometric = if (payload.isBiometric) 1L else 0L,
                keystoreAlias = payload.keystoreAlias,
                fido2CredentialId = payload.fido2CredentialId,
                fido2RpId = payload.fido2RpId,
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

    private fun toDomain(row: Ssh_keys): SshKey = SshKey(
        id = row.id,
        label = row.label,
        keyType = SshKeyType.valueOf(row.keyType),
        publicKey = row.publicKey,
        isBiometric = row.isBiometric == 1L,
        keystoreAlias = row.keystoreAlias,
        fido2CredentialId = row.fido2CredentialId,
        fido2RpId = row.fido2RpId,
    )
}
