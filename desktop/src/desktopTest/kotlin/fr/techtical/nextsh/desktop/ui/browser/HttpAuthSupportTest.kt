// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests of the realm sanitizing shown in the HTTP sign-in panel. */
class HttpAuthSupportTest {

    @Test
    fun `sanitizeAuthRealm keeps a plain realm and trims it`() {
        assertEquals("Private area", sanitizeAuthRealm("Private area"))
        assertEquals("Private area", sanitizeAuthRealm("  Private area  "))
    }

    @Test
    fun `sanitizeAuthRealm keeps ordinary non-ASCII text`() {
        val accented = "Espace priv" + 0xE9.toChar()
        val cjk = "" + 0x7BA1.toChar() + 0x7406.toChar()

        assertEquals(accented, sanitizeAuthRealm(accented))
        assertEquals(cjk, sanitizeAuthRealm(cjk))
    }

    @Test
    fun `sanitizeAuthRealm strips ISO control characters`() {
        val controls = listOf(0, 9, 10, 13, 0x1B, 0x7F, 0x85).map { it.toChar() }
        val raw = "Ad" + controls.joinToString("") + "min"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips bidi override and isolation characters`() {
        val bidi = (0x202A..0x202E).map { it.toChar() } + (0x2066..0x2069).map { it.toChar() }
        val raw = "Ad" + bidi.joinToString("") + "min"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips line and paragraph separators`() {
        val raw = "Ad" + 0x2028.toChar() + "mi" + 0x2029.toChar() + "n"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips zero-width and directional marks`() {
        // ZWSP, ZWNJ, ZWJ, LRM, RLM, ALM, word joiner and invisible operators.
        val invisible = listOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x061C, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064)
            .map { it.toChar() }
        val raw = "Ad" + invisible.joinToString("") + "min"

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm strips the byte order mark and the soft hyphen`() {
        val raw = 0xFEFF.toChar() + "Ad" + 0xAD.toChar() + "min" + 0xFEFF.toChar()

        assertEquals("Admin", sanitizeAuthRealm(raw))
    }

    @Test
    fun `sanitizeAuthRealm returns null when nothing printable is left`() {
        val junk = (0x202A..0x202E).map { it.toChar() }.joinToString("") +
            10.toChar() + 0.toChar() + 0x200B.toChar() + 0xFEFF.toChar()

        assertNull(sanitizeAuthRealm(null))
        assertNull(sanitizeAuthRealm(""))
        assertNull(sanitizeAuthRealm("   "))
        assertNull(sanitizeAuthRealm(junk))
        assertNull(sanitizeAuthRealm(" $junk "))
    }

    @Test
    fun `sanitizeAuthRealm caps at 80 characters and appends an ellipsis only when truncated`() {
        val ellipsis = 0x2026.toChar().toString()

        assertEquals("a".repeat(80), sanitizeAuthRealm("a".repeat(80)))
        assertEquals("a".repeat(80) + ellipsis, sanitizeAuthRealm("a".repeat(81)))
        assertEquals("a".repeat(80) + ellipsis, sanitizeAuthRealm("a".repeat(500)))
    }

    @Test
    fun `sanitizeAuthRealm bounds a huge input before filtering it`() {
        val ellipsis = 0x2026.toChar().toString()

        assertEquals("a".repeat(80) + ellipsis, sanitizeAuthRealm("a".repeat(1_000_000)))
        // Only the first AUTH_REALM_RAW_LIMIT characters are ever looked at.
        val buried = 0.toChar().toString().repeat(AUTH_REALM_RAW_LIMIT) + "visible"
        assertNull(sanitizeAuthRealm(buried))
    }

    @Test
    fun `sanitizeAuthRealm does not split a surrogate pair when truncating`() {
        val ellipsis = 0x2026.toChar().toString()
        val emoji = "" + 0xD83D.toChar() + 0xDE00.toChar()

        val result = sanitizeAuthRealm("a".repeat(79) + emoji + "bbb")

        assertEquals("a".repeat(79) + ellipsis, result)
    }

    @Test
    fun `sanitizeAuthRealm does not split a surrogate pair at the input bound`() {
        val emoji = "" + 0xD83D.toChar() + 0xDE00.toChar()
        val raw = "a".repeat(AUTH_REALM_RAW_LIMIT - 1) + emoji

        val result = sanitizeAuthRealm(raw)

        // 511 letters survive the bound and the emoji is dropped whole: no lone surrogate.
        assertEquals("a".repeat(80) + 0x2026.toChar(), result)
        assertTrue(assertNotNull(result).none { Character.isSurrogate(it) })
    }
}
