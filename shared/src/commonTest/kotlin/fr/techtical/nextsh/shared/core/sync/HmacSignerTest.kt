// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacSignerTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `sign produces deterministic hex signature`() {
        val body = "test-body".toByteArray(Charsets.UTF_8)
        val timestamp = 1_700_000_000_000L

        val sig1 = HmacSigner.sign("POST", "/api/sync/push", timestamp, body, secret)
        val sig2 = HmacSigner.sign("POST", "/api/sync/push", timestamp, body, secret)

        assertEquals(sig1, sig2)
        assertEquals(64, sig1.length)
        assertTrue(sig1.all { it in '0'..'9' || it in 'a'..'f' }, "Signature must be lowercase hex")
    }

    @Test
    fun `verify returns true for correct signature`() {
        val body = "payload".toByteArray(Charsets.UTF_8)
        val now = 1_700_000_000_000L
        val sig = HmacSigner.sign("GET", "/api/sync/pull", now, body, secret)

        val result = HmacSigner.verify(
            method = "GET",
            path = "/api/sync/pull",
            timestamp = now,
            body = body,
            expectedSignature = sig,
            sharedSecret = secret,
            now = now,
        )

        assertTrue(result)
    }

    @Test
    fun `verify returns false for wrong body`() {
        val body = "original".toByteArray(Charsets.UTF_8)
        val now = 1_700_000_000_000L
        val sig = HmacSigner.sign("POST", "/api/sync/push", now, body, secret)

        val alteredBody = "tampered".toByteArray(Charsets.UTF_8)
        val result = HmacSigner.verify(
            method = "POST",
            path = "/api/sync/push",
            timestamp = now,
            body = alteredBody,
            expectedSignature = sig,
            sharedSecret = secret,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun `verify returns false when timestamp too old`() {
        val body = "data".toByteArray(Charsets.UTF_8)
        val now = 1_700_000_600_000L
        val staleTimestamp = now - 10L * 60_000L  // 10 min ago, outside ±5 min window

        val sig = HmacSigner.sign("POST", "/api/sync/push", staleTimestamp, body, secret)

        val result = HmacSigner.verify(
            method = "POST",
            path = "/api/sync/push",
            timestamp = staleTimestamp,
            body = body,
            expectedSignature = sig,
            sharedSecret = secret,
            now = now,
        )

        assertFalse(result)
    }
}
