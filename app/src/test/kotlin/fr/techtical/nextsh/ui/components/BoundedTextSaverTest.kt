// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedTextSaverTest {

    private val scope = SaverScope { true }

    private fun save(text: String, max: Int): String? =
        with(boundedTextSaver(max)) { scope.save(text) }

    @Test
    fun `a text under the bound is saved and restored as it is`() {
        val saver = boundedTextSaver(10)
        val saved = with(saver) { scope.save("hello") }

        assertEquals("hello", saved)
        assertEquals("hello", saver.restore(saved!!))
    }

    @Test
    fun `a text exactly at the bound is still saved`() {
        assertEquals("abcde", save("abcde", 5))
    }

    @Test
    fun `a text over the bound saves nothing so the restore falls back to the initial value`() {
        assertNull(save("abcdef", 5))
    }

    @Test
    fun `the bound counts UTF-8 bytes, not characters`() {
        // Two characters, four bytes.
        assertNull(save("éé", 3))
        assertEquals("éé", save("éé", 4))
    }

    @Test
    fun `an empty text is saved`() {
        assertEquals("", save("", 0))
    }

    @Test
    fun `a huge pasted text is not saved and the bounds fit what the fields accept`() {
        val huge = "x".repeat(2 * 1024 * 1024)

        assertNull(save(huge, SavedTextLimits.COMMAND_BYTES))
        assertTrue(SavedTextLimits.COMMAND_BYTES >= 64 * 1024)
        assertTrue(SavedTextLimits.SFTP_NAME_BYTES >= 4 * 255)
        assertTrue(SavedTextLimits.LABEL_BYTES >= 4 * 256)
    }

    @Test
    fun `exceedsUtf8Bytes agrees with the encoded size`() {
        assertFalse(exceedsUtf8Bytes("abc", 3))
        assertTrue(exceedsUtf8Bytes("abcd", 3))
        // A surrogate pair is four bytes in UTF-8.
        assertTrue(exceedsUtf8Bytes("😀", 3))
        assertFalse(exceedsUtf8Bytes("😀", 4))
    }
}
