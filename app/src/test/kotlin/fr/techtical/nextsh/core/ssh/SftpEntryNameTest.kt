// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The rule of the name of a new folder, of a new file and of a renamed entry: a bare name, or nothing.
 * Refused and said, never corrected.
 */
class SftpEntryNameTest {

    // sanitizeFilename and sanitizePath read no state. The session manager is relaxed because the
    // constructor of SftpManager subscribes to the end of the sessions.
    private val sftpManager = SftpManager(mockk(relaxed = true))

    /** A name and what [sftpEntryNameProblem] has to say about it (null: nothing, though it is refused). */
    private data class Refused(val name: String, val problem: SftpEntryNameProblem?)

    private val accepted = listOf(
        "notes.txt",
        "x+y",
        "100%",
        "%2e%2e",
        "a%2Fb",
        "..%2F..%2Ftmp%2Fx",
        "a b",
        "..x",
        "x..",
        "...",
        ".hidden",
        "é",
        "a".repeat(255),
        // 254 and 255 bytes in UTF-8, though 127 and 128 characters.
        "é".repeat(127),
        "é".repeat(127) + "a",
    )

    private val refused = listOf(
        // Nothing to object to yet, or only edge spaces: not judged by sftpEntryNameProblem, refused all the same.
        Refused("", null),
        Refused("   ", null),
        Refused(" .. ", null),
        Refused(" notes", null),
        Refused("notes ", null),
        // The folder itself and its parent.
        Refused(".", SftpEntryNameProblem.RESERVED),
        Refused("..", SftpEntryNameProblem.RESERVED),
        // A separator, wherever it is.
        Refused("../x", SftpEntryNameProblem.SEPARATOR),
        Refused("a/b", SftpEntryNameProblem.SEPARATOR),
        Refused("a\\b", SftpEntryNameProblem.SEPARATOR),
        Refused("/", SftpEntryNameProblem.SEPARATOR),
        Refused("\\", SftpEntryNameProblem.SEPARATOR),
        Refused("x/", SftpEntryNameProblem.SEPARATOR),
        Refused("/x", SftpEntryNameProblem.SEPARATOR),
        // A control character: C0, DEL and C1.
        Refused("x\u0000y", SftpEntryNameProblem.CONTROL_CHARACTER),
        Refused("x\u0001y", SftpEntryNameProblem.CONTROL_CHARACTER),
        Refused("x\ny", SftpEntryNameProblem.CONTROL_CHARACTER),
        Refused("x\u007Fy", SftpEntryNameProblem.CONTROL_CHARACTER),
        Refused("x\u0085y", SftpEntryNameProblem.CONTROL_CHARACTER),
        Refused("x\u009By", SftpEntryNameProblem.CONTROL_CHARACTER),
        // An invisible format character: bidirectional controls, characters without width, soft hyphen,
        // line and paragraph separators.
        Refused("photo\u202Egpj.exe", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a\u2066b\u2069", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("x\u200Ey", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a\u200Bb", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("\uFEFFnotes", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("x\u2063y", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("Doku\u00ADment", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a.pdf\u2028.exe", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a.pdf\u2029.exe", SftpEntryNameProblem.INVISIBLE_FORMAT),
        // One byte too many (256 bytes in UTF-8), whatever the number of characters.
        Refused("a".repeat(256), SftpEntryNameProblem.TOO_LONG),
        Refused("é".repeat(128), SftpEntryNameProblem.TOO_LONG),
    )

    // ── The rule ─────────────────────────────────────────────────────────────────

    @Test
    fun `a bare name is valid and has no problem`() {
        accepted.forEach { name ->
            assertTrue(isValidEntryName(name), "<$name> must be valid")
            assertNull(sftpEntryNameProblem(name), "<$name> must have no problem")
        }
    }

    @Test
    fun `a name that is not a bare name is not valid and has the expected problem`() {
        refused.forEach { entry ->
            assertFalse(isValidEntryName(entry.name), "<${entry.name}> must be refused")
            assertEquals(entry.problem, sftpEntryNameProblem(entry.name), "<${entry.name}>")
        }
    }

    @Test
    fun `an empty name has no problem to report yet, but is not valid`() {
        assertNull(sftpEntryNameProblem(""))
        assertFalse(isValidEntryName(""))
    }

    @Test
    fun `the edge spaces are not a problem of the name, they make it invalid`() {
        // The dialogs trim what is typed before they ask: the rule itself never trims.
        assertNull(sftpEntryNameProblem(" notes"))
        assertNull(sftpEntryNameProblem("notes "))
        assertFalse(isValidEntryName(" notes"))
        assertFalse(isValidEntryName("notes "))
        assertTrue(isValidEntryName("no tes"))
    }

    @Test
    fun `a dot is reserved only when it is the whole name`() {
        assertEquals(SftpEntryNameProblem.RESERVED, sftpEntryNameProblem("."))
        assertEquals(SftpEntryNameProblem.RESERVED, sftpEntryNameProblem(".."))
        listOf("...", ".a", "a.", "..a", "a..", ".a.", ".bashrc").forEach { name ->
            assertNull(sftpEntryNameProblem(name), name)
            assertTrue(isValidEntryName(name), name)
        }
    }

    @Test
    fun `the length limit is 255 bytes of ASCII`() {
        assertTrue(isValidEntryName("a".repeat(255)))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem("a".repeat(256)))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem("a".repeat(1000)))
    }

    @Test
    fun `the length is counted in UTF-8 bytes, not in characters`() {
        // Two bytes a letter: 127 of them are 254 bytes, 128 are 256.
        assertTrue(isValidEntryName("é".repeat(127)))
        assertTrue(isValidEntryName("é".repeat(127) + "a"))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem("é".repeat(128)))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem("é".repeat(127) + "ab"))
        // Three bytes a letter: 85 of them are 255 bytes, 86 are 258.
        assertTrue(isValidEntryName("日".repeat(85)))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem("日".repeat(86)))
    }

    @Test
    fun `an emoji counts for four bytes`() {
        val emoji = "😀"
        assertEquals(2, emoji.length)
        assertEquals(4, emoji.toByteArray(Charsets.UTF_8).size)

        // 63 emojis are 252 bytes (126 chars), 63 emojis and 3 letters are 255 bytes.
        assertTrue(isValidEntryName(emoji.repeat(63)))
        assertTrue(isValidEntryName(emoji.repeat(63) + "aaa"))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem(emoji.repeat(63) + "aaaa"))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem(emoji.repeat(64)))
    }

    @Test
    fun `a separator is reported before a control character, a control character before the length`() {
        assertEquals(SftpEntryNameProblem.SEPARATOR, sftpEntryNameProblem("a/\u0001b"))
        assertEquals(SftpEntryNameProblem.SEPARATOR, sftpEntryNameProblem("a".repeat(300) + "/"))
        assertEquals(SftpEntryNameProblem.CONTROL_CHARACTER, sftpEntryNameProblem("a".repeat(300) + "\u0001"))
        assertEquals(SftpEntryNameProblem.SEPARATOR, sftpEntryNameProblem("a/\u202Eb"))
        assertEquals(SftpEntryNameProblem.CONTROL_CHARACTER, sftpEntryNameProblem("a\u0001\u202Eb"))
        assertEquals(SftpEntryNameProblem.INVISIBLE_FORMAT, sftpEntryNameProblem("a".repeat(300) + "\u202E"))
    }

    // ── Invisible format characters ──────────────────────────────────────────────

    /** Every refused character, as written in the KDoc of [SftpEntryNameProblem.INVISIBLE_FORMAT]. */
    private val invisibleFormat: List<Char> = buildList {
        addAll(listOf('\u00AD', '\u061C', '\u200B', '\u200E', '\u200F', '\u2028', '\u2029', '\uFEFF'))
        addAll('\u202A'..'\u202E')
        addAll('\u2060'..'\u2064')
        addAll('\u2066'..'\u2069')
        addAll('\u206A'..'\u206F')
        addAll('\uFFF9'..'\uFFFB')
        addAll(listOf(0x17B4, 0x17B5).map { it.toChar() })
    }

    @Test
    fun `every invisible format character is refused, at the start, inside and at the end`() {
        invisibleFormat.forEach { c ->
            val code = "U+%04X".format(c.code)
            listOf("${c}notes", "no${c}tes", "notes$c").forEach { name ->
                assertEquals(SftpEntryNameProblem.INVISIBLE_FORMAT, sftpEntryNameProblem(name), "$code in ${name.escaped()}")
                assertFalse(isValidEntryName(name), "$code must be refused")
            }
            assertTrue(c.isInvisibleFormat(), code)
        }
    }

    @Test
    fun `the characters next to the refused ranges stay allowed`() {
        // Code points, never raw characters: most of them cannot be seen in the source.
        val neighbours = listOf(
            0x00AC, 0x00AE, 0x061B, 0x061D, 0x2010, 0x200A, 0x200C, 0x200D, 0x2027, 0x202F, 0x205F,
            0x2070, 0xFEFE, 0xFF00, 0xFFF8, 0xFFFC, 0x180E, 0xFE0F, 0x034F, 0x17B3, 0x17B6,
        ).map { it.toChar() }
        neighbours.forEach { c ->
            assertFalse(c.isInvisibleFormat(), "U+%04X".format(c.code))
            assertTrue(isValidEntryName("a${c}b"), "U+%04X".format(c.code))
        }
        // A no-break space is no neighbour of any range, and stays allowed too.
        assertTrue(isValidEntryName("a" + 0x00A0.toChar() + "b"))
    }

    @Test
    fun `the Khmer inherent vowels are refused`() {
        listOf(0x17B4, 0x17B5).map { it.toChar() }.forEach { c ->
            assertTrue(c.isInvisibleFormat(), "U+%04X".format(c.code))
            assertEquals(SftpEntryNameProblem.INVISIBLE_FORMAT, sftpEntryNameProblem("a${c}b"))
            assertFalse(isValidEntryName("a${c}b"))
        }
    }

    @Test
    fun `the unassigned U+2065 inside the range of the invisible group is refused too`() {
        assertTrue(0x2065.toChar().isInvisibleFormat())
        assertEquals(SftpEntryNameProblem.INVISIBLE_FORMAT, sftpEntryNameProblem("a" + 0x2065.toChar() + "b"))
    }

    /** A string from code points, so that no invisible or composed character is written raw. */
    private fun cp(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    @Test
    fun `the joiners and the format characters of a script stay allowed`() {
        listOf(
            // Persian, with a zero width non-joiner.
            cp(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645),
            // Devanagari conjunct, with a zero width joiner.
            cp(0x0915, 0x094D, 0x200D, 0x0937),
            // Family emoji, with zero width joiners.
            cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467),
            // Heart with a variation selector.
            cp(0x2764, 0xFE0F),
            // Flag of England, made of tag characters.
            cp(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F),
            "a" + 0x180E.toChar() + "b",
            cp(0x0600) + "123",
            "a" + 0x00A0.toChar() + "b",
        ).forEach { name ->
            assertNull(sftpEntryNameProblem(name), name.escaped())
            assertTrue(isValidEntryName(name), name.escaped())
        }
    }

    @Test
    fun `replaceInvisibleCharacters replaces controls and format characters and nothing else`() {
        val replacement = 0xFFFD.toChar()
        assertEquals("photo_gpj.exe", replaceInvisibleCharacters("photo" + 0x202E.toChar() + "gpj.exe", '_'))
        assertEquals("a_b", replaceInvisibleCharacters("a\nb", '_'))
        assertEquals("a${replacement}b$replacement", replaceInvisibleCharacters(cp(0x61, 0x200B, 0x62, 0x2028), replacement))
        assertEquals(cp(0x61, 0x200D, 0x62, 0x200C), replaceInvisibleCharacters(cp(0x61, 0x200D, 0x62, 0x200C), '_'))
        val ordinary = "notes é " + cp(0x65E5, 0x1F600) + ".txt"
        assertEquals(ordinary, replaceInvisibleCharacters(ordinary, '_'))
        assertEquals("", replaceInvisibleCharacters("", '_'))
    }

    private fun String.escaped(): String = buildString {
        for (c in this@escaped) {
            if (c.code in 0x20..0x7E) append(c) else append("\\u%04X".format(c.code))
        }
    }

    // ── Invariants with sanitizeFilename and sanitizePath ────────────────────────

    private fun assertNameIsKeptByTheSanitizers(name: String) {
        // sanitizeFilename drops what it forbids and cuts: a name it leaves alone is a name nothing was dropped from.
        assertEquals(name, sftpManager.sanitizeFilename(name), "sanitizeFilename changes <$name>")
        // Joined to a folder, the name stays the last segment of the path, whatever the characters it holds.
        assertEquals("/home/u/$name", sftpManager.sanitizePath("/home/u/$name"), "sanitizePath changes <$name>")
    }

    @Test
    fun `an accepted name comes out of the sanitizers unchanged`() {
        accepted.forEach { assertNameIsKeptByTheSanitizers(it) }
    }

    @Test
    fun `a name that passes the rule comes out of the sanitizers unchanged on pseudo random input`() {
        // Fixed seed: the test stays deterministic.
        val random = Random(64)
        val pool = listOf(
            "a", "b", "Z", "0", ".", "..", "/", "\\", " ", "\u0000", "\n", "\t", "\u007F", "\u0085", "\u009F",
            "%2F", "%2e", "+", "é", "日", "😀", "-", "_", "~", "\u202E", "\u200B", "\u200D", "\uFEFF",
        )
        var acceptedCount = 0
        var refusedCount = 0
        repeat(6000) {
            val name = buildString {
                repeat(random.nextInt(0, 7)) { append(pool[random.nextInt(pool.size)]) }
            }
            if (isValidEntryName(name)) {
                acceptedCount++
                assertNameIsKeptByTheSanitizers(name)
            } else {
                refusedCount++
            }
        }

        // Both sides are reached: a rule that accepted everything, or nothing, would pass the loop for free.
        assertTrue(acceptedCount > 300, "only $acceptedCount names accepted")
        assertTrue(refusedCount > 300, "only $refusedCount names refused")
    }

    @Test
    fun `a name that goes through the upload sanitizer is a valid entry name`() {
        // uploadEntryName replaces what it forbids by an underscore: what it returns has to pass the strict rule.
        // ASCII names only: uploadEntryName cuts at 255 CHARACTERS, while the rule counts 255 BYTES, so a
        // long name of multi-byte letters (200 accented letters, 400 bytes) goes through the first and not
        // through the second. The server would refuse such a name, which is no traversal (see the KDoc of
        // uploadEntryName); the upload does not ask isValidEntryName.
        val raws = listOf(
            "../../.profile",
            "../.ssh/authorized_keys",
            "a/b.txt",
            "a\\b",
            "primary:Download/foo.txt",
            "a\u0000b",
            "a\u0085b\u009Fc",
            "photo\u202Egpj.exe",
            "\u200B",
            "a\u2066b\u2069",
            "\uFEFFreport.pdf",
            "a.pdf\u2028.exe",
            "  x  ",
            "...",
            ".hidden",
            "%2e%2e%2f",
            "a".repeat(300),
            "a".repeat(254) + " " + "b".repeat(5),
            " ".repeat(300) + "z",
        )
        raws.forEach { raw ->
            val name = uploadEntryName(raw)
            assertTrue(name != null && isValidEntryName(name), "uploadEntryName(<$raw>) gives <$name>")
        }
    }
}
