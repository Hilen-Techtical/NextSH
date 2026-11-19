// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

private val EACUTE = String(Character.toChars(0xE9))

/** The bounded read of a private key file: counted as it is read, never from a size a provider announces. */
class KeyFileReaderTest {

    @Test
    fun `a file within the bound is read as UTF-8`() {
        val result = readKeyFile(ByteArrayInputStream("cl${EACUTE}".toByteArray(Charsets.UTF_8)), 100)

        assertEquals(KeyFileRead.Content("cl${EACUTE}"), result)
    }

    @Test
    fun `a file of exactly the bound is read, one byte more is too large`() {
        assertTrue(readKeyFile(ByteArrayInputStream(ByteArray(20_000) { 'a'.code.toByte() }), 20_000) is KeyFileRead.Content)
        assertEquals(KeyFileRead.TooLarge, readKeyFile(ByteArrayInputStream(ByteArray(20_001) { 'a'.code.toByte() }), 20_000))
    }

    @Test
    fun `a stream that never ends stops at the bound`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                b.fill('a'.code.toByte(), off, off + len)
                return len
            }
        }

        assertEquals(KeyFileRead.TooLarge, readKeyFile(endless, 20_000))
        assertTrue(served < 100_000, "read $served bytes before giving up")
    }

    @Test
    fun `a stream that fails is a failed read, its message out of the result`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("/storage/secret/path")
        }

        assertEquals(KeyFileRead.Failed, readKeyFile(failing, 100))
    }

    @Test
    fun `a missing stream is a failed read`() {
        assertEquals(KeyFileRead.Failed, readKeyFile(null, 100))
    }
}
