// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.testutil.LEAK_MARKER
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * [KeyUsageReader] with mocked DAOs. The SQL itself (live hosts only, the key's own id, the auth
 * types that carry a key id, tunnels not deleted) is not run here: no Room database runs in these
 * tests. `KeyUsageQueriesGuardTest` reads the queries; what these tests prove is which auth types
 * the reader hands to the queries and what it makes of the answers.
 */
class KeyUsageReaderTest {

    private lateinit var hostDao: HostDao
    private lateinit var tunnelDao: TunnelDao
    private lateinit var reader: KeyUsageReader

    @BeforeEach
    fun setUp() {
        hostDao = mockk()
        tunnelDao = mockk()
        reader = KeyUsageReader(hostDao, tunnelDao)
    }

    @Test
    fun `the auth types that count are the ones whose credential is a key, never PASSWORD`() {
        assertEquals(
            setOf("SSH_KEY", "CERTIFICATE", "FIDO2", "BIOMETRIC_KEY"),
            KeyUsageReader.KEY_AUTH_TYPES.toSet(),
        )
        assertFalse("PASSWORD" in KeyUsageReader.KEY_AUTH_TYPES)
        assertEquals(KeyUsageReader.KEY_AUTH_TYPES.size, KeyUsageReader.KEY_AUTH_TYPES.toSet().size, "no duplicate")
    }

    @Test
    fun `every auth type is either counted or deliberately left out, so a new one forces a decision`() {
        // PASSWORD holds a password id, not a key id. Any other auth type must be in the list,
        // otherwise the warning would silently miss the hosts that use it.
        val counted = KeyUsageReader.KEY_AUTH_TYPES.toSet()
        val expected = AuthType.entries.filter { it != AuthType.PASSWORD }.map { it.name }.toSet()

        assertEquals(expected, counted, "AuthType changed: decide whether the new one carries a key id")
    }

    @Test
    fun `no host means no usage, and the tunnels are not even counted`() = runTest {
        coEvery { hostDao.getLabelsUsingKey("key-1", KeyUsageReader.KEY_AUTH_TYPES) } returns emptyList()

        val usage = reader.usageOf("key-1")

        assertEquals(KeyUsage.NONE, usage)
        assertTrue(usage.hostLabels.isEmpty())
        assertEquals(0, usage.tunnelCount)
        coVerify(exactly = 0) { tunnelDao.countLiveTunnelsOfHostsUsingKey(any(), any()) }
    }

    @Test
    fun `hosts come back in the order the query gave, with the tunnels that go through them`() = runTest {
        coEvery { hostDao.getLabelsUsingKey("key-1", KeyUsageReader.KEY_AUTH_TYPES) } returns listOf("db-02", "web-01")
        coEvery { tunnelDao.countLiveTunnelsOfHostsUsingKey("key-1", KeyUsageReader.KEY_AUTH_TYPES) } returns 3

        val usage = reader.usageOf("key-1")

        assertEquals(KeyUsage(hostLabels = listOf("db-02", "web-01"), tunnelCount = 3), usage)
    }

    @Test
    fun `hosts without tunnels count none`() = runTest {
        coEvery { hostDao.getLabelsUsingKey("key-1", KeyUsageReader.KEY_AUTH_TYPES) } returns listOf("web-01")
        coEvery { tunnelDao.countLiveTunnelsOfHostsUsingKey("key-1", KeyUsageReader.KEY_AUTH_TYPES) } returns 0

        assertEquals(KeyUsage(listOf("web-01"), 0), reader.usageOf("key-1"))
    }

    @Test
    fun `both queries ask for the key itself and the key auth types, nothing else`() = runTest {
        coEvery { hostDao.getLabelsUsingKey(any(), any()) } returns listOf("web-01")
        coEvery { tunnelDao.countLiveTunnelsOfHostsUsingKey(any(), any()) } returns 1

        reader.usageOf("key-42")

        coVerify(exactly = 1) { hostDao.getLabelsUsingKey("key-42", KeyUsageReader.KEY_AUTH_TYPES) }
        coVerify(exactly = 1) { tunnelDao.countLiveTunnelsOfHostsUsingKey("key-42", KeyUsageReader.KEY_AUTH_TYPES) }
        confirmVerified(hostDao, tunnelDao)
    }

    @Test
    fun `a blank key id uses nothing and asks nothing`() = runTest {
        // A host with no key stores an empty credential id: it must never match "the key".
        assertEquals(KeyUsage.NONE, reader.usageOf(""))
        assertEquals(KeyUsage.NONE, reader.usageOf("   "))

        confirmVerified(hostDao, tunnelDao)
    }

    @Test
    fun `a failing read is thrown to the caller, which decides what to show`() {
        coEvery { hostDao.getLabelsUsingKey(any(), any()) } throws IllegalStateException("db closed")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { reader.usageOf("key-1") }
        }
    }

    @Test
    fun `a failing tunnel count keeps the hosts that were read, with no tunnel counted`() = runTest {
        coEvery { hostDao.getLabelsUsingKey(any(), any()) } returns listOf("db-02", "web-01")
        coEvery { tunnelDao.countLiveTunnelsOfHostsUsingKey(any(), any()) } throws IllegalStateException(LEAK_MARKER)

        val usage = reader.usageOf("key-1")

        assertEquals(KeyUsage(hostLabels = listOf("db-02", "web-01"), tunnelCount = 0), usage)
    }

    @Test
    fun `a failing tunnel count logs the class only, never the message nor a label`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            coEvery { hostDao.getLabelsUsingKey(any(), any()) } returns listOf("my-secret-host-label")
            coEvery { tunnelDao.countLiveTunnelsOfHostsUsingKey(any(), any()) } throws IllegalStateException(LEAK_MARKER)

            reader.usageOf("my-secret-key-id")

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertFalse(entry.message.contains("my-secret-host-label"), "log leaks a label")
                assertFalse(entry.message.contains("my-secret-key-id"), "log leaks the key id")
                assertEquals(null, entry.throwable, "no throwable (its message and trace) may be logged")
            }
            assertTrue(tree.entries.any { it.message.contains("IllegalStateException") })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `a cancelled tunnel count is not swallowed`() {
        coEvery { hostDao.getLabelsUsingKey(any(), any()) } returns listOf("web-01")
        coEvery { tunnelDao.countLiveTunnelsOfHostsUsingKey(any(), any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { reader.usageOf("key-1") }
        }
    }
}
