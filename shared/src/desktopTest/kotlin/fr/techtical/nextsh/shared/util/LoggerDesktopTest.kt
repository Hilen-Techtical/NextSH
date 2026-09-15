// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the Android [ReleaseTree] contract: in release mode `d()` is silenced
 * and messages matching sensitive patterns are redacted on `w()` / `e()`.
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
        System.setOut(PrintStream(stdoutBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(stderrBuf, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        Logger.setDebugEnabled(originalDebug)
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    @Test
    fun `release mode silences debug logs`() {
        Logger.setDebugEnabled(false)
        Logger.d("Test", "regular debug noise")
        assertEquals("", stdoutBuf.toString(Charsets.UTF_8))
    }

    @Test
    fun `debug mode emits debug logs verbatim`() {
        Logger.setDebugEnabled(true)
        Logger.d("Test", "regular debug noise")
        assertTrue(stdoutBuf.toString(Charsets.UTF_8).contains("regular debug noise"))
    }

    @Test
    fun `release mode redacts sensitive warn messages`() {
        Logger.setDebugEnabled(false)
        Logger.w("Auth", "user password=hunter2 rejected")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("REDACTED"))
    }

    @Test
    fun `release mode redacts sensitive error messages`() {
        Logger.setDebugEnabled(false)
        Logger.e("Vault", "passphrase mismatch on import")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertFalse(out.contains("passphrase mismatch"))
        assertTrue(out.contains("REDACTED"))
    }

    @Test
    fun `release mode keeps neutral warn messages intact`() {
        Logger.setDebugEnabled(false)
        Logger.w("Net", "timeout reached, retrying")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertTrue(out.contains("timeout reached"))
        assertFalse(out.contains("REDACTED"))
    }

    @Test
    fun `debug mode bypasses redaction`() {
        Logger.setDebugEnabled(true)
        Logger.w("Auth", "user password=hunter2 rejected")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertTrue(out.contains("hunter2"))
        assertFalse(out.contains("REDACTED"))
    }

    @Test
    fun `error throwable is still printed when message is redacted`() {
        Logger.setDebugEnabled(false)
        val ex = RuntimeException("boom-marker")
        Logger.e("Vault", "decoded credential blob", ex)
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertTrue(out.contains("REDACTED"))
        assertTrue(out.contains("boom-marker"))
    }

    @Test
    fun `YubiKit tag bypasses redaction in release mode for warn`() {
        Logger.setDebugEnabled(false)
        // Message intentionally contains words that match sensitivePatterns
        // (fido2, authenticator) but these are diagnostic transport strings, not credentials.
        Logger.w("YubiKit", "detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC:0)")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertFalse(out.contains("REDACTED"), "YubiKit tag should bypass redaction")
        assertTrue(out.contains("NO DEVICES"))
    }

    @Test
    fun `YubiKit tag bypasses redaction in release mode for error`() {
        Logger.setDebugEnabled(false)
        Logger.e("YubiKit", "listHidDevicesRaw: hid4java native lib load FAILED, authenticator not found")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertFalse(out.contains("REDACTED"), "YubiKit tag should bypass redaction")
        assertTrue(out.contains("hid4java"))
    }

    @Test
    fun `non-YubiKit tag with sensitive content is still redacted in release mode`() {
        Logger.setDebugEnabled(false)
        Logger.w("SomeOtherTag", "fido2 assertion failed for user credential")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertTrue(out.contains("REDACTED"), "Other tags with sensitive content must still be redacted")
    }

    @Test
    fun `WinWebAuthn tag bypasses redaction in release mode`() {
        Logger.setDebugEnabled(false)
        // Message contains "key_" which matches sensitivePatterns but is a diagnostic transport string.
        Logger.w("WinWebAuthn", "makeCredential: HRESULT error 0x80090027 (NotSupportedError): key_ type rejected")
        val out = stderrBuf.toString(Charsets.UTF_8)
        assertFalse(out.contains("REDACTED"), "WinWebAuthn tag should bypass redaction")
        assertTrue(out.contains("NotSupportedError"))
    }
}
