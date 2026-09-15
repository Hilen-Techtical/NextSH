// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostImportServiceTest {

    private val service = HostImportService()

    @Test
    fun `imports password host and stores password in vault`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val pw = "hunter2".toCharArray()

        val entry = ParsedHost(
            label = "web",
            hostname = "10.0.0.1",
            port = 2200,
            username = "root",
            authType = AuthType.PASSWORD,
            group = "Prod",
            password = pw,
        )

        val result = service.import(listOf(entry), hosts, vault)

        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
        assertTrue(result.errors.isEmpty())

        val saved = hosts.saved.single()
        assertEquals("web", saved.label)
        assertEquals("10.0.0.1", saved.hostname)
        assertEquals(2200, saved.port)
        assertEquals("root", saved.username)
        assertEquals("Prod", saved.group)
        assertEquals(AuthType.PASSWORD, saved.authType)

        // Password stored under the generated credentialId.
        val stored = vault.passwords[saved.credentialId]
        assertNotNull(stored, "password must be stored under the host's credentialId")
        assertEquals("hunter2", String(stored!!))

        // The caller's CharArray must have been wiped after storage.
        assertTrue(pw.all { it == Char(32) }, "source password CharArray must be wiped after import")
    }

    @Test
    fun `imports ssh key host stores private key passphrase and key metadata`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val passphrase = "kp".toCharArray()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----"

        val entry = ParsedHost(
            label = "deploy",
            hostname = "git.example",
            port = 22,
            username = "git",
            authType = AuthType.SSH_KEY,
            group = null,
            privateKeyPem = pem,
            keyPassphrase = passphrase,
        )

        val result = service.import(listOf(entry), hosts, vault, keys)

        assertEquals(1, result.imported)
        val savedHost = hosts.saved.single()
        assertEquals(AuthType.SSH_KEY, savedHost.authType)

        // A key metadata row was created.
        val savedKey = keys.saved.single()
        assertEquals("deploy", savedKey.label)

        // Private key + passphrase stored under the same keyId (== savedKey.id).
        assertEquals(pem, vault.privateKeys[savedKey.id])
        val storedPass = vault.keyPassphrases[savedKey.id]
        assertNotNull(storedPass)
        assertEquals("kp", String(storedPass!!))

        // Passphrase CharArray wiped.
        assertTrue(passphrase.all { it == Char(32) }, "passphrase CharArray must be wiped")
        // No password stored for a key host.
        assertTrue(vault.passwords.isEmpty())
    }

    @Test
    fun `counts skipped and records error when repository save fails`() = runTest {
        val hosts = FakeHostRepository(failOnHostname = "boom.example")
        val vault = FakeVaultManager()

        val ok = ParsedHost("ok", "good.example", 22, "u", AuthType.PASSWORD, null, password = "p".toCharArray())
        val bad = ParsedHost("bad", "boom.example", 22, "u", AuthType.PASSWORD, null, password = "secret".toCharArray())

        val result = service.import(listOf(ok, bad), hosts, vault)

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(1, result.errors.size)
        // Error message references the label, never the secret.
        assertTrue(result.errors.single().contains("bad"))
        assertTrue(result.errors.none { it.contains("secret") }, "error must not leak the password")
        // Failed entry's secret still wiped (the bad password CharArray).
        assertTrue(bad.password!!.all { it == Char(32) })
    }

    @Test
    fun `ssh key host with no PEM imports host without storing a secret`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()

        // A Termius host referencing a key whose body lives outside the export.
        val entry = ParsedHost(
            label = "ext-key",
            hostname = "h.example",
            port = 22,
            username = "u",
            authType = AuthType.SSH_KEY,
            group = null,
            privateKeyPem = null,
        )

        val result = service.import(listOf(entry), hosts, vault, keys)
        assertEquals(1, result.imported)
        assertEquals(1, hosts.saved.size)
        assertTrue(vault.privateKeys.isEmpty())
        assertTrue(keys.saved.isEmpty(), "no key metadata row without a PEM")
    }

    @Test
    fun `empty input yields zero counts`() = runTest {
        val result = service.import(emptyList(), FakeHostRepository(), FakeVaultManager())
        assertEquals(0, result.imported)
        assertEquals(0, result.skipped)
        assertTrue(result.errors.isEmpty())
    }

    // ── Fakes ────────────────────────────────────────────────────────────────────

    private class FakeHostRepository(private val failOnHostname: String? = null) : HostRepository {
        val saved = mutableListOf<Host>()
        override suspend fun save(host: Host) {
            if (host.hostname == failOnHostname) error("simulated DB failure")
            saved += host
        }
        override fun observeAll(): Flow<List<Host>> = flowOf(saved)
        override fun observeByGroup(group: String): Flow<List<Host>> = flowOf(emptyList())
        override fun observeGroups(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getById(id: String): Host? = saved.firstOrNull { it.id == id }
        override suspend fun update(host: Host) {}
        override suspend fun delete(id: String) {}
        override suspend fun updateLastConnected(id: String) {}
        override fun observeFavorites(): Flow<List<Host>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {}
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) {}
        override suspend fun hardDelete(id: String) {}
    }

    private class FakeSshKeyRepository : SshKeyRepository {
        val saved = mutableListOf<SshKey>()
        override suspend fun save(key: SshKey) { saved += key }
        override fun observeAll(): Flow<List<SshKey>> = flowOf(saved)
        override suspend fun getById(id: String): SshKey? = saved.firstOrNull { it.id == id }
        override suspend fun update(key: SshKey) {}
        override suspend fun delete(id: String) {}
        override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) {}
        override suspend fun hardDelete(id: String) {}
    }

    private class FakeVaultManager : VaultManager {
        val passwords = mutableMapOf<String, CharArray>()
        val privateKeys = mutableMapOf<String, String>()
        val keyPassphrases = mutableMapOf<String, CharArray>()
        val certificates = mutableMapOf<String, String>()

        override suspend fun storePassword(credentialId: String, password: CharArray) {
            // Copy: the service wipes the caller's array after this returns.
            passwords[credentialId] = password.copyOf()
        }
        override suspend fun getPassword(credentialId: String): CharArray? = passwords[credentialId]
        override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) { privateKeys[keyId] = privateKeyPem }
        override suspend fun getPrivateKey(keyId: String): String? = privateKeys[keyId]
        override suspend fun storeCertificate(certId: String, certPem: String) { certificates[certId] = certPem }
        override suspend fun getCertificate(certId: String): String? = certificates[certId]
        override suspend fun deleteCertificate(certId: String) { certificates.remove(certId) }
        override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) {
            keyPassphrases[keyId] = passphrase.copyOf()
        }
        override suspend fun getKeyPassphrase(keyId: String): CharArray? = keyPassphrases[keyId]
        override suspend fun deleteKeyPassphrase(keyId: String) { keyPassphrases.remove(keyId) }
        override suspend fun listStoredKeyPassphraseIds(): List<String> = keyPassphrases.keys.toList()
        override suspend fun deleteCredential(credentialId: String) { passwords.remove(credentialId) }
        override suspend fun wipeVault() { passwords.clear(); privateKeys.clear(); keyPassphrases.clear(); certificates.clear() }
        override suspend fun isInitialized(): Boolean = true
        override suspend fun listStoredCredentialIds(): List<String> = passwords.keys.toList()
        override suspend fun listStoredKeyIds(): List<String> = privateKeys.keys.toList()
        override suspend fun listStoredCertificateIds(): List<String> = certificates.keys.toList()
    }
}
