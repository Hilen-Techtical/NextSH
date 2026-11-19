// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How an edit of the masked PIN field reaches the buffer that holds the PIN. The field shows one
 * bullet per character, so every edit it reports is a text made of bullets and of what was just
 * typed. All PINs are test values.
 */
class MaskedPinFieldTest {

    /** The bullet the field shows, by code point so that this file holds no look-alike. */
    private val bullet = '•'

    private fun buffer(pin: String) = pin.toMutableList()

    @Test
    fun `a typed character is appended to the buffer`() {
        val pin = buffer("12")

        // The field showed two bullets, the user typed a 3: the text it reports is "••3".
        applyMaskedPinEdit(pin, "$bullet${bullet}3")

        assertEquals("123", pin.joinToString(""))
    }

    @Test
    fun `typing in an empty field starts the buffer`() {
        val pin = buffer("")

        applyMaskedPinEdit(pin, "a")

        assertEquals("a", pin.joinToString(""))
    }

    @Test
    fun `a deletion removes the trailing characters`() {
        val pin = buffer("1234")

        applyMaskedPinEdit(pin, "$bullet$bullet$bullet")

        assertEquals("123", pin.joinToString(""))
    }

    @Test
    fun `deleting everything empties the buffer, and deleting from an empty one does nothing`() {
        val pin = buffer("12")

        applyMaskedPinEdit(pin, "")
        applyMaskedPinEdit(pin, "")

        assertEquals(emptyList<Char>(), pin)
    }

    @Test
    fun `a pasted text is appended as it is, and a bullet in it is not a character of the PIN`() {
        val pin = buffer("12")

        applyMaskedPinEdit(pin, "$bullet${bullet}ab${bullet}c")

        assertEquals("12abc", pin.joinToString(""))
    }

    @Test
    fun `an edit that changes nothing leaves the buffer as it is`() {
        // The cursor moved: the text is the same run of bullets.
        val pin = buffer("1234")

        applyMaskedPinEdit(pin, "$bullet$bullet$bullet$bullet")

        assertEquals("1234", pin.joinToString(""))
    }

    @Test
    fun `characters outside the basic plane are kept whole`() {
        val pin = buffer("")
        val emoji = "😀"

        applyMaskedPinEdit(pin, emoji)

        assertEquals(emoji, pin.joinToString(""))
        assertEquals(2, pin.size, "one character of the PIN is a pair of chars, the key counts the code points")
    }
}
