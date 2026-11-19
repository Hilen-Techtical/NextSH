// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.logging

import android.util.Log
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.LogScrubber
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The two Timber trees of the app apply the log policy to every call, whatever the call site
 * wrote: no exception message, no host, path, address or fingerprint in a line, in release and in
 * debug alike.
 */
class LogTreesTest {

    private data class Line(val priority: Int, val tag: String, val message: String)

    private val lines = mutableListOf<Line>()
    private val release = ReleaseLogTree { priority, tag, message -> lines += Line(priority, tag, message) }

    private val hostMessage = "connection to db-prod.corp.example:2222 refused"
    private val pathMessage = "cannot read /home/jean.dupont/.ssh/id_ed25519_work"

    /** A throwable whose messages (and whose cause message) carry data that must never be logged. */
    private fun leakyThrowable(): Throwable =
        IOException(hostMessage, SocketTimeoutException(pathMessage))

    @BeforeEach
    fun setUp() {
        Timber.plant(release)
    }

    @AfterEach
    fun tearDown() {
        Timber.uprootAll()
    }

    private fun assertNoPersonalData(text: String) {
        for (leak in listOf("db-prod", "corp.example", "2222", "jean.dupont", "id_ed25519_work", "connection to", "cannot read")) {
            assertFalse(text.contains(leak), "leaked [$leak] in: $text")
        }
    }

    // ── ReleaseLogTree ───────────────────────────────────────────────────────────

