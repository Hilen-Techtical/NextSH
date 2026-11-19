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
import java.util.Base64

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

    // ── storedKeyState: the question of a prompt that waited for its turn ────

    private fun unknownOf(hostname: String, port: Int, key: PublicKey): HostKeyVerifyResult.Unknown =
        verifier.checkKey(hostname, port, key) as HostKeyVerifyResult.Unknown

    @Test
    fun `a key is stored once the user trusted it, and not before`() {
        val key = generateTestKeyPair()
        val unknown = unknownOf("example.com", 22, key)
        assertEquals(StoredKeyState.NONE, verifier.storedKeyState(unknown), "nothing was trusted yet")

        verifier.acceptHost("example.com:22", key.algorithm, key.encoded)

        assertEquals(StoredKeyState.SAME, verifier.storedKeyState(unknown))
    }

    @Test
    fun `another key for the same host is a different key, never the stored one`() {
        val trusted = generateTestKeyPair()
        val other = generateDifferentKeyPair()
        val unknownOther = unknownOf("example.com", 22, other)
        verifier.acceptHost("example.com:22", trusted.algorithm, trusted.encoded)

        assertEquals(StoredKeyState.DIFFERENT, verifier.storedKeyState(unknownOther), "a different key is never skipped by this")
    }

    @Test
    fun `the same key for another host or port has nothing stored`() {
        val key = generateTestKeyPair()
        val elsewhere = unknownOf("other.example.com", 22, key)
        val otherPort = unknownOf("example.com", 2222, key)
        verifier.acceptHost("example.com:22", key.algorithm, key.encoded)

        assertEquals(StoredKeyState.NONE, verifier.storedKeyState(elsewhere))
        assertEquals(StoredKeyState.NONE, verifier.storedKeyState(otherPort))
    }

    @Test
    fun `an algorithm that differs is a different key`() {
        val key = generateTestKeyPair()
        val unknown = unknownOf("example.com", 22, key)
        verifier.acceptHost("example.com:22", "ssh-ed25519", key.encoded)

        assertEquals(StoredKeyState.DIFFERENT, verifier.storedKeyState(unknown))
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

    // ── Backup: exportEntries / importEntries ─────────────────────────────────

    private fun candidate(hostPort: String, key: PublicKey) =
        KnownHostCandidate(hostPort, key.algorithm, key.encoded)

    private fun fileText(): String = File(tempDir.toFile(), "known_hosts").readText(Charsets.UTF_8)

    @Test
    fun `exportEntries is empty without a file`() {
        assertTrue(verifier.exportEntries().isEmpty())
    }

    @Test
    fun `exportEntries keeps the first line of a host and skips unreadable lines`() {
        val first = generateTestKeyPair()
        val second = generateDifferentKeyPair()
        verifier.acceptHost("example.com:22", first.algorithm, first.encoded)
        verifier.acceptHost("example.com:22", second.algorithm, second.encoded)
        File(tempDir.toFile(), "known_hosts").appendText("garbage\nother.com:22 RSA not*base64\n", Charsets.UTF_8)
        verifier.acceptHost("other.org:22", second.algorithm, second.encoded)

        val entries = verifier.exportEntries()

        assertEquals(listOf("example.com:22", "other.org:22"), entries.map { it.hostPort })
        assertTrue(entries[0].encoded.contentEquals(first.encoded), "the first line wins, like verify()")
    }

    @Test
    fun `importEntries adds an absent host and the key is then trusted`() {
        val key = generateTestKeyPair()

        val outcome = verifier.importEntries(listOf(candidate("new.example.org:22", key)))

        assertEquals(1, outcome.added)
        assertEquals(0, outcome.alreadyKnown)
        assertTrue(outcome.conflicts.isEmpty())
        assertTrue(verifier.checkKey("new.example.org", 22, key) is HostKeyVerifyResult.Trusted)
    }

    @Test
    fun `importEntries counts the same key as already known and does not rewrite the file`() {
        val key = generateTestKeyPair()
        verifier.acceptHost("example.com:22", key.algorithm, key.encoded)
        val before = fileText()

        val outcome = verifier.importEntries(listOf(candidate("example.com:22", key)))

        assertEquals(0, outcome.added)
        assertEquals(1, outcome.alreadyKnown)
        assertEquals(before, fileText())
    }

    @Test
    fun `importEntries keeps the local key when the backup has another one and names the host`() {
        val local = generateTestKeyPair()
        val other = generateDifferentKeyPair()
        verifier.acceptHost("example.com:22", local.algorithm, local.encoded)
        val before = fileText()

        val outcome = verifier.importEntries(listOf(candidate("example.com:22", other), candidate("fresh.com:22", other)))

        assertEquals(listOf("example.com:22"), outcome.conflicts)
        assertEquals(1, outcome.added)
        assertTrue(verifier.checkKey("example.com", 22, local) is HostKeyVerifyResult.Trusted, "the local key stays trusted")
        assertTrue(verifier.checkKey("example.com", 22, other) is HostKeyVerifyResult.Mismatch, "the backup key was not installed")
        assertTrue(fileText().startsWith(before), "existing lines are kept as they were")
    }

    @Test
    fun `importEntries keeps the existing lines and adds a missing final newline`() {
        val key = generateTestKeyPair()
        val file = File(tempDir.toFile(), "known_hosts")
        file.writeText("old.example.org:22 RSA AAAA", Charsets.UTF_8) // no final newline

        verifier.importEntries(listOf(candidate("new.example.org:22", key)))

        val lines = fileText().lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals("old.example.org:22 RSA AAAA", lines[0])
        assertTrue(lines[1].startsWith("new.example.org:22 ${key.algorithm} "))
        assertTrue(fileText().endsWith("\n"))
    }

    @Test
    fun `importEntries treats a duplicate inside the batch like the second sight of a host`() {
        val key = generateTestKeyPair()
        val other = generateDifferentKeyPair()

        val outcome = verifier.importEntries(
            listOf(
                candidate("dup.example.org:22", key),
                candidate("dup.example.org:22", key),
                candidate("dup.example.org:22", other),
            ),
        )

        assertEquals(1, outcome.added)
        assertEquals(1, outcome.alreadyKnown)
        assertEquals(listOf("dup.example.org:22"), outcome.conflicts)
        assertEquals(1, fileText().lines().count { it.isNotEmpty() })
    }

    @Test
    fun `importEntries refuses a host with a space or a control character and writes nothing for it`() {
        val key = generateTestKeyPair()

        val outcome = verifier.importEntries(
            listOf(
                candidate("a b.example.org:22", key),
                candidate("tab\tx.example.org:22", key),
                candidate("nl\nx.example.org:22", key),
                candidate("", key),
                KnownHostCandidate("ok.example.org:22", "bad algo", key.encoded),
            ),
        )

        assertEquals(0, outcome.added)
        assertEquals(5, outcome.rejected)
        assertFalse(File(tempDir.toFile(), "known_hosts").exists(), "nothing added, nothing written")
    }

    @Test
    fun `importEntries leaves no temporary file behind`() {
        val key = generateTestKeyPair()

        verifier.importEntries(listOf(candidate("one.example.org:22", key)))

        assertEquals(listOf("known_hosts"), tempDir.toFile().list()!!.sorted())
    }

    @Test
    fun `an imported entry round-trips through exportEntries`() {
        val key = generateTestKeyPair()
        verifier.importEntries(listOf(candidate("rt.example.org:2222", key)))

        val exported = verifier.exportEntries().single()

        assertEquals("rt.example.org:2222", exported.hostPort)
        assertEquals(key.algorithm, exported.algorithm)
        assertTrue(exported.encoded.contentEquals(key.encoded))
    }

    // ── Held addresses ──────────────────────────────────────────────────────

    @Test
    fun `a held address without a local fingerprint is not added and is counted as held`() {
        val key = generateTestKeyPair()

        val outcome = verifier.importEntries(
            listOf(candidate("held.example.org:22", key), candidate("free.example.org:22", key)),
            held = setOf("held.example.org:22"),
        )

        assertEquals(1, outcome.added)
        assertEquals(listOf("held.example.org:22"), outcome.held)
        assertTrue(verifier.checkKey("held.example.org", 22, key) is HostKeyVerifyResult.Unknown, "the first-connection prompt stays")
        assertTrue(verifier.checkKey("free.example.org", 22, key) is HostKeyVerifyResult.Trusted)
    }

    @Test
    fun `a held address that already has the same fingerprint is already known, not held`() {
        val key = generateTestKeyPair()
        verifier.acceptHost("held.example.org:22", key.algorithm, key.encoded)

        val outcome = verifier.importEntries(listOf(candidate("held.example.org:22", key)), held = setOf("held.example.org:22"))

        assertEquals(1, outcome.alreadyKnown)
        assertTrue(outcome.held.isEmpty())
    }

    @Test
    fun `a held address with another local fingerprint is a conflict, not held`() {
        val local = generateTestKeyPair()
        val other = generateDifferentKeyPair()
        verifier.acceptHost("held.example.org:22", local.algorithm, local.encoded)

        val outcome = verifier.importEntries(listOf(candidate("held.example.org:22", other)), held = setOf("held.example.org:22"))

        assertEquals(listOf("held.example.org:22"), outcome.conflicts)
        assertTrue(outcome.held.isEmpty())
    }

    @Test
    fun `importing the same file twice never approves a held address`() {
        val key = generateTestKeyPair()
        val held = setOf("held.example.org:22")
        val entries = listOf(candidate("held.example.org:22", key))

        verifier.importEntries(entries, held)
        val second = verifier.importEntries(entries, held)

        assertEquals(listOf("held.example.org:22"), second.held)
        assertEquals(0, second.added)
        assertFalse(File(tempDir.toFile(), "known_hosts").exists())
    }

    @Test
    fun `a duplicate of a held address in the batch is held once`() {
        val key = generateTestKeyPair()

        val outcome = verifier.importEntries(
            listOf(candidate("held.example.org:22", key), candidate("held.example.org:22", key)),
            held = setOf("held.example.org:22"),
        )

        assertEquals(listOf("held.example.org:22"), outcome.held)
    }

    // ── Rule on host:port (acceptHost, importEntries, strict reading) ─────────

    private val formatChar = String(Character.toChars(0x202E))

    private fun invalidHostPorts(): List<String> = listOf(
        "a b:22",
        "a.test:22\nb.test:22 RSA AAAA",
        "a.test\t:22",
        "a$formatChar.test:22",
        ":22",
        "a.test",
        "a.test:",
        "a.test:0",
        "a.test:65536",
        "a.test:2x",
        "a.test:123456",
        "${"a".repeat(256)}:22",
        "",
    )

    @Test
    fun `acceptHost refuses an invalid host port, returns false and leaves the file untouched`() {
        val key = generateTestKeyPair()
        verifier.acceptHost("ok.example.org:22", key.algorithm, key.encoded)
        val before = fileText()

        for (hostPort in invalidHostPorts()) {
            assertFalse(verifier.acceptHost(hostPort, key.algorithm, key.encoded), "refused: ${hostPort.length} chars")
        }

        assertEquals(before, fileText())
    }

    @Test
    fun `acceptHost writes nothing for an invalid host even without a file`() {
        val key = generateTestKeyPair()

        assertFalse(verifier.acceptHost("a b:22", key.algorithm, key.encoded))

        assertFalse(File(tempDir.toFile(), "known_hosts").exists())
    }

    @Test
    fun `acceptHost refuses an empty key and a bad algorithm`() {
        val key = generateTestKeyPair()

        assertFalse(verifier.acceptHost("a.test:22", key.algorithm, ByteArray(0)))
        assertFalse(verifier.acceptHost("a.test:22", "bad algo", key.encoded))
        assertFalse(verifier.acceptHost("a.test:22", "", key.encoded))
        assertFalse(File(tempDir.toFile(), "known_hosts").exists())
    }

    @Test
    fun `acceptHost accepts IPv6 hosts and a zone id`() {
        val key = generateTestKeyPair()

        assertTrue(verifier.acceptHost("2001:db8::1:22", key.algorithm, key.encoded))
        assertTrue(verifier.acceptHost("fe80::1%wlan0:22", key.algorithm, key.encoded))
        assertTrue(verifier.acceptHost("a.test:65535", key.algorithm, key.encoded))

        assertTrue(verifier.checkKey("2001:db8::1", 22, key) is HostKeyVerifyResult.Trusted)
        assertTrue(verifier.checkKey("fe80::1%wlan0", 22, key) is HostKeyVerifyResult.Trusted)
    }

    @Test
    fun `importEntries rejects an invalid host port the same way`() {
        val key = generateTestKeyPair()

        val outcome = verifier.importEntries(
            listOf(
                candidate("a$formatChar.test:22", key),
                candidate("a.test:0", key),
                candidate("a.test:70000", key),
                candidate("a b:22", key),
                candidate("ok.example.org:22", key),
            ),
        )

        assertEquals(4, outcome.rejected)
        assertEquals(1, outcome.added)
    }

    @Test
    fun `a line with four fields written by hand is neither read nor listed nor exported`() {
        val key = generateTestKeyPair()
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        val file = File(tempDir.toFile(), "known_hosts")
        file.writeText("example.com:22 ${key.algorithm} $encoded extra\n", Charsets.UTF_8)
        val before = fileText()

        assertTrue(verifier.checkKey("example.com", 22, key) is HostKeyVerifyResult.Unknown)
        assertTrue(verifier.getAllEntries().isEmpty())
        assertTrue(verifier.exportEntries().isEmpty())
        assertEquals(before, fileText(), "an invalid line is ignored, never rewritten")
    }

    @Test
    fun `a line whose host is invalid is ignored by every reading and kept in the file`() {
        val key = generateTestKeyPair()
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        val file = File(tempDir.toFile(), "known_hosts")
        file.writeText("a.test:0 ${key.algorithm} $encoded\nok.example.org:22 ${key.algorithm} $encoded\n", Charsets.UTF_8)
        val before = fileText()

        assertEquals(listOf("ok.example.org:22"), verifier.getAllEntries().map { it.hostPort })
        assertEquals(listOf("ok.example.org:22"), verifier.exportEntries().map { it.hostPort })
        assertTrue(verifier.checkKey("a.test", 0, key) is HostKeyVerifyResult.Unknown)
        assertEquals(before, fileText())
    }

    @Test
    fun `isStorableHostPort gives the verdicts of the shared validator`() {
        assertTrue(KnownHostsVerifier.isStorableHostPort("a.test:22"))
        assertTrue(KnownHostsVerifier.isStorableHostPort("a.test:1"))
        assertTrue(KnownHostsVerifier.isStorableHostPort("a.test:65535"))
        assertTrue(KnownHostsVerifier.isStorableHostPort("2001:db8::1:22"))
        assertTrue(KnownHostsVerifier.isStorableHostPort("${"a".repeat(255)}:22"))
        for (hostPort in invalidHostPorts()) {
            assertFalse(KnownHostsVerifier.isStorableHostPort(hostPort), "refused: ${hostPort.length} chars")
        }
    }
}
