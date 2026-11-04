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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
    fun `ssh key host credentialId is the id of its key row, private key and passphrase`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----"

        val entry = ParsedHost(
            label = "deploy",
            hostname = "git.example",
            port = 22,
            username = "git",
            authType = AuthType.SSH_KEY,
            group = null,
            privateKeyPem = pem,
            keyPassphrase = "kp".toCharArray(),
        )

        service.import(listOf(entry), hosts, vault, keys)

        val host = hosts.saved.single()
        val key = keys.saved.single()

        // The connection code reads the key and its passphrase under host.credentialId.
        assertEquals(pem, vault.getPrivateKey(host.credentialId), "private key must be found under host.credentialId")
        assertEquals("kp", String(vault.getKeyPassphrase(host.credentialId)!!), "passphrase must be found under host.credentialId")
        // And the key row shown in the UI is that same id.
        assertEquals(host.credentialId, key.id)
        // Nothing is stored under any other id.
        assertEquals(setOf(host.credentialId), vault.privateKeys.keys)
        assertEquals(setOf(host.credentialId), vault.keyPassphrases.keys)
    }

    @Test
    fun `ssh key host without a key repository still finds its key and passphrase under credentialId`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nAAA\n-----END RSA PRIVATE KEY-----"

        val entry = ParsedHost(
            label = "legacy",
            hostname = "old.example",
            port = 22,
            username = "root",
            authType = AuthType.SSH_KEY,
            group = null,
            privateKeyPem = pem,
            keyPassphrase = "kp".toCharArray(),
        )

        // No SshKeyRepository: the key lands in the vault with no metadata row.
        service.import(listOf(entry), hosts, vault)

        val host = hosts.saved.single()
        assertEquals(pem, vault.getPrivateKey(host.credentialId), "private key must be found under host.credentialId")
        assertEquals("kp", String(vault.getKeyPassphrase(host.credentialId)!!), "passphrase must be found under host.credentialId")
        assertEquals(setOf(host.credentialId), vault.privateKeys.keys)
        assertEquals(setOf(host.credentialId), vault.keyPassphrases.keys)
        assertTrue(vault.passwords.isEmpty())
    }

    @Test
    fun `ssh key host without passphrase stores no passphrase`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----"

        val entry = ParsedHost("plain", "h.example", 22, "u", AuthType.SSH_KEY, null, privateKeyPem = pem)

        service.import(listOf(entry), hosts, vault, keys)

        val host = hosts.saved.single()
        assertEquals(pem, vault.getPrivateKey(host.credentialId))
        assertNull(vault.getKeyPassphrase(host.credentialId))
        assertEquals(host.credentialId, keys.saved.single().id)
    }

    @Test
    fun `each imported key host gets its own credentialId resolving to its own key`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val pemA = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----"
        val pemB = "-----BEGIN OPENSSH PRIVATE KEY-----\nBBB\n-----END OPENSSH PRIVATE KEY-----"

        val a = ParsedHost("a", "a.example", 22, "u", AuthType.SSH_KEY, null, privateKeyPem = pemA)
        val b = ParsedHost("b", "b.example", 22, "u", AuthType.SSH_KEY, null, privateKeyPem = pemB)

        val result = service.import(listOf(a, b), hosts, vault, keys)

        assertEquals(2, result.imported)
        val hostA = hosts.saved.single { it.label == "a" }
        val hostB = hosts.saved.single { it.label == "b" }
        assertTrue(hostA.credentialId != hostB.credentialId, "two key hosts must not share a credentialId")
        assertEquals(pemA, vault.getPrivateKey(hostA.credentialId))
        assertEquals(pemB, vault.getPrivateKey(hostB.credentialId))
        assertEquals(setOf(hostA.credentialId, hostB.credentialId), keys.saved.map { it.id }.toSet())
    }

    @Test
    fun `password host keeps its password under credentialId and creates no key material`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()

        val entry = ParsedHost("web", "10.0.0.1", 22, "root", AuthType.PASSWORD, null, password = "hunter2".toCharArray())

        service.import(listOf(entry), hosts, vault, keys)

        val host = hosts.saved.single()
        assertEquals("hunter2", String(vault.getPassword(host.credentialId)!!))
        assertEquals(setOf(host.credentialId), vault.passwords.keys)
        assertTrue(vault.privateKeys.isEmpty())
        assertTrue(vault.keyPassphrases.isEmpty())
        assertTrue(keys.saved.isEmpty(), "a password host must not create a key row")
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
        // And the secret it had already stored is rolled back: only the good host's password remains.
        assertEquals(setOf(hosts.saved.single().credentialId), vault.passwords.keys)
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

    // ── Atomic import ────────────────────────────────────────────────────────────

    private val keyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test
    fun `secrets are stored before the key row, and the host row comes last`() = runTest {
        val events = mutableListOf<String>()
        val hosts = FakeHostRepository(events = events)
        val vault = FakeVaultManager(events)
        val keys = FakeSshKeyRepository(events = events)
        val entry = ParsedHost(
            "deploy", "git.example", 22, "git", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = "kp".toCharArray(),
        )

        service.import(listOf(entry), hosts, vault, keys)

        assertEquals(
            listOf("vault.storePrivateKey", "vault.storeKeyPassphrase", "key.save", "host.save"),
            events,
        )
    }

    @Test
    fun `password host whose vault write fails leaves no host row, and a re-import creates it once`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        vault.failNext["storePassword"] = 1
        val pw = "hunter2".toCharArray()
        val entry = ParsedHost("web", "10.0.0.1", 22, "root", AuthType.PASSWORD, null, password = pw)

        val first = service.import(listOf(entry), hosts, vault)

        assertEquals(0, first.imported)
        assertEquals(1, first.skipped)
        assertTrue(first.errors.single().contains("web"))
        assertTrue(first.errors.none { it.contains("hunter2") }, "error must not leak the password")
        assertTrue(hosts.saved.isEmpty(), "no host row without its secret")
        assertTrue(vault.passwords.isEmpty())
        assertTrue(pw.all { it == Char(32) }, "password must be wiped on the failure path")

        // The vault is back (re-authenticated): importing again must not find a duplicate.
        val retry = ParsedHost("web", "10.0.0.1", 22, "root", AuthType.PASSWORD, null, password = "hunter2".toCharArray())
        val second = service.import(listOf(retry), hosts, vault)

        assertEquals(1, second.imported)
        assertEquals(1, hosts.saved.size)
        assertEquals("hunter2", String(vault.getPassword(hosts.saved.single().credentialId)!!))
    }

    @Test
    fun `key host whose passphrase write fails rolls back the private key, with no key row nor host row`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        vault.failNext["storeKeyPassphrase"] = 1
        val passphrase = "kp".toCharArray()
        val entry = ParsedHost(
            "deploy", "git.example", 22, "git", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = passphrase,
        )

        val result = service.import(listOf(entry), hosts, vault, keys)

        assertEquals(1, result.skipped)
        assertTrue(vault.privateKeys.isEmpty(), "the private key stored before the failure must be rolled back")
        assertTrue(vault.keyPassphrases.isEmpty())
        assertTrue(keys.saved.isEmpty())
        assertTrue(hosts.saved.isEmpty())
        assertTrue(passphrase.all { it == Char(32) }, "passphrase must be wiped on the failure path")
    }

    @Test
    fun `key host whose key row save fails rolls back its vault secrets and leaves no host row`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository(failOnSave = true)
        val entry = ParsedHost(
            "deploy", "git.example", 22, "git", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = "kp".toCharArray(),
        )

        val result = service.import(listOf(entry), hosts, vault, keys)

        assertEquals(1, result.skipped)
        assertTrue(vault.privateKeys.isEmpty())
        assertTrue(vault.keyPassphrases.isEmpty())
        assertTrue(hosts.saved.isEmpty())
    }

    @Test
    fun `key host whose host row save fails rolls back its secrets and its key row`() = runTest {
        val hosts = FakeHostRepository(failOnHostname = "boom.example")
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val entry = ParsedHost(
            "deploy", "boom.example", 22, "git", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = "kp".toCharArray(),
        )

        val result = service.import(listOf(entry), hosts, vault, keys)

        assertEquals(1, result.skipped)
        assertTrue(vault.privateKeys.isEmpty())
        assertTrue(vault.keyPassphrases.isEmpty())
        assertTrue(keys.saved.isEmpty(), "the key row must be rolled back")
        assertTrue(hosts.saved.isEmpty())
    }

    @Test
    fun `host row inserted before a late save failure is hard deleted`() = runTest {
        val hosts = FakeHostRepository(failOnHostname = "boom.example", failAfterInsert = true)
        val vault = FakeVaultManager()
        val entry = ParsedHost("bad", "boom.example", 22, "u", AuthType.PASSWORD, null, password = "secret".toCharArray())

        val result = service.import(listOf(entry), hosts, vault)

        assertEquals(1, result.skipped)
        assertEquals(1, hosts.hardDeleted.size, "the half-saved host row must be hard deleted")
        assertTrue(hosts.saved.isEmpty())
        assertTrue(vault.passwords.isEmpty())
    }

    @Test
    fun `a failing rollback never escapes the loop and the next entries are still imported`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        // The passphrase write fails, and so does the rollback of the private key.
        vault.failNext["storeKeyPassphrase"] = 1
        vault.failNext["deletePrivateKey"] = 1
        val bad = ParsedHost(
            "bad", "bad.example", 22, "u", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = "kp".toCharArray(),
        )
        val good = ParsedHost("good", "good.example", 22, "u", AuthType.PASSWORD, null, password = "pw".toCharArray())

        val result = service.import(listOf(bad, good), hosts, vault, keys)

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        val error = result.errors.single()
        assertTrue(error.contains("bad"))
        assertTrue(error.contains("storeKeyPassphrase"), "the reported error is the original failure")
        assertTrue(!error.contains("deletePrivateKey"), "the rollback failure must not replace the original error")
        assertEquals("good", hosts.saved.single().label)
        assertTrue(hosts.saved.none { it.label == "bad" })
    }

    @Test
    fun `an Error thrown by a rollback step neither replaces the original failure nor skips the next steps`() = runTest {
        val hosts = FakeHostRepository(failOnHostname = "boom.example")
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        // The host save fails; during the rollback the passphrase delete throws an Error.
        vault.errorNext += "deleteKeyPassphrase"
        val entry = ParsedHost(
            "deploy", "boom.example", 22, "git", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = "kp".toCharArray(),
        )

        val result = service.import(listOf(entry), hosts, vault, keys)

        assertEquals(1, result.skipped)
        assertTrue(result.errors.single().contains("simulated DB failure"), "the original failure is reported")
        assertTrue(vault.privateKeys.isEmpty(), "the private key rollback still ran after the failing step")
        assertTrue(keys.saved.isEmpty())
        assertTrue(hosts.saved.isEmpty())
    }

    @Test
    fun `cancellation in the middle of a store propagates, after the rollback ran to completion`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        vault.yieldOnDelete = true
        val cancelled = ParsedHost(
            "cancelled", "c.example", 22, "u", AuthType.SSH_KEY, null,
            privateKeyPem = keyPem, keyPassphrase = "kp".toCharArray(),
        )
        val never = ParsedHost("never", "n.example", 22, "u", AuthType.PASSWORD, null, password = "pw".toCharArray())

        lateinit var job: Job
        // The private key is stored, then the job is cancelled while the passphrase is stored.
        vault.beforeOp = { op ->
            if (op == "storeKeyPassphrase") {
                job.cancel()
                currentCoroutineContext().ensureActive()
            }
        }
        var thrown: Throwable? = null
        job = launch {
            try {
                service.import(listOf(cancelled, never), hosts, vault, keys)
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        job.join()

        assertTrue(thrown is CancellationException, "the cancellation must propagate out of import")
        assertTrue(job.isCancelled)
        // The rollback deletes suspend (yield) and would throw if they ran in the cancelled job:
        // an empty vault proves they ran under NonCancellable.
        assertTrue(vault.privateKeys.isEmpty(), "the private key stored before the cancel is rolled back")
        assertTrue(keys.saved.isEmpty())
        assertTrue(hosts.saved.isEmpty(), "the run stopped: the next entry is not imported")
        assertTrue(vault.passwords.isEmpty())
        assertTrue(cancelled.keyPassphrase!!.all { it == Char(32) }, "secrets are wiped on the cancellation path")
        assertTrue(never.password!!.all { it == Char(32) }, "the entries that will not be reached are wiped too")
    }

    @Test
    fun `a failure in the middle of a batch only affects its own entry`() = runTest {
        val hosts = FakeHostRepository(failOnHostname = "b.example")
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val a = ParsedHost("a", "a.example", 22, "u", AuthType.SSH_KEY, null, privateKeyPem = "$keyPem-A")
        val b = ParsedHost("b", "b.example", 22, "u", AuthType.SSH_KEY, null, privateKeyPem = "$keyPem-B")
        val c = ParsedHost("c", "c.example", 22, "u", AuthType.SSH_KEY, null, privateKeyPem = "$keyPem-C")

        val result = service.import(listOf(a, b, c), hosts, vault, keys)

        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        val hostA = hosts.saved.single { it.label == "a" }
        val hostC = hosts.saved.single { it.label == "c" }
        assertEquals(setOf(hostA.credentialId, hostC.credentialId), vault.privateKeys.keys)
        assertEquals(setOf(hostA.credentialId, hostC.credentialId), keys.saved.map { it.id }.toSet())
    }

    // ── Key shared by several hosts ──────────────────────────────────────────────

    private fun sharedKeyHost(
        label: String,
        keySourceId: String? = "k1",
        pem: String = keyPem,
        passphrase: CharArray? = "kp".toCharArray(),
        keyLabel: String? = "deploy-key",
    ) = ParsedHost(
        label, "$label.example", 22, "u", AuthType.SSH_KEY, null,
        privateKeyPem = pem, keyPassphrase = passphrase,
        keySourceId = keySourceId, keyLabel = keyLabel,
    )

    @Test
    fun `a key shared by two hosts is stored once and both hosts point at it`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val a = sharedKeyHost("a")
        val b = sharedKeyHost("b")

        val result = service.import(listOf(a, b), hosts, vault, keys)

        assertEquals(2, result.imported)
        val hostA = hosts.saved.single { it.label == "a" }
        val hostB = hosts.saved.single { it.label == "b" }
        assertEquals(hostA.credentialId, hostB.credentialId, "both hosts must be linked to the same key id")
        assertEquals(setOf(hostA.credentialId), vault.privateKeys.keys, "one vault entry for the key")
        assertEquals(setOf(hostA.credentialId), vault.keyPassphrases.keys, "one vault entry for the passphrase")
        val key = keys.saved.single()
        assertEquals(hostA.credentialId, key.id, "one SshKey row, whose id is the hosts credentialId")
        assertEquals("deploy-key", key.label, "the SshKey row takes the label of the key in the export")
        assertEquals(keyPem, vault.getPrivateKey(hostB.credentialId))
        assertEquals("kp", String(vault.getKeyPassphrase(hostB.credentialId)!!))
        assertTrue(a.keyPassphrase!!.all { it == Char(32) } && b.keyPassphrase!!.all { it == Char(32) })
    }

    @Test
    fun `hosts with different keys keep one key each even when they carry key identities`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val a = sharedKeyHost("a", keySourceId = "k1", pem = "$keyPem-A", keyLabel = "key-a")
        val b = sharedKeyHost("b", keySourceId = "k2", pem = "$keyPem-B", keyLabel = "key-b")

        service.import(listOf(a, b), hosts, vault, keys)

        val hostA = hosts.saved.single { it.label == "a" }
        val hostB = hosts.saved.single { it.label == "b" }
        assertTrue(hostA.credentialId != hostB.credentialId)
        assertEquals("$keyPem-A", vault.getPrivateKey(hostA.credentialId))
        assertEquals("$keyPem-B", vault.getPrivateKey(hostB.credentialId))
        assertEquals(setOf("key-a", "key-b"), keys.saved.map { it.label }.toSet())
    }

    @Test
    fun `the same key identity with a different PEM is not linked to the earlier key`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val a = sharedKeyHost("a", keySourceId = "k1", pem = "$keyPem-A")
        val b = sharedKeyHost("b", keySourceId = "k1", pem = "$keyPem-B")

        service.import(listOf(a, b), hosts, vault, keys)

        val hostA = hosts.saved.single { it.label == "a" }
        val hostB = hosts.saved.single { it.label == "b" }
        assertTrue(hostA.credentialId != hostB.credentialId, "a host must never be linked to a different key")
        assertEquals("$keyPem-A", vault.getPrivateKey(hostA.credentialId))
        assertEquals("$keyPem-B", vault.getPrivateKey(hostB.credentialId))
    }

    @Test
    fun `keys without an identity are not shared, and the SshKey label falls back to the host label`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val a = sharedKeyHost("a", keySourceId = null, keyLabel = null)
        val b = sharedKeyHost("b", keySourceId = null, keyLabel = null)

        service.import(listOf(a, b), hosts, vault, keys)

        assertEquals(2, vault.privateKeys.size, "same PEM without identity: one copy per host, as before")
        assertEquals(setOf("a", "b"), keys.saved.map { it.label }.toSet())
    }

    @Test
    fun `a vault failure on the first host of a shared key leaves nothing orphaned and the next host stores the key`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        vault.failNext["storePrivateKey"] = 1

        val result = service.import(listOf(sharedKeyHost("a"), sharedKeyHost("b")), hosts, vault, keys)

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        val hostB = hosts.saved.single()
        assertEquals("b", hostB.label, "host a failed, no row of it is left")
        assertEquals(setOf(hostB.credentialId), vault.privateKeys.keys, "nothing stored for the failed host")
        assertEquals(setOf(hostB.credentialId), vault.keyPassphrases.keys)
        assertEquals(listOf(hostB.credentialId), keys.saved.map { it.id })
    }

    @Test
    fun `a failure on the second host of a shared key keeps the key and leaves no row for that host`() = runTest {
        val hosts = FakeHostRepository(failOnHostname = "b.example")
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()

        val result = service.import(
            listOf(sharedKeyHost("a"), sharedKeyHost("b"), sharedKeyHost("c")),
            hosts, vault, keys,
        )

        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        val hostA = hosts.saved.single { it.label == "a" }
        val hostC = hosts.saved.single { it.label == "c" }
        assertTrue(hosts.saved.none { it.label == "b" })
        // The failure of b must not roll back the key a stored: a and c still use it.
        assertEquals(hostA.credentialId, hostC.credentialId)
        assertEquals(setOf(hostA.credentialId), vault.privateKeys.keys)
        assertEquals(setOf(hostA.credentialId), vault.keyPassphrases.keys)
        assertEquals(listOf(hostA.credentialId), keys.saved.map { it.id })
    }

    @Test
    fun `the same key identity and PEM with a different passphrase is not linked`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()
        val a = sharedKeyHost("a", passphrase = "kp-one".toCharArray())
        val b = sharedKeyHost("b", passphrase = "kp-two".toCharArray())
        val c = sharedKeyHost("c", passphrase = "kp-one".toCharArray())

        service.import(listOf(a, b, c), hosts, vault, keys)

        val hostA = hosts.saved.single { it.label == "a" }
        val hostB = hosts.saved.single { it.label == "b" }
        val hostC = hosts.saved.single { it.label == "c" }
        assertTrue(hostA.credentialId != hostB.credentialId, "another passphrase means another key")
        assertEquals("kp-one", String(vault.getKeyPassphrase(hostA.credentialId)!!))
        assertEquals("kp-two", String(vault.getKeyPassphrase(hostB.credentialId)!!))
        assertEquals(hostA.credentialId, hostC.credentialId, "same PEM and same passphrase as a: linked")
    }

    @Test
    fun `a key shared by two hosts is stored once even without a key repository`() = runTest {
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()

        val result = service.import(listOf(sharedKeyHost("a"), sharedKeyHost("b")), hosts, vault)

        assertEquals(2, result.imported)
        val hostA = hosts.saved.single { it.label == "a" }
        val hostB = hosts.saved.single { it.label == "b" }
        assertEquals(hostA.credentialId, hostB.credentialId)
        assertEquals(setOf(hostA.credentialId), vault.privateKeys.keys)
        assertEquals(setOf(hostA.credentialId), vault.keyPassphrases.keys)
    }

    @Test
    fun `when the first host of a shared key fails to save, nothing is remembered and the next host stores the key`() = runTest {
        // a's key and key row are stored, then its host row fails: all of it is rolled back.
        val hosts = FakeHostRepository(failOnHostname = "a.example")
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()

        val result = service.import(listOf(sharedKeyHost("a"), sharedKeyHost("b")), hosts, vault, keys)

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        val hostB = hosts.saved.single()
        assertEquals("b", hostB.label)
        // b did not link to a's rolled-back key: it stored its own, and only its own exists.
        assertEquals(setOf(hostB.credentialId), vault.privateKeys.keys)
        assertEquals(setOf(hostB.credentialId), vault.keyPassphrases.keys)
        assertEquals(listOf(hostB.credentialId), keys.saved.map { it.id })
        assertEquals(keyPem, vault.getPrivateKey(hostB.credentialId))
    }

    @Test
    fun `a Termius export where three hosts share one key imports a single key`() = runTest {
        val pemJson = keyPem.replace("\n", "\\n")
        val json = """
            {
              "ssh_keys": [ { "id": "k1", "label": "deploy-key", "private_key": "$pemJson", "passphrase": "kp" } ],
              "identities": [ { "id": "i1", "username": "deploy", "ssh_key": "k1" } ],
              "hosts": [
                { "id": "h1", "label": "web", "address": "web.example", "identity": "i1" },
                { "id": "h2", "label": "db", "address": "db.example", "identity": "i1" },
                { "id": "h3", "label": "app", "address": "app.example", "username": "ops", "ssh_key": "k1" }
              ]
            }
        """.trimIndent()
        val hosts = FakeHostRepository()
        val vault = FakeVaultManager()
        val keys = FakeSshKeyRepository()

        val result = service.import(TermiusImporter.parse(json), hosts, vault, keys)

        assertEquals(3, result.imported)
        assertEquals(1, hosts.saved.map { it.credentialId }.toSet().size, "the three hosts share one credentialId")
        assertEquals(1, vault.privateKeys.size)
        assertEquals(1, vault.keyPassphrases.size)
        assertEquals("deploy-key", keys.saved.single().label)
    }

    // ── Fakes ────────────────────────────────────────────────────────────────────

    private class FakeHostRepository(
        private val failOnHostname: String? = null,
        /** When true, save() inserts the row and THEN throws (failure after the insert). */
        private val failAfterInsert: Boolean = false,
        private val events: MutableList<String> = mutableListOf(),
    ) : HostRepository {
        val saved = mutableListOf<Host>()
        val hardDeleted = mutableListOf<String>()
        override suspend fun save(host: Host) {
            if (host.hostname == failOnHostname) {
                if (failAfterInsert) saved += host
                error("simulated DB failure")
            }
            events += "host.save"
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
        override suspend fun hardDelete(id: String) {
            hardDeleted += id
            saved.removeAll { it.id == id }
        }
    }

    private class FakeSshKeyRepository(
        private val failOnSave: Boolean = false,
        private val events: MutableList<String> = mutableListOf(),
    ) : SshKeyRepository {
        val saved = mutableListOf<SshKey>()
        override suspend fun save(key: SshKey) {
            if (failOnSave) error("simulated key row failure")
            events += "key.save"
            saved += key
        }
        override fun observeAll(): Flow<List<SshKey>> = flowOf(saved)
        override suspend fun getById(id: String): SshKey? = saved.firstOrNull { it.id == id }
        override suspend fun update(key: SshKey) {}
        override suspend fun delete(id: String) {}
        override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) {}
        override suspend fun hardDelete(id: String) { saved.removeAll { it.id == id } }
    }

    private class FakeVaultManager(private val events: MutableList<String> = mutableListOf()) : VaultManager {
        val passwords = mutableMapOf<String, CharArray>()
        val privateKeys = mutableMapOf<String, String>()
        val keyPassphrases = mutableMapOf<String, CharArray>()
        val certificates = mutableMapOf<String, String>()

        /** Operation name (e.g. "storePrivateKey") to the number of upcoming calls that must throw. */
        val failNext = mutableMapOf<String, Int>()

        /** Operations whose next call throws an Error (not an Exception), consumed on use. */
        val errorNext = mutableSetOf<String>()

        /** Called first by every operation, e.g. to cancel the running job in the middle of a store. */
        var beforeOp: (suspend (String) -> Unit)? = null

        /** When true, deletes suspend at a cancellation point (yield), like a real vault does. */
        var yieldOnDelete = false

        private suspend fun maybeFail(op: String) {
            beforeOp?.invoke(op)
            if (yieldOnDelete && op.startsWith("delete")) yield()
            if (errorNext.remove(op)) throw AssertionError("simulated error ($op)")
            val remaining = failNext[op] ?: 0
            if (remaining > 0) {
                failNext[op] = remaining - 1
                throw IllegalStateException("simulated vault failure ($op)")
            }
        }

        override suspend fun storePassword(credentialId: String, password: CharArray) {
            maybeFail("storePassword")
            events += "vault.storePassword"
            // Copy: the service wipes the caller's array after this returns.
            passwords[credentialId] = password.copyOf()
        }
        override suspend fun getPassword(credentialId: String): CharArray? = passwords[credentialId]
        override suspend fun deletePassword(credentialId: String) {
            maybeFail("deletePassword")
            passwords.remove(credentialId)
        }
        override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) {
            maybeFail("storePrivateKey")
            events += "vault.storePrivateKey"
            privateKeys[keyId] = privateKeyPem
        }
        override suspend fun getPrivateKey(keyId: String): String? = privateKeys[keyId]
        override suspend fun deletePrivateKey(keyId: String) {
            maybeFail("deletePrivateKey")
            privateKeys.remove(keyId)
        }
        override suspend fun storeCertificate(certId: String, certPem: String) { certificates[certId] = certPem }
        override suspend fun getCertificate(certId: String): String? = certificates[certId]
        override suspend fun deleteCertificate(certId: String) { certificates.remove(certId) }
        override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) {
            maybeFail("storeKeyPassphrase")
            events += "vault.storeKeyPassphrase"
            keyPassphrases[keyId] = passphrase.copyOf()
        }
        override suspend fun getKeyPassphrase(keyId: String): CharArray? = keyPassphrases[keyId]
        override suspend fun deleteKeyPassphrase(keyId: String) {
            maybeFail("deleteKeyPassphrase")
            keyPassphrases.remove(keyId)
        }
        override suspend fun listStoredKeyPassphraseIds(): List<String> = keyPassphrases.keys.toList()
        override suspend fun wipeVault() { passwords.clear(); privateKeys.clear(); keyPassphrases.clear(); certificates.clear() }
        override suspend fun isInitialized(): Boolean = true
        override suspend fun listStoredCredentialIds(): List<String> = passwords.keys.toList()
        override suspend fun listStoredKeyIds(): List<String> = privateKeys.keys.toList()
        override suspend fun listStoredCertificateIds(): List<String> = certificates.keys.toList()
    }
}