    @Test
    fun `release drops verbose and debug before Timber formats the message`() {
        var formatted = false
        val argument = object {
            override fun toString(): String {
                formatted = true
                return "never printed"
            }
        }

        Timber.v("verbose %s", argument)
        Timber.d("debug %s", argument)
        Timber.tag("T").v("verbose %s", argument)
        Timber.tag("T").d("debug %s", argument)

        assertFalse(formatted, "a line below INFO must be dropped before formatting")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `release logs info warn and error`() {
        Timber.tag("Sync").i("started")
        Timber.tag("Sync").w("slow")
        Timber.tag("Sync").e("failed")

        assertEquals(
            listOf(
                Line(Log.INFO, "Sync", "started"),
                Line(Log.WARN, "Sync", "slow"),
                Line(Log.ERROR, "Sync", "failed"),
            ),
            lines,
        )
    }

    @Test
    fun `release uses a default tag when the call site set none`() {
        Timber.w("no tag")

        assertEquals("NextSH", lines.single().tag)
    }

    @Test
    fun `release logs the class chain of a throwable and never its message`() {
        Timber.tag("Ssh").e(leakyThrowable(), "Connect failed")

        val line = lines.single()
        assertEquals("Connect failed [IOException<-SocketTimeoutException]", line.message)
        assertNoPersonalData(line.message)
        assertFalse(line.message.contains("\tat "), "no stack frames in release: ${line.message}")
    }

    @Test
    fun `release logs a throwable given without any message`() {
        Timber.tag("Ssh").e(leakyThrowable())

        val line = lines.single()
        assertEquals("Error [IOException<-SocketTimeoutException]", line.message)
        assertNoPersonalData(line.message)
    }

    @Test
    fun `release keeps the call site text when Timber formats it with arguments`() {
        Timber.tag("Sftp").w(leakyThrowable(), "Transfer %d of %d failed", 2, 5)

        assertEquals("Transfer 2 of 5 failed [IOException<-SocketTimeoutException]", lines.single().message)
    }

    @Test
    fun `release masks a host an address a path and a fingerprint in the message`() {
        Timber.tag("Net").w(
            "Reach 192.168.1.20:22 via build-server.corp.example, key /home/jean/id_rsa, " +
                "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
        )

        val message = lines.single().message
        for (leak in listOf("192.168.1.20", "build-server", "/home/jean", "abcdefghijklmnopqrstuvwxyz")) {
            assertFalse(message.contains(leak), "leaked [$leak] in: $message")
        }
    }

    @Test
    fun `release keeps the LogId and the codes of the app log lines readable`() {
        val host = LogId.of("3f2c9a52-6d0f-4c58-a0a0-1d2f3e4f5a6b")
        val device = LogId.of("device-uuid-1")
        val messages = listOf(
            "SSH connected (password) hostId=$host",
            "Sync pull timeout for device=$device",
            "SFTP ls failed status=PERMISSION_DENIED",
            "KnownHosts: host key mismatch, algorithm=ssh-ed25519",
            "Key generated: id=$host type=ED25519",
            "Transfer $device failed (UNKNOWN)",
            "runSync: device=$device pushed=3 pulled=2 conflicts=0",
        )

        messages.forEach { Timber.tag("App").w(it) }

        assertEquals(messages, lines.map { it.message })
    }

    @Test
    fun `release replaces a line carrying a secret assignment by a fixed notice`() {
        Timber.tag("Vault").e("Unlock failed password=hunter2-extra")

        assertEquals(LogScrubber.REDACTED_LINE, lines.single().message)
    }

    @Test
    fun `release keeps working when a throwable carries a secret in its message`() {
        Timber.tag("Vault").e(IllegalStateException("passphrase=hunter2"), "Import failed")

        val message = lines.single().message
        assertEquals("Import failed [IllegalStateException]", message)
        assertFalse(message.contains("hunter2"))
    }

    @Test
    fun `release keeps only the first line of a multi-line message`() {
        Timber.tag("Net").w("Sync failed\nsecond line from the peer\r\nthird line")

        assertEquals("Sync failed", lines.single().message)
    }

    @Test
    fun `release keeps the first line and the class of a throwable, no stack and no exception message`() {
        // A message that carries a pasted trace, plus the trace Timber appends for the throwable.
        Timber.tag("Ssh").e(leakyThrowable(), "Connect failed\n\tat x.Y.z(Y.kt:1)\nCaused by: other")

        val line = lines.single().message
        assertEquals("Connect failed [IOException<-SocketTimeoutException]", line)
        assertFalse(line.contains("\n"), line)
        assertFalse(line.contains("\tat "), line)
        assertNoPersonalData(line)
    }

    @Test
    fun `release reduces an exception with a multi-line message to its class`() {
        val t = IllegalStateException("first db-prod.corp.example\nsecond jean.dupont")

        Timber.tag("Ssh").e(t, "Import failed")

        val line = lines.single().message
        assertEquals("Import failed [IllegalStateException]", line)
        assertNoPersonalData(line)
    }

    @Test
    fun `release still masks a host or a secret on the first line`() {
        Timber.tag("Net").w("Reach build-server.corp.example\nsecond line")
        Timber.tag("Vault").e("Unlock failed password=hunter2-extra\nsecond line")

        assertFalse(lines[0].message.contains("build-server"), lines[0].message)
        assertFalse(lines[0].message.contains("second"), lines[0].message)
        assertEquals(LogScrubber.REDACTED_LINE, lines[1].message)
    }

    // ── messageWithoutTrace: robust to the shape Timber hands a tree ─────────────

    @Test
    fun `messageWithoutTrace cuts the appended trace of the throwable and its causes`() {
        val t = leakyThrowable()
        val appended = "Connect failed\n" + t.stackTraceToString()

        assertEquals("Connect failed", LogRendering.messageWithoutTrace(appended, t))
        assertEquals("", LogRendering.messageWithoutTrace(t.stackTraceToString(), t))
    }

    @Test
    fun `messageWithoutTrace cuts at the throwable class name when the trace differs`() {
        val t = leakyThrowable()
        val reshaped = "Connect failed\n${t.javaClass.name}: some other rendering\n\tat x.Y.z(Y.kt:1)"

        assertEquals("Connect failed", LogRendering.messageWithoutTrace(reshaped, t))
        assertEquals("", LogRendering.messageWithoutTrace("${t.javaClass.name}: odd", t))
    }

    /** A throwable whose `toString()` prints data and does not start with its class name. */
    private fun customToStringThrowable(): Throwable = object : Exception("hidden") {
        override fun toString(): String = "Custom[host=db-prod.corp.example, user=jean]"
    }

    @Test
    fun `a throwable with a custom toString leaves nothing in a release or debug line`() {
        val t = customToStringThrowable()

        Timber.tag("Ssh").e(t, "Connect failed")

        val line = lines.single().message
        assertTrue(line.startsWith("Connect failed ["), line)
        assertFalse(line.contains("db-prod"), line)
        assertFalse(line.contains("jean"), line)
    }

    @Test
    fun `messageWithoutTrace cuts a custom toString line even when the trace was reshaped`() {
        val t = customToStringThrowable()
        // The embedded trace is not what the throwable prints now: the exact cut cannot match.
        val reshaped = "Connect failed\n${t}\n\tat x.Y.z(Y.kt:1)\nCaused by: other"

        assertEquals("Connect failed", LogRendering.messageWithoutTrace(reshaped, t))
        assertEquals("", LogRendering.messageWithoutTrace("${t}\n\tat x.Y.z(Y.kt:1)", t))
    }

    @Test
    fun `messageWithoutTrace falls back on the first stack frame when no class name matches`() {
        val t = IOException("other message")
        val unexpected = "Connect failed\nMyWrapper[host=db-prod.corp.example]\n\tat x.Y.z(Y.kt:1)\nCaused by: foo"

        assertEquals("Connect failed", LogRendering.messageWithoutTrace(unexpected, t))
        // Nothing before the throwable text: nothing to keep.
        assertEquals("", LogRendering.messageWithoutTrace("MyWrapper[host=db-prod]\n\tat x.Y.z(Y.kt:1)", t))
        assertEquals("", LogRendering.messageWithoutTrace("MyWrapper[host=db-prod]\nCaused by: foo", t))
    }

    @Test
    fun `messageWithoutTrace keeps a class name that is only mentioned inside the message`() {
        val t = IOException("secret")
        val text = "See the java.io.IOException documentation"

        assertEquals(text, LogRendering.messageWithoutTrace(text, t))
        assertEquals(
            text,
            LogRendering.messageWithoutTrace("$text\n${t.stackTraceToString()}", t),
        )
        // Reshaped trace: only the first line survives.
        assertEquals(
            text,
            LogRendering.messageWithoutTrace("$text\nMyWrapper[secret]\n\tat x.Y.z(Y.kt:1)", t),
        )
    }

    @Test
    fun `messageWithoutTrace drops a multi-line throwable message with the trace`() {
        val t = IllegalStateException("first line db-prod\nsecond line jean\nthird line")
        val appended = "Connect failed\n" + t.stackTraceToString()
        assertEquals("Connect failed", LogRendering.messageWithoutTrace(appended, t))

        // The trace is reshaped and the message is spread over several lines: fail closed.
        val reshaped = "Connect failed\nWrapped: first line db-prod\nsecond line jean\nthird line\n\tat x.Y.z(Y.kt:1)"
        val cut = LogRendering.messageWithoutTrace(reshaped, t)
        assertEquals("Connect failed", cut)
        assertFalse(cut.contains("db-prod") || cut.contains("jean"), cut)
    }

    @Test
    fun `messageWithoutTrace returns the message untouched without a throwable`() {
        assertEquals("plain text", LogRendering.messageWithoutTrace("plain text", null))
    }

    // ── DebugLogTree ─────────────────────────────────────────────────────────────

    private fun withDebugTree(block: (printed: MutableList<Line>) -> Unit) {
        val printed = mutableListOf<Line>()
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } answers {
            printed += Line(firstArg(), secondArg(), thirdArg())
            0
        }
        val tree = DebugLogTree()
        Timber.plant(tree)
        try {
            block(printed)
        } finally {
            Timber.uproot(tree)
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `debug keeps every level and scrubs the message`() = withDebugTree { printed ->
        Timber.tag("Net").d("Reach 10.0.0.7:22 and build-server.corp.example")
        Timber.tag("Net").v("verbose is kept in debug")

        assertEquals(2, printed.size)
        assertFalse(printed[0].message.contains("10.0.0.7"), printed[0].message)
        assertFalse(printed[0].message.contains("build-server"), printed[0].message)
        assertEquals("verbose is kept in debug", printed[1].message)
        assertEquals("Net", printed[0].tag)
    }

    @Test
    fun `debug prints a stack trace without any exception message`() = withDebugTree { printed ->
        Timber.tag("Ssh").e(leakyThrowable(), "Connect failed")

        val text = printed.joinToString("\n") { it.message }
        assertTrue(text.startsWith("Connect failed\njava.io.IOException\n"), text)
        assertTrue(text.contains("\tat fr.techtical.nextsh.core.logging.LogTreesTest"), text)
        assertTrue(text.contains("Caused by: java.net.SocketTimeoutException"), text)
        assertNoPersonalData(text)
    }

    @Test
    fun `debug prints a throwable given without any message without its message`() = withDebugTree { printed ->
        Timber.tag("Ssh").e(leakyThrowable())

        val text = printed.joinToString("\n") { it.message }
        assertTrue(text.startsWith("Error\njava.io.IOException\n"), text)
        assertNoPersonalData(text)
    }

    @Test
    fun `debug replaces a line carrying a secret assignment by a fixed notice`() = withDebugTree { printed ->
        Timber.tag("Vault").d("token=abcdef123456")

        assertEquals(LogScrubber.REDACTED_LINE, printed.single().message)
    }
}
