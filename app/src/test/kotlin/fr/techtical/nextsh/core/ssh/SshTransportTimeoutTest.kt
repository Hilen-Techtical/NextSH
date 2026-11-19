// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.every
import io.mockk.mockk
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `SSHClient.auth` waits for the server's answer for `Transport.timeoutMs` (30 s), and that wait
 * runs while the user touches a security key or types a PIN. The FIDO2 authentication raises it
 * above the longest prompt for its own duration only, as a backstop (the silent server is the job
 * of [AuthWatchdog]), and the SSH session keeps its usual timeout once authenticated.
 */
class SshTransportTimeoutTest {

    private val transport = mockk<Transport>(relaxed = true)
    private val client = mockk<SSHClient>().also { every { it.transport } returns transport }

    /** Every value written to `Transport.timeoutMs`, in order. */
    private val writes = mutableListOf<Int>()

    init {
        every { transport.timeoutMs = any() } answers { writes += firstArg<Int>() }
    }

    @Test
    fun `the timeout is set for the block and restored after it`() {
        val result = client.withTransportTimeout(timeoutMs = 330_000, restoreMs = 30_000) {
            writes += -1 // marks the block among the writes
            "authenticated"
        }

        assertEquals("authenticated", result)
        assertEquals(listOf(330_000, -1, 30_000), writes)
    }

    @Test
    fun `the timeout is restored when the block fails`() {
        assertThrows(IllegalStateException::class.java) {
            client.withTransportTimeout(timeoutMs = 330_000, restoreMs = 30_000) {
                error("auth failed")
            }
        }

        assertEquals(listOf(330_000, 30_000), writes)
    }

    @Test
    fun `the FIDO2 authentication raises the wait and the session gets the usual timeout back`() {
        client.withTransportTimeout(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, TRANSPORT_TIMEOUT_MS) { }

        assertEquals(listOf(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, TRANSPORT_TIMEOUT_MS), writes)
        assertEquals(30_000, TRANSPORT_TIMEOUT_MS)
    }

    @Test
    fun `the FIDO2 wait is finite and outlasts the longest prompt plus the server's answer`() {
        // 0 would make SSHJ wait without limit, and a silent network would then pin the thread for
        // ever: the wait must be a real, finite number, sized on the longest prompt.
        assertTrue(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS > 0, "0 is no limit for SSHJ's Promise: not valid here")
        assertEquals(
            Fido2PromptCoordinator.MAX_PROMPT_TOTAL_MS + TRANSPORT_TIMEOUT_MS,
            FIDO2_AUTH_TRANSPORT_TIMEOUT_MS.toLong(),
        )
        assertTrue(
            FIDO2_AUTH_TRANSPORT_TIMEOUT_MS > Fido2PromptCoordinator.MAX_PROMPT_TOTAL_MS,
            "the wait must not end before the longest prompt does",
        )
    }
}
