// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The host key refused by SSHJ (`TransportException(HOST_KEY_NOT_VERIFIABLE)`, from `client.connect()`
 * or from a re-key) is recognized by its disconnect reason, wherever it sits in the cause chain,
 * and never by the wording of its message.
 */
class HostKeyFailureTest {

    private fun refused() = TransportException(
        DisconnectReason.HOST_KEY_NOT_VERIFIABLE,
        "Could not verify `ssh-ed25519` host key with fingerprint SHA256:abc for host.example.com on port 22",
    )

    @Test
    fun `the exception of client connect is recognized`() {
        assertTrue(isHostKeyFailure(refused()))
    }

    @Test
    fun `it is recognized under a wrapper`() {
        assertTrue(isHostKeyFailure(IOException("connect failed", refused())))
        assertTrue(isHostKeyFailure(UserAuthException(refused())))
        assertTrue(isHostKeyFailure(UserAuthException("Exhausted available authentication methods", UserAuthException(refused()))))
        assertTrue(isHostKeyFailure(IllegalStateException("outer", ConnectionException("middle", refused()))))
    }

    @Test
    fun `the message alone decides nothing`() {
        // The same words, another reason: not a host key refusal.
        assertFalse(isHostKeyFailure(TransportException(DisconnectReason.CONNECTION_LOST, "Could not verify host key")))
        assertFalse(isHostKeyFailure(TransportException("Could not verify host key")))
        assertFalse(isHostKeyFailure(IOException("verify host key failed")))
    }

    @Test
    fun `other transport failures are not host key failures`() {
        for (reason in DisconnectReason.entries.filter { it != DisconnectReason.HOST_KEY_NOT_VERIFIABLE }) {
            assertFalse(isHostKeyFailure(TransportException(reason, "x")), "$reason")
        }
    }

    @Test
    fun `a cause cycle ends`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)

        assertFalse(isHostKeyFailure(a))
    }

    // ── A prompt that ended without an answer is not a key that does not match ──

    @Test
    fun `a prompt that ended carries its end, found under a wrapper`() {
        for (end in HostKeyPromptEnd.entries) {
            val failure = HostKeyPromptEndedException(end, refused())

            assertEquals(end, hostKeyPromptEnd(failure))
            assertEquals(end, hostKeyPromptEnd(IllegalStateException("outer", failure)))
        }
    }

    @Test
    fun `it still looks like SSHJ's refusal of the key, which is why the end is looked at first`() {
        // The exception SSHJ throws is the same for a key that changed and for a prompt nobody
        // answered: only the marker tells them apart.
        assertTrue(isHostKeyFailure(HostKeyPromptEndedException(HostKeyPromptEnd.NO_SCREEN, refused())))
    }

    @Test
    fun `a plain refusal of the key or an unrelated failure carries no end`() {
        assertNull(hostKeyPromptEnd(refused()))
        assertNull(hostKeyPromptEnd(IOException("connect failed", refused())))
        assertNull(hostKeyPromptEnd(IOException("Could not verify host key")))
    }

    @Test
    fun `a cause cycle ends for the end too`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)

        assertNull(hostKeyPromptEnd(a))
    }

    @Test
    fun `no end becomes the error of a key that changed`() {
        for (end in HostKeyPromptEnd.entries) {
            assertNotEquals(SshErrorCode.HOST_KEY_MISMATCH, hostKeyPromptEndCode(end), "$end")
        }
    }

    @Test
    fun `every end is non-retryable, so that nothing opens the same prompt again by itself`() {
        for (end in HostKeyPromptEnd.entries) {
            assertTrue(hostKeyPromptEndCode(end).isNonRetryable(), "$end")
        }
    }

    @Test
    fun `no screen and no answer in time read as a prompt nobody answered, a cancelled connection as cancelled`() {
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, hostKeyPromptEndCode(HostKeyPromptEnd.NO_SCREEN))
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, hostKeyPromptEndCode(HostKeyPromptEnd.TIMED_OUT))
        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, hostKeyPromptEndCode(HostKeyPromptEnd.CANCELLED))
    }

    @Test
    fun `each end has a message of its own, and none is the one of a key that changed`() {
        val messages = HostKeyPromptEnd.entries.map { hostKeyPromptEndMessageRes(it) }

        assertEquals(messages.size, messages.toSet().size, "$messages")
        assertFalse(R.string.auth_error_host_key in messages)
        assertEquals(R.string.auth_error_host_key_no_screen, hostKeyPromptEndMessageRes(HostKeyPromptEnd.NO_SCREEN))
        assertEquals(R.string.auth_error_host_key_timeout, hostKeyPromptEndMessageRes(HostKeyPromptEnd.TIMED_OUT))
    }
}
