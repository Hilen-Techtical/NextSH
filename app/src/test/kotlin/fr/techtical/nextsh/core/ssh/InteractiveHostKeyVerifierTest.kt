// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.PublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What SSHJ's verifier does with the answer of the prompt, and above all how it remembers the
 * prompts that ended without one: SSHJ only sees a refusal of the key, which the connection would
 * read as a key that changed.
 */
class InteractiveHostKeyVerifierTest {

    // A relaxed mock would answer false to acceptHost, which now means "not stored": stored by default.
    private val knownHosts = mockk<KnownHostsVerifier>(relaxed = true).also {
        every { it.acceptHost(any(), any(), any()) } returns true
    }
    private val key = mockk<PublicKey> {
        every { algorithm } returns "ssh-ed25519"
        every { encoded } returns byteArrayOf(1, 2, 3)
    }
    private val unknown = HostKeyVerifyResult.Unknown("prod.example.org:22", "ssh-ed25519", "SHA256:abc")

    private var callback: (suspend (HostKeyVerifyResult.Unknown) -> HostKeyPromptResponse)? = null
    private var connection: Job? = null

    private fun verifier() = InteractiveHostKeyVerifier(knownHosts, { callback }, connection)

    private fun unknownKey() {
        every { knownHosts.checkKey("prod.example.org", 22, key) } returns unknown
    }

    private fun answering(response: HostKeyPromptResponse) {
        callback = { response }
    }

    @Test
    fun `a trusted key goes through with no prompt`() {
        every { knownHosts.checkKey("prod.example.org", 22, key) } returns HostKeyVerifyResult.Trusted
        var asked = false
        callback = { asked = true; HostKeyPromptResponse.Trusted }
        val verifier = verifier()

        assertTrue(verifier.verify("prod.example.org", 22, key))

        assertFalse(asked)
        assertNull(verifier.promptEnd)
    }

    @Test
    fun `a key that changed is refused with no prompt, and it is not a prompt that ended`() {
        every { knownHosts.checkKey("prod.example.org", 22, key) } returns
            HostKeyVerifyResult.Mismatch("prod.example.org:22", "SHA256:old", "SHA256:new")
        var asked = false
        callback = { asked = true; HostKeyPromptResponse.Trusted }
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        assertFalse(asked)
        assertNull(verifier.promptEnd, "this one IS a key that does not match")
    }

    @Test
    fun `a key the user trusts is stored and accepted`() {
        unknownKey()
        answering(HostKeyPromptResponse.Trusted)
        val verifier = verifier()

        assertTrue(verifier.verify("prod.example.org", 22, key))

        verify(exactly = 1) { knownHosts.acceptHost("prod.example.org:22", "ssh-ed25519", byteArrayOf(1, 2, 3)) }
        assertNull(verifier.promptEnd)
    }

