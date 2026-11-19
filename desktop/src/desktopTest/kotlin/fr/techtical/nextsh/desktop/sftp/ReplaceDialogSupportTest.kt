// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure parts of the replacement dialog: the guard against the release of a key whose
 * press the window never saw, and the cutting of a long path in its middle.
 */
class ReplaceDialogSupportTest {

    // ── StrayKeyUpGuard ───────────────────────────────────────────────────────

    private val guard = StrayKeyUpGuard()

    @Test
    fun `the release of Enter that was pressed elsewhere is absorbed`() {
        // The user validated the file chooser with Enter: the key goes up over the new window.
        assertTrue(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Enter))
    }

    @Test
    fun `the release of a key pressed in the window goes through, once`() {
        assertFalse(guard.shouldAbsorb(KeyEventType.KeyDown, Key.Enter))
        assertFalse(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Enter))

        // A second release has no press behind it any more.
        assertTrue(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Enter))
    }

    @Test
    fun `a key held down repeats its press and is released once`() {
        repeat(5) { assertFalse(guard.shouldAbsorb(KeyEventType.KeyDown, Key.Spacebar)) }

        assertFalse(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Spacebar))
        assertTrue(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Spacebar))
    }

    @Test
    fun `every key that activates a button is guarded, each on its own`() {
        val activation = listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)

        for (key in activation) {
            assertTrue(StrayKeyUpGuard().shouldAbsorb(KeyEventType.KeyUp, key), "stray release of $key")
        }
        // The press of one key does not vouch for the release of another.
        assertFalse(guard.shouldAbsorb(KeyEventType.KeyDown, Key.Enter))
        assertTrue(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Spacebar))
        assertTrue(guard.shouldAbsorb(KeyEventType.KeyUp, Key.NumPadEnter))
        assertFalse(guard.shouldAbsorb(KeyEventType.KeyUp, Key.Enter))
    }

    @Test
    fun `other keys and other events are never absorbed`() {
        for (key in listOf(Key.Escape, Key.Tab, Key.A, Key.DirectionLeft)) {
            assertFalse(guard.shouldAbsorb(KeyEventType.KeyDown, key), "press of $key")
            assertFalse(guard.shouldAbsorb(KeyEventType.KeyUp, key), "stray release of $key")
        }
        assertFalse(guard.shouldAbsorb(KeyEventType.Unknown, Key.Enter))
    }

    // ── shortenPathInMiddle ───────────────────────────────────────────────────

    private val ellipsis = Char(0x2026).toString()

    @Test
    fun `a path that fits is returned as it is`() {
        assertEquals("/home/t/a.txt", shortenPathInMiddle("/home/t/a.txt", 100))
        assertEquals("/home/t/a.txt", shortenPathInMiddle("/home/t/a.txt", "/home/t/a.txt".length))
    }

    @Test
    fun `a long path keeps its start and its last segment whole and loses its middle`() {
        val path = "/home/thibaud/Documents/projects/nextsh/exports/2026/october/monthly-report-final.pdf"

        val shortened = shortenPathInMiddle(path, 60)

        assertEquals(60, shortened.length)
        assertTrue(shortened.startsWith("/home/thibaud"), shortened)
        assertTrue(shortened.endsWith("/monthly-report-final.pdf"), shortened)
        assertTrue(ellipsis in shortened, shortened)
    }

    @Test
    fun `the last segment stays whole whatever the room left for the start`() {
        val path = "/a/very/deep/folder/structure/that/goes/on/and/on/report.pdf"

        val shortened = shortenPathInMiddle(path, "/report.pdf".length + 1)

        // Only the ellipsis fits before the last segment.
        assertEquals("$ellipsis/report.pdf", shortened)
    }

    @Test
    fun `Windows separators are understood too`() {
        val path = "C:\\Users\\thibaud\\Documents\\projects\\nextsh\\exports\\2026\\october\\monthly-report-final.pdf"

        val shortened = shortenPathInMiddle(path, 50)

        assertEquals(50, shortened.length)
        assertTrue(shortened.startsWith("C:\\Users"), shortened)
        assertTrue(shortened.endsWith("\\monthly-report-final.pdf"), shortened)
    }

    @Test
    fun `a last segment too long to fit is cut at its start so that its end stays visible`() {
        val name = "a-very-long-name-".repeat(10) + "version-2.final.pdf"
        val path = "/srv/$name"

        val shortened = shortenPathInMiddle(path, 40)

        assertEquals(40, shortened.length)
        assertTrue(shortened.startsWith(ellipsis), shortened)
        assertTrue(shortened.endsWith("version-2.final.pdf"), shortened)
    }

    @Test
    fun `a bare name too long to fit is cut the same way`() {
        val name = "x".repeat(300) + ".txt"

        val shortened = shortenPathInMiddle(name, 50)

        assertEquals(50, shortened.length)
        assertTrue(shortened.startsWith(ellipsis))
        assertTrue(shortened.endsWith(".txt"))
    }

    @Test
    fun `a silly limit never breaks the function`() {
        for (limit in listOf(-5, 0, 1, 2, 3)) {
            val shortened = shortenPathInMiddle("/home/thibaud/a-long-file-name.txt", limit)

            assertTrue(shortened.isNotEmpty(), "limit $limit")
            assertTrue(shortened.length <= 34, "limit $limit")
        }
    }

    @Test
    fun `the text kept is always taken from the path, nothing is invented`() {
        val path = "/home/thibaud/Documents/projects/nextsh/exports/2026/october/monthly-report-final.pdf"

        val shortened = shortenPathInMiddle(path, 45)
        val parts = shortened.split(ellipsis)

        assertEquals(2, parts.size, shortened)
        assertTrue(path.startsWith(parts[0]), shortened)
        assertTrue(path.endsWith(parts[1]), shortened)
    }
}
