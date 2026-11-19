// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a paste sends: cleaned of ESC and C1, line ends as CR, wrapped only when bracketed paste is on. */
class TerminalPasteTest {

    // Control characters are built from code points, never written raw.
    private fun cp(vararg codes: Int): String = buildString { codes.forEach { appendCodePoint(it) } }

    private val esc = cp(0x1B)
    private val start = esc + "[200~"
    private val end = esc + "[201~"

    @Test
    fun `an ordinary text is unchanged`() {
        assertEquals("ls -la /tmp", pastePayload("ls -la /tmp", bracketed = false))
        assertEquals(start + "ls -la" + end, pastePayload("ls -la", bracketed = true))
    }

    @Test
    fun `ESC is removed without bracketed paste`() {
        assertEquals("a[31mb", pastePayload("a" + esc + "[31mb", bracketed = false))
    }

    @Test
    fun `a pasted end marker cannot close the bracketed paste early`() {
        val payload = pastePayload("x" + end + "id\n", bracketed = true)
        assertEquals(start + "x[201~id\r" + end, payload)
        // The markers are the only ESC characters left.
        assertEquals(2, payload.count { it == '\u001B' })
        assertEquals(end, payload.takeLast(end.length))
    }

    @Test
    fun `the C1 controls are removed`() {
        val text = "a" + cp(0x80, 0x85, 0x9B, 0x9F) + "b"
        assertEquals("ab", pastePayload(text, bracketed = false))
    }

    @Test
    fun `CRLF and LF become CR`() {
        assertEquals("a\rb", pastePayload("a\r\nb", bracketed = false))
        assertEquals("a\rb", pastePayload("a\nb", bracketed = false))
        assertEquals("a\r\rb", pastePayload("a\n\nb", bracketed = false))
        assertEquals("a\rb", pastePayload("a\rb", bracketed = false))
    }

    @Test
    fun `a tab is kept`() {
        assertEquals("a\tb", pastePayload("a\tb", bracketed = false))
    }

    @Test
    fun `the renderer holds no raw ESC and pastes through pastePayload`() {
        val relative = "desktopMain/kotlin/fr/techtical/nextsh/desktop/sessions/compose/ComposeTerminalRenderer.kt"
        val file = listOf("src", "desktop/src").map { java.io.File(it, relative) }.firstOrNull { it.isFile }
            ?: error("ComposeTerminalRenderer.kt not found")
        val source = file.readText(Charsets.UTF_8)
        assertFalse(source.contains(esc), "ComposeTerminalRenderer.kt holds a raw ESC")
        assertTrue(source.contains("pastePayload("), "the renderer does not paste through pastePayload")
    }

    @Test
    fun `no marker without bracketed paste`() {
        assertFalse(pastePayload("a\nb", bracketed = false).contains(start))
    }

    @Test
    fun `bracketed paste keeps the markers around the cleaned text`() {
        assertEquals(start + "a\rb" + end, pastePayload("a\r\nb", bracketed = true))
    }
}
