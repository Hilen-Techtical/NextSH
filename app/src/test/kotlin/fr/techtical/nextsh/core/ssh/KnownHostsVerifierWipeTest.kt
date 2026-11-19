// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * The known hosts during the "Wipe the vault" action: the file and its temporary sibling go, a file that
 * stays is a failure, and nothing is written while the app is closed (a "trust" answer that arrives late
 * must not write the fingerprint of an erased host into the file).
 */
class KnownHostsVerifierWipeTest {

    @TempDir
    private lateinit var tempDir: Path

    private val gate = VaultWipeGate()
    private val context = mockk<Context>()

    private fun verifier(): KnownHostsVerifier {
        every { context.filesDir } returns tempDir.toFile()
        return KnownHostsVerifier(context, gate)
    }

    private val file get() = File(tempDir.toFile(), "known_hosts")
    private val temp get() = File(tempDir.toFile(), "known_hosts.tmp")

    @Test
    fun `clearAll deletes the file and the temporary one an interrupted write left`() {
        val verifier = verifier()
        verifier.acceptHost("example.com:22", "ssh-ed25519", byteArrayOf(1, 2, 3))
        temp.writeText("example.com:22 ssh-ed25519 AQID\n")

        verifier.clearAll()

        assertFalse(file.exists())
        assertFalse(temp.exists())
    }

    @Test
    fun `clearAll with nothing to delete is fine`() {
        verifier().clearAll()
    }

    @Test
    fun `a file that is still there after the deletion is an error with a fixed text`() {
        val verifier = verifier()
        // A directory in place of the file: it cannot be deleted while it holds something.
        file.mkdirs()
        File(file, "child").writeText("x")

        val failure = assertThrows(IOException::class.java) { verifier.clearAll() }

        assertEquals("Known hosts file could not be deleted", failure.message)
    }

    @Test
    fun `the temporary file is deleted even when the file cannot be`() {
        val verifier = verifier()
        file.mkdirs()
        File(file, "child").writeText("x")
        temp.writeText("x")

        assertThrows(IOException::class.java) { verifier.clearAll() }

        assertFalse(temp.exists(), "every deletion is tried before the error")
    }

    // ── Nothing is written while the app is closed ──────────────────────────

    @Test
    fun `a trusted key is not written while the app is closed, whatever the state of the wipe`() {
        val verifier = verifier()
        for (state in listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)) {
            gate.set(state)

            verifier.acceptHost("erased.example:22", "ssh-ed25519", byteArrayOf(1, 2, 3))

            assertFalse(file.exists(), "nothing written in $state")
            assertTrue(verifier.isWriteRefused())
        }
    }

    @Test
    fun `a key is written again once the app is open`() {
        val verifier = verifier()
        gate.set(VaultWipeState.DONE)
        gate.acknowledgeDone()

        verifier.acceptHost("example.com:22", "ssh-ed25519", byteArrayOf(1, 2, 3))

        assertTrue(file.exists())
        assertFalse(verifier.isWriteRefused())
    }

    @Test
    fun `an import of known hosts writes nothing while the app is closed and counts its entries as rejected`() {
        val verifier = verifier()
        gate.set(VaultWipeState.RUNNING)

        val outcome = verifier.importEntries(
            listOf(KnownHostCandidate("example.com:22", "ssh-ed25519", byteArrayOf(1, 2, 3))),
        )

        assertEquals(0, outcome.added)
        assertEquals(1, outcome.rejected)
        assertFalse(file.exists())
    }
}
