// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportSanitizerTest {

    // Build hostile strings via unicode escapes so this source file contains no
    // raw control bytes (NUL = U+0000, bell = U+0007).
    private val nul = Char(0).toString()
    private val bell = Char(7).toString()

    @Test
    fun `sanitizeIdentifier trims clean value`() {
        assertEquals("host.example", ImportSanitizer.sanitizeIdentifier("  host.example  "))
    }

    @Test
    fun `sanitizeIdentifier rejects embedded newline`() {
        assertNull(ImportSanitizer.sanitizeIdentifier("user\nadmin"))
    }

    @Test
    fun `sanitizeIdentifier rejects nul and control chars`() {
        assertNull(ImportSanitizer.sanitizeIdentifier("foo${nul}bar"))
        assertNull(ImportSanitizer.sanitizeIdentifier("foo${bell}bar"))
    }

    @Test
    fun `sanitizeIdentifier rejects blank`() {
        assertNull(ImportSanitizer.sanitizeIdentifier(""))
        assertNull(ImportSanitizer.sanitizeIdentifier("   "))
        assertNull(ImportSanitizer.sanitizeIdentifier(null))
    }

    @Test
    fun `sanitizePortStrict rejects out of range port 99999`() {
        assertNull(ImportSanitizer.sanitizePortStrict("99999"))
        assertNull(ImportSanitizer.sanitizePortStrict("0"))
        assertNull(ImportSanitizer.sanitizePortStrict("-1"))
    }

    @Test
    fun `sanitizePortStrict defaults blank to 22 and accepts valid`() {
        assertEquals(22, ImportSanitizer.sanitizePortStrict(null))
        assertEquals(22, ImportSanitizer.sanitizePortStrict(""))
        assertEquals(2222, ImportSanitizer.sanitizePortStrict("2222"))
    }

    @Test
    fun `sanitizePortLenient clamps out of range`() {
        assertEquals(65535, ImportSanitizer.sanitizePortLenient("99999"))
        assertEquals(1, ImportSanitizer.sanitizePortLenient("0"))
        assertEquals(22, ImportSanitizer.sanitizePortLenient("not-a-number"))
        assertEquals(2200, ImportSanitizer.sanitizePortLenient("2200"))
    }

    @Test
    fun `sanitizeLabel degrades unsafe to fallback`() {
        assertEquals("fallback.host", ImportSanitizer.sanitizeLabel("  ", "fallback.host"))
        assertEquals("clean", ImportSanitizer.sanitizeLabel("clean", "fb"))
        // Control chars are stripped, not rejected.
        assertEquals("ab", ImportSanitizer.sanitizeLabel("a${bell}b", "fb"))
    }

    @Test
    fun `sanitizeGroup drops root-only and blank`() {
        assertNull(ImportSanitizer.sanitizeGroup("/"))
        assertNull(ImportSanitizer.sanitizeGroup(""))
        assertEquals("Prod", ImportSanitizer.sanitizeGroup("Prod"))
    }

    @Test
    fun `termius skips malicious entry with control chars and clamps port`() {
        // A host whose username contains an embedded newline and port is out of
        // range. The newline username must NOT survive; the port must be clamped.
        val json = """
            {
              "hosts": [
                { "label": "evil", "address": "good.host", "port": "99999", "username": "user\nadmin" }
              ]
            }
        """.trimIndent()
        val hosts = TermiusImporter.parse(json)
        assertEquals(1, hosts.size)
        val h = hosts.first()
        assertEquals("good.host", h.hostname)
        assertEquals(65535, h.port, "out-of-range port clamped to 65535")
        assertEquals("", h.username, "newline username rejected → empty, never persisted with a line break")
        assertTrue(h.username.none { it == '\n' })
        assertEquals(AuthType.PASSWORD, h.authType)
    }

    @Test
    fun `termius skips host whose address contains a nul byte`() {
        // Embed a literal NUL into the JSON string value via the escaped sequence.
        val json = "{ \"hosts\": [ { \"label\": \"x\", \"address\": \"bad${nul}host\" } ] }"
        val hosts = TermiusImporter.parse(json)
        assertTrue(hosts.isEmpty(), "address with embedded NUL is unusable → host skipped")
    }
}
