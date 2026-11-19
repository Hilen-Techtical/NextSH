// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.core.crypto.BiometricKeyAlias
import fr.techtical.nextsh.data.db.FakeTransactionRunner
import fr.techtical.nextsh.data.db.dao.SshKeyDao
import fr.techtical.nextsh.data.db.entity.SshKeyEntity
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Save semantics of [SshKeyRepositoryImpl]: an existing id (a backup restore over the same key)
 * is updated in place and never REPLACEd, with one exception worth a test of its own, the binding
 * to this device's Android Keystore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class SshKeyRepositoryImplTest {

    @MockK
    private lateinit var sshKeyDao: SshKeyDao

    private val transactionRunner = FakeTransactionRunner()

    private lateinit var repository: SshKeyRepositoryImpl

    private val keyId = "0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val ownAlias = BiometricKeyAlias.forKeyId(keyId)

    @BeforeEach
    fun setUp() {
        repository = SshKeyRepositoryImpl(sshKeyDao, transactionRunner)
    }

    private fun entity(
        clock: VectorClock = VectorClock.EMPTY,
        createdAt: Long = 111L,
        isBiometric: Boolean = false,
        keystoreAlias: String? = null,
        deleted: Boolean = false,
        deletedAt: Long? = null,
    ) = SshKeyEntity(
        id = keyId,
        label = "Local label",
        keyType = "ED25519",
        publicKey = "ssh-ed25519 LOCAL",
        isBiometric = isBiometric,
        keystoreAlias = keystoreAlias,
        createdAt = createdAt,
        vectorClock = VectorClockCodec.encode(clock),
        deleted = deleted,
        deletedAt = deletedAt,
        updatedAt = 1_000L,
    )

    private fun backupKey(
        isBiometric: Boolean = false,
        keystoreAlias: String? = null,
        fido2CredentialId: String? = null,
        fido2RpId: String? = null,
    ) = SshKey(
        id = keyId,
        label = "Backup label",
        keyType = SshKeyType.ECDSA_256,
        publicKey = "ecdsa-sha2-nistp256 BACKUP",
        isBiometric = isBiometric,
        keystoreAlias = keystoreAlias,
        fido2CredentialId = fido2CredentialId,
        fido2RpId = fido2RpId,
    )

    @Test
    fun `save of a new id goes through upsert with a fresh clock, never insert`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns null
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.save(backupKey(isBiometric = true, keystoreAlias = ownAlias))
        val after = System.currentTimeMillis()

        val saved = written.captured
        assertEquals("Backup label", saved.label)
        // A brand new row takes the key exactly as given (generation of a biometric key goes here).
        assertTrue(saved.isBiometric)
        assertEquals(ownAlias, saved.keystoreAlias)
        assertTrue(saved.updatedAt in before..after)
        assertTrue(saved.createdAt in before..after)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        val clock = VectorClockCodec.decode(saved.vectorClock)
        assertEquals(setOf(DeviceIdentity.deviceId()), clock.entries.keys)
    }

    @Test
    fun `save over an existing key updates in place, keeps createdAt, ticks the stored clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns entity(clock = previous, createdAt = 111L)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.save(backupKey(fido2CredentialId = "cred", fido2RpId = "ssh:"))

        coVerify(exactly = 0) { sshKeyDao.deleteById(any()) }
        coVerify(exactly = 0) { sshKeyDao.delete(any()) }
        val saved = written.captured
        assertEquals("Backup label", saved.label)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", saved.publicKey)
        assertEquals("cred", saved.fido2CredentialId)
        assertEquals("ssh:", saved.fido2RpId)
        assertEquals(111L, saved.createdAt)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `save over a tombstoned key revives it in place and ticks the tombstone clock`() = runTest {
        val previous = VectorClock(mapOf("desktop-device" to 9L))
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns
            entity(clock = previous, createdAt = 111L, deleted = true, deletedAt = 900L)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.save(backupKey())

        val saved = written.captured
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        assertEquals(111L, saved.createdAt)
        assertTrue(VectorClockCodec.decode(saved.vectorClock).dominates(previous))
    }

    @Test
    fun `save keeps the local Keystore binding even when the backup says the key is not biometric`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns
            entity(isBiometric = true, keystoreAlias = ownAlias)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.save(backupKey(isBiometric = false, keystoreAlias = null))

        val saved = written.captured
        assertTrue(saved.isBiometric, "the hardware key lives only on this device")
        assertEquals(ownAlias, saved.keystoreAlias)
        // The rest of the metadata still comes from the backup.
        assertEquals("Backup label", saved.label)
    }

    @Test
    fun `save keeps the local Keystore alias over a different alias carried by the backup`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns
            entity(isBiometric = true, keystoreAlias = ownAlias)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.save(backupKey(isBiometric = true, keystoreAlias = "alias-of-another-device"))

        assertEquals(ownAlias, written.captured.keystoreAlias)
    }

    @Test
    fun `save over a key without local Keystore binding takes the backup values`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns entity(isBiometric = false, keystoreAlias = null)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.save(backupKey(isBiometric = true, keystoreAlias = ownAlias))

        assertTrue(written.captured.isBiometric)
        assertEquals(ownAlias, written.captured.keystoreAlias)
    }

    // delete: a key already deleted (or unknown) keeps its tombstone untouched.

    @Test
    fun `delete tombstones a live key with a ticked clock`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { sshKeyDao.getById(keyId) } returns entity(clock = previous)
        val deletedAt = slot<Long>()
        val updatedAt = slot<Long>()
        val clockJson = slot<String>()
        coEvery {
            sshKeyDao.softDelete(keyId, capture(deletedAt), capture(updatedAt), capture(clockJson))
        } returns Unit

        val before = System.currentTimeMillis()
        repository.delete(keyId)
        val after = System.currentTimeMillis()

        assertTrue(deletedAt.captured in before..after)
        assertEquals(deletedAt.captured, updatedAt.captured)
        val newClock = VectorClockCodec.decode(clockJson.captured)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `delete of an unknown or already deleted key writes nothing`() = runTest {
        // SshKeyDao.getById filters deleted = 0: a tombstone and an unknown id both come back null.
        coEvery { sshKeyDao.getById("gone") } returns null

        repository.delete("gone")

        coVerify(exactly = 0) { sshKeyDao.softDelete(any(), any(), any(), any()) }
    }

    // save: restoring a key that is already stored with the same content is a no-op.

    @Test
    fun `save of a live key that already holds this content writes nothing`() = runTest {
        val same = backupKey(fido2CredentialId = "cred", fido2RpId = "ssh:")
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns SshKeyEntity.fromDomain(same).copy(
            createdAt = 111L,
            vectorClock = VectorClockCodec.encode(VectorClock(mapOf("desktop-device" to 9L))),
            updatedAt = 1_000L,
        )

        repository.save(same)

        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    @Test
    fun `save that only differs by a Keystore binding the local row keeps writes nothing`() = runTest {
        // The local binding wins, so the resulting row equals the stored one: no clock tick.
        val stored = SshKeyEntity.fromDomain(backupKey(isBiometric = true, keystoreAlias = ownAlias))
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns stored

        repository.save(backupKey(isBiometric = false, keystoreAlias = null))

        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    @Test
    fun `save of a live key that differs by one field writes`() = runTest {
        val same = backupKey()
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns
            SshKeyEntity.fromDomain(same.copy(label = "Old label"))
        coEvery { sshKeyDao.upsert(any()) } returns Unit

        repository.save(same)

        coVerify(exactly = 1) { sshKeyDao.upsert(match { it.label == "Backup label" }) }
    }

    @Test
    fun `save of a tombstoned key with the same content still revives it`() = runTest {
        val same = backupKey()
        val tombstoneClock = VectorClock(mapOf("desktop-device" to 9L))
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns SshKeyEntity.fromDomain(same).copy(
            vectorClock = VectorClockCodec.encode(tombstoneClock),
            deleted = true,
            deletedAt = 900L,
        )
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.save(same)

        assertFalse(written.captured.deleted)
        assertNull(written.captured.deletedAt)
        assertTrue(VectorClockCodec.decode(written.captured.vectorClock).dominates(tombstoneClock))
    }

    // update: aligned with the other repositories (one transaction, no revive, createdAt kept).

    @Test
    fun `update keeps createdAt, ticks the stored clock and replaces the metadata`() = runTest {
        val previous = VectorClock(mapOf(DeviceIdentity.deviceId() to 5L, "other-device" to 7L))
        coEvery { sshKeyDao.getById(keyId) } returns entity(clock = previous, createdAt = 111L)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.update(capture(written)) } returns Unit

        val before = System.currentTimeMillis()
        repository.update(backupKey(fido2CredentialId = "cred", fido2RpId = "ssh:"))
        val after = System.currentTimeMillis()

        val saved = written.captured
        assertEquals("Backup label", saved.label)
        assertEquals("cred", saved.fido2CredentialId)
        assertEquals(111L, saved.createdAt)
        assertTrue(saved.updatedAt in before..after)
        assertFalse(saved.deleted)
        assertNull(saved.deletedAt)
        val newClock = VectorClockCodec.decode(saved.vectorClock)
        assertTrue(newClock.dominates(previous))
        assertEquals(7L, newClock.entries["other-device"])
    }

    @Test
    fun `update of a deleted or unknown key writes nothing`() = runTest {
        // SshKeyDao.getById filters deleted = 0: a tombstone and an unknown id both come back null.
        coEvery { sshKeyDao.getById(keyId) } returns null

        repository.update(backupKey())

        coVerify(exactly = 0) { sshKeyDao.update(any()) }
        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    @Test
    fun `update reads and writes inside one transaction`() = runTest {
        val insideTransaction = mutableListOf<Boolean>()
        coEvery { sshKeyDao.getById(keyId) } coAnswers {
            insideTransaction += transactionRunner.active
            entity()
        }
        coEvery { sshKeyDao.update(any()) } coAnswers { insideTransaction += transactionRunner.active }

        repository.update(backupKey())

        assertEquals(1, transactionRunner.runs)
        assertEquals(listOf(true, true), insideTransaction)
    }

    @Test
    fun `update of a deleted key still runs its check inside the transaction`() = runTest {
        coEvery { sshKeyDao.getById(keyId) } coAnswers {
            assertTrue(transactionRunner.active)
            null
        }

        repository.update(backupKey())

        assertEquals(1, transactionRunner.runs)
        coVerify(exactly = 0) { sshKeyDao.update(any()) }
    }

    @Test
    fun `update keeps the local Keystore binding of the stored key`() = runTest {
        coEvery { sshKeyDao.getById(keyId) } returns entity(isBiometric = true, keystoreAlias = ownAlias)
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.update(capture(written)) } returns Unit

        repository.update(backupKey(isBiometric = false, keystoreAlias = null))

        assertTrue(written.captured.isBiometric, "the hardware key lives only on this device")
        assertEquals(ownAlias, written.captured.keystoreAlias)
        assertEquals("Backup label", written.captured.label)
    }

    // A Keystore alias coming from a backup is only accepted if it is exactly the alias the app
    // generates for this key: any other one would make a later delete of the key remove an
    // unrelated Keystore entry (the vault master key included).

    /** The row [repository] writes when [backup] is saved over [stored] (null = a new id). */
    private suspend fun savedFrom(stored: SshKeyEntity?, backup: SshKey): SshKeyEntity {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns stored
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit
        repository.save(backup)
        return written.captured
    }

    @Test
    fun `a new key takes the alias the app generates for it`() = runTest {
        val saved = savedFrom(null, backupKey(isBiometric = true, keystoreAlias = ownAlias))

        assertTrue(saved.isBiometric)
        assertEquals(ownAlias, saved.keystoreAlias)
    }

    @Test
    fun `a new key claiming another key's alias loses its Keystore binding`() = runTest {
        val otherKeysAlias = BiometricKeyAlias.forKeyId("11111111-2222-4333-8444-555555555555")

        val saved = savedFrom(null, backupKey(isBiometric = true, keystoreAlias = otherKeysAlias))

        assertNull(saved.keystoreAlias)
        assertFalse(saved.isBiometric)
    }

    @Test
    fun `a new key claiming the vault master key alias loses its Keystore binding`() = runTest {
        val saved = savedFrom(null, backupKey(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key"))

        assertNull(saved.keystoreAlias)
        assertFalse(saved.isBiometric)
    }

    @Test
    fun `a new key with an alias that is not a UUID alias loses its Keystore binding`() = runTest {
        for (alias in listOf("", "nextsh_bio_", "nextsh_bio_$keyId-extra", " $ownAlias", "$ownAlias\n", ownAlias.uppercase())) {
            val saved = savedFrom(null, backupKey(isBiometric = true, keystoreAlias = alias))

            assertNull(saved.keystoreAlias, "alias [$alias] must be refused")
            assertFalse(saved.isBiometric, "alias [$alias] must be refused")
        }
    }

    @Test
    fun `a key flagged biometric without alias is not biometric`() = runTest {
        val saved = savedFrom(null, backupKey(isBiometric = true, keystoreAlias = null))

        assertFalse(saved.isBiometric)
        assertNull(saved.keystoreAlias)
    }

    @Test
    fun `a live hardware-bound local key keeps its keyType and publicKey against the backup`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias)

        val saved = savedFrom(stored, backupKey(isBiometric = true, keystoreAlias = ownAlias))

        assertEquals("ED25519", saved.keyType)
        assertEquals("ssh-ed25519 LOCAL", saved.publicKey)
        assertEquals(ownAlias, saved.keystoreAlias)
        assertTrue(saved.isBiometric)
        // Plain metadata still comes from the backup.
        assertEquals("Backup label", saved.label)
    }

    @Test
    fun `a live hardware-bound local key ignores a foreign alias in the backup`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias)

        val saved = savedFrom(stored, backupKey(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key"))

        assertEquals(ownAlias, saved.keystoreAlias)
        assertTrue(saved.isBiometric)
    }

    @Test
    fun `a tombstoned local key does not keep its Keystore binding`() = runTest {
        // Its Keystore entry was destroyed when the key was deleted: the backup version rules.
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias, deleted = true, deletedAt = 900L)

        val withoutBinding = savedFrom(stored, backupKey(isBiometric = false, keystoreAlias = null))

        assertNull(withoutBinding.keystoreAlias)
        assertFalse(withoutBinding.isBiometric)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", withoutBinding.publicKey)
        assertEquals("ECDSA_256", withoutBinding.keyType)
        assertFalse(withoutBinding.deleted)
    }

    @Test
    fun `a tombstoned local key takes a valid alias from the backup but not a foreign one`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias, deleted = true, deletedAt = 900L)

        val valid = savedFrom(stored, backupKey(isBiometric = true, keystoreAlias = ownAlias))
        val foreign = savedFrom(stored, backupKey(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key"))

        assertEquals(ownAlias, valid.keystoreAlias)
        assertNull(foreign.keystoreAlias)
        assertFalse(foreign.isBiometric)
    }

    @Test
    fun `a local row whose stored alias is not the app alias is not kept as a binding`() = runTest {
        // A bogus alias already in the database must not be protected by the "local binding wins" rule.
        val stored = entity(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key")

        val saved = savedFrom(stored, backupKey(isBiometric = true, keystoreAlias = ownAlias))

        assertEquals(ownAlias, saved.keystoreAlias)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", saved.publicKey)
    }

    // ── upsertSyncEntry: a remote payload gets the same Keystore rules as a restore ──
    //
    // A peer must never be able to orphan a hardware key of this device, nor to make the app
    // trust (and later delete) a Keystore entry that is not the key's own.

    private val remoteClock = VectorClock(mapOf("desktop-device" to 9L))

    private fun remoteEntry(payload: SshKey) = SyncEntry(
        id = keyId,
        payload = payload,
        clock = remoteClock,
        updatedAt = 5_000L,
    )

    /** The row written by upsertSyncEntry([entry]) over [stored] (null = an unknown key). */
    private suspend fun syncedFrom(stored: SshKeyEntity?, entry: SyncEntry<SshKey>): SshKeyEntity {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns stored
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit
        repository.upsertSyncEntry(entry)
        return written.captured
    }

    @Test
    fun `sync of an unknown key takes the alias the app generates for it`() = runTest {
        val saved = syncedFrom(null, remoteEntry(backupKey(isBiometric = true, keystoreAlias = ownAlias)))

        assertTrue(saved.isBiometric)
        assertEquals(ownAlias, saved.keystoreAlias)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertEquals(5_000L, saved.updatedAt)
        assertFalse(saved.deleted)
    }

    @Test
    fun `sync drops an alias that is not the one of that key`() = runTest {
        for (alias in listOf(
            "nextsh_vault_auth_key",
            BiometricKeyAlias.forKeyId("11111111-2222-4333-8444-555555555555"),
            "nextsh_bio_$keyId-extra",
            "",
        )) {
            val saved = syncedFrom(null, remoteEntry(backupKey(isBiometric = true, keystoreAlias = alias)))

            assertNull(saved.keystoreAlias, "alias [$alias] must be dropped")
            assertFalse(saved.isBiometric, "alias [$alias] must be dropped")
        }
    }

    @Test
    fun `sync of a biometric flag without alias leaves the key not biometric`() = runTest {
        val saved = syncedFrom(null, remoteEntry(backupKey(isBiometric = true, keystoreAlias = null)))

        assertFalse(saved.isBiometric)
        assertNull(saved.keystoreAlias)
    }

    @Test
    fun `a live key bound to its own alias keeps its whole binding whatever the peer says`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias)

        // The peer sends another type, another public key, no alias, not biometric.
        val saved = syncedFrom(stored, remoteEntry(backupKey(isBiometric = false, keystoreAlias = null)))

        assertEquals(ownAlias, saved.keystoreAlias)
        assertTrue(saved.isBiometric)
        assertEquals("ED25519", saved.keyType)
        assertEquals("ssh-ed25519 LOCAL", saved.publicKey)
        // The rest of the payload is applied as before.
        assertEquals("Backup label", saved.label)
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
        assertEquals(5_000L, saved.updatedAt)
    }

    @Test
    fun `a live bound key keeps its alias against a foreign alias sent by a peer`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias)

        val saved = syncedFrom(stored, remoteEntry(backupKey(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key")))

        assertEquals(ownAlias, saved.keystoreAlias)
        assertTrue(saved.isBiometric)
    }

    @Test
    fun `a tombstoned local key does not keep its binding against a live remote version`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias, deleted = true, deletedAt = 900L)

        val saved = syncedFrom(stored, remoteEntry(backupKey(isBiometric = false, keystoreAlias = null)))

        assertNull(saved.keystoreAlias)
        assertFalse(saved.isBiometric)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", saved.publicKey)
        assertFalse(saved.deleted)
    }

    @Test
    fun `a stored alias that is not the app alias is not protected as a binding`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key")

        val saved = syncedFrom(stored, remoteEntry(backupKey(isBiometric = true, keystoreAlias = ownAlias)))

        assertEquals(ownAlias, saved.keystoreAlias)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", saved.publicKey)
    }

    @Test
    fun `sync keeps createdAt from the stored row and looks the row up by id`() = runTest {
        val stored = entity(createdAt = 111L)

        val saved = syncedFrom(stored, remoteEntry(backupKey()))

        assertEquals(111L, saved.createdAt)
        coVerify(exactly = 0) { sshKeyDao.getAllIncludingDeleted() }
    }

    @Test
    fun `sync of an unknown key stamps createdAt now`() = runTest {
        val before = System.currentTimeMillis()
        val saved = syncedFrom(null, remoteEntry(backupKey()))
        val after = System.currentTimeMillis()

        assertTrue(saved.createdAt in before..after)
    }

    @Test
    fun `sync tombstone only changes the sync columns and looks the row up by id`() = runTest {
        val stored = entity(isBiometric = true, keystoreAlias = ownAlias, createdAt = 111L)
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns stored
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(
                id = keyId,
                payload = null,
                clock = remoteClock,
                deleted = true,
                deletedAt = 4_000L,
                updatedAt = 4_000L,
            )
        )

        assertEquals(
            stored.copy(
                deleted = true,
                deletedAt = 4_000L,
                updatedAt = 4_000L,
                vectorClock = VectorClockCodec.encode(remoteClock),
            ),
            written.captured,
        )
        coVerify(exactly = 0) { sshKeyDao.getAllIncludingDeleted() }
    }

    @Test
    fun `sync tombstone for an unknown key writes nothing`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns null

        repository.upsertSyncEntry(
            SyncEntry(id = keyId, payload = null, clock = remoteClock, deleted = true, deletedAt = 4_000L, updatedAt = 4_000L)
        )

        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    // ── The alias and the biometric flag always go together ──────────────────

    @Test
    fun `a valid alias on a key that is not biometric is dropped, on save`() = runTest {
        val saved = savedFrom(null, backupKey(isBiometric = false, keystoreAlias = ownAlias))

        assertNull(saved.keystoreAlias)
        assertFalse(saved.isBiometric)
    }

    @Test
    fun `a valid alias on a key that is not biometric is dropped, on sync`() = runTest {
        val saved = syncedFrom(null, remoteEntry(backupKey(isBiometric = false, keystoreAlias = ownAlias)))

        assertNull(saved.keystoreAlias)
        assertFalse(saved.isBiometric)
    }

    @Test
    fun `a stored row with an alias but not biometric is not a binding and does not survive a save`() = runTest {
        // A legacy row: alias set, flag off. It is not protected as a hardware binding, and the
        // incoming key (not biometric) does not bring an alias back.
        val stored = entity(isBiometric = false, keystoreAlias = ownAlias)

        val saved = savedFrom(stored, backupKey(isBiometric = false, keystoreAlias = null))

        assertNull(saved.keystoreAlias)
        assertFalse(saved.isBiometric)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", saved.publicKey)
    }

    @Test
    fun `no row written by save, update or sync has an alias without the biometric flag, or the flag without an alias`() = runTest {
        val aliases = listOf(null, ownAlias, "nextsh_vault_auth_key", BiometricKeyAlias.forKeyId("11111111-2222-4333-8444-555555555555"))
        val storedRows = listOf(
            null,
            entity(isBiometric = true, keystoreAlias = ownAlias),
            entity(isBiometric = false, keystoreAlias = ownAlias),
            entity(isBiometric = true, keystoreAlias = "nextsh_vault_auth_key"),
            entity(isBiometric = false, keystoreAlias = null),
            entity(isBiometric = true, keystoreAlias = ownAlias, deleted = true, deletedAt = 900L),
        )
        for (stored in storedRows) {
            for (alias in aliases) {
                for (flag in listOf(true, false)) {
                    val key = backupKey(isBiometric = flag, keystoreAlias = alias)
                    for (saved in listOf(savedFrom(stored, key), syncedFrom(stored, remoteEntry(key)))) {
                        val context = "stored=$stored alias=$alias flag=$flag"
                        assertEquals(saved.isBiometric, saved.keystoreAlias != null, context)
                    }
                }
            }
        }
    }

    // ── upsertSyncEntry: the shared rule of a key that comes from outside (SshKeyRules) ──

    @Test
    fun `sync cleans a public key line that spans two lines instead of storing it as received`() = runTest {
        val payload = backupKey().copy(publicKey = "ecdsa-sha2-nistp256 AAAA first\r\nssh-ed25519 BBBB second\u0007")

        val saved = syncedFrom(null, remoteEntry(payload))

        assertFalse(saved.publicKey.any { it.isISOControl() }, "no control character may reach the stored line")
        assertEquals(1, saved.publicKey.lines().size)
        assertTrue(saved.publicKey.startsWith("ecdsa-sha2-nistp256 AAAA first"))
        assertEquals(remoteClock, VectorClockCodec.decode(saved.vectorClock))
    }

    @Test
    fun `sync cleans the label and the FIDO2 application of a key`() = runTest {
        val payload = backupKey().copy(label = "Line\nbreak", fido2RpId = "ssh:\u0007")

        val saved = syncedFrom(null, remoteEntry(payload))

        assertEquals("Line break", saved.label)
        assertEquals("ssh: ", saved.fido2RpId)
    }

    @Test
    fun `sync drops a key whose public key line is over 64 KiB and writes nothing`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns null
        val payload = backupKey().copy(publicKey = "ssh-ed25519 " + "A".repeat(64 * 1024 + 1))

        repository.upsertSyncEntry(remoteEntry(payload))

        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    @Test
    fun `sync drops a key with a FIDO2 credential holding a control character and keeps the stored row`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns entity()

        repository.upsertSyncEntry(remoteEntry(backupKey(fido2CredentialId = "cred\u0007")))

        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    @Test
    fun `sync drops a key with a foreign alias that is over 256 characters`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns null

        repository.upsertSyncEntry(
            remoteEntry(backupKey(isBiometric = true, keystoreAlias = "a".repeat(257))),
        )

        coVerify(exactly = 0) { sshKeyDao.upsert(any()) }
    }

    @Test
    fun `sync still applies a tombstone to a stored key`() = runTest {
        coEvery { sshKeyDao.getByIdIncludingDeleted(keyId) } returns entity()
        val written = slot<SshKeyEntity>()
        coEvery { sshKeyDao.upsert(capture(written)) } returns Unit

        repository.upsertSyncEntry(
            SyncEntry(id = keyId, payload = null, clock = remoteClock, deleted = true, deletedAt = 4_000L, updatedAt = 5_000L),
        )

        assertTrue(written.captured.deleted)
    }

    @Test
    fun `a normal key from a peer keeps working through the shared rule`() = runTest {
        val saved = syncedFrom(null, remoteEntry(backupKey()))

        assertEquals("Backup label", saved.label)
        assertEquals("ecdsa-sha2-nistp256 BACKUP", saved.publicKey)
    }
}
