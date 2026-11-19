// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import io.mockk.mockk
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule of the name of a new folder, of a new file and of a renamed entry: a bare name, or nothing.
 * Refused and said, never corrected. The same tables as the Android tests (`SftpEntryNameTest` of `:app`),
 * which keep the two copies of the rule in step.
 */
class SftpEntryNameTest {

    // Non-ASCII text is built from code points, never written raw: invisible or look-alike characters
    // must not hide in this source.
    private fun cp(vararg codes: Int): String = buildString { codes.forEach { appendCodePoint(it) } }

    private val E_ACUTE = cp(0xE9)
    private val KANJI = cp(0x65E5)
    private val EMOJI = cp(0x1F600)

    // sanitizePath reads no state: the session manager is only there to build the manager.
    private val manager = DesktopSftpManager(mockk(relaxed = true))

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
        E_ACUTE,
        "a".repeat(255),
        // 254 and 255 bytes in UTF-8, though 127 and 128 characters.
        E_ACUTE.repeat(127),
        E_ACUTE.repeat(127) + "a",
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
        // An invisible format character: bidirectional control, no width, soft hyphen, line separator.
        Refused("photo\u202Egpj.exe", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a\u2066b\u2069", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("x\u200Ey", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a\u200Bb", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("\uFEFFnotes", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("x\u2063y", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("Doku\u00ADment", SftpEntryNameProblem.INVISIBLE_FORMAT),
        Refused("a.pdf\u2028.exe", SftpEntryNameProblem.INVISIBLE_FORMAT),
        // One byte too many (256 bytes in UTF-8), whatever the number of characters.
        Refused("a".repeat(256), SftpEntryNameProblem.TOO_LONG),
        Refused(E_ACUTE.repeat(128), SftpEntryNameProblem.TOO_LONG),
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
        assertTrue(isValidEntryName(E_ACUTE.repeat(127)))
        assertTrue(isValidEntryName(E_ACUTE.repeat(127) + "a"))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem(E_ACUTE.repeat(128)))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem(E_ACUTE.repeat(127) + "ab"))
        // Three bytes a letter: 85 of them are 255 bytes, 86 are 258.
        assertTrue(isValidEntryName(KANJI.repeat(85)))
        assertEquals(SftpEntryNameProblem.TOO_LONG, sftpEntryNameProblem(KANJI.repeat(86)))
    }

    @Test
    fun `an emoji counts for four bytes`() {
        val emoji = EMOJI
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
        assertEquals(SftpEntryNameProblem.CONTROL_CHARACTER, sftpEntryNameProblem("a\u0001\u202Eb"))
        assertEquals(SftpEntryNameProblem.INVISIBLE_FORMAT, sftpEntryNameProblem("a".repeat(300) + "\u202E"))
        assertEquals(SftpEntryNameProblem.SEPARATOR, sftpEntryNameProblem("a/\u202Eb"))
    }

    // ── Invisible format characters ──────────────────────────────────────────────

    /** The refused set, range by range: first and last code point of each. */
    private val refusedRanges = listOf(
        '\u00AD'..'\u00AD',
        '\u061C'..'\u061C',
        '\u17B4'..'\u17B5',
        '\u200B'..'\u200B',
        '\u200E'..'\u200F',
        '\u2028'..'\u2029',
        '\u202A'..'\u202E',
        '\u2060'..'\u206F',
        '\uFEFF'..'\uFEFF',
        '\uFFF9'..'\uFFFB',
    )

    @Test
    fun `every invisible format character is refused, at the start, inside and at the end`() {
        var count = 0
        refusedRanges.forEach { range ->
            range.forEach { c ->
                count++
                assertTrue(c.isInvisibleFormat(), "U+${c.code.toString(16)} must be recognized")
                listOf("${c}ab", "a${c}b", "ab$c").forEach { name ->
                    assertEquals(
                        SftpEntryNameProblem.INVISIBLE_FORMAT,
                        sftpEntryNameProblem(name),
                        "U+${c.code.toString(16)} in <$name>",
                    )
                    assertFalse(isValidEntryName(name))
                }
            }
        }
        assertEquals(1 + 1 + 2 + 1 + 2 + 2 + 5 + 16 + 1 + 3, count)
    }

    @Test
    fun `the characters right next to the refused ranges are not recognized`() {
        // The code point just below and just above each refused range (a neighbor that is itself refused is left out).
        listOf(
            0x00AC, 0x00AE, 0x061B, 0x061D, 0x17B3, 0x17B6, 0x200A, 0x200C, 0x200D, 0x2010, 0x2027, 0x202F,
            0x205F, 0x2070, 0xFEFE, 0xFF00, 0xFFF8, 0xFFFC,
        ).forEach { code ->
            assertFalse(code.toChar().isInvisibleFormat(), "U+${code.toString(16)} must stay allowed")
        }
    }