    @Test
    fun `a trusted key that cannot be stored is refused`() {
        unknownKey()
        answering(HostKeyPromptResponse.Trusted)
        every { knownHosts.acceptHost(any(), any(), any()) } returns false
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))
    }

    @Test
    fun `an invalid host is refused with no prompt`() {
        every { knownHosts.checkKey("bad host", 22, key) } returns
            HostKeyVerifyResult.Unknown("bad host:22", "ssh-ed25519", "SHA256:abc")
        var asked = false
        callback = { asked = true; HostKeyPromptResponse.Trusted }
        val verifier = verifier()

        assertFalse(verifier.verify("bad host", 22, key))

        assertFalse(asked)
        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
    }

    @Test
    fun `a key trusted while the request waited goes through, and is not stored a second time`() {
        unknownKey()
        answering(HostKeyPromptResponse.AlreadyTrusted)
        val verifier = verifier()

        assertTrue(verifier.verify("prod.example.org", 22, key))

        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
        assertNull(verifier.promptEnd)
    }

    @Test
    fun `a key the user refuses is refused and stored nowhere, without a marker`() {
        unknownKey()
        answering(HostKeyPromptResponse.Refused)
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
        assertNull(verifier.promptEnd, "a refusal keeps its reading: the key is not trusted")
    }

    @Test
    fun `a prompt with no screen is refused, stored nowhere, and marked NO_SCREEN`() {
        unknownKey()
        answering(HostKeyPromptResponse.NoScreen)
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
        assertEquals(HostKeyPromptEnd.NO_SCREEN, verifier.promptEnd)
    }

    @Test
    fun `a prompt nobody answered in time is refused, stored nowhere, and marked TIMED_OUT`() {
        unknownKey()
        answering(HostKeyPromptResponse.TimedOut)
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
        assertEquals(HostKeyPromptEnd.TIMED_OUT, verifier.promptEnd)
    }

    @Test
    fun `with no callback installed the key is refused as before, without a marker`() {
        unknownKey()
        callback = null
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        assertNull(verifier.promptEnd)
    }

    @Test
    fun `the callback is read when the key is checked, not when the verifier is built`() {
        unknownKey()
        callback = null
        val verifier = verifier()
        // Installed after the client was built, as App.onCreate does for the first connection.
        answering(HostKeyPromptResponse.Trusted)

        assertTrue(verifier.verify("prod.example.org", 22, key))
    }

    @Test
    fun `the known keys of a host are the ones of the known hosts`() {
        every { knownHosts.findExistingAlgorithms("prod.example.org", 22) } returns listOf("ssh-ed25519")

        assertEquals(listOf("ssh-ed25519"), verifier().findExistingAlgorithms("prod.example.org", 22))
    }

    // ── The prompt is a child of the connection ───────────────────────────────

    @Test
    fun `cancelling the connection while the prompt is up refuses the key at once and marks CANCELLED`() {
        unknownKey()
        val connecting = Job()
        connection = connecting
        val promptUp = CountDownLatch(1)
        callback = {
            promptUp.countDown()
            awaitCancellation()
        }
        val verifier = verifier()

        val result = CompletableFuture.supplyAsync { verifier.verify("prod.example.org", 22, key) }
        assertTrue(promptUp.await(5, TimeUnit.SECONDS), "the prompt is up")
        connecting.cancel()

        assertFalse(result.get(5, TimeUnit.SECONDS), "the handshake ends instead of waiting for a person who is not coming")
        assertEquals(HostKeyPromptEnd.CANCELLED, verifier.promptEnd)
        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
    }

    @Test
    fun `a connection that failed takes the prompt still up or queued for it away`() {
        unknownKey()
        // The connection itself goes on (its job is not cancelled): it is the failure path that abandons.
        connection = Job()
        val promptUp = CountDownLatch(1)
        callback = {
            promptUp.countDown()
            awaitCancellation()
        }
        val verifier = verifier()

        val result = CompletableFuture.supplyAsync { verifier.verify("prod.example.org", 22, key) }
        assertTrue(promptUp.await(5, TimeUnit.SECONDS), "the prompt is up")
        verifier.abandonPrompt()

        assertFalse(result.get(5, TimeUnit.SECONDS), "the thread of the handshake is released")
        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
    }

    @Test
    fun `a prompt that comes after the connection was abandoned is never shown`() {
        unknownKey()
        var asked = false
        callback = { asked = true; HostKeyPromptResponse.Trusted }
        val verifier = verifier()

        // The connection gave up (SSHJ's wait ran out); the thread that was still on its way to ask now arrives.
        verifier.abandonPrompt()
        val accepted = verifier.verify("prod.example.org", 22, key)

        assertFalse(accepted)
        assertFalse(asked, "no dialog for a connection that no longer exists")
        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
        assertEquals(HostKeyPromptEnd.CANCELLED, verifier.promptEnd)
    }

    @Test
    fun `an abandoned connection still lets a known key through`() {
        every { knownHosts.checkKey("prod.example.org", 22, key) } returns HostKeyVerifyResult.Trusted
        val verifier = verifier()

        verifier.abandonPrompt()

        assertTrue(verifier.verify("prod.example.org", 22, key), "nothing is asked for a key that is trusted")
    }

    @Test
    fun `a prompt abandoned just as it starts never gets its dialog`() {
        unknownKey()
        val connecting = Job()
        connection = connecting
        var asked = false
        lateinit var verifier: InteractiveHostKeyVerifier
        // The callback is read at the moment the prompt starts: the connection is abandoned right there.
        callback = {
            asked = true
            HostKeyPromptResponse.Trusted
        }
        verifier = InteractiveHostKeyVerifier(
            knownHosts,
            { callback.also { verifier.abandonPrompt() } },
            connecting,
        )

        assertFalse(verifier.verify("prod.example.org", 22, key))

        assertFalse(asked)
        assertEquals(HostKeyPromptEnd.CANCELLED, verifier.promptEnd)
        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
    }

    @Test
    fun `abandoning with no prompt up does nothing to a key that was already asked`() {
        unknownKey()
        answering(HostKeyPromptResponse.Trusted)
        val verifier = verifier()

        assertTrue(verifier.verify("prod.example.org", 22, key))
        verifier.abandonPrompt()

        assertNull(verifier.promptEnd)
    }

    @Test
    fun `a prompt that ended leaves nothing behind to hold the connecting coroutine`() {
        unknownKey()
        val connecting = Job()
        connection = connecting
        answering(HostKeyPromptResponse.Trusted)

        verifier().verify("prod.example.org", 22, key)

        // A child that stayed active would keep the connecting coroutine from ever completing.
        assertEquals(0, connecting.children.count(), "no prompt scope left under the connection")
        assertTrue(connecting.isActive, "and the connection itself is untouched")
    }

    @Test
    fun `a connection already cancelled never asks`() {
        unknownKey()
        connection = Job().also { it.cancel() }
        var asked = false
        callback = { asked = true; HostKeyPromptResponse.Trusted }
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        assertFalse(asked)
        assertEquals(HostKeyPromptEnd.CANCELLED, verifier.promptEnd)
    }

    @Test
    fun `a connection that goes on lets the prompt through`() {
        unknownKey()
        connection = Job()
        answering(HostKeyPromptResponse.Trusted)

        assertTrue(verifier().verify("prod.example.org", 22, key))
    }

    @Test
    fun `a trust answer that reaches the connection while the vault is being wiped is refused and stores nothing`() {
        unknownKey()
        answering(HostKeyPromptResponse.Trusted)
        every { knownHosts.isWriteRefused() } returns true
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key), "the connection of an app being wiped does not open")

        verify(exactly = 0) { knownHosts.acceptHost(any(), any(), any()) }
        assertEquals(HostKeyPromptEnd.NO_SCREEN, verifier.promptEnd, "told as nobody on screen, not as a key that changed")
    }

    @Test
    fun `a key that cannot be stored because the gate closed after the check is no key that changed`() {
        unknownKey()
        answering(HostKeyPromptResponse.Trusted)
        // The gate is open when the answer is looked at, closed when the key is stored.
        every { knownHosts.isWriteRefused() } returnsMany listOf(false, true)
        every { knownHosts.acceptHost(any(), any(), any()) } returns false
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        assertEquals(HostKeyPromptEnd.NO_SCREEN, verifier.promptEnd)
    }

    @Test
    fun `a key that cannot be stored for another reason keeps no marker`() {
        unknownKey()
        answering(HostKeyPromptResponse.Trusted)
        every { knownHosts.isWriteRefused() } returns false
        every { knownHosts.acceptHost(any(), any(), any()) } returns false
        val verifier = verifier()

        assertFalse(verifier.verify("prod.example.org", 22, key))

        assertNull(verifier.promptEnd)
    }
}
