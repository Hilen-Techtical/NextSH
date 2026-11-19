// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogIdTest {

    private val hexFingerprint = Regex("[0-9a-f]{${LogId.LENGTH}}")

    @Test
    fun `null or blank id gives a dash`() {
        assertEquals("-", LogId.of(null))
        assertEquals("-", LogId.of(""))
        assertEquals("-", LogId.of("   "))
    }

    @Test
    fun `fingerprint is 8 lowercase hex chars`() {
        val id = LogId.of("550e8400-e29b-41d4-a716-446655440000")
        assertTrue(hexFingerprint.matches(id), "unexpected fingerprint: $id")
    }

    @Test
    fun `same id gives the same fingerprint within the process`() {
        assertEquals(LogId.of("pwd:host-42"), LogId.of("pwd:host-42"))
    }

    @Test
    fun `different ids give different fingerprints`() {
        // 50 ids over 32 bits: a chance collision is about 3e-7, low enough for a stable test.
        val ids = (1..50).map { "host-$it" }
        val fingerprints = ids.map { LogId.of(it) }.toSet()
        assertEquals(ids.size, fingerprints.size)
    }

    @Test
    fun `fingerprint is not a plain SHA-256 prefix`() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val plainPrefix = digest.joinToString("") { "%02x".format(it) }.substring(0, LogId.LENGTH)
        assertNotEquals(plainPrefix, LogId.of(id))
    }

    @Test
    fun `fingerprint never contains the raw id`() {
        val ids = listOf("550e8400-e29b-41d4-a716-446655440000", "pwd:host-42", "device-A", "web")
        for (id in ids) {
            val fp = LogId.of(id)
            assertFalse(fp.contains(id), "raw id leaked in $fp")
            assertFalse(id.contains(fp), "fingerprint is a substring of the id: $fp")
        }
    }

    @Test
    fun `fingerprint survives the scrubber`() {
        val line = "skip id=${LogId.of("pwd:host-42")}"
        assertEquals(line, LogScrubber.scrub(line))
    }
}
