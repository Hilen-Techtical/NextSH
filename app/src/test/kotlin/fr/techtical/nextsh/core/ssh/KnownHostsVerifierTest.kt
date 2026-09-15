// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PublicKey

@ExtendWith(MockKExtension::class)
class KnownHostsVerifierTest {

    @MockK
    private lateinit var mockContext: Context

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var verifier: KnownHostsVerifier

    @BeforeEach
    fun setUp() {
        // Mock context to return temp directory as filesDir
        every { mockContext.filesDir } returns tempDir.toFile()
        verifier = KnownHostsVerifier(mockContext)
    }

    // ── Helper functions ──────────────────────────────────────────────────────

    /**
     * Generates a test RSA key pair (2048-bit).
     */
    private fun generateTestKeyPair(): PublicKey {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        return keyGen.generateKeyPair().public
    }

    /**
     * Generates a second different RSA key pair for mismatch testing.
     */
    private fun generateDifferentKeyPair(): PublicKey {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        return keyGen.generateKeyPair().public
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `checkKey returns Unknown for new host`() {
        val hostname = "example.com"
        val port = 22
        val key = generateTestKeyPair()

        val result = verifier.checkKey(hostname, port, key)

        assertTrue(result is HostKeyVerifyResult.Unknown, "Should return Unknown for new host")
        val unknown = result as HostKeyVerifyResult.Unknown
        assertEquals("$hostname:$port", unknown.hostname)
        assertEquals("RSA", unknown.algorithm)
        assertTrue(unknown.fingerprint.startsWith("SHA256:"), "Fingerprint should start with SHA256:")
    }

    @Test
    fun `checkKey returns Trusted for known host`() {
        val hostname = "example.com"
        val port = 22
        val key = generateTestKeyPair()

        // First, accept the host
        verifier.acceptHost("$hostname:$port", key.algorithm, key.encoded)

        // Now check the same key again
        val result = verifier.checkKey(hostname, port, key)

        assertTrue(result is HostKeyVerifyResult.Trusted, "Should return Trusted for known host with same key")
    }

    @Test
    fun `checkKey returns Mismatch when key differs`() {
        val hostname = "example.com"
        val port = 22
        val key1 = generateTestKeyPair()
        val key2 = generateDifferentKeyPair()

        // Accept first key
        verifier.acceptHost("$hostname:$port", key1.algorithm, key1.encoded)

        // Try to connect with different key
        val result = verifier.checkKey(hostname, port, key2)

        assertTrue(result is HostKeyVerifyResult.Mismatch, "Should return Mismatch when key differs")
        val mismatch = result as HostKeyVerifyResult.Mismatch
        assertEquals("$hostname:$port", mismatch.hostname)
        assertTrue(mismatch.storedFingerprint.startsWith("SHA256:"), "Stored fingerprint should have SHA256: prefix")
        assertTrue(mismatch.receivedFingerprint.startsWith("SHA256:"), "Received fingerprint should have SHA256: prefix")
    }

    @Test
    fun `acceptHost saves entry to file`() {
        val hostPort = "example.com:22"
        val algorithm = "RSA"
        val key = generateTestKeyPair()

        verifier.acceptHost(hostPort, algorithm, key.encoded)

        val file = File(tempDir.toFile(), "known_hosts")
        assertTrue(file.exists(), "known_hosts file should be created")
        val content = file.readText()
        assertTrue(content.contains(hostPort), "File should contain the host:port")
        assertTrue(content.contains(algorithm), "File should contain the algorithm")
    }

    @Test
    fun `getAllEntries returns empty list when no file exists`() {
        val entries = verifier.getAllEntries()

        assertTrue(entries.isEmpty(), "Should return empty list when known_hosts file does not exist")
    }

    @Test
    fun `getAllEntries returns parsed entries`() {
        val hostPort1 = "example.com:22"
        val hostPort2 = "github.com:22"
        val algorithm = "RSA"
        val key1 = generateTestKeyPair()
        val key2 = generateTestKeyPair()

        verifier.acceptHost(hostPort1, algorithm, key1.encoded)
        verifier.acceptHost(hostPort2, algorithm, key2.encoded)

        val entries = verifier.getAllEntries()

        assertEquals(2, entries.size, "Should have two entries")
        val hostPorts = entries.map { it.hostPort }
        assertTrue(hostPorts.contains(hostPort1), "Should contain first host")
        assertTrue(hostPorts.contains(hostPort2), "Should contain second host")
    }

    @Test
    fun `getAllEntries entries have correct fingerprint format`() {
        val hostPort = "example.com:22"
        val algorithm = "RSA"
        val key = generateTestKeyPair()

        verifier.acceptHost(hostPort, algorithm, key.encoded)

        val entries = verifier.getAllEntries()

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertTrue(entry.fingerprint.startsWith("SHA256:"), "Fingerprint should start with SHA256:")
        // Base64 should have alphanumeric and +/= chars (but no padding per OpenSSH format)
        assertTrue(entry.fingerprint.length > 10, "Fingerprint should be long enough")
    }

    @Test
    fun `removeEntry removes specific entry`() {
        val hostPort1 = "example.com:22"
        val hostPort2 = "github.com:22"
        val algorithm = "RSA"
        val key1 = generateTestKeyPair()
        val key2 = generateTestKeyPair()

        verifier.acceptHost(hostPort1, algorithm, key1.encoded)
        verifier.acceptHost(hostPort2, algorithm, key2.encoded)

        val removed = verifier.removeEntry(hostPort1)

        assertTrue(removed, "Should return true when entry is removed")
        val entries = verifier.getAllEntries()
        assertEquals(1, entries.size, "Should have one entry after removal")
        assertEquals(hostPort2, entries[0].hostPort, "Remaining entry should be github.com:22")
    }

    @Test
    fun `removeEntry returns false for unknown entry`() {
        val hostPort = "unknown.com:22"

        val removed = verifier.removeEntry(hostPort)

        assertFalse(removed, "Should return false when trying to remove unknown entry")
    }

    @Test
    fun `clearAll deletes the file`() {
        val hostPort = "example.com:22"
        val algorithm = "RSA"
        val key = generateTestKeyPair()

        // Create some entries
        verifier.acceptHost(hostPort, algorithm, key.encoded)
        val fileBeforeClear = File(tempDir.toFile(), "known_hosts")
        assertTrue(fileBeforeClear.exists(), "File should exist before clear")

        // Clear all
        verifier.clearAll()

        val fileAfterClear = File(tempDir.toFile(), "known_hosts")
        assertFalse(fileAfterClear.exists(), "File should be deleted after clearAll")
    }

    @Test
    fun `verify fallback always rejects unknown hosts`() {
        val hostname = "unknown.com"
        val port = 22
        val key = generateTestKeyPair()

        val result = verifier.verify(hostname, port, key)

        assertFalse(result, "verify() should reject unknown hosts (no callback available)")
    }

    @Test
    fun `verify accepts trusted hosts`() {
        val hostname = "example.com"
        val port = 22
        val key = generateTestKeyPair()

        // Accept the host first
        verifier.acceptHost("$hostname:$port", key.algorithm, key.encoded)

        // Now verify should return true
        val result = verifier.verify(hostname, port, key)

        assertTrue(result, "verify() should accept trusted hosts")
    }

    @Test
    fun `verify rejects mismatched hosts`() {
        val hostname = "example.com"
        val port = 22
        val key1 = generateTestKeyPair()
        val key2 = generateDifferentKeyPair()

        // Accept first key
        verifier.acceptHost("$hostname:$port", key1.algorithm, key1.encoded)

        // Try with different key
        val result = verifier.verify(hostname, port, key2)

        assertFalse(result, "verify() should reject hosts with mismatched keys")
    }

    @Test
    fun `Mismatch fingerprints are different`() {
        val hostname = "example.com"
        val port = 22
        val key1 = generateTestKeyPair()
        val key2 = generateDifferentKeyPair()

        // Accept first key
        verifier.acceptHost("$hostname:$port", key1.algorithm, key1.encoded)

        // Check with different key
        val result = verifier.checkKey(hostname, port, key2)

        assertTrue(result is HostKeyVerifyResult.Mismatch)
        val mismatch = result as HostKeyVerifyResult.Mismatch
        assertFalse(
            mismatch.storedFingerprint.equals(mismatch.receivedFingerprint),
            "Stored and received fingerprints should be different"
        )
    }

    @Test
    fun `findExistingAlgorithms returns algorithm for known host`() {
        val hostname = "example.com"
        val port = 22
        val key = generateTestKeyPair()

        verifier.acceptHost("$hostname:$port", key.algorithm, key.encoded)

        val algorithms = verifier.findExistingAlgorithms(hostname, port)

        assertEquals(1, algorithms.size)
        assertEquals(key.algorithm, algorithms[0])
    }

    @Test
    fun `findExistingAlgorithms returns empty list for unknown host`() {
        val hostname = "unknown.com"
        val port = 22

        val algorithms = verifier.findExistingAlgorithms(hostname, port)

        assertTrue(algorithms.isEmpty())
    }

    @Test
    fun `multiple entries with same host different ports are independent`() {
        val hostname = "example.com"
        val port1 = 22
        val port2 = 2222
        val key1 = generateTestKeyPair()
        val key2 = generateTestKeyPair()

        verifier.acceptHost("$hostname:$port1", key1.algorithm, key1.encoded)
        verifier.acceptHost("$hostname:$port2", key2.algorithm, key2.encoded)

        val entries = verifier.getAllEntries()
        assertEquals(2, entries.size, "Should have two independent entries")

        val result1 = verifier.checkKey(hostname, port1, key1)
        assertTrue(result1 is HostKeyVerifyResult.Trusted, "Port 22 should be trusted with key1")

        val result2 = verifier.checkKey(hostname, port2, key2)
        assertTrue(result2 is HostKeyVerifyResult.Trusted, "Port 2222 should be trusted with key2")
    }

    @Test
    fun `Known hosts file has correct format with newlines`() {
        val hostPort1 = "example.com:22"
        val hostPort2 = "github.com:22"
        val algorithm = "RSA"
        val key1 = generateTestKeyPair()
        val key2 = generateTestKeyPair()

        verifier.acceptHost(hostPort1, algorithm, key1.encoded)
        verifier.acceptHost(hostPort2, algorithm, key2.encoded)

        val file = File(tempDir.toFile(), "known_hosts")
        val lines = file.readLines()

        assertEquals(2, lines.size, "Should have two lines")
        assertTrue(lines[0].startsWith(hostPort1))
        assertTrue(lines[1].startsWith(hostPort2))
    }
}
