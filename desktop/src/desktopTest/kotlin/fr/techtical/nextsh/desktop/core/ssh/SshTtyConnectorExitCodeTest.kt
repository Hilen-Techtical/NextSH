// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.connection.channel.direct.Signal
import com.jediterm.core.util.TermSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exit code of a shell: the server sends exit-status AFTER the end of the stream and before CLOSE, so
 * [SshTtyConnector.exitCode] waits (bounded) for the channel to close before reading it.
 */
class SshTtyConnectorExitCodeTest {

    private fun channel(): SessionChannel {
        val channel = mockk<SessionChannel>(relaxed = true)
        every { channel.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { channel.outputStream } returns ByteArrayOutputStream()
        return channel
    }

    @Test
    fun `the exit status is read after the channel closed`() {
        val channel = channel()
        var closed = false
        every { channel.join(any<Long>(), any<TimeUnit>()) } answers { closed = true }
        every { channel.exitStatus } answers { if (closed) 7 else null }

        assertEquals(7, SshTtyConnector(channel).exitCode())

        verifyOrder {
            channel.join(any<Long>(), any<TimeUnit>())
            channel.exitStatus
        }
    }

    @Test
    fun `a signal is reported as 128 plus its number`() {
        val channel = channel()
        every { channel.exitStatus } returns null
        every { channel.exitSignal } returns Signal.TERM

        assertEquals(143, SshTtyConnector(channel).exitCode())
    }

    @Test
    fun `a failing wait still reads what is there`() {
        val channel = channel()
        every { channel.join(any<Long>(), any<TimeUnit>()) } throws RuntimeException("timeout")
        every { channel.exitStatus } returns 2

        assertEquals(2, SshTtyConnector(channel).exitCode())
    }

    @Test
    fun `no code at all is null`() {
        val channel = channel()
        every { channel.exitStatus } returns null
        every { channel.exitSignal } returns null

        assertNull(SshTtyConnector(channel).exitCode())
    }

    @Test
    fun `closing the connector marks the end as ours`() {
        val shell = mockk<Session.Shell>(relaxed = true)
        every { shell.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { shell.outputStream } returns ByteArrayOutputStream()
        val connector = SshTtyConnector(shell)

        assertTrue(!connector.closedLocally)
        connector.close()

        assertTrue(connector.closedLocally)
    }

    @Test
    fun `a resize on a closed or ended channel is never sent`() {
        // sshd answers a request for a channel it closed by dropping the whole connection.
        for ((open, eof) in listOf(false to true, false to false, true to true)) {
            val channel = channel()
            every { channel.isOpen } returns open
            every { channel.isEOF } returns eof

            SshTtyConnector(channel).resize(TermSize(100, 20))

            verify(exactly = 0) { channel.changeWindowDimensions(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `a resize on a live channel is sent once`() {
        val channel = channel()
        every { channel.isOpen } returns true
        every { channel.isEOF } returns false

        SshTtyConnector(channel).resize(TermSize(100, 20))

        verify(exactly = 1) { channel.changeWindowDimensions(100, 20, 0, 0) }
    }

    @Test
    fun `a resize after a local close is never sent`() {
        val channel = channel()
        every { channel.isOpen } returns true
        every { channel.isEOF } returns false
        val connector = SshTtyConnector(channel)
        connector.close()

        connector.resize(TermSize(100, 20))

        verify(exactly = 0) { channel.changeWindowDimensions(any(), any(), any(), any()) }
    }
}
