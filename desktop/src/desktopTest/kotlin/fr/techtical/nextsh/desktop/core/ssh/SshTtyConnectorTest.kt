// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import io.mockk.every
import io.mockk.mockk
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SshTtyConnector] read-side behaviour: OSC 52 interception,
 * the 1049 -> 1047 rewrite, and the [TtyConnector.read] contract (never 0,
 * never more than requested, -1 at end of stream).
 */
class SshTtyConnectorTest {

    /** Hands out at most [chunk] bytes per `read`, like a network stream delivering small packets. */
    private class ChunkedInputStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun shellOf(input: InputStream, output: OutputStream = ByteArrayOutputStream()): Session.Shell {
        val shell = mockk<Session.Shell>(relaxed = true)
        every { shell.inputStream } returns input
        every { shell.outputStream } returns output
        return shell
    }

    private fun connectorOf(
        stream: String,
        chunk: Int = Int.MAX_VALUE,
        onWrite: (String) -> Unit = {},
    ): SshTtyConnector {
        val bytes = stream.toByteArray(Charsets.UTF_8)
        val input = if (chunk == Int.MAX_VALUE) ByteArrayInputStream(bytes) else ChunkedInputStream(bytes, chunk)
        return SshTtyConnector(shellOf(input), onWrite)
    }

    /** Reads until end of stream, checking the `read` contract on every call. */
    private fun readAll(connector: SshTtyConnector, readSize: Int): String {
        val buf = CharArray(readSize + 4) { '#' }
        val sb = StringBuilder()
        var guard = 0
        while (true) {
            val n = connector.read(buf, 2, readSize)
            if (n == -1) break
            assertTrue(n > 0, "read returned 0")
            assertTrue(n <= readSize, "read returned more than requested")
            sb.append(buf, 2, n)
            assertTrue(++guard < 100_000, "read loop does not terminate")
        }
        return sb.toString()
    }

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun osc52(text: String, terminator: String = BEL): String = "$ESC]52;c;${b64(text)}$terminator"

    @Test
    fun `OSC 52 disappears from the output and the callback receives the text`() {
        val writes = mutableListOf<String>()
        val connector = connectorOf("hello " + osc52("copied text") + "world", onWrite = { writes += it })

        assertEquals("hello world", readAll(connector, 1024))
        assertEquals(listOf("copied text"), writes)
    }

    @Test
    fun `result does not depend on the read size or on how the network chunks the stream`() {
        // `ESC ] 5 2 0 ;` holds the 4-char prefix then emits it plus the
        // mismatching char: the worst case for the spill buffer at tiny reads.
        val stream = "abé😀" + osc52("one") + "mid$ESC[31m" + osc52("two", ST) +
            "$ESC]520;c;x$BEL" + "$ESC]0;title${BEL}end"
        val expected = "abé😀" + "mid$ESC[31m" + "$ESC]520;c;x$BEL" + "$ESC]0;title${BEL}end"

        for (chunk in listOf(1, 2, 3, 5, 7, 64, Int.MAX_VALUE)) {
            for (readSize in listOf(1, 2, 3, 4, 5, 6, 16, 1024)) {
                val writes = mutableListOf<String>()
                val connector = connectorOf(stream, chunk) { writes += it }
                assertEquals(expected, readAll(connector, readSize), "output with chunk=$chunk readSize=$readSize")
                assertEquals(listOf("one", "two"), writes, "writes with chunk=$chunk readSize=$readSize")
            }
        }
    }

    @Test
    fun `alt screen 1049 is still rewritten to 1047`() {
        val connector = connectorOf("$ESC[?1049h" + "x" + "$ESC[?1049l")
        assertEquals("$ESC[?1047h" + "x" + "$ESC[?1047l", readAll(connector, 1024))
    }

    @Test
    fun `alt screen rewrite and OSC 52 interception work together`() {
        val writes = mutableListOf<String>()
        val connector = connectorOf("$ESC[?1049h" + osc52("clip") + "$ESC[?1049l") { writes += it }
        assertEquals("$ESC[?1047h$ESC[?1047l", readAll(connector, 1024))
        assertEquals(listOf("clip"), writes)
    }

    @Test
    fun `a stream starting with a complete OSC 52 never makes read return 0`() {
        val connector = connectorOf(osc52("clip") + "text after")
        val buf = CharArray(64)
        val n = connector.read(buf, 0, buf.size)
        assertEquals("text after", String(buf, 0, n))
        assertEquals(-1, connector.read(buf, 0, buf.size))
    }

    @Test
    fun `chunks entirely consumed by a sequence are skipped without returning 0`() {
        val writes = mutableListOf<String>()
        // Chunks of 4 bytes: several consecutive reads contain only sequence bytes.
        val connector = connectorOf(osc52("a rather long clipboard text".repeat(3)) + "x", chunk = 4) { writes += it }
        val buf = CharArray(64)
        val n = connector.read(buf, 0, buf.size)
        assertTrue(n > 0)
        assertEquals("x", String(buf, 0, n))
        assertEquals(listOf("a rather long clipboard text".repeat(3)), writes)
    }

    @Test
    fun `end of stream returns -1`() {
        val empty = connectorOf("")
        assertEquals(-1, empty.read(CharArray(16), 0, 16))

        val onlySequence = connectorOf(osc52("clip"))
        assertEquals(-1, onlySequence.read(CharArray(16), 0, 16))

        val text = connectorOf("abc")
        val buf = CharArray(16)
        assertEquals(3, text.read(buf, 0, 16))
        assertEquals(-1, text.read(buf, 0, 16))
        assertEquals(-1, text.read(buf, 0, 16))
    }

    @Test
    fun `a throwing callback does not break reading`() {
        val connector = connectorOf("a" + osc52("boom") + "b", onWrite = { throw IllegalStateException("no clipboard") })
        assertEquals("ab", readAll(connector, 1024))
    }

    @Test
    fun `ready reports pending data and write still reaches the shell`() {
        val output = ByteArrayOutputStream()
        val shell = shellOf(ByteArrayInputStream("abc".toByteArray()), output)
        val connector = SshTtyConnector(shell)

        assertTrue(connector.ready())
        connector.write("ls\n")
        assertEquals("ls\n", output.toString(Charsets.UTF_8))
    }

    private companion object {
        const val ESC = "\u001b"
        const val BEL = "\u0007"
        const val ST = "\u001b\\"
    }
}
