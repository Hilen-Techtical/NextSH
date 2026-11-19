// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reservations of a drain, against a session manager that counts: what is given back, and how
 * many times. The stories with real sessions are in [SessionLifecycleScenarioTest].
 */
class SftpReservationsTest {

    private val sftpClient = mockk<SFTPClient>(relaxed = true)
    private val sshClient = mockk<SSHClient>(relaxed = true).also { every { it.newSFTPClient() } returns sftpClient }
    private val ends = slot<SessionEndListener>()
    private val sessionManager = mockk<SshSessionManager>(relaxed = true).also {
        every { it.addSessionEndListener(capture(ends)) } just Runs
        every { it.getClient("s1") } returns sshClient
        every { it.acquire("s1") } returns true
    }
    private val sftpManager = SftpManager(sessionManager)

    @Test
    fun `a reservation is handed out again while its session is there`() = runTest {
        val reservations = SftpReservations(sftpManager)

        val first = (reservations.obtain("s1") as SshResult.Success).data
        val second = (reservations.obtain("s1") as SshResult.Success).data

        assertSame(first, second)
        verify(exactly = 1) { sessionManager.acquire("s1") }
    }

    @Test
    fun `a reservation whose session died is dropped, given back once, and the next ask fails cleanly`() = runTest {
        val reservations = SftpReservations(sftpManager)
        assertTrue(reservations.obtain("s1") is SshResult.Success)

        // The Wi-Fi drops: the session ends, and the SFTP client with it. The manager no longer
        // knows the session, so a new reservation cannot be taken.
        ends.captured.onSessionEnded("s1", SessionEnd.Lost("CONNECTION_LOST"))
        every { sessionManager.acquire("s1") } returns false
        every { sessionManager.getClient("s1") } returns null

        val second = reservations.obtain("s1")
        val third = reservations.obtain("s1")

        assertTrue(second is SshResult.Error, "not the stale reservation")
        assertTrue(third is SshResult.Error)
        // The stale reservation was given back once, when it was dropped: nothing more at the end.
        reservations.releaseAll()
        verify(exactly = 1) { sessionManager.release("s1") }
        assertFalse(sftpManager.isOpen("s1"))
    }

    @Test
    fun `a stale reservation is replaced by a new one when the session is still there`() = runTest {
        val reservations = SftpReservations(sftpManager)
        val stale = (reservations.obtain("s1") as SshResult.Success).data
        // The SFTP client was closed under the reservation (its channel), the session is alive.
        ends.captured.onSessionEnded("s1", SessionEnd.Closed)

        val fresh = (reservations.obtain("s1") as SshResult.Success).data

        assertFalse(stale === fresh)
        verify(exactly = 2) { sessionManager.acquire("s1") }
    }
}
