// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogThrowablesTest {

    private class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val userData = listOf("srv01.example.com", "/home/thibaud", "hunter2", "web-prod")

    private fun assertNoUserData(text: String) {
        for (value in userData) assertFalse(text.contains(value), "'$value' leaked in: $text")
    }

    // ── errorClass ───────────────────────────────────────────────────────────

    @Test
    fun `errorClass gives the simple class name without the message`() {
        val text = errorClass(IllegalStateException("cannot reach srv01.example.com"))
        assertEquals("IllegalStateException", text)
    }

    @Test
    fun `errorClass walks the cause chain`() {
        val t = TransportException(
            "connect web-prod failed",
            IOException("/home/thibaud unreadable", SocketTimeoutException("hunter2")),
        )
        assertEquals("TransportException<-IOException<-SocketTimeoutException", errorClass(t))
    }

    @Test
    fun `errorClass falls back on a stable name for an anonymous class`() {
        val anonymous = object : RuntimeException("srv01.example.com") {}
        val first = errorClass(anonymous)
        assertTrue(first.isNotBlank())
        assertEquals(first, errorClass(anonymous))
        assertFalse(first.contains('.'), "expected a name without package: $first")
        assertNoUserData(first)
    }

    @Test
    fun `errorClass is bounded on a long chain`() {
        var t: Throwable = IOException("leaf")
        repeat(20) { t = RuntimeException("level $it", t) }
        val text = errorClass(t)
        assertTrue(text.endsWith("<-..."), text)
        assertEquals(MAX_CAUSE_DEPTH + 1, text.split("<-").size)
    }

    @Test
    fun `errorClass survives a cause cycle`() {
        val a = RuntimeException("a")
        val b = IllegalArgumentException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals("RuntimeException<-IllegalArgumentException<-...", errorClass(a))
    }

    // ── safeStackTrace ───────────────────────────────────────────────────────

    @Test
    fun `safeStackTrace prints classes and frames without any message`() {
        val t = TransportException(
            "connect web-prod failed",
            IOException("/home/thibaud unreadable", SocketTimeoutException("hunter2")),
        )
        val text = safeStackTrace(t)
        assertTrue(text.startsWith(TransportException::class.java.name), text)
        assertTrue(text.contains("\n\tat "), text)
        assertTrue(text.contains("Caused by: java.io.IOException"), text)
        assertTrue(text.contains("Caused by: java.net.SocketTimeoutException"), text)
        assertTrue(text.contains("LogThrowablesTest"), "own frame expected: $text")
        assertNoUserData(text)
    }

    @Test
    fun `safeStackTrace elides frames shared with the enclosing trace`() {
        val t = RuntimeException("outer", IOException("inner"))
        val causePart = safeStackTrace(t).substringAfter("Caused by: ")
        assertTrue(causePart.contains("more"), causePart)
    }

    @Test
    fun `safeStackTrace caps the frames of one throwable`() {
        fun deep(n: Int): Throwable = if (n == 0) IllegalStateException("x") else deep(n - 1)
        val text = safeStackTrace(deep(MAX_FRAMES_PER_THROWABLE + 30))
        val frames = text.lines().count { it.startsWith("\tat ") }
        assertEquals(MAX_FRAMES_PER_THROWABLE, frames)
        assertTrue(text.lines().last().matches(Regex("\t\\.\\.\\. \\d+ more")), text.lines().last())
    }

    @Test
    fun `safeStackTrace survives a cause cycle`() {
        val a = RuntimeException("srv01.example.com")
        val b = IllegalArgumentException("hunter2")
        a.initCause(b)
        b.initCause(a)
        val text = safeStackTrace(a)
        assertTrue(text.endsWith("Caused by: ..."), text)
        assertNoUserData(text)
    }
}
