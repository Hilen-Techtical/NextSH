// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermiusImporterTest {

    // Realistic Termius-style export: top-level hosts/identities/groups/ssh_keys
    // collections, hosts referencing identity & group by id, plus an inline-username
    // host and a malformed (no address) host that must be skipped.
    private val sampleJson = """
        {
          "groups": [
            { "id": "g1", "label": "Production" },
            { "id": "g2", "label": "Staging" }
          ],
          "ssh_keys": [
            { "id": "k1", "label": "deploy-key", "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----", "passphrase": "keypass1" }
          ],
          "identities": [
            { "id": "i1", "label": "root-pw", "username": "root", "password": "s3cr3t" },
            { "id": "i2", "label": "deploy", "username": "deploy", "ssh_key": "k1" }
          ],
          "hosts": [
            {
              "id": "h1",
              "label": "web-01",
              "address": "10.0.0.1",
              "port": 2200,
              "group": "g1",
              "identity": "i1"
            },
            {
              "id": "h2",
              "label": "db-01",
              "address": "db.internal",
              "group": "g2",
              "identity": "i2"
            },
            {
              "id": "h3",
              "label": "inline-host",
              "address": "192.168.1.5",
              "username": "admin",
              "port": 22
            },
            {
              "id": "h4",
              "label": "broken",
              "port": 22
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses host with referenced password identity and group`() {
        val hosts = TermiusImporter.parse(sampleJson)
        val web = hosts.first { it.hostname == "10.0.0.1" }
        assertEquals("web-01", web.label)
        assertEquals(2200, web.port)
        assertEquals("root", web.username)
        assertEquals("Production", web.group)
        assertEquals(AuthType.PASSWORD, web.authType)
        assertNotNull(web.password)
        assertEquals("s3cr3t", String(web.password!!))
    }

    @Test
    fun `parses host with referenced ssh key identity`() {
        val hosts = TermiusImporter.parse(sampleJson)
        val db = hosts.first { it.hostname == "db.internal" }
        assertEquals("deploy", db.username)
        assertEquals("Staging", db.group)
        assertEquals(22, db.port, "missing port defaults to 22")
        assertEquals(AuthType.SSH_KEY, db.authType)
        assertNotNull(db.privateKeyPem)
        assertTrue(db.privateKeyPem!!.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertNotNull(db.keyPassphrase)
        assertEquals("keypass1", String(db.keyPassphrase!!))
    }

    @Test
    fun `parses host with inline username and no identity`() {
        val hosts = TermiusImporter.parse(sampleJson)
        val inline = hosts.first { it.hostname == "192.168.1.5" }
        assertEquals("admin", inline.username)
        assertEquals(AuthType.PASSWORD, inline.authType)
        assertNull(inline.group)
        assertNull(inline.password)
    }

    @Test
    fun `skips malformed host with no address`() {
        val hosts = TermiusImporter.parse(sampleJson)
        assertEquals(3, hosts.size, "the address-less host must be skipped, the other 3 kept")
        assertTrue(hosts.none { it.label == "broken" })
    }

    @Test
    fun `tolerates internal sync model field names host title user_name key_id`() {
        // Field names from the reverse-engineered internal/sync model.
        val internalJson = """
            {
              "ssh_keys": [
                { "id": "kk", "label": "mykey", "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\nXX\n-----END OPENSSH PRIVATE KEY-----" }
              ],
              "hosts": [
                { "id": "c1", "title": "legacy", "host": "legacy.example.com", "port": 22, "user_name": "ops", "key_id": "kk" }
              ]
            }
        """.trimIndent()
        val hosts = TermiusImporter.parse(internalJson)
        assertEquals(1, hosts.size)
        val h = hosts.first()
        assertEquals("legacy.example.com", h.hostname)
        assertEquals("legacy", h.label)
        assertEquals("ops", h.username)
        assertEquals(AuthType.SSH_KEY, h.authType)
        assertNotNull(h.privateKeyPem)
    }

    @Test
    fun `returns empty list for non-json content`() {
        assertTrue(TermiusImporter.parse("this is not json").isEmpty())
        assertTrue(TermiusImporter.parse("").isEmpty())
    }

    @Test
    fun `accepts bare array of hosts at document root`() {
        val bareArray = """
            [
              { "label": "solo", "address": "host.example", "port": 22, "username": "me" }
            ]
        """.trimIndent()
        val hosts = TermiusImporter.parse(bareArray)
        assertEquals(1, hosts.size)
        assertEquals("host.example", hosts.first().hostname)
        assertEquals("me", hosts.first().username)
    }
}
