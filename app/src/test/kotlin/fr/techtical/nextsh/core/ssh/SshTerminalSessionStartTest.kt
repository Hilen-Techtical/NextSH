// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import com.termux.terminal.TerminalSessionClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A terminal session outlives its view, and may never have one: a tab opened while the app is in
 * the background, or hidden behind another one. Its emulator and its SSH reader used to start at the
 * first `updateSize`, that is, at the first layout of a view. Without an emulator the end of the
 * shell crashed the Handler of Termux (`mEmulator.append` on null), and without a reader the end of
 * the transport was never seen: the tab stayed connected and the service stayed in the foreground.
 *
 * The real session is built here, on the Android stubs of the unit tests: the Handler it creates is
 * inert there, so what is checked is what the constructor starts, not the delivery of a message.
 * `disconnect()` and the end of the shell post a message to that Handler and are not run on the JVM;
 * they can only reach `mEmulator.append` on an emulator that exists now from the start, which is the
 * first test.
 */
class SshTerminalSessionStartTest {

    /** A shell output that says when it is first read, then stays silent: the reader blocks on it. */
    private class SilentOutput : InputStream() {
        val readerStarted = CountDownLatch(1)
        private val never = CountDownLatch(1)

        override fun read(): Int {
            readerStarted.countDown()
            never.await()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
    }

    private fun shellWith(output: InputStream): Session.Shell {
        val shell = mockk<Session.Shell>(relaxed = true)
        every { shell.inputStream } returns output
        return shell
    }

    private fun session(
        shell: Session.Shell = shellWith(SilentOutput()),
        client: TerminalSessionClient = mockk(relaxed = true),
    ) = SshTerminalSession(sshShell = shell, sessionLabel = "label", client = client, transcriptRows = 2000)

    @Test
    fun `the emulator exists as soon as the session is built, with no view to measure it`() {
        val session = session()

        assertNotNull(session.emulator, "the end of the shell appends to it on the main thread")
        assertTrue(session.isRunning)
    }

    @Test
    fun `the emulator starts at the size of the PTY that openShell opens`() {
        val emulator = session().emulator

        assertEquals(DEFAULT_PTY_COLUMNS, emulator.mColumns)
        assertEquals(DEFAULT_PTY_ROWS, emulator.mRows)
        assertEquals(80, DEFAULT_PTY_COLUMNS)
        assertEquals(24, DEFAULT_PTY_ROWS)
    }

    @Test
    fun `a size can be given for a PTY opened at another one`() {
        val session = SshTerminalSession(
            sshShell = shellWith(SilentOutput()),
            sessionLabel = "label",
            client = mockk(relaxed = true),
            transcriptRows = 2000,
            initialColumns = 120,
            initialRows = 30,
        )

        assertEquals(120, session.emulator.mColumns)
        assertEquals(30, session.emulator.mRows)
    }

    @Test
    fun `the reader runs without a view, so the end of the transport is seen`() {
        val output = SilentOutput()

        session(shell = shellWith(output))

        assertTrue(output.readerStarted.await(5, TimeUnit.SECONDS), "the SSH reader reads the shell from the construction")
    }

    @Test
    fun `the client learns the shell is up when the session is built`() {
        val client = mockk<TerminalSessionClient>(relaxed = true)

        val session = session(client = client)

        verify(exactly = 1) { client.setTerminalShellPid(session, 1) }
    }

    @Test
    fun `the first size of a view resizes the emulator and tells the server the real size`() {
        val shell = shellWith(SilentOutput())
        val session = session(shell = shell)
        val emulator = session.emulator

        session.updateSize(100, 40, 9, 18)

        // The same emulator, not a second one: its content is kept and reflowed.
        assertSame(emulator, session.emulator)
        assertEquals(100, emulator.mColumns)
        assertEquals(40, emulator.mRows)
        // Sent on the writer thread: the main thread does not touch the network.
        verify(timeout = 5_000, exactly = 1) { shell.changeWindowDimensions(100, 40, 0, 0) }
    }

    @Test
    fun `a view whose size is the default one still tells the server`() {
        val shell = shellWith(SilentOutput())
        val session = session(shell = shell)

        session.updateSize(80, 24, 9, 18)

        verify(timeout = 5_000, exactly = 1) { shell.changeWindowDimensions(80, 24, 0, 0) }
    }

    @Test
    fun `initializeEmulator on a session that has its emulator keeps it`() {
        val session = session()
        val emulator = session.emulator

        session.initializeEmulator(132, 50, 9, 18)

        assertSame(emulator, session.emulator)
        assertEquals(DEFAULT_PTY_COLUMNS, emulator.mColumns)
    }
}
