// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
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
    fun `decrypt failure log carries neither the credential id nor the exception message`() = runTest {
        val vaultA = FakeVault().apply { passwords["host-42"] = "s3cret".toCharArray() }
        val (rA, _, _) = repo(vault = vaultA)
        val entries = rA.exportEncryptedEntries(sharedSecret)
        val (rB, _, _) = repo(vault = FakeVault(), deviceId = "peer-B")

        val lines = Logger.capture { rB.applyRemoteEntries(entries, wrongSecret) }

        val line = lines.single { it.contains("Credential decrypt failed") }
        assertTrue(line.contains("type=HOST_PASSWORD"), line)
        assertTrue(line.contains("id=${LogId.of("pwd:host-42")}"), line)
        assertTrue(line.contains("SyncCodecException"), line)
        for (value in listOf("host-42", "pwd:", "mismatch", "s3cret")) {
            assertFalse(line.contains(value), "'$value' leaked in: $line")
        }
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

    // ─── Keep local (reassertLocalCredential) ──────────────────────────────

    /**
     * Both peers set a different password for the same credential while offline, then
     * each applies the other's export: both record a conflict. Returns the two repos
     * with their stores so a test can resolve one side and sync again.
     */
    private class ConflictedPeers(
        val vaultA: FakeVault,
        val vaultB: FakeVault,
        val repoA: JvmCredentialSyncRepository,
        val repoB: JvmCredentialSyncRepository,
        val metaA: InMemoryMetaStore,
        val metaB: InMemoryMetaStore,
        val conflictsA: FakeConflictRepo,
        val conflictsB: FakeConflictRepo,
    )

    private suspend fun conflictedPeers(): ConflictedPeers {
        val vaultA = FakeVault().apply { passwords["host-x"] = "pw-A".toCharArray() }
        val vaultB = FakeVault().apply { passwords["host-x"] = "pw-B".toCharArray() }
        val (rA, _, stA) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1_000)
        val (rB, _, stB) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2_000)

        val exportA = rA.exportEncryptedEntries(sharedSecret)
        val exportB = rB.exportEncryptedEntries(sharedSecret)
        assertEquals(1, rA.applyRemoteEntries(exportB, sharedSecret).conflicts.size)
        assertEquals(1, rB.applyRemoteEntries(exportA, sharedSecret).conflicts.size)
        return ConflictedPeers(vaultA, vaultB, rA, rB, stA.first, stB.first, stA.second, stB.second)
    }

    private fun FakeConflictRepo.remoteEntry(): SyncEntry<CredentialEntry> =
        ConflictSerializer.decodeCredential(saved.single().remoteJson)

    @Test
    fun `keeping the local credential stops the conflict from coming back on the next sync`() = runTest {
        val p = conflictedPeers()
        val localBefore = p.metaA.get("pwd:host-x")!!
        val remote = p.conflictsA.remoteEntry()

        assertTrue(p.repoA.reassertLocalCredential(remote))

        val after = p.metaA.get("pwd:host-x")!!
        assertTrue(after.vectorClock.dominates(localBefore.vectorClock), "must dominate the local clock")
        assertTrue(after.vectorClock.dominates(remote.clock), "must dominate the remote clock")
        assertTrue(after.updatedAt > localBefore.updatedAt)
        assertEquals(localBefore.contentHashBase64, after.contentHashBase64, "content hash is untouched")
        assertEquals(localBefore.type, after.type)
        assertFalse(after.deleted)
        assertTrue(p.conflictsA.saved.isEmpty(), "the pending conflict is removed")
        assertContentEquals("pw-A".toCharArray(), p.vaultA.getPassword("host-x"), "the vault is untouched")

        // A sends its kept version: B applies it cleanly and converges on A's password.
        val onB = p.repoB.applyRemoteEntries(p.repoA.exportEncryptedEntries(sharedSecret), sharedSecret)
        assertEquals(0, onB.conflicts.size, "no conflict is recreated on the peer")
        assertEquals(1, onB.cleanApplied)
        assertContentEquals("pw-A".toCharArray(), p.vaultB.getPassword("host-x"))
        assertTrue(p.conflictsB.saved.isEmpty(), "the peer's own marker is cleared by the clean apply")

        // And the other direction stays quiet too.
        val onA = p.repoA.applyRemoteEntries(p.repoB.exportEncryptedEntries(sharedSecret), sharedSecret)
        assertEquals(0, onA.conflicts.size)
        assertTrue(p.conflictsA.saved.isEmpty())
        assertContentEquals("pw-A".toCharArray(), p.vaultA.getPassword("host-x"))
    }

    @Test
    fun `deleting only the conflict marker brings the same conflict back`() = runTest {
        // Control: removing only the marker leaves the two clocks concurrent, so the same
        // conflict is detected again.
        val p = conflictedPeers()
        val marker = p.conflictsA.saved.single()
        p.conflictsA.deleteById(marker.id)

        val again = p.repoA.applyRemoteEntries(p.repoB.exportEncryptedEntries(sharedSecret), sharedSecret)

        assertEquals(1, again.conflicts.size)
    }

    @Test
    fun `keeping a local deletion keeps the tombstone and removes the credential on the peer`() = runTest {
        val vaultA = FakeVault().apply { passwords["host-x"] = "pw-1".toCharArray() }
        val vaultB = FakeVault()
        val (rA, _, stA) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1_000)
        val (rB, _, stB) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2_000)
        // Shared starting point: B has A's credential.
        rB.applyRemoteEntries(rA.exportEncryptedEntries(sharedSecret), sharedSecret)
        // A deletes it, B edits it, while apart.
        vaultA.passwords.remove("host-x")
        val tombstoneExport = rA.exportEncryptedEntries(sharedSecret)
        assertTrue(tombstoneExport.single().deleted)
        vaultB.passwords["host-x"] = "pw-edited-on-B".toCharArray()
        val editExport = rB.exportEncryptedEntries(sharedSecret)
        assertEquals(1, rA.applyRemoteEntries(editExport, sharedSecret).conflicts.size)
        assertEquals(1, rB.applyRemoteEntries(tombstoneExport, sharedSecret).conflicts.size)
        val deletedAtBefore = stA.first.get("pwd:host-x")!!.deletedAt

        assertTrue(rA.reassertLocalCredential(stA.second.remoteEntry()))

        val after = stA.first.get("pwd:host-x")!!
        assertTrue(after.deleted, "the kept local deletion stays a tombstone")
        assertEquals(deletedAtBefore, after.deletedAt)
        val onB = rB.applyRemoteEntries(rA.exportEncryptedEntries(sharedSecret), sharedSecret)
        assertEquals(0, onB.conflicts.size)
        assertEquals(1, onB.cleanApplied)
        assertNull(vaultB.getPassword("host-x"), "the peer applies the kept deletion")
        assertTrue(stB.first.get("pwd:host-x")!!.deleted)
    }

    @Test
    fun `reassert uses the current local metadata, not the snapshot stored in the conflict`() = runTest {
        val p = conflictedPeers()
        val remote = p.conflictsA.remoteEntry()
        // The user edits the password again between the conflict and the choice.
        p.vaultA.passwords["host-x"] = "pw-A-edited-later".toCharArray()
        p.repoA.exportEncryptedEntries(sharedSecret)
        val editedMeta = p.metaA.get("pwd:host-x")!!

        assertTrue(p.repoA.reassertLocalCredential(remote))

        val after = p.metaA.get("pwd:host-x")!!
        assertTrue(after.vectorClock.dominates(editedMeta.vectorClock), "the later local edit is not lost")
        assertEquals(editedMeta.contentHashBase64, after.contentHashBase64)
    }

    @Test
    fun `reassert returns false and changes nothing when there is no local metadata`() = runTest {
        val (r, _, stores) = repo(deviceId = "peer-A")
        val remote = SyncEntry(
            id = "pwd:unknown",
            payload = CredentialEntry("unknown", CredentialType.HOST_PASSWORD, "iv", "ct", "h"),
            clock = VectorClock.EMPTY.tick("peer-B", 5),
            updatedAt = 5,
        )
        val marker = PendingConflict(
            id = "marker-unknown",
            entityType = SyncableEntityType.CREDENTIAL,
            entityId = "pwd:unknown",
            localJson = "{}",
            remoteJson = "{}",
            detectedAt = 1L,
        )
        stores.second.save(marker)

        assertFalse(r.reassertLocalCredential(remote))

        assertNull(stores.first.get("pwd:unknown"), "no metadata is invented")
        // The caller deletes the marker itself by id when the result is false.
        assertEquals(listOf(marker), stores.second.saved.toList(), "the marker is left for the caller")
    }

    @Test
    fun `reasserting twice keeps returning true and the clock keeps strictly increasing`() = runTest {
        val p = conflictedPeers()
        val remote = p.conflictsA.remoteEntry()

        assertTrue(p.repoA.reassertLocalCredential(remote))
        val afterFirst = p.metaA.get("pwd:host-x")!!
        assertTrue(p.repoA.reassertLocalCredential(remote))
        val afterSecond = p.metaA.get("pwd:host-x")!!

        assertTrue(afterSecond.vectorClock.dominates(afterFirst.vectorClock))
        assertTrue(afterSecond.updatedAt > afterFirst.updatedAt)
        assertEquals(afterFirst.contentHashBase64, afterSecond.contentHashBase64)
        assertTrue(p.conflictsA.saved.isEmpty())
    }

    @Test
    fun `keeping a live local credential against a remote tombstone makes it live again on the peer`() = runTest {
        val vaultA = FakeVault().apply { passwords["host-x"] = "pw-1".toCharArray() }
        val vaultB = FakeVault()
        val (rA, _, stA) = repo(vault = vaultA, deviceId = "peer-A", nowMs = 1_000)
        val (rB, _, stB) = repo(vault = vaultB, deviceId = "peer-B", nowMs = 2_000)
        // Shared starting point: B has A's credential.
        rB.applyRemoteEntries(rA.exportEncryptedEntries(sharedSecret), sharedSecret)
        // B deletes it, A edits it, while apart.
        vaultB.passwords.remove("host-x")
        val tombstoneExport = rB.exportEncryptedEntries(sharedSecret)
        assertTrue(tombstoneExport.single().deleted)
        vaultA.passwords["host-x"] = "pw-edited-on-A".toCharArray()
        val editExport = rA.exportEncryptedEntries(sharedSecret)
        assertEquals(1, rA.applyRemoteEntries(tombstoneExport, sharedSecret).conflicts.size)
        assertEquals(1, rB.applyRemoteEntries(editExport, sharedSecret).conflicts.size)

        assertTrue(rA.reassertLocalCredential(stA.second.remoteEntry()))

        assertFalse(stA.first.get("pwd:host-x")!!.deleted, "the kept local credential stays live")
        val onB = rB.applyRemoteEntries(rA.exportEncryptedEntries(sharedSecret), sharedSecret)
        assertEquals(0, onB.conflicts.size)
        assertEquals(1, onB.cleanApplied)
        assertContentEquals("pw-edited-on-A".toCharArray(), vaultB.getPassword("host-x"), "live again on the peer")
        assertFalse(stB.first.get("pwd:host-x")!!.deleted)
        assertTrue(stB.second.saved.isEmpty(), "the peer's own marker is cleared by the clean apply")
    }

    @Test
    fun `reassert refuses a LAN secret entry`() = runTest {
        val lanId = "${LAN_SYNC_SECRET_KEY_PREFIX}desktop-1"
        val metaStore = InMemoryMetaStore()
        val (r, _, _) = repo(metaStore = metaStore, deviceId = "peer-A")
        val remote = SyncEntry(
            id = "pwd:$lanId",
            payload = CredentialEntry(lanId, CredentialType.HOST_PASSWORD, "iv", "ct", "h"),
            clock = VectorClock.EMPTY.tick("peer-B", 5),
            updatedAt = 5,
        )

        assertFalse(r.reassertLocalCredential(remote))

        assertNull(metaStore.get("pwd:$lanId"))
    }

    @Test
    fun `reassert leaves the conflict of another credential alone`() = runTest {
        val p = conflictedPeers()
        val other = PendingConflict(
            id = "other-marker",
            entityType = SyncableEntityType.CREDENTIAL,
            entityId = "pwd:host-y",
            localJson = "{}",
            remoteJson = "{}",
            detectedAt = 1L,
        )
        p.conflictsA.save(other)
        val remote = ConflictSerializer.decodeCredential(
            p.conflictsA.saved.first { it.entityId == "pwd:host-x" }.remoteJson,
        )

        assertTrue(p.repoA.reassertLocalCredential(remote))

        assertEquals(listOf(other), p.conflictsA.saved.toList())
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
