// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.SftpEntryNameProblem
import fr.techtical.nextsh.desktop.core.ssh.isValidEntryName
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_control
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_invisible_format
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_separator
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_reserved
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_too_long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the name dialogs (new folder, new file, rename) make of the text typed: the name that will be sent,
 * whether the button may be enabled, and the sentence that says what is wrong. The texts of the sentences
 * are tested in [SftpEntryNameStringsTest].
 */
class SftpEntryNameCheckTest {

    // Non-ASCII text is built from code points, never written raw.
    private fun cp(vararg codes: Int): String = buildString { codes.forEach { appendCodePoint(it) } }

    /** The string resource of each problem, by its key. */
    private val keys = mapOf(
        SftpEntryNameProblem.RESERVED to Res.string.sftp_error_name_reserved.key,
        SftpEntryNameProblem.SEPARATOR to Res.string.sftp_error_name_has_separator.key,
        SftpEntryNameProblem.CONTROL_CHARACTER to Res.string.sftp_error_name_has_control.key,
        SftpEntryNameProblem.INVISIBLE_FORMAT to Res.string.sftp_error_name_has_invisible_format.key,
        SftpEntryNameProblem.TOO_LONG to Res.string.sftp_error_name_too_long.key,
    )

    // ── What is typed ────────────────────────────────────────────────────────────

    @Test
    fun `nothing typed says nothing and the button stays off`() {
        val check = checkEntryName("")

        assertEquals("", check.name)
        assertFalse(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `only spaces typed says nothing and the button stays off`() {
        val check = checkEntryName("   ")

        assertEquals("", check.name)
        assertFalse(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `a single dot says nothing, it is the start of a hidden name, and the button stays off`() {
        listOf(".", " . ", "  .").forEach { typed ->
            val check = checkEntryName(typed)

            assertEquals(".", check.name, "<$typed>")
            assertFalse(check.canConfirm, "<$typed>")
            assertNull(check.help, "<$typed>")
        }
    }

    @Test
    fun `a hidden name goes through the single dot and is valid`() {
        val check = checkEntryName(".bashrc")

        assertEquals(".bashrc", check.name)
        assertTrue(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `two dots say that the name stands for the parent folder`() {
        val check = checkEntryName("..")

        assertEquals("..", check.name)
        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.RESERVED), check.help?.key)
    }

    @Test
    fun `a slash says that a name cannot hold a separator`() {
        val check = checkEntryName("a/b")

        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.SEPARATOR), check.help?.key)
    }

    @Test
    fun `a backslash says that a name cannot hold a separator`() {
        val check = checkEntryName("a\\b")

        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.SEPARATOR), check.help?.key)
    }

    @Test
    fun `a tabulation inside the name says that it holds an invisible character`() {
        val check = checkEntryName("a\tb")

        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.CONTROL_CHARACTER), check.help?.key)
    }

    @Test
    fun `a bidirectional control says that the name holds an invisible character to type again`() {
        val check = checkEntryName("photo\u202Egpj.exe")

        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.INVISIBLE_FORMAT), check.help?.key)
    }

    @Test
    fun `a zero width space at the edge is not trimmed and is reported`() {
        val check = checkEntryName("\u200Bnotes")

        assertEquals("\u200Bnotes", check.name)
        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.INVISIBLE_FORMAT), check.help?.key)
    }

    @Test
    fun `a family emoji with joiners is accepted`() {
        val check = checkEntryName(cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467))

        assertTrue(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `displayEntryName shows a hidden character as the replacement character`() {
        assertEquals(cp('p'.code, 0xFFFD, 'g'.code), displayEntryName(cp('p'.code, 0x202E, 'g'.code)))
        assertEquals(cp('a'.code, 0xFFFD, 'b'.code), displayEntryName("a\nb"))
        assertEquals(cp('a'.code, 0xFFFD, 'b'.code), displayEntryName("a\u2028b"))
    }

    @Test
    fun `displayEntryName keeps the joiners and an ordinary name as they are`() {
        val family = cp(0x1F468, 0x200D, 0x1F469)
        assertEquals(family, displayEntryName(family))
        val persian = cp(0x645, 0x6CC, 0x200C, 0x62E)
        assertEquals(persian, displayEntryName(persian))
        assertEquals("notes.txt", displayEntryName("notes.txt"))
    }

    @Test
    fun `a tabulation at the edge is removed with the spaces, like them`() {
        val check = checkEntryName("\tok\t")

        assertEquals("ok", check.name)
        assertTrue(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `a name of 256 characters says that it is too long`() {
        val check = checkEntryName("a".repeat(256))

        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.TOO_LONG), check.help?.key)
    }

    @Test
    fun `a name of 255 characters is valid`() {
        val check = checkEntryName("a".repeat(255))

        assertTrue(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `the length is counted in bytes, a name of 128 accented letters is too long`() {
        // 128 times a two-byte letter is 256 bytes: 128 characters, and too long for the file system.
        val tooLong = checkEntryName(cp(0xE9).repeat(128))
        assertFalse(tooLong.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.TOO_LONG), tooLong.help?.key)

        val fits = checkEntryName(cp(0xE9).repeat(127))
        assertTrue(fits.canConfirm)
        assertNull(fits.help)
    }

    @Test
    fun `the spaces around the name are removed, and nothing else is`() {
        val check = checkEntryName("  ok  ")

        assertEquals("ok", check.name)
        assertTrue(check.canConfirm)
        assertNull(check.help)
    }

    @Test
    fun `a name with a space inside is kept as typed`() {
        val check = checkEntryName(" my notes.txt ")

        assertEquals("my notes.txt", check.name)
        assertTrue(check.canConfirm)
    }

    @Test
    fun `the spaces around do not hide a separator`() {
        val check = checkEntryName("  a/b  ")

        assertEquals("a/b", check.name)
        assertFalse(check.canConfirm)
        assertEquals(keys.getValue(SftpEntryNameProblem.SEPARATOR), check.help?.key)
    }

    @Test
    fun `the button follows the rule of the manager, and a sentence only comes with a refused name`() {
        val typed = listOf(
            "", " ", ".", "..", "...", ".a", "a.", "a b", " a b ", "a/b", "/", "\\", "a\\b", "x\u0000y", "x\u0085y",
            "x+y", "100%", "a%2Fb", "..%2F..%2Ftmp%2Fx", "a".repeat(255), "a".repeat(256), "notes.txt",
        )

        typed.forEach { text ->
            val check = checkEntryName(text)

            assertEquals(text.trim(), check.name, "<$text>")
            assertEquals(isValidEntryName(text.trim()), check.canConfirm, "<$text>")
            if (check.help != null) assertFalse(check.canConfirm, "a sentence for the valid name <$text>")
        }
    }

    // ── The sentences ────────────────────────────────────────────────────────────

    @Test
    fun `every problem has its own sentence`() {
        val all = SftpEntryNameProblem.entries.map { it.messageRes().key }

        assertEquals(SftpEntryNameProblem.entries.size, all.toSet().size, "two problems share a sentence")
        assertEquals(SftpEntryNameProblem.entries.toSet(), keys.keys, "a problem has no resource in this test")
        keys.forEach { (problem, key) ->
            assertEquals(key, problem.messageRes().key, problem.name)
        }
    }
}
