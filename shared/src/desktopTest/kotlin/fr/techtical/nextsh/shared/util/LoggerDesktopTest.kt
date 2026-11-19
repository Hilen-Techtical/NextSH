// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop logger contract: debug lines only in debug mode, every message scrubbed in every
 * mode, whole line dropped on an unmistakable secret, throwables reduced to class names
 * (release) or class names plus frames (debug), never their message.
 */
class LoggerDesktopTest {

    private val stdoutBuf = ByteArrayOutputStream()
    private val stderrBuf = ByteArrayOutputStream()
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private var originalDebug: Boolean = false

    @BeforeTest
    fun setUp() {
        originalOut = System.out
        originalErr = System.err
        originalDebug = Logger.isDebugEnabled()
        Logger.sink = null
        System.setOut(PrintStream(stdoutBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(stderrBuf, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        Logger.setDebugEnabled(originalDebug)
        Logger.sink = null
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun stdout() = stdoutBuf.toString(Charsets.UTF_8)
    private fun stderr() = stderrBuf.toString(Charsets.UTF_8)

    // ── Routing and modes ────────────────────────────────────────────────────

    @Test
    fun `release mode silences debug logs`() {
        Logger.setDebugEnabled(false)
        Logger.d("Test", "regular debug noise")
        assertEquals("", stdout())
    }

    @Test
    fun `debug mode prints debug logs on stdout`() {
        Logger.setDebugEnabled(true)
        Logger.d("Test", "regular debug noise")
        assertEquals("D/Test: regular debug noise", stdout().trim())
        assertEquals("", stderr())
    }

    @Test
    fun `warnings and errors go to stderr`() {
        Logger.setDebugEnabled(false)
        Logger.w("Net", "timeout reached, retrying")
        Logger.e("Net", "gave up after 3 attempts")
        assertEquals(listOf("W/Net: timeout reached, retrying", "E/Net: gave up after 3 attempts"), stderr().trim().lines())
        assertEquals("", stdout())
    }

    // ── Scrubbing in every mode ──────────────────────────────────────────────

    @Test
    fun `debug lines are scrubbed too`() {
        Logger.setDebugEnabled(true)
        Logger.d("Ssh", "connect root@10.0.0.5:2222 key /home/thibaud/.ssh/id_ed25519")
        val out = stdout()
        assertFalse(out.contains("10.0.0.5"), out)
        assertFalse(out.contains("thibaud"), out)
        assertFalse(out.contains("root"), out)
        assertTrue(out.contains("<host>") && out.contains("<path>"), out)
    }

    @Test
    fun `release and debug both drop a line carrying a password`() {
        for (debug in listOf(false, true)) {
            Logger.setDebugEnabled(debug)
            val lines = Logger.capture { Logger.w("Auth", "user password=hunter2 rejected") }
            assertEquals(listOf("W/Auth: ${LogScrubber.REDACTED_LINE}"), lines, "debug=$debug")
        }
    }

    @Test
    fun `a PEM block drops the whole line`() {
        val lines = Logger.capture {
            Logger.e("Import", "bad key -----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\n-----END OPENSSH PRIVATE KEY-----")
        }
        assertEquals(listOf("E/Import: ${LogScrubber.REDACTED_LINE}"), lines)
    }

    @Test
    fun `neutral lines and words that look sensitive are kept intact`() {
        Logger.setDebugEnabled(false)
        val lines = Logger.capture {
            Logger.w("Net", "timeout reached, retrying")
            Logger.e("Vault", "passphrase mismatch on import")
            Logger.w("SomeOtherTag", "fido2 assertion failed for user credential")
        }
        assertEquals(
            listOf(
                "W/Net: timeout reached, retrying",
                "E/Vault: passphrase mismatch on import",
                "W/SomeOtherTag: fido2 assertion failed for user credential",
            ),
            lines,
        )
    }

    @Test
    fun `FIDO diagnostics survive scrubbing without any tag bypass`() {
        Logger.setDebugEnabled(false)
        val lines = Logger.capture {
            Logger.w("YubiKit", "detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC:0)")
            Logger.w("WinWebAuthn", "makeCredential: HRESULT error 0x80090027 (NotSupportedError): key_ type rejected")
        }
        assertEquals(
            listOf(
                "W/YubiKit: detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC:0)",
                "W/WinWebAuthn: makeCredential: HRESULT error 0x80090027 (NotSupportedError): key_ type rejected",
            ),
            lines,
        )
    }

    @Test
    fun `diagnostic tags are scrubbed like any other`() {
        Logger.setDebugEnabled(false)
        val lines = Logger.capture {
            Logger.w("YubiKit", "reader at /dev/hidraw3 behind 192.168.1.5")
            Logger.e("WinWebAuthn", "rpId=srv01.example.com rejected")
        }
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains("hidraw3"), joined)
        assertFalse(joined.contains("192.168.1.5"), joined)
        assertFalse(joined.contains("srv01"), joined)
    }

    @Test
    fun `control characters in tags and messages cannot forge lines`() {
        Logger.setDebugEnabled(true)
        val lines = Logger.capture {
            Logger.w("Tag\nE/Fake", "a\nW/Forged: b")
            Logger.d("T\u001B[2J", "x\ry")
        }
        assertEquals(listOf("W/Tag\\nE/Fake: a\\nW/Forged: b", "D/T\\u001B[2J: x\\ry"), lines)
    }
    // ── Throwables ───────────────────────────────────────────────────────────

    private class TransportException(message: String, cause: Throwable) : Exception(message, cause)

    private fun leakyThrowable() = TransportException(
        "connect srv01.example.com failed for web-prod",
        IOException("boom-marker /home/thibaud/.ssh/known_hosts"),
    )

    private val leakedValues = listOf("srv01", "web-prod", "boom-marker", "/home/thibaud", "known_hosts")

    @Test
    fun `release error prints the class chain and no exception message`() {
        Logger.setDebugEnabled(false)
        Logger.e("Ssh", "connection failed", leakyThrowable())
        val out = stderr()
        assertEquals("E/Ssh: connection failed [TransportException<-IOException]", out.trim())
        for (value in leakedValues) assertFalse(out.contains(value), "'$value' leaked: $out")
    }

    @Test
    fun `debug error prints classes and frames but no exception message`() {
        Logger.setDebugEnabled(true)
        Logger.e("Ssh", "connection failed", leakyThrowable())
        val out = stderr()
        // The line break between the message and the trace is kept: only the message is neutralised.
        assertTrue(out.startsWith("E/Ssh: connection failed [TransportException<-IOException]\n"), out)
        assertTrue(out.contains("\tat "), "frames expected in debug: $out")
        assertTrue(out.contains("Caused by: java.io.IOException"), out)
        for (value in leakedValues) assertFalse(out.contains(value), "'$value' leaked: $out")
    }

    @Test
    fun `error throwable is reduced even when the message is redacted`() {
        Logger.setDebugEnabled(false)
        val lines = Logger.capture { Logger.e("Vault", "token=abc", RuntimeException("boom-marker")) }
        assertEquals(listOf("E/Vault: ${LogScrubber.REDACTED_LINE} [RuntimeException]"), lines)
    }

    // ── Test hook ────────────────────────────────────────────────────────────

    @Test
    fun `capture collects records and restores the previous sink`() {
        Logger.setDebugEnabled(true)
        val outer = mutableListOf<String>()
        Logger.sink = Logger.Sink { level, text -> outer += "$level $text" }
        val captured = Logger.capture {
            Logger.d("A", "one")
            Logger.w("B", "two")
        }
        assertEquals(listOf("D/A: one", "W/B: two"), captured)
        Logger.w("C", "three")
        assertEquals(listOf("WARN W/C: three"), outer)
        assertEquals("", stdout() + stderr(), "a sink replaces the console output")
    }

    @Test
    fun `capture restores a null sink after an exception`() {
        runCatching { Logger.capture { error("fail inside block") } }
        assertNull(Logger.sink)
    }
}