    @Test
    fun `the format characters that belong to a script are not recognized`() {
        // Not neighbors of a refused range: the no-break space, the combining grapheme joiner, the variation
        // selector 16, the Mongolian selectors (U+180B to U+180F) and the Arabic number sign.
        listOf(0x00A0, 0x034F, 0xFE0F, 0x180B, 0x180C, 0x180D, 0x180E, 0x180F, 0x0600).forEach { code ->
            assertFalse(code.toChar().isInvisibleFormat(), "U+${code.toString(16)} must stay allowed")
        }
    }

    @Test
    fun `the joiners and the format characters of a script stay allowed`() {
        listOf(
            // Persian spelling with a ZWNJ.
            cp(0x645, 0x6CC, 0x200C, 0x62E, 0x648, 0x627, 0x647, 0x645),
            // Devanagari conjunct with a ZWJ.
            cp(0x915, 0x94D, 0x200D, 0x937),
            // Family emoji, heart with variation selector, flag of England (tag characters).
            cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467),
            cp(0x2764, 0xFE0F),
            cp(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F),
            cp('a'.code, 0x180E, 'b'.code),
            cp(0x180B, 0x180C, 0x180D, 0x180F),
            cp(0x600, '1'.code, '2'.code, '3'.code),
            cp('a'.code, 0xA0, 'b'.code),
        ).forEach { name ->
            assertNull(sftpEntryNameProblem(name), name)
            assertTrue(isValidEntryName(name), name)
        }
    }

    @Test
    fun `the Khmer inherent vowels are refused`() {
        listOf(0x17B4, 0x17B5).forEach { code ->
            assertEquals(SftpEntryNameProblem.INVISIBLE_FORMAT, sftpEntryNameProblem(cp('a'.code, code, 'b'.code)))
        }
    }

    @Test
    fun `replaceInvisibleCharacters replaces the controls and the format characters and nothing else`() {
        assertEquals("a_b", replaceInvisibleCharacters("a\u202Eb", '_'))
        assertEquals("a_b", replaceInvisibleCharacters("a\nb", '_'))
        assertEquals(cp('a'.code, 0xFFFD, 'b'.code), replaceInvisibleCharacters("a\u2028b", 0xFFFD.toChar()))
        assertEquals("__", replaceInvisibleCharacters("\u200B\u200B", '_'))
        assertEquals("_x_", replaceInvisibleCharacters("\u0085x\uFEFF", '_'))
    }

    @Test
    fun `replaceInvisibleCharacters keeps the joiners and an ordinary name as they are`() {
        val family = cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467)
        assertEquals(family, replaceInvisibleCharacters(family, '_'))
        val conjunct = cp(0x915, 0x94D, 0x200D, 0x937)
        assertEquals(conjunct, replaceInvisibleCharacters(conjunct, '_'))
        assertEquals("notes a\\b.txt", replaceInvisibleCharacters("notes a\\b.txt", '_'))
        val ordinary = "report.pdf"
        assertTrue(ordinary === replaceInvisibleCharacters(ordinary, '_'))
    }

    @Test
    fun `a name made of replaced characters is a valid entry name`() {
        listOf("photo\u202Egpj.exe", "\u200B", "a\u2066b\u2069", "\uFEFFreport.pdf", "a\u0001b").forEach { raw ->
            val replaced = replaceInvisibleCharacters(raw, '_')
            assertTrue(isValidEntryName(replaced), "<$raw> gives <$replaced>")
        }
    }

    // ── Invariant with sanitizePath ──────────────────────────────────────────────

    private fun assertNameIsKeptByTheSanitizer(name: String) {
        // Joined to a folder, the name stays the last segment of the path, whatever the characters it holds.
        assertEquals("/home/u/$name", manager.sanitizePath("/home/u/$name"), "sanitizePath changes <$name>")
    }

    @Test
    fun `an accepted name comes out of sanitizePath unchanged`() {
        accepted.forEach { assertNameIsKeptByTheSanitizer(it) }
    }

    @Test
    fun `a name that passes the rule comes out of sanitizePath unchanged on pseudo random input`() {
        // Fixed seed: the test stays deterministic. Same pool as the Android test.
        val random = Random(64)
        val pool = listOf(
            "a", "b", "Z", "0", ".", "..", "/", "\\", " ", "\u0000", "\n", "\t", "\u007F", "\u0085", "\u009F",
            "\u202E", "\u200B", "\u200D", "\uFEFF",
            "%2F", "%2e", "+", E_ACUTE, KANJI, EMOJI, "-", "_", "~",
        )
        var acceptedCount = 0
        var refusedCount = 0
        repeat(6000) {
            val name = buildString {
                repeat(random.nextInt(0, 7)) { append(pool[random.nextInt(pool.size)]) }
            }
            if (isValidEntryName(name)) {
                acceptedCount++
                assertNameIsKeptByTheSanitizer(name)
            } else {
                refusedCount++
            }
        }

        // Both sides are reached: a rule that accepted everything, or nothing, would pass the loop for free.
        assertTrue(acceptedCount > 300, "only $acceptedCount names accepted")
        assertTrue(refusedCount > 300, "only $refusedCount names refused")
    }
}
