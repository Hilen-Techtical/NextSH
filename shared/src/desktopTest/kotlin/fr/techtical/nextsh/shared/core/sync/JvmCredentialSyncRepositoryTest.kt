// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmCredentialSyncRepositoryTest {

    /** Two peers share the same ECDH secret: we simulate that. */
    private val sharedSecret: ByteArray = ByteArray(32).also { it.indices.forEach { i -> it[i] = i.toByte() } }
    private val wrongSecret: ByteArray = ByteArray(32).also { it.indices.forEach { i -> it[i] = (i xor 0xAB).toByte() } }

    // ─── Fakes ─────────────────────────────────────────────────────────────

    private class FakeVault : VaultManager {
        val passwords = mutableMapOf<String, CharArray>()
        val privateKeys = mutableMapOf<String, String>()
        val certificates = mutableMapOf<String, String>()

        override suspend fun storePassword(credentialId: String, password: CharArray) {
            passwords[credentialId] = password.copyOf()
        }
        override suspend fun getPassword(credentialId: String): CharArray? = passwords[credentialId]?.copyOf()
        /** When true, every typed delete throws (vault locked, Android auth expired). */
        var failDeletes = false
        private fun maybeFailDelete() {
            if (failDeletes) throw IllegalStateException("simulated vault failure")
        }

        override suspend fun deletePassword(credentialId: String) {
            maybeFailDelete()
            passwords.remove(credentialId)
        }
        override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) { privateKeys[keyId] = privateKeyPem }
        override suspend fun getPrivateKey(keyId: String): String? = privateKeys[keyId]
        override suspend fun deletePrivateKey(keyId: String) {
            maybeFailDelete()
            privateKeys.remove(keyId)
        }
        override suspend fun storeCertificate(certId: String, certPem: String) { certificates[certId] = certPem }
        override suspend fun getCertificate(certId: String): String? = certificates[certId]
        override suspend fun deleteCertificate(certId: String) { certificates.remove(certId) }
        override suspend fun wipeVault() { passwords.clear(); privateKeys.clear(); certificates.clear() }
        override suspend fun isInitialized(): Boolean = true
        override suspend fun listStoredCredentialIds(): List<String> = passwords.keys.toList()
        override suspend fun listStoredKeyIds(): List<String> = privateKeys.keys.toList()
        override suspend fun listStoredCertificateIds(): List<String> = certificates.keys.toList()
        val passphrases = mutableMapOf<String, CharArray>()
        override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) {
            passphrases[keyId] = passphrase.copyOf()
        }
        override suspend fun getKeyPassphrase(keyId: String): CharArray? = passphrases[keyId]?.copyOf()
        override suspend fun deleteKeyPassphrase(keyId: String) { passphrases.remove(keyId) }
        override suspend fun listStoredKeyPassphraseIds(): List<String> = passphrases.keys.toList()
    }

    private class InMemoryMetaStore : CredentialMetaStore {
        private val map = mutableMapOf<String, CredentialMeta>()
        override suspend fun get(credentialId: String): CredentialMeta? = map[credentialId]
        override suspend fun put(credentialId: String, meta: CredentialMeta) { map[credentialId] = meta }
        override suspend fun delete(credentialId: String) { map.remove(credentialId) }
        override suspend fun listIds(): Set<String> = map.keys.toSet()
        override suspend fun getAll(): Map<String, CredentialMeta> = map.toMap()
    }

    private class FakeConflictRepo : PendingConflictRepository {
        val saved = mutableListOf<PendingConflict>()
        private val state = MutableStateFlow<List<PendingConflict>>(emptyList())
        override suspend fun getAll(): List<PendingConflict> = saved.toList()
        override fun observeAll(): Flow<List<PendingConflict>> = state
        override fun observeCount(): Flow<Int> = state.map { it.size }
        override suspend fun save(conflict: PendingConflict) { saved.add(conflict); state.value = saved.toList() }
        override suspend fun deleteById(id: String) { saved.removeAll { it.id == id }; state.value = saved.toList() }
        override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) {
            saved.removeAll { it.entityType == entityType && it.entityId == entityId }
            state.value = saved.toList()
        }
        override suspend fun deleteAll() { saved.clear(); state.value = emptyList() }
    }

    private fun repo(
        vault: FakeVault = FakeVault(),
        metaStore: InMemoryMetaStore = InMemoryMetaStore(),
        conflicts: FakeConflictRepo = FakeConflictRepo(),
        deviceId: String = "device-A",
        nowMs: Long = 1_000L,
    ): Triple<JvmCredentialSyncRepository, FakeVault, Pair<InMemoryMetaStore, FakeConflictRepo>> {
        var time = nowMs
        val r = JvmCredentialSyncRepository(
            vault = vault,
            metaStore = metaStore,
            pendingConflictRepository = conflicts,
            localDeviceId = { deviceId },
            nowMs = { time++ },
        )
        return Triple(r, vault, metaStore to conflicts)
    }

    // ─── Export tests ──────────────────────────────────────────────────────

    @Test
    fun `export emits one entry per stored password`() = runTest {
        val vault = FakeVault().apply {
            passwords["host-1"] = "pw1".toCharArray()
            passwords["host-2"] = "pw2".toCharArray()
        }
        val (r, _, _) = repo(vault = vault)
        val entries = r.exportEncryptedEntries(sharedSecret)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.payload?.type == CredentialType.HOST_PASSWORD })
    }

    @Test
    fun `export skips and tombstones credentials removed since last sync`() = runTest {
        val vault = FakeVault().apply { passwords["host-1"] = "pw1".toCharArray() }
        val (r, _, store) = repo(vault = vault)

        // First export → creates meta for host-1
        val first = r.exportEncryptedEntries(sharedSecret)
        assertEquals(1, first.size)

        // User deletes the password locally
        vault.passwords.remove("host-1")

        // Second export → tombstone
        val second = r.exportEncryptedEntries(sharedSecret)
        assertEquals(1, second.size)
        assertTrue(second[0].deleted)
        assertNull(second[0].payload)
        assertNotNull(second[0].deletedAt)
        assertNotNull(store.first.get("pwd:host-1"))
        assertTrue(store.first.get("pwd:host-1")!!.deleted)
    }

    @Test
    fun `export reuses clock when content unchanged`() = runTest {
        val vault = FakeVault().apply { passwords["host-1"] = "pw1".toCharArray() }
        val (r, _, store) = repo(vault = vault)

        r.exportEncryptedEntries(sharedSecret)
        val clockAfter1 = store.first.get("pwd:host-1")!!.vectorClock
        r.exportEncryptedEntries(sharedSecret)
        val clockAfter2 = store.first.get("pwd:host-1")!!.vectorClock

        assertEquals(clockAfter1, clockAfter2, "Unchanged content must not bump the clock")
    }

    @Test
    fun `export bumps clock when content changes (hash differs)`() = runTest {
        val vault = FakeVault().apply { passwords["host-1"] = "pw1".toCharArray() }
        val (r, _, store) = repo(vault = vault)

        r.exportEncryptedEntries(sharedSecret)
        val clockBefore = store.first.get("pwd:host-1")!!.vectorClock

        vault.passwords["host-1"] = "pw1-changed".toCharArray()  // user edited

        r.exportEncryptedEntries(sharedSecret)
        val clockAfter = store.first.get("pwd:host-1")!!.vectorClock

        assertTrue(clockAfter.dominates(clockBefore), "Hash change must bump the clock")
    }

    // ─── Roundtrip Peer A → Peer B ─────────────────────────────────────────

    @Test
    fun `peer A exports, peer B applies and recovers plaintext in vault`() = runTest {
        // Peer A
        val vaultA = FakeVault().apply { passwords["host-42"] = "s3cret".toCharArray() }
        val (rA, _, _) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1000)
        val entries = rA.exportEncryptedEntries(sharedSecret)

        // Peer B
        val vaultB = FakeVault()
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2000)
        val result = rB.applyRemoteEntries(entries, sharedSecret)

        assertEquals(1, result.cleanApplied)
        assertEquals(0, result.skipped)
        assertEquals(0, result.conflicts.size)
        val recovered = vaultB.getPassword("host-42")
        assertContentEquals("s3cret".toCharArray(), recovered)
    }

    @Test
    fun `peer B with wrong secret skips entry (graceful degradation)`() = runTest {
        val vaultA = FakeVault().apply { passwords["host-42"] = "s3cret".toCharArray() }
        val (rA, _, _) = repo(vault = vaultA)
        val entries = rA.exportEncryptedEntries(sharedSecret)

        val vaultB = FakeVault()
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B")
        val result = rB.applyRemoteEntries(entries, wrongSecret)

        assertEquals(0, result.cleanApplied)
        assertEquals(1, result.skipped)
        assertNull(vaultB.getPassword("host-42"), "Vault must remain empty when decryption fails")
    }

    @Test
    fun `remote tombstone deletes local credential when lineage is consistent`() = runTest {
        // Realistic lineage: A creates → B pulls → A deletes → B pulls tombstone.
        // B's local clock after the first pull carries peer-A's tick, so A's
        // later tombstone clock strictly dominates → Clean(tombstone), no conflict.
        val vaultA = FakeVault().apply { passwords["host-9"] = "tmp".toCharArray() }
        val (rA, _, _) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1000)
        val firstExport = rA.exportEncryptedEntries(sharedSecret)

        val vaultB = FakeVault()
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2000)
        val applied = rB.applyRemoteEntries(firstExport, sharedSecret)
        assertEquals(1, applied.cleanApplied, "first apply: B receives A's entry")
        assertContentEquals("tmp".toCharArray(), vaultB.getPassword("host-9"))

        // A deletes and publishes a tombstone
        vaultA.passwords.remove("host-9")
        val tombstoneEntries = rA.exportEncryptedEntries(sharedSecret)
        assertTrue(tombstoneEntries[0].deleted)

        val result = rB.applyRemoteEntries(tombstoneEntries, sharedSecret)
        assertEquals(1, result.cleanApplied, "tombstone apply must be clean (dominates local)")
        assertNull(vaultB.getPassword("host-9"), "vault must have the credential removed")
    }

    // ─── Typed tombstones (regression: a tombstone must not erase other types) ─

    /** Vault with all four types stored under the SAME raw id (a host switched from key to password). */
    private fun vaultWithEveryTypeUnder(id: String) = FakeVault().apply {
        passwords[id] = "pw".toCharArray()
        privateKeys[id] = "PEM"
        certificates[id] = "CERT"
        passphrases[id] = "pass".toCharArray()
    }

    private fun tombstone(syncId: String) = SyncEntry<CredentialEntry>(
        id = syncId,
        payload = null,
        clock = VectorClock.EMPTY.tick("peer-A", 9_999),
        deleted = true,
        deletedAt = 9_999,
        updatedAt = 9_999,
    )

    /** Snapshot of which types are still stored under [id]. */
    private fun FakeVault.typesUnder(id: String): Set<String> = buildSet {
        if (id in passwords) add("pwd")
        if (id in privateKeys) add("key")
        if (id in certificates) add("cert")
        if (id in passphrases) add("keypass")
    }

    @Test
    fun `password tombstone keeps private key, certificate and passphrase stored under the same id`() = runTest {
        val vault = vaultWithEveryTypeUnder("X")
        val (r, _, _) = repo(vault = vault, deviceId = "peer-B")

        val result = r.applyRemoteEntries(listOf(tombstone("pwd:X")), sharedSecret)

        assertEquals(1, result.cleanApplied)
        assertEquals(setOf("key", "cert", "keypass"), vault.typesUnder("X"))
        assertEquals("PEM", vault.getPrivateKey("X"))
        assertEquals("CERT", vault.getCertificate("X"))
        assertContentEquals("pass".toCharArray(), vault.getKeyPassphrase("X"))
    }

    @Test
    fun `each tombstone prefix deletes only its own type`() = runTest {
        val expectedRemaining = mapOf(
            "pwd:X" to setOf("key", "cert", "keypass"),
            "key:X" to setOf("pwd", "cert", "keypass"),
            "cert:X" to setOf("pwd", "key", "keypass"),
            "keypass:X" to setOf("pwd", "key", "cert"),
        )
        for ((syncId, remaining) in expectedRemaining) {
            val vault = vaultWithEveryTypeUnder("X")
            val (r, _, _) = repo(vault = vault, deviceId = "peer-B")

            r.applyRemoteEntries(listOf(tombstone(syncId)), sharedSecret)

            assertEquals(remaining, vault.typesUnder("X"), "tombstone $syncId must delete only its own type")
        }
    }

    @Test
    fun `forced tombstone is typed too`() = runTest {
        val vault = vaultWithEveryTypeUnder("X")
        val (r, _, _) = repo(vault = vault, deviceId = "peer-B")

        assertTrue(r.applyRemoteCredentialForced(tombstone("key:X"), sharedSecret))

        assertEquals(setOf("pwd", "cert", "keypass"), vault.typesUnder("X"))
    }

    @Test
    fun `tombstone with an unknown prefix deletes nothing`() = runTest {
        val vault = vaultWithEveryTypeUnder("X")
        val (r, _, _) = repo(vault = vault, deviceId = "peer-B")

        r.applyRemoteEntries(listOf(tombstone("totp:X"), tombstone("X")), sharedSecret)

        assertEquals(setOf("pwd", "key", "cert", "keypass"), vault.typesUnder("X"))
    }

    @Test
    fun `a failing vault delete skips the tombstone, keeps the meta live and the next sync retries`() = runTest {
        // A creates, B pulls, A deletes: B receives the tombstone while its vault delete fails.
        val vaultA = FakeVault().apply { passwords["host-9"] = "tmp".toCharArray() }
        val (rA, _, _) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1000)
        val firstExport = rA.exportEncryptedEntries(sharedSecret)

        val vaultB = FakeVault()
        val metaB = InMemoryMetaStore()
        val (rB, _, _) = repo(vault = vaultB, metaStore = metaB, deviceId = "peer-B", nowMs = 2000)
        rB.applyRemoteEntries(firstExport, sharedSecret)
        val liveMeta = metaB.get("pwd:host-9")!!

        vaultA.passwords.remove("host-9")
        val tombstones = rA.exportEncryptedEntries(sharedSecret)
        assertTrue(tombstones.single().deleted)

        vaultB.failDeletes = true
        val failed = rB.applyRemoteEntries(tombstones, sharedSecret)

        assertEquals(0, failed.cleanApplied)
        assertEquals(1, failed.skipped, "a tombstone whose vault delete failed is skipped, not clean")
        assertContentEquals("tmp".toCharArray(), vaultB.getPassword("host-9"), "the credential is still stored")
        val metaAfterFailure = metaB.get("pwd:host-9")!!
        assertFalse(metaAfterFailure.deleted, "meta must not be stamped deleted over a stored credential")
        assertEquals(liveMeta.vectorClock, metaAfterFailure.vectorClock)

        // B's next export must not re-publish it with a clock that beats the tombstone.
        val exported = rB.exportEncryptedEntries(sharedSecret).single { it.id == "pwd:host-9" }
        assertFalse(exported.deleted)
        assertFalse(exported.clock.dominates(tombstones.single().clock), "the remote tombstone must still win")

        // The vault is back: the same tombstone now applies cleanly.
        vaultB.failDeletes = false
        val retry = rB.applyRemoteEntries(tombstones, sharedSecret)
        assertEquals(1, retry.cleanApplied)
        assertNull(vaultB.getPassword("host-9"))
        assertTrue(metaB.get("pwd:host-9")!!.deleted)
    }

    @Test
    fun `forced tombstone reports false and stamps nothing when the vault delete fails`() = runTest {
        val vault = vaultWithEveryTypeUnder("X").apply { failDeletes = true }
        val metaStore = InMemoryMetaStore()
        val (r, _, _) = repo(vault = vault, metaStore = metaStore, deviceId = "peer-B")

        assertFalse(r.applyRemoteCredentialForced(tombstone("pwd:X"), sharedSecret))

        assertEquals(setOf("pwd", "key", "cert", "keypass"), vault.typesUnder("X"))
        assertNull(metaStore.get("pwd:X"), "no meta may be stamped when the delete failed")
    }

    @Test
    fun `export after a local password delete emits only the pwd tombstone and keeps the key live`() = runTest {
        // Wire-format guard: tombstones stay typed per syncId, so a peer never receives
        // a tombstone for a type the user did not delete.
        val vault = FakeVault().apply {
            passwords["X"] = "pw".toCharArray()
            privateKeys["X"] = "PEM"
        }
        val (r, _, _) = repo(vault = vault)
        r.exportEncryptedEntries(sharedSecret)

        vault.deletePassword("X")
        val entries = r.exportEncryptedEntries(sharedSecret)

        assertEquals(setOf("pwd:X", "key:X"), entries.map { it.id }.toSet())
        val pwd = entries.single { it.id == "pwd:X" }
        assertTrue(pwd.deleted)
        assertNull(pwd.payload)
        val key = entries.single { it.id == "key:X" }
        assertFalse(key.deleted, "the private key under the same id stays live")
        assertEquals(CredentialType.SSH_PRIVATE_KEY, key.payload?.type)
    }

    // ─── Equality / hash behavior ──────────────────────────────────────────

    @Test
    fun `CredentialEntry equality compares hash not ciphertext`() {
        val a = CredentialEntry("id-1", CredentialType.HOST_PASSWORD, "iv-a", "ct-a", "hash-xyz")
        val b = CredentialEntry("id-1", CredentialType.HOST_PASSWORD, "iv-b", "ct-b", "hash-xyz")
        val c = CredentialEntry("id-1", CredentialType.HOST_PASSWORD, "iv-a", "ct-a", "hash-different")
        assertEquals(a, b, "same hash must equal despite different iv/ciphertext")
        assertFalse(a == c, "different hash must not equal")
    }

    @Test
    fun `passphrase syncs alongside private key from peer A to peer B`() = runTest {
        val vaultA = FakeVault().apply {
            privateKeys["key-1"] = "-----BEGIN OPENSSH PRIVATE KEY-----\nabcdef\n-----END OPENSSH PRIVATE KEY-----"
            passphrases["key-1"] = "secret-passphrase".toCharArray()
        }
        val (rA, _, _) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1000)
        val entries = rA.exportEncryptedEntries(sharedSecret)
        // Expect one SSH_PRIVATE_KEY entry and one SSH_PRIVATE_KEY_PASSPHRASE entry
        val types = entries.mapNotNull { it.payload?.type }.toSet()
        assertTrue(CredentialType.SSH_PRIVATE_KEY in types, "private key should be exported")
        assertTrue(CredentialType.SSH_PRIVATE_KEY_PASSPHRASE in types, "passphrase should be exported")

        val vaultB = FakeVault()
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2000)
        val result = rB.applyRemoteEntries(entries, sharedSecret)

        assertEquals(2, result.cleanApplied)
        assertEquals(0, result.conflicts.size)
        assertEquals(0, result.skipped)

        val recoveredPem = vaultB.getPrivateKey("key-1")
        val recoveredPass = vaultB.getKeyPassphrase("key-1")
        assertNotNull(recoveredPem)
        assertNotNull(recoveredPass)
        assertContentEquals("secret-passphrase".toCharArray(), recoveredPass!!)
    }

    @Test
    fun `same content from two peers produces MergeResult Clean not Conflict`() = runTest {
        // Both peers independently set the same password offline: no real conflict.
        val vaultA = FakeVault().apply { passwords["host-x"] = "same".toCharArray() }
        val vaultB = FakeVault().apply { passwords["host-x"] = "same".toCharArray() }
        val (rA, _, _) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1000)
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2000)

        val exportA = rA.exportEncryptedEntries(sharedSecret)
        rB.exportEncryptedEntries(sharedSecret)  // peer-B tracks its own meta

        val result = rB.applyRemoteEntries(exportA, sharedSecret)
        assertEquals(0, result.conflicts.size, "Same-content concurrent edits should not conflict (hash equality collapses clocks)")
        assertEquals(1, result.cleanApplied)
    }

    // ─── Internal LAN secret isolation (regression: B3) ────────────────────

    @Test
    fun `export never includes device-local LAN sync secrets`() = runTest {
        val lanId = "${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"
        val vault = FakeVault().apply {
            passwords["host-1"] = "pw1".toCharArray()
            passwords[lanId] = "authkey".toCharArray()   // LAN secret shares the pwd_ namespace
        }
        val (r, _, store) = repo(vault = vault)

        val entries = r.exportEncryptedEntries(sharedSecret)

        assertEquals(1, entries.size, "only the host password is syncable")
        assertEquals("host-1", entries[0].payload?.credentialId)
        assertTrue(entries.none { it.id.contains(LAN_SYNC_SECRET_KEY_PREFIX) }, "no LAN secret may be exported")
        assertNull(store.first.get("pwd:$lanId"), "LAN secret must never be tracked in credential meta")
    }

    @Test
    fun `incoming LAN secret tombstone never deletes the local secret`() = runTest {
        val lanId = "${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"
        val vaultB = FakeVault().apply { passwords[lanId] = "authkey".toCharArray() }
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B")

        // A peer on an older build emits a tombstone for the LAN secret.
        val tombstone = SyncEntry<CredentialEntry>(
            id = "pwd:$lanId",
            payload = null,
            clock = VectorClock.EMPTY.tick("peer-A", 9_999),
            deleted = true,
            deletedAt = 9_999,
            updatedAt = 9_999,
        )

        val result = rB.applyRemoteEntries(listOf(tombstone), sharedSecret)

        assertEquals(0, result.cleanApplied)
        assertEquals(1, result.skipped, "LAN secret tombstone must be ignored")
        assertContentEquals("authkey".toCharArray(), vaultB.getPassword(lanId), "LAN secret must survive a peer tombstone")
    }

    @Test
    fun `export self-heals poisoned LAN secret meta left by older builds`() = runTest {
        val lanId = "${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"
        val vault = FakeVault().apply {
            passwords["host-1"] = "pw1".toCharArray()
            passwords[lanId] = "authkey".toCharArray()
        }
        val metaStore = InMemoryMetaStore().apply {
            // Poison: an older build tracked the LAN secret and tombstoned it.
            put(
                "pwd:$lanId",
                CredentialMeta(
                    type = CredentialType.HOST_PASSWORD,
                    vectorClock = VectorClock.EMPTY.tick("old", 1),
                    updatedAt = 1,
                    deleted = true,
                    deletedAt = 1,
                    contentHashBase64 = "",
                ),
            )
        }
        val (r, _, _) = repo(vault = vault, metaStore = metaStore)

        val entries = r.exportEncryptedEntries(sharedSecret)

        assertNull(metaStore.get("pwd:$lanId"), "poisoned LAN meta must be purged")
        assertContentEquals("authkey".toCharArray(), vault.getPassword(lanId), "secret stays in the vault")
        assertEquals(1, entries.size, "only host-1 is exported")
        assertTrue(entries.none { it.id.contains(LAN_SYNC_SECRET_KEY_PREFIX) }, "no LAN tombstone may be re-emitted")
    }

    @Test
    fun `applyRemoteCredentialForced refuses a LAN secret entry`() = runTest {
        val lanId = "${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"
        val vaultB = FakeVault()
        val (rB, _, _) = repo(vault = vaultB, deviceId = "peer-B")

        val entry = SyncEntry(
            id = "pwd:$lanId",
            payload = CredentialEntry(
                credentialId = lanId,
                type = CredentialType.HOST_PASSWORD,
                iv = "irrelevant",
                encryptedPayload = "irrelevant",
                contentHashBase64 = "h",
            ),
            clock = VectorClock.EMPTY.tick("peer-A", 5),
            deleted = false,
            deletedAt = null,
            updatedAt = 5,
        )

        val applied = rB.applyRemoteCredentialForced(entry, sharedSecret)

        assertFalse(applied, "forced apply must refuse a LAN secret")
        assertNull(vaultB.getPassword(lanId), "LAN secret must not be written from a peer")
    }
}
