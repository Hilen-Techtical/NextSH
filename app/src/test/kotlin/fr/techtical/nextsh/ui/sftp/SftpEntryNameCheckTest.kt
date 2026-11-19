// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SftpEntryNameProblem
import fr.techtical.nextsh.core.ssh.isValidEntryName
import fr.techtical.nextsh.testutil.StringResourceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the name dialogs (new folder, new file, rename) make of the text typed: the name that will be
 * sent, whether the button may be enabled, and the sentence that says what is wrong. The sentences are
 * resources, in French and in English.
 */
class SftpEntryNameCheckTest {

    /** A string from code points, so that no invisible or composed character is written raw. */
    private fun cp(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    private val french = StringResourceFile("values/strings.xml")
    private val english = StringResourceFile("values-en/strings.xml")

    /** The string resource of each problem, and its name in the compiled R class. */
    private val resources = mapOf(
        SftpEntryNameProblem.RESERVED to ("sftp_error_name_reserved" to R.string.sftp_error_name_reserved),
        SftpEntryNameProblem.SEPARATOR to ("sftp_error_name_has_separator" to R.string.sftp_error_name_has_separator),
        SftpEntryNameProblem.CONTROL_CHARACTER to ("sftp_error_name_has_control" to R.string.sftp_error_name_has_control),
        SftpEntryNameProblem.INVISIBLE_FORMAT to
            ("sftp_error_name_has_invisible_format" to R.string.sftp_error_name_has_invisible_format),
        SftpEntryNameProblem.TOO_LONG to ("sftp_error_name_too_long" to R.string.sftp_error_name_too_long),
    )

    /** What the Android resource compiler does to a raw text: every `\x` becomes `x` (`\'`, `\"`, `\\`). */
    private fun aapt(raw: String): String = Regex("""\\(.)""").replace(raw) { it.groupValues[1] }

    private fun frenchText(name: String) = aapt(french.strings.getValue(name))

    private fun englishText(name: String) = aapt(english.strings.getValue(name))

    // ── What is typed ────────────────────────────────────────────────────────────

    @Test
    fun `nothing typed says nothing and the button stays off`() {
        val check = checkEntryName("")

        assertEquals("", check.name)
        assertFalse(check.canConfirm)
        assertNull(check.helpRes)
    }

    @Test
    fun `only spaces typed says nothing and the button stays off`() {
        val check = checkEntryName("   ")

        assertEquals("", check.name)
        assertFalse(check.canConfirm)
        assertNull(check.helpRes)
    }

    @Test
    fun `a single dot says nothing, it is the start of a hidden name, and the button stays off`() {
        listOf(".", " . ", "  .").forEach { typed ->
            val check = checkEntryName(typed)

            assertEquals(".", check.name, "<$typed>")
            assertFalse(check.canConfirm, "<$typed>")
            assertNull(check.helpRes, "<$typed>")
        }
    }

    @Test
    fun `a hidden name goes through the single dot and is valid`() {
        val check = checkEntryName(".bashrc")

        assertEquals(".bashrc", check.name)
        assertTrue(check.canConfirm)
        assertNull(check.helpRes)
    }

    @Test
    fun `two dots say that the name stands for the parent folder`() {
        val check = checkEntryName("..")

        assertEquals("..", check.name)
        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_reserved, check.helpRes)
    }

