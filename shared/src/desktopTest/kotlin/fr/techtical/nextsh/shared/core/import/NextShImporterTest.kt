// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextShImporterTest {

    // Build hostile strings via unicode escapes so this source file contains no
    // raw control bytes (NUL = U+0000), mirroring ImportSanitizerTest.
    private val nul = Char(0).toString()

    @Test
    fun `parses minimal host with hostname only`() {
        val jsonText = """
            {
              "format": "nextsh-hosts",
              "version": 1,
              "hosts": [
                { "hostname": "10.0.0.12" }
              ]
            }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        val h = hosts.first()
        assertEquals("10.0.0.12", h.hostname)
        assertEquals("10.0.0.12", h.label, "label falls back to hostname")
        assertEquals(22, h.port, "missing port defaults to 22")
        assertEquals("", h.username)
        assertNull(h.group)
        assertEquals(AuthType.PASSWORD, h.authType)
        assertNull(h.password)
        assertNull(h.privateKeyPem)
        assertNull(h.keyPassphrase)
        assertNull(h.sourceId)
    }

    @Test
    fun `parses fully populated host`() {
        val jsonText = """
            {
              "format": "nextsh-hosts",
              "version": 1,
              "hosts": [
                {
                  "hostname": "10.0.0.12",
                  "label": "Prod Web 01",
                  "port": 2222,
                  "username": "deploy",
                  "group": "Production",
                  "auth": "ssh_key",
                  "favorite": true,
                  "id": "row-42",
                  "password": "unused-because-ssh-key",
                  "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----",
                  "keyPassphrase": "keypass1"
                }
              ]
            }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        val h = hosts.first()
        assertEquals("10.0.0.12", h.hostname)
        assertEquals("Prod Web 01", h.label)
        assertEquals(2222, h.port)
        assertEquals("deploy", h.username)
        assertEquals("Production", h.group)
        assertEquals(AuthType.SSH_KEY, h.authType)
        assertEquals("row-42", h.sourceId)
        assertNotNull(h.privateKeyPem)
        assertTrue(h.privateKeyPem!!.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertNotNull(h.keyPassphrase)
        assertEquals("keypass1", String(h.keyPassphrase!!))
        // `password` is extracted unconditionally, independent of authType: the
        // importer never decides which secret fields matter, it just carries them.
        assertNotNull(h.password)
        assertEquals("unused-because-ssh-key", String(h.password!!))
    }

    @Test
    fun `accepts bare array of hosts at document root`() {
        val bareArray = """
            [
              { "hostname": "bare.example", "username": "op" }
            ]
        """.trimIndent()
        val hosts = NextShImporter.parse(bareArray)
        assertEquals(1, hosts.size)
        assertEquals("bare.example", hosts.first().hostname)
        assertEquals("op", hosts.first().username)
    }

    @Test
    fun `tolerates termius-style field aliases`() {
        val jsonText = """
            {
              "hosts": [
                { "host": "aliased.example", "title": "Aliased Host", "user": "opuser", "folder": "Ops", "auth_type": "SSH_KEY", "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\nBBBB\n-----END OPENSSH PRIVATE KEY-----" },
                { "address": "addr-alias.example", "name": "Name Alias", "authType": "password" }
              ]
            }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(2, hosts.size)

        val first = hosts.first { it.hostname == "aliased.example" }
        assertEquals("Aliased Host", first.label)
        assertEquals("opuser", first.username)
        assertEquals("Ops", first.group)
        assertEquals(AuthType.SSH_KEY, first.authType, "auth_type value is case-insensitive")

        val second = hosts.first { it.hostname == "addr-alias.example" }
        assertEquals("Name Alias", second.label)
        assertEquals(AuthType.PASSWORD, second.authType)
    }

    @Test
    fun `tolerates unknown future version in wrapper`() {
        val jsonText = """
            { "format": "nextsh-hosts", "version": 99, "hosts": [ { "hostname": "future.example" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        assertEquals("future.example", hosts.first().hostname)
    }

    @Test
    fun `skips entries with invalid port but keeps valid ones`() {
        val jsonText = """
            {
              "hosts": [
                { "hostname": "bad-port-range.example", "port": 99999 },
                { "hostname": "bad-port-str.example", "port": "not-a-port" },
                { "hostname": "ok.example", "port": 2022 }
              ]
            }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size, "only the entry with a valid port survives")
        assertEquals("ok.example", hosts.first().hostname)
        assertEquals(2022, hosts.first().port)
    }

    @Test
    fun `rejects entries with empty, blank, or control-char hostname`() {
        val jsonText = """
            {
              "hosts": [
                { "hostname": "" },
                { "hostname": "   " },
                { "hostname": "bad${nul}host" },
                { "label": "no-hostname-field-at-all" }
              ]
            }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertTrue(hosts.isEmpty(), "every entry lacks a usable hostname")
    }

    @Test
    fun `falls back to password auth for unrecognised auth value`() {
        val jsonText = """
            { "hosts": [ { "hostname": "unknown-auth.example", "auth": "totp" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        assertEquals(AuthType.PASSWORD, hosts.first().authType, "unrecognised auth value falls back to password")
    }

    @Test
    fun `missing auth field defaults to ssh_key when a private key is present`() {
        val jsonText = """
            { "hosts": [ { "hostname": "no-auth-field.example", "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\nCCCC\n-----END OPENSSH PRIVATE KEY-----" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        assertEquals(AuthType.SSH_KEY, hosts.first().authType, "absent auth + a present privateKey should resolve to SSH_KEY, not the password fallback")
    }

    @Test
    fun `unrecognised auth value still resolves to ssh_key when a private key is present`() {
        val jsonText = """
            { "hosts": [ { "hostname": "totp-auth.example", "auth": "totp", "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\nDDDD\n-----END OPENSSH PRIVATE KEY-----" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        assertEquals(AuthType.SSH_KEY, hosts.first().authType, "an unrecognised auth value with a present privateKey should still resolve to SSH_KEY")
    }

    @Test
    fun `ssh_key auth without a private key still imports the host without a secret`() {
        val jsonText = """
            { "hosts": [ { "hostname": "keyless.example", "auth": "ssh_key" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        val h = hosts.first()
        assertEquals(AuthType.SSH_KEY, h.authType)
        assertNull(h.privateKeyPem)
        assertFalse(h.hasSecret)
    }

    @Test
    fun `ignores privateKeyFile field and never reads from disk`() {
        val jsonText = """
            { "hosts": [ { "hostname": "filekey.example", "auth": "ssh_key", "privateKeyFile": "/etc/passwd" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(1, hosts.size)
        val h = hosts.first()
        assertEquals(AuthType.SSH_KEY, h.authType, "explicit auth is honoured")
        assertNull(h.privateKeyPem, "privateKeyFile is not a recognised field: no PEM is produced from it")
        assertFalse(h.hasSecret)
    }

    @Test
    fun `truncates to MAX_IMPORT_ENTRIES on a pathologically large file`() {
        val overflow = MAX_IMPORT_ENTRIES + 5
        val hostsJson = (0 until overflow).joinToString(",") { i -> """{ "hostname": "h$i.example" }""" }
        val jsonText = """{ "hosts": [ $hostsJson ] }"""
        val hosts = NextShImporter.parse(jsonText)
        assertEquals(MAX_IMPORT_ENTRIES, hosts.size)
    }

    @Test
    fun `reads sourceId from id field`() {
        val jsonText = """
            { "hosts": [ { "hostname": "src.example", "id": "abc-123" } ] }
        """.trimIndent()
        val hosts = NextShImporter.parse(jsonText)
        assertEquals("abc-123", hosts.first().sourceId)
    }

    @Test
    fun `returns empty list for unreadable or unrelated content`() {
        assertTrue(NextShImporter.parse("this is not json").isEmpty())
        assertTrue(NextShImporter.parse("").isEmpty())
        assertTrue(NextShImporter.parse("""{"foo":"bar"}""").isEmpty(), "object with no hosts array yields nothing")
    }
}
