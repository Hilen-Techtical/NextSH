// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

// ──────────────────────────────────────────────────────────────────────────────
// `onShellExited`: reported ONCE when the remote shell ends by itself (end of stream), never when the end is
// ours (close, watchdog shutdown) and never when the link broke (an exception ends the emulator loop).
// A fake connector stands in for SSH: its read blocks until the test ends the stream.
// ──────────────────────────────────────────────────────────────────────────────

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import fr.techtical.nextsh.desktop.core.ssh.ShellEndInfo
import fr.techtical.nextsh.desktop.sessions.TechticalTerminalSettings
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeTerminalSessionExitTest {

    private data class Report(val code: Int?)

    private class FakeConnector(
        private val failOnRead: Boolean = false,
        private val code: Int? = null,
        /** The server sends the exit status AFTER the end of the stream: the code is only known once published. */
        delayCode: Boolean = false,
    ) : TtyConnector, ShellEndInfo {
        private val codeKnown = CountDownLatch(if (delayCode) 1 else 0)
        val writes = CopyOnWriteArrayList<String>()
        val resizes = CopyOnWriteArrayList<TermSize>()

        fun publishCode() = codeKnown.countDown()
        private val streamEnd = CountDownLatch(1)
        val readStarted = CountDownLatch(1)

        @Volatile private var connected = true

        @Volatile
        override var closedLocally = false

        /** The server closes the channel: the pending read returns end of stream. */
        fun endStream() = streamEnd.countDown()

        override fun exitCode(): Int? {
            codeKnown.await(5, TimeUnit.SECONDS)
            return code
        }

        override fun read(buf: CharArray, offset: Int, length: Int): Int {
            readStarted.countDown()
            if (failOnRead) throw IOException("link broken")
            streamEnd.await()
            return -1
        }

        override fun write(bytes: ByteArray) {
            writes.add(String(bytes))
        }

        override fun write(string: String) {
            writes.add(string)
        }
        override fun resize(termSize: TermSize) {
            resizes.add(termSize)
        }

        override fun isConnected(): Boolean = connected
        override fun waitFor(): Int = 0
        override fun ready(): Boolean = false
        override fun getName(): String = "fake"

        override fun close() {
            closedLocally = true
            connected = false
            streamEnd.countDown()
        }
    }

    private val palette = TerminalThemePalette(
        background = 0x000000,
        foreground = 0xFFFFFF,
        cursor = 0xFFFFFF,
        selectionBg = 0x333333,
        ansi = IntArray(16),
    )

    private fun sessionOn(connector: TtyConnector, reports: LinkedBlockingQueue<Report>): ComposeTerminalSession =
        ComposeTerminalSession(
            sessionId = "test-session",
            connector = connector,
            settings = TechticalTerminalSettings(palette),
        ).apply {
            // A queue cannot hold null: the code travels in a wrapper.
            onShellExited = { reports.put(Report(it)) }
        }

    @Test
    fun `the end of the stream is reported once with the exit code`() {
        val connector = FakeConnector(code = 3, delayCode = true)
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        try {
            session.start()
            assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))
            connector.endStream()
            // exit-status comes after the end of the stream: the report waits for it.
            Thread.sleep(150)
            assertNull(reports.peek(), "reported before the exit code was known")
            connector.publishCode()

            assertEquals(Report(3), reports.poll(5, TimeUnit.SECONDS))
            assertNull(reports.poll(300, TimeUnit.MILLISECONDS), "reported a second time")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a read failure on a connected link reports nothing`() {
        val connector = FakeConnector(failOnRead = true)
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        try {
            session.start()
            assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))

            assertNull(reports.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            session.close()
        }
    }

    @Test
    fun `closing the session before the end of the stream reports nothing`() {
        val connector = FakeConnector()
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        session.start()
        assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))

        session.close()

        assertNull(reports.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a shell closed on our side by the watchdog reports nothing`() {
        val connector = FakeConnector(code = 0)
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        try {
            session.start()
            assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))
            // DesktopSshTerminalSession.shutdown closes the shell through the connector.
            connector.closedLocally = true
            connector.endStream()

            assertNull(reports.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            session.close()
        }
    }

    @Test
    fun `an unknown exit code is reported as null`() {
        val connector = FakeConnector(code = null)
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        try {
            session.start()
            assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))
            connector.endStream()

            assertEquals(Report(null), reports.poll(5, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
    }

    @Test
    fun `after the end of the stream nothing typed is written to the shell`() {
        val connector = FakeConnector(code = 0)
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        try {
            session.start()
            assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))
            connector.endStream()
            assertEquals(Report(0), reports.poll(5, TimeUnit.SECONDS))

            session.sendString("ls\n")
            session.sendBytes(byteArrayOf(3))

            assertTrue(connector.writes.isEmpty(), "written after the end: " + connector.writes)
        } finally {
            session.close()
        }
    }

    @Test
    fun `after the end of the stream a resize never reaches the shell`() {
        // sshd drops the whole connection on a window-change for a channel it closed: the SFTP pane that
        // shares it would die with the terminal.
        val connector = FakeConnector(code = 0)
        val reports = LinkedBlockingQueue<Report>()
        val session = sessionOn(connector, reports)
        try {
            session.start()
            assertTrue(connector.readStarted.await(5, TimeUnit.SECONDS))
            connector.endStream()
            assertEquals(Report(0), reports.poll(5, TimeUnit.SECONDS))

            session.resize(100, 20)
            // JediTerm debounces a resize for 500 ms before it reaches the connector.
            Thread.sleep(900)

            assertTrue(connector.resizes.isEmpty(), "resized after the end: " + connector.resizes)
            assertEquals(100, session.terminal.terminalWidth, "the local model still follows the view")
        } finally {
            session.close()
        }
    }
}