    @Test
    fun `a slash says that a name cannot hold a separator`() {
        val check = checkEntryName("a/b")

        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_has_separator, check.helpRes)
    }

    @Test
    fun `a backslash says that a name cannot hold a separator`() {
        val check = checkEntryName("a\\b")

        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_has_separator, check.helpRes)
    }

    @Test
    fun `a tabulation inside the name says that it holds an invisible character`() {
        val check = checkEntryName("a\tb")

        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_has_control, check.helpRes)
    }

    @Test
    fun `a right to left override says that the name holds an invisible character to type again`() {
        val check = checkEntryName("photo\u202Egpj.exe")

        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_has_invisible_format, check.helpRes)
    }

    @Test
    fun `a zero width space at the start is not trimmed and is refused`() {
        val check = checkEntryName("\u200Bnotes")

        assertEquals("\u200Bnotes", check.name)
        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_has_invisible_format, check.helpRes)
    }

    @Test
    fun `a family emoji with joiners is accepted`() {
        val check = checkEntryName(cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467))

        assertTrue(check.canConfirm)
        assertNull(check.helpRes)
    }

    @Test
    fun `a name of 256 characters says that it is too long`() {
        val check = checkEntryName("a".repeat(256))

        assertFalse(check.canConfirm)
        assertEquals(R.string.sftp_error_name_too_long, check.helpRes)
    }

    @Test
    fun `a name of 255 characters is valid`() {
        val check = checkEntryName("a".repeat(255))

        assertTrue(check.canConfirm)
        assertNull(check.helpRes)
    }

    @Test
    fun `the length is counted in bytes, a name of 128 accented letters is too long`() {
        // 128 times a two-byte letter is 256 bytes: 128 characters, and too long for the file system.
        val tooLong = checkEntryName("é".repeat(128))
        assertFalse(tooLong.canConfirm)
        assertEquals(R.string.sftp_error_name_too_long, tooLong.helpRes)

        val fits = checkEntryName("é".repeat(127))
        assertTrue(fits.canConfirm)
        assertNull(fits.helpRes)
    }

    @Test
    fun `the spaces around the name are removed, and nothing else is`() {
        val check = checkEntryName("  ok  ")

        assertEquals("ok", check.name)
        assertTrue(check.canConfirm)
        assertNull(check.helpRes)
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
        assertEquals(R.string.sftp_error_name_has_separator, check.helpRes)
    }

    @Test
    fun `the button follows the rule of the manager, and a sentence only comes with a refused name`() {
        val typed = listOf(
            "", " ", ".", "..", "...", ".a", "a.", "a b", " a b ", "a/b", "/", "\\", "a\\b", "x\u0000y", "x\u0085y",
            "x+y", "100%", "a%2Fb", "..%2F..%2Ftmp%2Fx", "a".repeat(255), "a".repeat(256), "notes.txt",
            "photo\u202Egpj.exe", "\u200Bnotes", "a\u200Db", "a\u2028b",
        )

        typed.forEach { text ->
            val check = checkEntryName(text)

            assertEquals(text.trim(), check.name, "<$text>")
            assertEquals(isValidEntryName(text.trim()), check.canConfirm, "<$text>")
            if (check.helpRes != null) assertFalse(check.canConfirm, "a sentence for the valid name <$text>")
        }
    }

    // ── The sentences ────────────────────────────────────────────────────────────

    @Test
    fun `every problem has its own sentence`() {
        val all = SftpEntryNameProblem.entries.map { it.messageRes() }

        assertEquals(SftpEntryNameProblem.entries.size, all.toSet().size, "two problems share a sentence")
        assertEquals(SftpEntryNameProblem.entries.toSet(), resources.keys, "a problem has no resource in this test")
        resources.forEach { (problem, resource) ->
            assertEquals(resource.second, problem.messageRes(), problem.name)
        }
    }

    @Test
    fun `the name of each resource is the one of the compiled R class`() {
        resources.values.forEach { (name, res) ->
            assertEquals(res, R.string::class.java.getField(name).getInt(null), name)
        }
        assertEquals(
            R.string.sftp_error_entry_name_invalid,
            R.string::class.java.getField("sftp_error_entry_name_invalid").getInt(null),
        )
    }

    @Test
    fun `every sentence exists in French and in English, translated and not empty`() {
        val names = resources.values.map { it.first } + "sftp_error_entry_name_invalid"

        names.forEach { name ->
            assertTrue(name in french.strings, "$name missing from French")
            assertTrue(name in english.strings, "$name missing from English")
            assertFalse(french.strings.getValue(name).isBlank(), "$name is empty in French")
            assertFalse(english.strings.getValue(name).isBlank(), "$name is empty in English")
            assertNotEquals(french.strings.getValue(name), english.strings.getValue(name), "$name is not translated")
        }
    }

    @Test
    fun `the French sentences are the expected ones`() {
        assertEquals("Les noms « . » et « .. » sont réservés, choisissez-en un autre", frenchText("sftp_error_name_reserved"))
        assertEquals("Un nom ne peut contenir ni / ni \\", frenchText("sftp_error_name_has_separator"))
        assertEquals("Le nom contient un caractère invisible non autorisé", frenchText("sftp_error_name_has_control"))
        assertEquals(
            "Le nom contient un caractère invisible qui peut fausser son affichage, retapez-le",
            frenchText("sftp_error_name_has_invisible_format"),
        )
        assertEquals("Le nom est trop long", frenchText("sftp_error_name_too_long"))
        assertEquals("Nom invalide", frenchText("sftp_error_entry_name_invalid"))
    }

    @Test
    fun `the English sentences are the expected ones`() {
        assertEquals("The names \".\" and \"..\" are reserved, choose another one", englishText("sftp_error_name_reserved"))
        assertEquals("A name cannot contain / or \\", englishText("sftp_error_name_has_separator"))
        assertEquals("The name contains an invisible character that is not allowed", englishText("sftp_error_name_has_control"))
        assertEquals(
            "The name contains an invisible character that can change how it looks, type it again",
            englishText("sftp_error_name_has_invisible_format"),
        )
        assertEquals("The name is too long", englishText("sftp_error_name_too_long"))
        assertEquals("Invalid name", englishText("sftp_error_entry_name_invalid"))
    }

    @Test
    fun `the backslash is escaped in the XML so that the resource compiler keeps it`() {
        // A lone backslash would be taken as the start of an escape: the file has to hold two of them.
        assertTrue(french.strings.getValue("sftp_error_name_has_separator").endsWith("\\\\"))
        assertTrue(english.strings.getValue("sftp_error_name_has_separator").endsWith("\\\\"))
        // The quotes of the English sentence are escaped too.
        assertTrue(english.strings.getValue("sftp_error_name_reserved").contains("\\\".\\\""))
    }

    private val sentences = listOf(
        "sftp_error_name_reserved",
        "sftp_error_name_has_separator",
        "sftp_error_name_has_control",
        "sftp_error_name_has_invisible_format",
        "sftp_error_name_too_long",
        "sftp_error_entry_name_invalid",
    )

    // ── Display and download name ────────────────────────────────────────────────

    @Test
    fun `displayEntryName shows a replacement character for a control or format character`() {
        assertEquals("photo" + 0xFFFD.toChar() + "gpj.exe", displayEntryName("photo" + 0x202E.toChar() + "gpj.exe"))
        assertEquals("a" + 0xFFFD.toChar() + "b", displayEntryName("a" + 0x0A.toChar() + "b"))
    }

    @Test
    fun `displayEntryName keeps the joiners and an ordinary name`() {
        val joined = cp(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645, 0x20, 0x1F468, 0x200D, 0x1F469)
        assertEquals(joined, displayEntryName(joined))
        assertEquals("notes é.txt", displayEntryName("notes é.txt"))
    }

    @Test
    fun `downloadEntryName replaces a control or format character by an underscore`() {
        assertEquals("facture_fdp.exe", downloadEntryName("facture\u202Efdp.exe"))
        assertEquals("a_b", downloadEntryName("a\u0000b"))
        assertEquals("notes é.txt", downloadEntryName("notes é.txt"))
    }

    @Test
    fun `no sentence holds an em dash, an en dash or a placeholder`() {
        sentences.forEach { name ->
            listOf(french.strings.getValue(name), english.strings.getValue(name)).forEach { text ->
                assertFalse(text.any { it.code == 0x2014 || it.code == 0x2013 }, "$name holds a dash: $text")
                assertFalse(text.contains('%') || text.contains('$'), "$name holds a placeholder: $text")
            }
        }
    }

    @Test
    fun `the French sentences address the user as vous, never tu`() {
        sentences.forEach { name ->
            val text = frenchText(name)
            // (?U): word characters are Unicode, otherwise the boundary falls inside a word with an accent.
            assertFalse(Regex("""(?U)\b(?:tu|ton|ta|tes|toi)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text), "$name: $text")
        }
    }

    @Test
    fun `the French sentences say vault and never coffre`() {
        sentences.forEach { name ->
            assertFalse(frenchText(name).contains("coffre", ignoreCase = true), name)
        }
    }

    @Test
    fun `the English sentences are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis", "cancelled")
        sentences.forEach { name ->
            british.forEach { word -> assertFalse(englishText(name).contains(word, ignoreCase = true), "$name uses the British \"$word\"") }
        }
    }
}
