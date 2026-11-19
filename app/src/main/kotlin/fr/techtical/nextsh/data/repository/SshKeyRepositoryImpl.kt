// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.core.crypto.BiometricKeyAlias
import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.db.dao.SshKeyDao
import fr.techtical.nextsh.data.db.entity.SshKeyEntity
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.core.backup.SshKeyRules
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyRepositoryImpl @Inject constructor(
    private val sshKeyDao: SshKeyDao,
    private val transactionRunner: TransactionRunner,
) : SshKeyRepository {

    override fun observeAll(): Flow<List<SshKey>> =
        sshKeyDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: String): SshKey? =
        sshKeyDao.getById(id)?.toDomain()

    /**
     * [incoming] (built from a backup, an edit or a sync payload) with the rules of the Android
     * Keystore binding applied against the stored row [existing]. Shared by [save], [update] and
     * [upsertSyncEntry] so that no path lets outside data decide what the app trusts in its
     * Keystore.
     *
     * - A live key bound to this device's Keystore (the alias is the one the app generated for
     *   it) keeps that binding, and with it its keyType and publicKey: the pair lives in the
     *   hardware, there is no private key for it in a backup or a payload, and a version that
     *   swapped the public key or the alias would orphan the hardware key or point the key at
     *   another entry. A peer can never do that.
     * - A tombstoned row does not keep its binding: its Keystore entry was destroyed when the
     *   key was deleted.
     * - Otherwise the alias is only taken from [incoming] if it is exactly the alias the app
     *   generates for this key id (see [BiometricKeyAlias]) and [incoming] is biometric. Any
     *   other alias (another key's, the vault master key's...) would let a later delete of this
     *   key remove an unrelated Keystore entry; it is dropped, and the key is not biometric.
     *   The alias and the biometric flag always go together.
     *
     * Everything else, FIDO2 metadata included, is taken from [incoming].
     */
    private fun withKeystoreRules(incoming: SshKeyEntity, existing: SshKeyEntity?): SshKeyEntity {
        val localBinding = existing?.takeIf {
            !it.deleted && it.isBiometric &&
                it.keystoreAlias?.let { alias -> BiometricKeyAlias.matches(alias, it.id) } == true
        }
        // An alias goes with the biometric flag: only a biometric key can carry one, so no row
        // ends up with an alias and isBiometric = false (or the flag without an alias).
        val acceptedAlias = incoming.keystoreAlias
            ?.takeIf { BiometricKeyAlias.matches(it, incoming.id) }
            ?.takeIf { incoming.isBiometric }
        return incoming.copy(
            keyType = localBinding?.keyType ?: incoming.keyType,
            publicKey = localBinding?.publicKey ?: incoming.publicKey,
            isBiometric = localBinding != null || acceptedAlias != null,
            keystoreAlias = localBinding?.keystoreAlias ?: acceptedAlias,
        )
    }

    override suspend fun save(key: SshKey) {
        // An existing id (a backup restore over the same key, tombstone included) is updated in
        // place like hosts and tunnels: createdAt stays, the row is revived and its clock is
        // ticked from the stored one. A new id gets a fresh clock.
        val now = System.currentTimeMillis()
        val existing = sshKeyDao.getByIdIncludingDeleted(key.id)
        val fresh = SshKeyEntity.fromDomain(key)
        val merged = withKeystoreRules(fresh, existing).copy(
            createdAt = existing?.createdAt ?: fresh.createdAt,
        )
        // A live row that already holds exactly this content is left alone (no clock tick for
        // nothing: it would make every peer pull the row again and could raise false conflicts).
        if (existing != null && !existing.deleted && merged.toDomain() == existing.toDomain()) return
        sshKeyDao.upsert(
            merged.copy(
                vectorClock = tickedClockJson(existing?.vectorClock ?: "{}", now),
                updatedAt = now,
                deleted = false,
                deletedAt = null,
            )
        )
    }

    override suspend fun update(key: SshKey) {
        // Same as HostRepositoryImpl.update: getById hides tombstones, so an edit never revives a
        // key deleted meanwhile and never creates a missing one, it writes nothing. The read and
        // the write are one transaction, so a sync tombstone cannot land between them and be
        // overwritten. createdAt is local-only (not in the domain model): carry it over.
        transactionRunner.run {
            val existing = sshKeyDao.getById(key.id) ?: return@run
            val now = System.currentTimeMillis()
            sshKeyDao.update(
                withKeystoreRules(SshKeyEntity.fromDomain(key), existing).copy(
                    createdAt = existing.createdAt,
                    vectorClock = tickedClockJson(existing.vectorClock, now),
                    updatedAt = now,
                    deleted = false,
                    deletedAt = null,
                )
            )
        }
    }

    override suspend fun delete(id: String) {
        // Same guard as HostRepositoryImpl.delete: a key already deleted (or unknown) keeps its
        // tombstone untouched instead of getting a clock restarted from "{}".
        val existing = sshKeyDao.getById(id) ?: return
        val now = System.currentTimeMillis()
        sshKeyDao.softDelete(id, now, now, tickedClockJson(existing.vectorClock, now))
    }

    // Sync primitives

    override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> =
        sshKeyDao.getAllIncludingDeleted().map { entity ->
            SyncEntry(
                id = entity.id,
                payload = if (entity.deleted) null else entity.toDomain(),
                clock = VectorClockCodec.decode(entity.vectorClock),
                deleted = entity.deleted,
                deletedAt = entity.deletedAt,
                updatedAt = entity.updatedAt,
            )
        }

    override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) {
        // One row by id, tombstones included (not a scan of the whole table).
        val existing = sshKeyDao.getByIdIncludingDeleted(entry.id)
        val payload = entry.payload
        if (payload == null) {
            if (existing != null) {
                sshKeyDao.upsert(
                    existing.copy(
                        deleted = true,
                        deletedAt = entry.deletedAt,
                        updatedAt = entry.updatedAt,
                        vectorClock = VectorClockCodec.encode(entry.clock),
                    )
                )
            }
        } else {
            // Same rule as a backup restore for a row that comes from outside: the public key line is
            // cleaned (one line, 64 KiB at most), a key with an invalid id, alias or FIDO2 field is
            // dropped. Fixed log text: nothing of the payload is logged.
            val normalized = SshKeyRules.normalizeForStorage(payload)
            if (normalized == null) {
                Timber.w("Sync: SSH key entry dropped (invalid fields)")
                return
            }
            // The remote payload cannot decide what this device trusts in its Keystore:
            // see withKeystoreRules. createdAt is local-only and stays.
            val fresh = SshKeyEntity.fromDomain(normalized)
            sshKeyDao.upsert(
                withKeystoreRules(fresh, existing).copy(
                    createdAt = existing?.createdAt ?: fresh.createdAt,
                    vectorClock = VectorClockCodec.encode(entry.clock),
                    deleted = entry.deleted,
                    deletedAt = entry.deletedAt,
                    updatedAt = entry.updatedAt,
                )
            )
        }
    }

    override suspend fun hardDelete(id: String) {
        sshKeyDao.deleteById(id)
    }
}
