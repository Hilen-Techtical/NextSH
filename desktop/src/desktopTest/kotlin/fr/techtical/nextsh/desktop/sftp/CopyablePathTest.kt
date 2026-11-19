// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Copy path": the path is copied as it is, or refused when a list would show a U+FFFD in it. */
class CopyablePathTest {

    // Special characters are built from code points, never written raw.
    private fun cp(vararg codes: Int): String = buildString { codes.forEach { appendCodePoint(it) } }

    @Test
    fun `an ordinary path is copied as it is`() {
        val paths = listOf(
            "/home/a b/c'd\"\$e",
            "/srv/" + cp(0xE9, 't'.code, 0xE9) + "/notes.txt",
            "/tmp/" + cp(0x0645, 0x200C, 0x06CC) + "/f",
            "/tmp/" + cp(0x1F468, 0x200D, 0x1F469) + "/f",
            "/tmp/" + cp('a'.code, 0xFE0F) + "/f",
        )
        paths.forEach { assertEquals(it, copyablePath(it)) }
    }

    @Test
    fun `a path with a line break is refused`() {
        assertNull(copyablePath("/srv/a\nb"))
        assertNull(copyablePath("/srv/a\rb"))
    }

    @Test
    fun `a path with another control character is refused`() {
        listOf(0x09, 0x1B, 0x7F, 0x85, 0x9B).forEach {
            assertNull(copyablePath("/srv/a" + cp(it) + "b"), "U+" + it.toString(16))
        }
    }

    @Test
    fun `a path with an invisible format character is refused`() {
        listOf(0x2028, 0x202E, 0x200B, 0xFEFF, 0x00AD).forEach {
            assertNull(copyablePath("/srv/a" + cp(it) + "b.pdf"), "U+" + it.toString(16))
        }
    }

    @Test
    fun `a character in a parent folder refuses the path too`() {
        assertNull(copyablePath("/tmp/a\nb/c.txt"))
    }
}
