// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/** The token that both the notification and the launcher shortcuts use: how it is made and compared. */
class SecretTokenTest {

    private val random = SecureRandom()

    @Test
    fun `a token is 43 characters of URL-safe text and never the same twice`() {
        val first = SecretToken.generate(random)
        val second = SecretToken.generate(random)

        assertEquals(SecretToken.LENGTH, first.length)
        assertTrue(SecretToken.isWellFormed(first), first)
        assertNotEquals(first, second)
    }

    @Test
    fun `only the exact token matches`() {
        val token = SecretToken.generate(random)
        val expected = SecretToken.toBytes(token)

        assertTrue(SecretToken.matches(expected, token))
        assertFalse(SecretToken.matches(expected, null))
        assertFalse(SecretToken.matches(expected, ""))
        assertFalse(SecretToken.matches(expected, token.dropLast(1)))
        assertFalse(SecretToken.matches(expected, token + "A"))
        assertFalse(SecretToken.matches(expected, SecretToken.generate(random)))
    }

    @Test
    fun `text beyond ASCII never matches, even where the lossy encoding would give the same bytes`() {
        val token = SecretToken.generate(random)
        val expected = SecretToken.toBytes(token)
        val replaced = "é" + token.drop(1) // same length, a letter with an accent first

        assertFalse(SecretToken.matches(expected, replaced))
        assertFalse(SecretToken.matches(expected, token + "é"))

        // The case the check is for: an expected value that has a '?', which the encoder writes for
        // any character it cannot represent. Real tokens never have one; the guarantee does not rest on it.
        val withQuestionMark = SecretToken.toBytes("ab?d")
        assertTrue(SecretToken.matches(withQuestionMark, "ab?d"))
        assertFalse(SecretToken.matches(withQuestionMark, "abéd"))
        assertFalse(SecretToken.matches(withQuestionMark, "ab€d"))
    }

    @Test
    fun `a stored value is well formed only if it has the shape of a token`() {
        assertFalse(SecretToken.isWellFormed(null))
        assertFalse(SecretToken.isWellFormed(""))
        assertFalse(SecretToken.isWellFormed("A".repeat(42)))
        assertFalse(SecretToken.isWellFormed("A".repeat(44)))
        assertFalse(SecretToken.isWellFormed("+".repeat(43)), "not the URL-safe alphabet")
        assertFalse(SecretToken.isWellFormed("=".repeat(43)), "no padding")
        assertTrue(SecretToken.isWellFormed("A".repeat(42) + "-"))
        assertTrue(SecretToken.isWellFormed("_".repeat(43)))
    }
}
