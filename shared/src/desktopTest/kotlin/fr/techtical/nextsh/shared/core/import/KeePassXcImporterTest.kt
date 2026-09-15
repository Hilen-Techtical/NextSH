// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeePassXcImporterTest {

    @Test
    fun `parses keepassxc csv with quoted fields embedded comma and ssh url`() {
        // Row 1: a notes field containing an embedded comma and an embedded newline,
        // a URL of the form ssh://user@host:2222, and an escaped "" quote.
        val csv = "Group,Title,Username,Password,URL,Notes,TOTP,Icon,Last Modified,Created\r\n" +
            "\"Servers/Prod\",\"web server\",\"alice\",\"p@ss,word\",\"ssh://deploy@10.0.0.9:2222\"," +
            "\"line one,\nline two with \"\"quotes\"\"\",,0,,\r\n" +
            "Home,router,admin,admin123,192.168.1.1:22,note,,0,,\r\n"

        val hosts = KeePassXcImporter.parse(csv)
        assertEquals(2, hosts.size)

        val web = hosts.first { it.label == "web server" }
        assertEquals("10.0.0.9", web.hostname)
        assertEquals(2222, web.port)
        // CSV Username column wins over the URL userinfo.
        assertEquals("alice", web.username)
        assertEquals("Servers/Prod", web.group)
        assertEquals(AuthType.PASSWORD, web.authType)
        assertNotNull(web.password)
        assertEquals("p@ss,word", String(web.password!!), "embedded comma inside quotes preserved")

        val router = hosts.first { it.label == "router" }
        assertEquals("192.168.1.1", router.hostname)
        assertEquals(22, router.port)
        assertEquals("admin", router.username)
        assertEquals("Home", router.group)
    }

    @Test
    fun `csv derives username from url userinfo when username column blank`() {
        val csv = "Group,Title,Username,Password,URL\n" +
            "g,host,,secret,ssh://bob@server.example:22\n"
        val hosts = KeePassXcImporter.parse(csv)
        assertEquals(1, hosts.size)
        assertEquals("server.example", hosts.first().hostname)
        assertEquals("bob", hosts.first().username, "URL userinfo fills in blank username column")
    }

    @Test
    fun `csv falls back to title when no url column`() {
        val csv = "Group,Title,Username,Password\n" +
            "g,my.host.example,user,pw\n"
        val hosts = KeePassXcImporter.parse(csv)
        assertEquals(1, hosts.size)
        assertEquals("my.host.example", hosts.first().hostname)
        assertEquals("my.host.example", hosts.first().label)
    }

    @Test
    fun `parses keepass 2 xml export`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <KeePassFile>
              <Root>
                <Group>
                  <Name>Infra</Name>
                  <Entry>
                    <String><Key>Title</Key><Value>bastion</Value></String>
                    <String><Key>UserName</Key><Value>jump</Value></String>
                    <String><Key>Password</Key><Value ProtectInMemory="True">bastionpw</Value></String>
                    <String><Key>URL</Key><Value>ssh://gateway.corp:2022</Value></String>
                  </Entry>
                  <Group>
                    <Name>Nested</Name>
                    <Entry>
                      <String><Key>Title</Key><Value>inner</Value></String>
                      <String><Key>UserName</Key><Value>svc</Value></String>
                      <String><Key>Password</Key><Value>innerpw</Value></String>
                      <String><Key>URL</Key><Value>inner.corp</Value></String>
                    </Entry>
                  </Group>
                </Group>
              </Root>
            </KeePassFile>
        """.trimIndent()

        val hosts = KeePassXcImporter.parse(xml)
        assertEquals(2, hosts.size)

        val bastion = hosts.first { it.label == "bastion" }
        assertEquals("gateway.corp", bastion.hostname)
        assertEquals(2022, bastion.port)
        assertEquals("jump", bastion.username)
        assertEquals("Infra", bastion.group)
        assertEquals(AuthType.PASSWORD, bastion.authType)
        assertNotNull(bastion.password)
        assertEquals("bastionpw", String(bastion.password!!), "ProtectInMemory value read as plain text")

        val inner = hosts.first { it.label == "inner" }
        assertEquals("inner.corp", inner.hostname)
        assertEquals(22, inner.port, "no port in URL defaults to 22")
        assertEquals("svc", inner.username)
        assertEquals("Nested", inner.group, "uses nearest enclosing group name")
    }

    @Test
    fun `xml skips history entries`() {
        val xml = """
            <?xml version="1.0"?>
            <KeePassFile><Root><Group><Name>G</Name>
              <Entry>
                <String><Key>Title</Key><Value>current</Value></String>
                <String><Key>URL</Key><Value>now.example</Value></String>
                <History>
                  <Entry>
                    <String><Key>Title</Key><Value>old</Value></String>
                    <String><Key>URL</Key><Value>old.example</Value></String>
                  </Entry>
                </History>
              </Entry>
            </Group></Root></KeePassFile>
        """.trimIndent()
        val hosts = KeePassXcImporter.parse(xml)
        assertEquals(1, hosts.size, "history entry must be skipped")
        assertEquals("now.example", hosts.first().hostname)
    }

    @Test
    fun `detects xml even with leading whitespace and bom`() {
        val xml = "﻿  \n<KeePassFile><Root><Group><Name>g</Name>" +
            "<Entry><String><Key>Title</Key><Value>x</Value></String>" +
            "<String><Key>URL</Key><Value>x.example</Value></String></Entry>" +
            "</Group></Root></KeePassFile>"
        val hosts = KeePassXcImporter.parse(xml)
        assertEquals(1, hosts.size)
        assertEquals("x.example", hosts.first().hostname)
    }

    @Test
    fun `returns empty for malformed xml`() {
        assertTrue(KeePassXcImporter.parse("<?xml version=\"1.0\"?><KeePassFile><unclosed").isEmpty())
    }

    /**
     * XXE regression guard. The parser is hardened against external-entity
     * expansion (doctype disallowed + entity expansion off). A future change to
     * the [javax.xml.parsers.DocumentBuilderFactory] config that silently
     * re-enabled DOCTYPE / entity resolution must not pass: feeding an XXE
     * payload referencing a local file must NOT embed that file's contents into
     * any parsed value. With `disallow-doctype-decl=true` the document is
     * rejected outright (empty list); even if a future config only disabled
     * expansion, the `&xxe;` reference would never resolve to file contents.
     */
    @Test
    fun `does not resolve external entities (XXE guard)`() {
        val malicious = "<?xml version=\"1.0\"?>\n" +
            "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n" +
            "<KeePassFile><Root><Group><Name>g</Name>" +
            "<Entry>" +
            "<String><Key>Title</Key><Value>pwn</Value></String>" +
            "<String><Key>URL</Key><Value>host.example</Value></String>" +
            "<String><Key>Password</Key><Value>&xxe;</Value></String>" +
            "</Entry>" +
            "</Group></Root></KeePassFile>"

        val hosts = KeePassXcImporter.parse(malicious)

        // Primary assertion: a DOCTYPE-bearing document is rejected (doctype-decl
        // disallowed) → empty list, so no entity can ever be expanded.
        assertTrue(
            hosts.isEmpty(),
            "XXE payload with a DOCTYPE must be rejected, not parsed",
        )
        // Defence in depth: even if a future config change let the document
        // parse, the &xxe; reference must never have embedded file contents.
        hosts.forEach { host ->
            host.password?.let { pw ->
                val value = String(pw)
                assertTrue(
                    !value.contains("root:") && !value.contains("/bin/"),
                    "no /etc/passwd contents may leak into a parsed value",
                )
            }
        }
    }

    @Test
    fun `csv extracts host and port from bracketed ipv6 ssh url`() {
        val csv = "Group,Title,Username,Password,URL\n" +
            "net,v6host,,pw,ssh://user@[2001:db8::1]:2222\n"
        val hosts = KeePassXcImporter.parse(csv)
        assertEquals(1, hosts.size)
        assertEquals("2001:db8::1", hosts.first().hostname, "bracketed IPv6 host extracted without brackets")
        assertEquals(2222, hosts.first().port, "port after the bracketed IPv6 literal extracted")
        assertEquals("user", hosts.first().username, "URL userinfo fills the blank username column")
    }

    @Test
    fun `xml extracts host and port from bracketed ipv6 ssh url`() {
        val xml = "<?xml version=\"1.0\"?>" +
            "<KeePassFile><Root><Group><Name>net</Name>" +
            "<Entry>" +
            "<String><Key>Title</Key><Value>v6host</Value></String>" +
            "<String><Key>URL</Key><Value>ssh://user@[2001:db8::1]:2222</Value></String>" +
            "</Entry>" +
            "</Group></Root></KeePassFile>"
        val hosts = KeePassXcImporter.parse(xml)
        assertEquals(1, hosts.size)
        assertEquals("2001:db8::1", hosts.first().hostname)
        assertEquals(2222, hosts.first().port)
        assertEquals("user", hosts.first().username)
    }
}
