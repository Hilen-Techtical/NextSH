// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import kotlinx.coroutines.test.runTest
import java.security.PublicKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopKnownHostsVerifier].
 *
 * Uses a fake [PublicKey] carrying arbitrary encoded bytes: SSHJ never sees
 * these test keys so we don't need JCA-conformant implementations.
 */
class DesktopKnownHostsVerifierTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: DesktopKnownHostsStore
    private lateinit var verifier: DesktopKnownHostsVerifier

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NextShDatabase.Schema.create(driver)
        store = DesktopKnownHostsStore(NextShDatabase(driver))
        verifier = DesktopKnownHostsVerifier(store)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ── checkKey branches ─────────────────────────────────────────────────────

    @Test
    fun `checkKey returns Unknown when no entry is stored`() {
        val result = verifier.checkKey("host.example.com", 22, fakeKey("ssh-ed25519", byteArrayOf(1, 2)))
        assertIs<HostKeyVerifyResult.Unknown>(result)
        assertEquals("host.example.com:22", result.hostPort)
        assertEquals("ssh-ed25519", result.algorithm)
        assertTrue(result.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `checkKey returns Trusted when stored key matches exactly`() {
        val keyBytes = byteArrayOf(1, 2, 3, 4)
        store.upsert("host.example.com:22", "ssh-ed25519", keyBytes)
        val result = verifier.checkKey("host.example.com", 22, fakeKey("ssh-ed25519", keyBytes))
        assertEquals(HostKeyVerifyResult.Trusted, result)
    }

    @Test
    fun `checkKey returns Mismatch when key bytes differ from stored`() {
        store.upsert("host.example.com:22", "ssh-ed25519", byteArrayOf(1, 2, 3))
        val result = verifier.checkKey("host.example.com", 22, fakeKey("ssh-ed25519", byteArrayOf(9, 9, 9)))
        assertIs<HostKeyVerifyResult.Mismatch>(result)
        assertEquals("host.example.com:22", result.hostPort)
        assertTrue(result.storedFingerprint != result.receivedFingerprint)
    }

    @Test
    fun `checkKey returns Mismatch when algorithm differs even if bytes are equal`() {
        val bytes = byteArrayOf(1, 2, 3)
        store.upsert("host:22", "ssh-rsa", bytes)
        val result = verifier.checkKey("host", 22, fakeKey("ssh-ed25519", bytes))
        assertIs<HostKeyVerifyResult.Mismatch>(result)
    }

    // ── verify() (SSHJ entry point) ───────────────────────────────────────────

    @Test
    fun `verify returns false on Unknown when no UI callback is wired`() {
        val ok = verifier.verify("unknown.example.com", 22, fakeKey("ssh-ed25519", byteArrayOf(1)))
        assertFalse(ok)
    }

    @Test
    fun `verify persists the key and returns true when the callback accepts`() = runTest {
        verifier.unknownHostCallback = { _ -> true }
        val keyBytes = byteArrayOf(7, 8, 9)
        val ok = verifier.verify("new.example.com", 22, fakeKey("ssh-ed25519", keyBytes))
        assertTrue(ok)
        val stored = store.getStored("new.example.com:22")
        assertNotNull(stored)
        assertContentEquals(keyBytes, stored!!.second)
    }

    @Test
    fun `verify does NOT persist the key when the callback rejects`() = runTest {
        verifier.unknownHostCallback = { _ -> false }
        val ok = verifier.verify("new.example.com", 22, fakeKey("ssh-ed25519", byteArrayOf(1)))
        assertFalse(ok)
        assertNull(store.getStored("new.example.com:22"))
    }

    @Test
    fun `verify returns false AND fires mismatchListener on Mismatch`() {
        store.upsert("host:22", "ssh-ed25519", byteArrayOf(1, 2, 3))
        var notified: HostKeyVerifyResult.Mismatch? = null
        verifier.mismatchListener = { notified = it }

        val ok = verifier.verify("host", 22, fakeKey("ssh-ed25519", byteArrayOf(9, 9, 9)))

        assertFalse(ok, "mismatch must reject the connection")
        assertNotNull(notified, "listener must fire so UI can alert the user")
    }

    @Test
    fun `mismatch is rejected even without listener (no silent trust)`() {
        store.upsert("host:22", "ssh-ed25519", byteArrayOf(1))
        // No mismatchListener wired, should still reject
        val ok = verifier.verify("host", 22, fakeKey("ssh-ed25519", byteArrayOf(2)))
        assertFalse(ok)
    }

    @Test
    fun `findExistingAlgorithms returns stored algo for known hosts, empty for unknown`() {
        store.upsert("host:22", "ssh-ed25519", byteArrayOf(1))
        assertEquals(listOf("ssh-ed25519"), verifier.findExistingAlgorithms("host", 22))
        assertEquals(emptyList(), verifier.findExistingAlgorithms("other", 22))
    }

    @Test
    fun `callback accept then a second connect with the same key is Trusted without re-prompting`() = runTest {
        var callCount = 0
        verifier.unknownHostCallback = { _ -> callCount++; true }
        val keyBytes = byteArrayOf(1, 2, 3)

        val first = verifier.verify("host", 22, fakeKey("ssh-ed25519", keyBytes))
        val second = verifier.verify("host", 22, fakeKey("ssh-ed25519", keyBytes))

        assertTrue(first)
        assertTrue(second)
        assertEquals(1, callCount, "second connect must skip the prompt: callback only fires once")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fakeKey(algo: String, encoded: ByteArray): PublicKey = object : PublicKey {
        override fun getAlgorithm() = algo
        override fun getFormat() = "X.509"
        override fun getEncoded() = encoded
    }
}
