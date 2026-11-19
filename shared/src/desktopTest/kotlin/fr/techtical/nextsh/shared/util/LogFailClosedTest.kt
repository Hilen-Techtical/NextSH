// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Logging helpers never throw and never fall back to the raw input. */
class LogFailClosedTest {

    @Test
    fun `failClosed returns the block value when nothing goes wrong`() {
        assertEquals("ok", failClosed("fallback") { "ok" })
    }

    @Test
    fun `failClosed returns the fallback on any throwable, errors included`() {
        val failures: List<() -> Throwable> = listOf(
            { IllegalStateException("x") },
            { ExceptionInInitializerError("x") },
            { NoClassDefFoundError("x") },
            { StackOverflowError("x") },
        )
        for (failure in failures) {
            assertEquals("fallback", failClosed("fallback") { throw failure() })
        }
    }

    private class HostileCause : RuntimeException("srv01.example.com") {
        override val cause: Throwable get() = throw IllegalStateException("cause getter failed")
    }

    private class HostileTrace : RuntimeException("srv01.example.com") {
        override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("trace getter failed")
    }

    @Test
    fun `errorClass gives a fixed marker when the throwable cannot be read`() {
        assertEquals(UNKNOWN_ERROR_CLASS, errorClass(HostileCause()))
    }

    @Test
    fun `safeStackTrace gives a fixed marker when the trace cannot be read`() {
        assertEquals(UNAVAILABLE_STACK_TRACE, safeStackTrace(HostileTrace()))
        assertFalse(safeStackTrace(HostileCause()).contains("srv01"))
    }

    @Test
    fun `clean bounds the input before looking for secrets`() {
        val prefix = "word ".repeat(LogScrubber.MAX_INPUT_CHARS / 5 + 10)
        val out = LogScrubber.clean(prefix + "password=hunter2")
        assertFalse(out.contains("hunter2"), "secret beyond the bound must be cut, not printed")
        assertTrue(out.endsWith(" [truncated]"))
        assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean("password=hunter2 " + prefix))
    }

    @Test
    fun `assignment parsing survives random input`() {
        val alphabet = "ab=:\"'{}[](),;& \n\r\t\\-_pP@/.0123456789<>%ü".toCharArray()
        val keys = listOf("password=", "\"token\":", "label=", "id=", "Authorization: ", "{\"a\":{", "cookie=")
        val random = Random(42)
        repeat(3_000) {
            val sb = StringBuilder()
            repeat(random.nextInt(1, 120)) {
                if (random.nextInt(8) == 0) sb.append(keys[random.nextInt(keys.size)])
                else sb.append(alphabet[random.nextInt(alphabet.size)])
            }
            val input = sb.toString()
            // Not wrapped by failClosed: an exception here fails the test.
            LogAssignments.mask(input)
            LogAssignments.containsSecret(input)
        }
    }
}
