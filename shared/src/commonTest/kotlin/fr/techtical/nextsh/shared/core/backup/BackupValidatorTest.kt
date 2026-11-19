// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupValidatorTest {

    // Invisible and control characters are built from their code point, never written raw.
    private val rlo = Char(0x202E).toString() // right-to-left override
    private val zwsp = Char(0x200B).toString()
    private val bom = Char(0xFEFF).toString()
    private val nul = Char(0).toString()
    private val esc = Char(0x1B).toString()
    private val c1 = Char(0x85).toString()
    private val cr = Char(0x0D).toString()
    private val zwj = Char(0x200D).toString()

    private fun snippets(vararg s: BackupSnippet) =
        validOrFail(sampleDocument(snippets = s.toList(), customThemes = emptyList(), knownHosts = emptyList(), settings = null))

    private fun themes(vararg t: BackupCustomTheme) =
        validOrFail(sampleDocument(snippets = emptyList(), customThemes = t.toList(), knownHosts = emptyList(), settings = null))

    private fun hosts(vararg k: BackupKnownHost, check: (BackupKnownHost) -> Boolean = { true }) =
        validOrFail(sampleDocument(snippets = emptyList(), customThemes = emptyList(), knownHosts = k.toList(), settings = null), check)

    // ---- snippets ----

    @Test
    fun `a valid snippet is accepted`() {
        val v = snippets(sampleSnippet())
        assertEquals(1, v.document.snippets.size)
        assertEquals(0, v.invalidTotal)
    }

    @Test
    fun `snippet ids are checked`() {
        val v = snippets(
            sampleSnippet(id = ""),
            sampleSnippet(id = "x".repeat(129)),
            sampleSnippet(id = "bad${nul}id"),
            sampleSnippet(id = "ok-id"),
            sampleSnippet(id = "x".repeat(128)),
        )
        assertEquals(listOf("ok-id", "x".repeat(128)), v.document.snippets.map { it.id })
        assertEquals(3, v.invalidSnippets)
    }

    @Test
    fun `a duplicate snippet id is invalid and the first one wins`() {
        val v = snippets(sampleSnippet(id = "a", label = "first"), sampleSnippet(id = "a", label = "second"))
        assertEquals(listOf("first"), v.document.snippets.map { it.label })
        assertEquals(1, v.invalidSnippets)
    }

    @Test
    fun `snippet label and category rules`() {
        val v = snippets(
            sampleSnippet(id = "1", label = "x".repeat(257)),
            sampleSnippet(id = "2", label = "x".repeat(256)),
            sampleSnippet(id = "3", label = "a${rlo}b"),
            sampleSnippet(id = "4", label = "a${zwsp}b"),
            sampleSnippet(id = "5", label = "a${c1}b"),
            sampleSnippet(id = "6", category = "c${bom}"),
            sampleSnippet(id = "7", category = null),
            sampleSnippet(id = "8", label = "Red${Char(0xE9)}marrer ${zwj}ok"), // ZWJ stays allowed
        )
        assertEquals(listOf("2", "7", "8"), v.document.snippets.map { it.id })
        assertEquals(5, v.invalidSnippets)
    }

    @Test
    fun `snippet command rules`() {
        val v = snippets(
            sampleSnippet(id = "1", command = "echo a\n\techo b"),
            sampleSnippet(id = "2", command = "echo a${cr}echo b"),
            sampleSnippet(id = "3", command = "echo ${esc}[31m"),
            sampleSnippet(id = "4", command = "echo ${rlo}evil"),
            sampleSnippet(id = "5", command = "x".repeat(64 * 1024)),
            sampleSnippet(id = "6", command = "x".repeat(64 * 1024 + 1)),
            sampleSnippet(id = "7", command = "${Char(0xE9)}".repeat(33_000)), // 2 bytes each in UTF-8
        )
        assertEquals(listOf("1", "5"), v.document.snippets.map { it.id })
        assertEquals(5, v.invalidSnippets)
    }

    @Test
    fun `snippet dates and host id`() {
        val v = snippets(
            sampleSnippet(id = "1").copy(createdAt = -1),
            sampleSnippet(id = "2").copy(createdAt = 0),
            sampleSnippet(id = "3", hostId = null),
            sampleSnippet(id = "4", hostId = "bad${nul}"),
            sampleSnippet(id = "5", hostId = "not-in-the-file"), // a dangling host id is kept
        )
        assertEquals(listOf("2", "3", "5"), v.document.snippets.map { it.id })
    }

    // ---- themes ----

    @Test
    fun `a valid theme is accepted and a translucent selection color is kept`() {
        val v = themes(sampleTheme(selectionBg = 0x44FFFFFF))
        assertEquals(1, v.document.customThemes.size)
        val v2 = themes(sampleTheme(selectionBg = 0x00123456))
        assertEquals(1, v2.document.customThemes.size)
    }

    @Test
    fun `theme id must be a unique uuid and never a preset name`() {
        val v = themes(
            sampleTheme(id = "DRACULA"),
            sampleTheme(id = "TECHTICAL_DARK"),
            sampleTheme(id = "not-a-uuid"),
            sampleTheme(id = ""),
            sampleTheme(id = "5F0C1F3E-8A53-4C53-9D3E-0A1B2C3D4E5F"),
            sampleTheme(id = "5F0C1F3E-8A53-4C53-9D3E-0A1B2C3D4E5F", name = "dup"),
            sampleTheme(id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        )
        assertEquals(listOf("5F0C1F3E-8A53-4C53-9D3E-0A1B2C3D4E5F", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), v.document.customThemes.map { it.id })
        assertEquals(5, v.invalidThemes)
    }

    @Test
    fun `theme name rules`() {
        val v = themes(
            sampleTheme(id = "11111111-1111-1111-1111-111111111111", name = ""),
            sampleTheme(id = "22222222-2222-2222-2222-222222222222", name = "   "),
            sampleTheme(id = "33333333-3333-3333-3333-333333333333", name = "n".repeat(257)),
            sampleTheme(id = "44444444-4444-4444-4444-444444444444", name = "n${rlo}"),
            sampleTheme(id = "55555555-5555-5555-5555-555555555555", name = "n".repeat(256)),
        )
        assertEquals(listOf("55555555-5555-5555-5555-555555555555"), v.document.customThemes.map { it.id })
    }

    @Test
    fun `theme ansi must have exactly 16 opaque entries`() {
        val ok = List(16) { opaque(it) }
        val v = themes(
            sampleTheme(id = "11111111-1111-1111-1111-111111111111", ansi = ok.take(15)),
            sampleTheme(id = "22222222-2222-2222-2222-222222222222", ansi = ok + opaque(0)),
            sampleTheme(id = "33333333-3333-3333-3333-333333333333", ansi = emptyList()),
            sampleTheme(id = "44444444-4444-4444-4444-444444444444", ansi = ok.toMutableList().also { it[3] = 0x00112233 }),
            sampleTheme(id = "55555555-5555-5555-5555-555555555555", ansi = ok),
        )
        assertEquals(listOf("55555555-5555-5555-5555-555555555555"), v.document.customThemes.map { it.id })
        assertEquals(4, v.invalidThemes)
    }

    @Test
    fun `theme main colors must be opaque and the date non negative`() {
        val base = sampleTheme()
        val v = themes(
            base.copy(id = "11111111-1111-1111-1111-111111111111", background = 0x00101010),
            base.copy(id = "22222222-2222-2222-2222-222222222222", foreground = 0x7F101010),
            base.copy(id = "33333333-3333-3333-3333-333333333333", cursor = 0x00FFFFFF),
            base.copy(id = "44444444-4444-4444-4444-444444444444", createdAt = -5),
            base.copy(id = "55555555-5555-5555-5555-555555555555"),
        )
        assertEquals(listOf("55555555-5555-5555-5555-555555555555"), v.document.customThemes.map { it.id })
    }

    // ---- known hosts ----

    @Test
    fun `a valid known host is accepted`() {
        assertEquals(1, hosts(sampleKnownHost()).document.knownHosts.size)
    }

    @Test
    fun `host port rules`() {
        val v = hosts(
            sampleKnownHost(hostPort = "ok.example.test:22"),
            sampleKnownHost(hostPort = "10.0.0.1:65535"),
            sampleKnownHost(hostPort = "::1:2222"), // split at the last colon
            sampleKnownHost(hostPort = "nocolon"),
            sampleKnownHost(hostPort = ":22"),
            sampleKnownHost(hostPort = "h:0"),
            sampleKnownHost(hostPort = "h:65536"),
            sampleKnownHost(hostPort = "h:"),
            sampleKnownHost(hostPort = "h:-1"),
            sampleKnownHost(hostPort = "h:+22"),
            sampleKnownHost(hostPort = "h:2x2"),
            sampleKnownHost(hostPort = "h:123456"),
            sampleKnownHost(hostPort = "a b:22"),
            sampleKnownHost(hostPort = "a\tb:22"),
            sampleKnownHost(hostPort = "a${nul}b:22"),
            sampleKnownHost(hostPort = "a${zwsp}b:22"),
            sampleKnownHost(hostPort = "${"h".repeat(256)}:22"),
            sampleKnownHost(hostPort = "${"h".repeat(255)}:22"),
        )
        assertEquals(
            listOf("ok.example.test:22", "10.0.0.1:65535", "::1:2222", "${"h".repeat(255)}:22"),
            v.document.knownHosts.map { it.hostPort },
        )
        assertEquals(14, v.invalidKnownHosts)
    }

    @Test
    fun `a duplicate host port is invalid and the first one wins`() {
        val v = hosts(sampleKnownHost(hostPort = "a:22", addedAt = 1), sampleKnownHost(hostPort = "a:22", addedAt = 2))
        assertEquals(listOf(1L), v.document.knownHosts.map { it.addedAt })
        assertEquals(1, v.invalidKnownHosts)
    }

    @Test
    fun `key type allow-list and date`() {
        val v = hosts(
            sampleKnownHost(hostPort = "a:1", keyType = "ssh-rsa"),
            sampleKnownHost(hostPort = "b:1", keyType = "ecdsa-sha2-nistp256"),
            sampleKnownHost(hostPort = "c:1", keyType = "ecdsa-sha2-nistp384"),
            sampleKnownHost(hostPort = "d:1", keyType = "ecdsa-sha2-nistp521"),
            sampleKnownHost(hostPort = "e:1", keyType = "ssh-dss"),
            sampleKnownHost(hostPort = "f:1", keyType = "sk-ssh-ed25519@openssh.com"),
            sampleKnownHost(hostPort = "g:1", keyType = ""),
            sampleKnownHost(hostPort = "h:1", keyType = "SSH-ED25519"),
            sampleKnownHost(hostPort = "i:1", addedAt = -1),
        )
        assertEquals(listOf("a:1", "b:1", "c:1", "d:1"), v.document.knownHosts.map { it.hostPort })
    }

    @Test
    fun `public key base64 rules`() {
        val valid = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi"
        val v = hosts(
            sampleKnownHost(hostPort = "a:1", publicKey = valid),
            sampleKnownHost(hostPort = "b:1", publicKey = ""),
            sampleKnownHost(hostPort = "c:1", publicKey = "AAAAAAA="),
            sampleKnownHost(hostPort = "d:1", publicKey = valid.dropLast(1)),
            sampleKnownHost(hostPort = "e:1", publicKey = "AAA*"),
            sampleKnownHost(hostPort = "f:1", publicKey = "AA==AAAA"),
            sampleKnownHost(hostPort = "g:1", publicKey = "AAA="),
            sampleKnownHost(hostPort = "h:1", publicKey = "AAAA".repeat(2731)), // 8193 bytes
            sampleKnownHost(hostPort = "i:1", publicKey = "AAAA".repeat(2730)), // 8190 bytes
            sampleKnownHost(hostPort = "j:1", publicKey = "A A A "),
        )
        assertEquals(listOf("a:1", "c:1", "g:1", "i:1"), v.document.knownHosts.map { it.hostPort })
    }

    @Test
    fun `the platform hook can reject an entry`() {
        val v = hosts(
            sampleKnownHost(hostPort = "a:1"),
            sampleKnownHost(hostPort = "b:1"),
            check = { it.hostPort != "b:1" },
        )
        assertEquals(listOf("a:1"), v.document.knownHosts.map { it.hostPort })
        assertEquals(1, v.invalidKnownHosts)
    }

    // ---- settings ----

    @Test
    fun `settings in their domain are kept`() {
        val v = validOrFail(sampleDocument())
        val s = v.document.settings!!
        assertEquals(15, s.common?.connectionTimeoutSeconds)
        assertEquals(24, s.android?.terminalFontSize)
        assertEquals("FR", s.desktop?.language)
        assertEquals(0, v.invalidSettings)
        assertEquals(5, s.countFor(BackupPlatform.ANDROID)) // 1 common + 4 android
        assertEquals(3, s.countFor(BackupPlatform.DESKTOP)) // 1 common + 2 desktop
    }

    @Test
    fun `settings out of their domain are dropped and counted without clamping`() {
        val v = validOrFail(
            sampleDocument(
                settings = BackupSettings(
                    common = BackupCommonSettings(connectionTimeoutSeconds = 12),
                    android = BackupAndroidSettings(
                        terminalFontSize = 47,
                        scrollbackLines = 999,
                        keepAliveIntervalSeconds = 121,
                        autoReconnect = false,
                    ),
                    desktop = BackupDesktopSettings(terminalFontSizePx = 9, language = "DE"),
                ),
            ),
        )
        val s = v.document.settings!!
        assertNull(s.common?.connectionTimeoutSeconds)
        assertNull(s.android?.terminalFontSize)
        assertNull(s.android?.scrollbackLines)
        assertNull(s.android?.keepAliveIntervalSeconds)
        assertEquals(false, s.android?.autoReconnect)
        assertNull(s.desktop?.terminalFontSizePx)
        assertNull(s.desktop?.language)
        assertEquals(6, v.invalidSettings)
    }

    @Test
    fun `settings domain edges are accepted`() {
        fun android(size: Int = 24, lines: Int = 1000, keep: Int = 0) = validOrFail(
            sampleDocument(settings = BackupSettings(android = BackupAndroidSettings(size, lines, keep, null))),
        )
        assertEquals(0, android(size = 12).invalidSettings)
        assertEquals(0, android(size = 46).invalidSettings)
        assertEquals(0, android(lines = 100_000).invalidSettings)
        assertEquals(0, android(keep = 120).invalidSettings)
        assertEquals(1, android(size = 11).invalidSettings)
        assertEquals(1, android(keep = -1).invalidSettings)
        for (t in listOf(5, 10, 15, 20, 25, 30)) {
            val v = validOrFail(sampleDocument(settings = BackupSettings(common = BackupCommonSettings(t))))
            assertEquals(0, v.invalidSettings)
        }
        for (lang in listOf("SYSTEM", "FR", "EN")) {
            val v = validOrFail(sampleDocument(settings = BackupSettings(desktop = BackupDesktopSettings(language = lang))))
            assertEquals(0, v.invalidSettings)
        }
        val lower = validOrFail(sampleDocument(settings = BackupSettings(desktop = BackupDesktopSettings(language = "fr"))))
        assertEquals(1, lower.invalidSettings)
    }

    // ---- limits and producer ----

    @Test
    fun `a section over its maximum count keeps the first entries and counts the surplus as invalid`() {
        val tooManySnippets = List(BackupLimits.MAX_SNIPPETS + 3) { sampleSnippet(id = "s$it") }
        val vs = validOrFail(sampleDocument(snippets = tooManySnippets))
        assertEquals(BackupLimits.MAX_SNIPPETS, vs.document.snippets.size)
        assertEquals("s0", vs.document.snippets.first().id)
        assertEquals("s${BackupLimits.MAX_SNIPPETS - 1}", vs.document.snippets.last().id)
        assertEquals(3, vs.invalidSnippets)

        val tooManyThemes = List(BackupLimits.MAX_CUSTOM_THEMES + 2) {
            sampleTheme(id = "00000000-0000-0000-0000-" + it.toString().padStart(12, '0'))
        }
        val vt = validOrFail(sampleDocument(customThemes = tooManyThemes))
        assertEquals(BackupLimits.MAX_CUSTOM_THEMES, vt.document.customThemes.size)
        assertEquals(2, vt.invalidThemes)

        val tooManyHosts = List(BackupLimits.MAX_KNOWN_HOSTS + 4) { sampleKnownHost(hostPort = "h$it:22") }
        val vh = validOrFail(sampleDocument(knownHosts = tooManyHosts))
        assertEquals(BackupLimits.MAX_KNOWN_HOSTS, vh.document.knownHosts.size)
        assertEquals(4, vh.invalidKnownHosts)
        assertEquals(4, vh.invalidTotal)
    }

    @Test
    fun `certificates are validated, capped and counted`() {
        val many = LinkedHashMap<String, String>()
        for (i in 0 until BackupLimits.MAX_CERTIFICATES + 5) many["cert-$i"] = "FIXTURE-cert"
        val capped = validOrFail(sampleDocument().copy(certificates = many))
        assertEquals(BackupLimits.MAX_CERTIFICATES, capped.document.certificates.size)
        assertEquals(5, capped.invalidCertificates)
        assertTrue("cert-0" in capped.document.certificates)

        val mixed = validOrFail(
            sampleDocument().copy(
                certificates = linkedMapOf(
                    "ok" to "FIXTURE-cert",
                    "" to "FIXTURE-cert",
                    "x".repeat(129) to "FIXTURE-cert",
                    "bad${nul}id" to "FIXTURE-cert",
                    "empty-value" to "",
                    "big" to "x".repeat(64 * 1024 + 1),
                    "edge" to "x".repeat(64 * 1024),
                ),
            ),
        )
        assertEquals(listOf("ok", "edge"), mixed.document.certificates.keys.toList())
        assertEquals(5, mixed.invalidCertificates)
        assertEquals(5, mixed.invalidTotal)
    }

    @Test
    fun `the editors and the validator share the field rules`() {
        val rules = BackupFieldRules
        assertNull(rules.snippetLabelProblem("ok"))
        assertNull(rules.snippetLabelProblem(""))
        assertEquals(BackupFieldProblem.TOO_LONG, rules.snippetLabelProblem("x".repeat(257)))
        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, rules.snippetLabelProblem("a${nul}b"))
        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, rules.snippetLabelProblem("a\nb"))
        assertEquals(BackupFieldProblem.INVISIBLE_FORMAT, rules.snippetLabelProblem("a${rlo}b"))

        assertNull(rules.snippetCategoryProblem(null))
        assertNull(rules.snippetCategoryProblem("ops"))
        assertEquals(BackupFieldProblem.INVISIBLE_FORMAT, rules.snippetCategoryProblem("o${zwsp}ps"))

        assertEquals(BackupFieldProblem.EMPTY, rules.themeNameProblem(""))
        assertEquals(BackupFieldProblem.EMPTY, rules.themeNameProblem("  "))
        assertEquals(BackupFieldProblem.TOO_LONG, rules.themeNameProblem("n".repeat(257)))
        assertNull(rules.themeNameProblem("Nord"))

        assertNull(rules.snippetCommandProblem("a\n\tb"))
        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, rules.snippetCommandProblem("a${cr}b"))
        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, rules.snippetCommandProblem("a${esc}b"))
        assertEquals(BackupFieldProblem.INVISIBLE_FORMAT, rules.snippetCommandProblem("a${bom}b"))
        assertEquals(BackupFieldProblem.TOO_LONG, rules.snippetCommandProblem("x".repeat(64 * 1024 + 1)))
        // ZWJ stays allowed.
        assertNull(rules.snippetLabelProblem("a${zwj}b"))

        // The validator agrees with the editor rules.
        assertFalse(BackupValidator.isValidSnippet(sampleSnippet(label = "a${rlo}b")))
        assertFalse(BackupValidator.isValidTheme(sampleTheme(name = " ")))
        assertTrue(BackupValidator.isValidSnippet(sampleSnippet()))
        assertTrue(BackupValidator.isValidTheme(sampleTheme()))
    }

    @Test
    fun `the document text form holds no content`() {
        val text = sampleDocument().toString()
        assertFalse(text.contains("FIXTURE"))
        assertFalse(text.contains("web.example.test"))
    }

    @Test
    fun `sections at exactly their maximum count are accepted`() {
        val snippets = List(BackupLimits.MAX_SNIPPETS) { sampleSnippet(id = "s$it") }
        val v = validOrFail(sampleDocument(snippets = snippets))
        assertEquals(BackupLimits.MAX_SNIPPETS, v.document.snippets.size)
    }

    @Test
    fun `an invalid producer is dropped silently`() {
        val bad = sampleDocument().copy(producer = BackupProducer("IOS", "1"))
        val v = validOrFail(bad)
        assertNull(v.document.producer)
        assertEquals(0, v.invalidTotal)
        assertEquals("ANDROID", validOrFail(sampleDocument().copy(producer = BackupProducer("ANDROID", "1.1.0"))).document.producer?.platform)
    }

    @Test
    fun `a valid v1 core and valid secret maps are kept as is`() {
        val doc = sampleDocument()
        val v = validOrFail(doc)
        assertEquals(doc.hosts, v.document.hosts)
        assertEquals(doc.sshKeys, v.document.sshKeys)
        assertEquals(doc.tunnels, v.document.tunnels)
        assertEquals(doc.credentials, v.document.credentials)
        assertEquals(doc.privateKeys, v.document.privateKeys)
        assertEquals(doc.keyPassphrases, v.document.keyPassphrases)
        assertEquals(doc.certificates, v.document.certificates)
        assertEquals(0, v.invalidTotal)
        assertTrue(v.toString() == "ValidatedBackup")
        assertFalse(v.toString().contains("FIXTURE"))
    }

    // ---- v1 core: hosts ----

    private val baseHost = sampleDocument().hosts[0]
    private val baseKey = sampleDocument().sshKeys[0]
    private val baseTunnel = sampleDocument().tunnels[0]

    private fun core(
        hosts: List<Host> = listOf(baseHost),
        sshKeys: List<SshKey> = listOf(baseKey),
        tunnels: List<TunnelConfig> = listOf(baseTunnel),
    ) = validOrFail(sampleDocument().copy(hosts = hosts, sshKeys = sshKeys, tunnels = tunnels))

    private fun hostIds(vararg h: Host): List<String> = core(hosts = h.toList(), tunnels = emptyList()).document.hosts.map { it.id }

    @Test
    fun `host ids are checked and a duplicate id is invalid with the first one winning`() {
        val v = core(
            hosts = listOf(
                baseHost.copy(id = ""),
                baseHost.copy(id = "x".repeat(129)),
                baseHost.copy(id = "bad${nul}id"),
                baseHost.copy(id = "a", label = "first"),
                baseHost.copy(id = "a", label = "second"),
            ),
            tunnels = emptyList(),
        )
        assertEquals(listOf("first"), v.document.hosts.map { it.label })
        assertEquals(4, v.invalidHosts)
        assertEquals(4, v.invalidTotal)
    }

    @Test
    fun `host label and group are cleaned and never refused, the user name is refused only when too long`() {
        fun cleaned(h: Host): Host = core(hosts = listOf(h), tunnels = emptyList()).document.hosts.single()
        assertEquals("x".repeat(4096), cleaned(baseHost.copy(label = "x".repeat(4096))).label)
        assertEquals("x".repeat(4096), cleaned(baseHost.copy(label = "x".repeat(4097))).label) // cut, not refused
        assertEquals("a b", cleaned(baseHost.copy(label = "a\tb")).label)
        assertEquals("a b", cleaned(baseHost.copy(label = "a\nb")).label)
        assertEquals("a b", cleaned(baseHost.copy(label = "a${c1}b")).label)
        assertEquals("a${rlo}b", cleaned(baseHost.copy(label = "a${rlo}b")).label) // invisible chars are kept
        assertNull(cleaned(baseHost.copy(group = null)).group)
        assertEquals("prod", cleaned(baseHost.copy(group = "prod")).group)
        assertEquals("a b", cleaned(baseHost.copy(group = "a\tb")).group)
        assertEquals("y".repeat(4096), cleaned(baseHost.copy(group = "y".repeat(5000))).group)
        assertTrue(BackupValidator.isValidHost(baseHost.copy(label = "a\tb", group = "c\nd")))
        assertEquals(0, core(hosts = listOf(baseHost.copy(label = "a\tb")), tunnels = emptyList()).invalidHosts)

        assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", username = "")))
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", username = "x".repeat(4097))))
        assertEquals("x".repeat(4096), cleaned(baseHost.copy(username = "x".repeat(4096))).username)
    }

    @Test
    fun `a control char in a user name is replaced and the host and its tunnels are kept`() {
        fun cleaned(h: Host): Host = core(hosts = listOf(h), tunnels = emptyList()).document.hosts.single()
        assertEquals("a b", cleaned(baseHost.copy(username = "a${nul}b")).username)
        assertEquals("ro ot", cleaned(baseHost.copy(username = "ro\tot")).username)
        assertEquals("a b", cleaned(baseHost.copy(username = "a${c1}b")).username)
        assertEquals("a${rlo}b", cleaned(baseHost.copy(username = "a${rlo}b")).username) // invisible chars are not control chars

        val v = core(
            hosts = listOf(baseHost.copy(id = "h", username = "us\ner")),
            tunnels = listOf(baseTunnel.copy(id = "t", hostId = "h")),
        )
        assertEquals(listOf("h"), v.document.hosts.map { it.id })
        assertEquals(listOf("t"), v.document.tunnels.map { it.id })
        assertEquals(0, v.invalidTotal)
        assertTrue(BackupValidator.isValidHost(baseHost.copy(username = "a${esc}b")))
        assertFalse(BackupValidator.isValidHost(baseHost.copy(username = "x".repeat(4097))))
    }

    @Test
    fun `labels of keys and tunnels are cleaned too`() {
        val v = core(
            sshKeys = listOf(baseKey.copy(label = "a\tb" + "x".repeat(5000))),
            tunnels = listOf(baseTunnel.copy(label = "t\nu")),
        )
        assertEquals("a b" + "x".repeat(4093), v.document.sshKeys.single().label)
        assertEquals("t u", v.document.tunnels.single().label)
        assertEquals(0, v.invalidTotal)
    }

    @Test
    fun `a huge host name or public key is refused on its raw length`() {
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", hostname = " ".repeat(400) + "a")))
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", hostname = "a".repeat(5 * 1024 * 1024))))
        assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", hostname = " ".repeat(20) + "a" + " ".repeat(20))))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", publicKey = "x".repeat(5 * 1024 * 1024))))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", publicKey = "x" + " ".repeat(64 * 1024 + 100))))
    }

    @Test
    fun `a SOCKS5 tunnel keeps working whatever its remote end holds`() {
        val junk = baseTunnel.copy(id = "s", type = TunnelType.DYNAMIC_SOCKS5, remoteHost = "left over\nhost", remotePort = 70000)
        val v = core(tunnels = listOf(junk, junk.copy(id = "s2", remoteHost = "x".repeat(300), remotePort = 0)))
        assertEquals(listOf("s", "s2"), v.document.tunnels.map { it.id })
        assertTrue(v.document.tunnels.all { it.remoteHost == "" })
        assertEquals(0, v.invalidTunnels)
        assertTrue(BackupValidator.isValidTunnel(junk))
        // A local forward still needs a valid remote end.
        assertFalse(BackupValidator.isValidTunnel(junk.copy(type = TunnelType.LOCAL_FORWARD)))
    }

    @Test
    fun `host name rules`() {
        val good = listOf("web-01.example.test", "2001:db8::1", "fe80::1%eth0", "x".repeat(255), "10.0.0.1")
        for (h in good) assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", hostname = h)), h)
        val bad = listOf("", " ", "a b", "a${nul}b", "x".repeat(256), "a${zwsp}b", "a\nb", "a${rlo}b")
        for (h in bad) assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", hostname = h)), "hostname of ${h.length} chars")
    }

    @Test
    fun `host port bounds`() {
        for (p in listOf(1, 22, 65535)) assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", port = p)))
        for (p in listOf(0, -1, 65536, Int.MAX_VALUE)) assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", port = p)))
    }

    @Test
    fun `host credential id and terminal theme rules`() {
        assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", credentialId = "")))
        assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", credentialId = "x".repeat(128))))
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", credentialId = "x".repeat(129))))
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", credentialId = "a${nul}b")))
        assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", terminalTheme = "DRACULA")))
        assertEquals(listOf("1"), hostIds(baseHost.copy(id = "1", terminalTheme = THEME_UUID)))
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", terminalTheme = "x".repeat(129))))
        assertEquals(emptyList(), hostIds(baseHost.copy(id = "1", terminalTheme = "a${esc}b")))
    }

    // ---- v1 core: SSH keys ----

    private fun keyIds(vararg k: SshKey): List<String> = core(sshKeys = k.toList()).document.sshKeys.map { it.id }

    @Test
    fun `ssh key rules`() {
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", publicKey = "")))
        // A control char in the public key line is cleaned (one line), never a reason to drop the key.
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", publicKey = "ssh-ed25519 AAAA a\nssh-ed25519 BBBB b")))
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", publicKey = "ssh-ed25519 AAAA a\rb")))
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", publicKey = "x".repeat(64 * 1024))))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", publicKey = "x".repeat(64 * 1024 + 1))))
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", keystoreAlias = "a".repeat(256))))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", keystoreAlias = "a".repeat(257))))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", keystoreAlias = "a${nul}b")))
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", fido2CredentialId = "cred", fido2RpId = "ssh:")))
        // The relying party is informative: cleaned. The credential id never has a control char: refused.
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", fido2RpId = "a\nb")))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", fido2CredentialId = "a\nb")))
        assertEquals(emptyList(), keyIds(baseKey.copy(id = "1", fido2RpId = "r".repeat(4097))))
        assertEquals(listOf("1"), keyIds(baseKey.copy(id = "1", label = "x".repeat(4097)))) // cut, not refused
    }

    @Test
    fun `a public key line and a relying party are cleaned to what gets stored`() {
        val v = core(
            sshKeys = listOf(
                baseKey.copy(id = "k1", publicKey = "ssh-ed25519 AAAA my\tkey"),
                baseKey.copy(id = "k2", publicKey = "ssh-ed25519 AAAA a\nssh-ed25519 BBBB b\n"),
                baseKey.copy(id = "k3", fido2CredentialId = "Y3JlZA==", fido2RpId = "ssh:${esc}x"),
            ),
        )
        assertEquals(
            listOf("ssh-ed25519 AAAA my key", "ssh-ed25519 AAAA a ssh-ed25519 BBBB b", baseKey.publicKey),
            v.document.sshKeys.map { it.publicKey },
        )
        assertEquals("ssh: x", v.document.sshKeys[2].fido2RpId)
        assertEquals("Y3JlZA==", v.document.sshKeys[2].fido2CredentialId)
        assertEquals(0, v.invalidSshKeys)
    }

    @Test
    fun `a public key and host names are trimmed and the trimmed value is kept`() {
        val pk = "ssh-ed25519 AAAA fixture"
        val v = core(
            hosts = listOf(baseHost.copy(id = "h1", hostname = " srv.example "), baseHost.copy(id = "h2", hostname = "a b")),
            sshKeys = listOf(
                baseKey.copy(id = "k1", publicKey = pk + "\n"),
                baseKey.copy(id = "k2", publicKey = pk + "\r\n"),
                baseKey.copy(id = "k3", publicKey = "  " + pk + " \t"),
                baseKey.copy(id = "k4", publicKey = "ssh-ed25519 AAAA a\nssh-ed25519 BBBB b\n"),
            ),
            tunnels = listOf(
                baseTunnel.copy(id = "t1", hostId = "h1", remoteHost = " db.internal\n"),
                baseTunnel.copy(id = "t2", hostId = "h1", remoteHost = "bad host"),
            ),
        )
        assertEquals(listOf("srv.example"), v.document.hosts.map { it.hostname })
        assertEquals(1, v.invalidHosts)
        assertEquals(listOf("k1", "k2", "k3", "k4"), v.document.sshKeys.map { it.id })
        assertTrue(v.document.sshKeys.take(3).all { it.publicKey == pk })
        assertEquals("ssh-ed25519 AAAA a ssh-ed25519 BBBB b", v.document.sshKeys[3].publicKey)
        assertEquals(0, v.invalidSshKeys)
        assertEquals(listOf("db.internal"), v.document.tunnels.map { it.remoteHost })
        assertEquals(1, v.invalidTunnels)
        assertTrue(BackupValidator.isValidHost(baseHost.copy(hostname = " srv.example ")))
        assertFalse(BackupValidator.isValidHost(baseHost.copy(hostname = "  ")))
        assertTrue(BackupValidator.isValidSshKey(baseKey.copy(publicKey = pk + "\n")))
    }

    @Test
    fun `ssh key duplicate id is invalid and counted`() {
        val v = core(sshKeys = listOf(baseKey.copy(id = "k", label = "first"), baseKey.copy(id = "k", label = "second"), baseKey.copy(id = "")))
        assertEquals(listOf("first"), v.document.sshKeys.map { it.label })
        assertEquals(2, v.invalidSshKeys)
    }

    // ---- v1 core: tunnels ----

    private fun tunnelIds(vararg t: TunnelConfig): List<String> = core(tunnels = t.toList()).document.tunnels.map { it.id }

    @Test
    fun `tunnel port rules`() {
        assertEquals(listOf("1"), tunnelIds(baseTunnel.copy(id = "1", localPort = 80))) // below 1024 stays restorable
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", localPort = 0)))
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", localPort = 65536)))
        assertEquals(listOf("1"), tunnelIds(baseTunnel.copy(id = "1", localPort = 65535)))
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", remotePort = 0)))
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", type = TunnelType.REMOTE_FORWARD, remotePort = 70000)))
        val socks = baseTunnel.copy(id = "1", type = TunnelType.DYNAMIC_SOCKS5, remoteHost = "", remotePort = 0)
        assertEquals(listOf("1"), tunnelIds(socks))
        assertEquals(emptyList(), tunnelIds(socks.copy(localPort = 0)))
    }

    @Test
    fun `tunnel remote host label and id rules`() {
        assertEquals(listOf("1"), tunnelIds(baseTunnel.copy(id = "1", remoteHost = "db.internal")))
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", remoteHost = "bad host")))
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", remoteHost = "x".repeat(256))))
        assertEquals(listOf("1"), tunnelIds(baseTunnel.copy(id = "1", label = "a\nb"))) // cleaned, not refused
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "1", hostId = "")))
        assertEquals(emptyList(), tunnelIds(baseTunnel.copy(id = "")))
        val v = core(tunnels = listOf(baseTunnel.copy(id = "t", label = "first"), baseTunnel.copy(id = "t", label = "second")))
        assertEquals(listOf("first"), v.document.tunnels.map { it.label })
        assertEquals(1, v.invalidTunnels)
    }

    private fun restoredPath(t: TunnelConfig): String {
        val v = core(tunnels = listOf(t))
        assertEquals(0, v.invalidTunnels, "a browser path never drops a tunnel")
        return v.document.tunnels.single().browserPath
    }

    @Test
    fun `a valid browser path of a local forward is kept normalized`() {
        assertEquals("/", restoredPath(baseTunnel))
        assertEquals("/admin", restoredPath(baseTunnel.copy(browserPath = "admin")))
        assertEquals("/?tab=2", restoredPath(baseTunnel.copy(browserPath = "?tab=2")))
        assertEquals("/admin/users?tab=2#top", restoredPath(baseTunnel.copy(browserPath = "  /admin/users?tab=2#top \n")))
        assertEquals("/a%20b", restoredPath(baseTunnel.copy(browserPath = "/a%20b")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "   ")))
        val longest = "/" + "a".repeat(511)
        assertEquals(longest, restoredPath(baseTunnel.copy(browserPath = longest)))
    }

    @Test
    fun `an invalid browser path falls back to the root`() {
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/" + "a".repeat(512)))) // 513 chars
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/" + "a".repeat(5 * 1024 * 1024))))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/has space")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/a\\b")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/a${esc}b")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/a" + Char(0xE9) + "b")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/100%")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/%zz")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/a#b#c")))
        assertEquals("/", restoredPath(baseTunnel.copy(browserPath = "/a\nb")))
    }

    @Test
    fun `a remote forward or a SOCKS5 proxy always restores the root path`() {
        assertEquals("/", restoredPath(baseTunnel.copy(type = TunnelType.REMOTE_FORWARD, browserPath = "/admin")))
        val socks = baseTunnel.copy(type = TunnelType.DYNAMIC_SOCKS5, remoteHost = "", remotePort = 0)
        assertEquals("/", restoredPath(socks.copy(browserPath = "/admin?token=x")))
        assertEquals("/", restoredPath(socks.copy(browserPath = "/has space")))
    }

    @Test
    fun `the tunnels of a dropped host are dropped and counted, other tunnels are kept`() {
        val v = core(
            hosts = listOf(baseHost.copy(id = "good"), baseHost.copy(id = "bad", port = 0)),
            tunnels = listOf(
                baseTunnel.copy(id = "t-good", hostId = "good"),
                baseTunnel.copy(id = "t-bad", hostId = "bad"),
                baseTunnel.copy(id = "t-absent", hostId = "not-in-file"), // the importer decides
            ),
        )
        assertEquals(listOf("good"), v.document.hosts.map { it.id })
        assertEquals(listOf("t-good", "t-absent"), v.document.tunnels.map { it.id })
        assertEquals(1, v.invalidHosts)
        assertEquals(1, v.invalidTunnels)
    }

    @Test
    fun `a host id dropped as a duplicate but kept once does not drop its tunnels`() {
        val v = core(
            hosts = listOf(baseHost.copy(id = "h", label = "first"), baseHost.copy(id = "h", label = "second")),
            tunnels = listOf(baseTunnel.copy(id = "t", hostId = "h")),
        )
        assertEquals(1, v.invalidHosts)
        assertEquals(listOf("t"), v.document.tunnels.map { it.id })
        assertEquals(0, v.invalidTunnels)
    }

    @Test
    fun `a dropped host or key does not drop secrets`() {
        val doc = sampleDocument().copy(
            hosts = listOf(baseHost.copy(port = 0)),
            sshKeys = listOf(baseKey.copy(fido2CredentialId = "a\nb")),
        )
        val v = validOrFail(doc)
        assertEquals(doc.credentials, v.document.credentials)
        assertEquals(doc.privateKeys, v.document.privateKeys)
        assertEquals(doc.keyPassphrases, v.document.keyPassphrases)
        assertEquals(1, v.invalidHosts)
        assertEquals(1, v.invalidSshKeys)
        assertEquals(1, v.invalidTunnels) // its host is dropped
    }

    @Test
    fun `v1 core sections over their maximum keep the first entries and cascade to the tunnels`() {
        val hosts = List(BackupLimits.MAX_HOSTS + 4) { baseHost.copy(id = "h$it") }
        val tunnels = listOf(
            baseTunnel.copy(id = "t-first", hostId = "h0"),
            baseTunnel.copy(id = "t-surplus", hostId = "h${BackupLimits.MAX_HOSTS + 1}"),
        )
        val v = core(hosts = hosts, tunnels = tunnels)
        assertEquals(BackupLimits.MAX_HOSTS, v.document.hosts.size)
        assertEquals(4, v.invalidHosts)
        assertEquals(listOf("t-first"), v.document.tunnels.map { it.id })
        assertEquals(1, v.invalidTunnels)

        val keys = List(BackupLimits.MAX_SSH_KEYS + 2) { baseKey.copy(id = "k$it") }
        assertEquals(2, core(sshKeys = keys).invalidSshKeys)
        val many = List(BackupLimits.MAX_TUNNELS + 3) { baseTunnel.copy(id = "t$it") }
        val vt = core(tunnels = many)
        assertEquals(BackupLimits.MAX_TUNNELS, vt.document.tunnels.size)
        assertEquals(3, vt.invalidTunnels)
    }

    @Test
    fun `the entry rules are usable at export time`() {
        assertTrue(BackupValidator.isValidHost(baseHost))
        assertFalse(BackupValidator.isValidHost(baseHost.copy(port = 0)))
        assertTrue(BackupValidator.isValidSshKey(baseKey))
        assertTrue(BackupValidator.isValidSshKey(baseKey.copy(publicKey = "a\nb"))) // cleaned at restore
        assertFalse(BackupValidator.isValidSshKey(baseKey.copy(publicKey = "x".repeat(64 * 1024 + 1))))
        assertFalse(BackupValidator.isValidSshKey(baseKey.copy(fido2CredentialId = "a\nb")))
        assertTrue(BackupValidator.isValidHost(baseHost.copy(username = "a\tb"))) // cleaned at restore
        assertTrue(BackupValidator.isValidTunnel(baseTunnel))
        assertFalse(BackupValidator.isValidTunnel(baseTunnel.copy(localPort = 0)))
        assertTrue(BackupValidator.isValidSecretValue("", allowEmpty = true))
        assertFalse(BackupValidator.isValidSecretValue("", allowEmpty = false))
        assertTrue(BackupValidator.isValidSecretValue("x".repeat(64 * 1024), allowEmpty = false))
        assertFalse(BackupValidator.isValidSecretValue("x".repeat(64 * 1024 + 1), allowEmpty = true))
    }

    @Test
    fun `isValidHostPort is unchanged by the host name extraction`() {
        assertTrue(BackupValidator.isValidHostPort("a.test:22"))
        assertTrue(BackupValidator.isValidHostPort("2001:db8::1:22"))
        assertFalse(BackupValidator.isValidHostPort(":22"))
        assertFalse(BackupValidator.isValidHostPort("a b:22"))
        assertFalse(BackupValidator.isValidHostPort("${"x".repeat(256)}:22"))
        assertFalse(BackupValidator.isValidHostPort("a.test:0"))
        assertFalse(BackupValidator.isValidHostPort("a.test"))
    }

    // ---- secrets ----

    private fun secrets(
        credentials: Map<String, String> = emptyMap(),
        privateKeys: Map<String, String> = emptyMap(),
        keyPassphrases: Map<String, String> = emptyMap(),
        certificates: Map<String, String> = emptyMap(),
    ) = validOrFail(
        sampleDocument().copy(
            credentials = credentials,
            privateKeys = privateKeys,
            keyPassphrases = keyPassphrases,
            certificates = certificates,
        ),
    )

    @Test
    fun `secret ids and values are checked in the three maps`() {
        val v = secrets(
            credentials = linkedMapOf("ok" to "pw", "empty" to "", "" to "pw", "x".repeat(129) to "pw", "bad${nul}id" to "pw"),
            privateKeys = linkedMapOf("ok" to "FIXTURE-NOT-A-REAL-PRIVATE-KEY", "empty" to "", "" to "pem"),
            keyPassphrases = linkedMapOf("ok" to "pp", "empty" to "", "x".repeat(129) to "pp"),
        )
        assertEquals(listOf("ok", "empty"), v.document.credentials.keys.toList())
        assertEquals(listOf("ok"), v.document.privateKeys.keys.toList())
        assertEquals(listOf("ok", "empty"), v.document.keyPassphrases.keys.toList())
        assertEquals(3 + 2 + 1, v.invalidSecrets)
        assertEquals(0, v.invalidCertificates)
    }

    @Test
    fun `a secret value is bounded in UTF-8 bytes and the frontier counts multi byte chars`() {
        val max = 64 * 1024
        val e = Char(0xE9).toString() // 2 bytes
        val emoji = charArrayOf(Char(0xD83D), Char(0xDE00)).concatToString() // 4 bytes, one surrogate pair
        val v = secrets(
            credentials = linkedMapOf(
                "ascii-edge" to "x".repeat(max),
                "ascii-over" to "x".repeat(max + 1),
                "two-edge" to e.repeat(max / 2),
                "two-over" to e.repeat(max / 2) + "x",
                "emoji-edge" to emoji.repeat(max / 4),
                "emoji-over" to emoji.repeat(max / 4) + "x",
                "three-over" to Char(0x20AC).toString().repeat(max / 3 + 1),
            ),
        )
        assertEquals(listOf("ascii-edge", "two-edge", "emoji-edge"), v.document.credentials.keys.toList())
        assertEquals(4, v.invalidSecrets)
    }

    @Test
    fun `a huge secret value is refused on its length without being encoded`() {
        val v = secrets(privateKeys = linkedMapOf("big" to "x".repeat(5 * 1024 * 1024), "ok" to "pem"))
        assertEquals(listOf("ok"), v.document.privateKeys.keys.toList())
        assertEquals(1, v.invalidSecrets)
    }

    @Test
    fun `each secret map is capped and the surplus counted`() {
        val credentials = LinkedHashMap<String, String>()
        for (i in 0 until BackupLimits.MAX_CREDENTIALS + 1) credentials["c$i"] = "p"
        val vc = secrets(credentials = credentials)
        assertEquals(BackupLimits.MAX_CREDENTIALS, vc.document.credentials.size)
        assertEquals(1, vc.invalidSecrets)
        assertTrue("c0" in vc.document.credentials)

        val keys = LinkedHashMap<String, String>()
        val passphrases = LinkedHashMap<String, String>()
        for (i in 0 until BackupLimits.MAX_PRIVATE_KEYS + 2) keys["k$i"] = "pem"
        for (i in 0 until BackupLimits.MAX_KEY_PASSPHRASES + 3) passphrases["k$i"] = "pp"
        val vk = secrets(privateKeys = keys, keyPassphrases = passphrases)
        assertEquals(BackupLimits.MAX_PRIVATE_KEYS, vk.document.privateKeys.size)
        assertEquals(BackupLimits.MAX_KEY_PASSPHRASES, vk.document.keyPassphrases.size)
        assertEquals(5, vk.invalidSecrets)
    }

    @Test
    fun `the LAN sync secret is removed without being counted`() {
        val lan = "lan_sync_secret_device-1"
        val v = secrets(credentials = linkedMapOf("ok" to "pw", lan to "FIXTURE-lan", "bad${nul}id" to "pw"))
        assertEquals(listOf("ok"), v.document.credentials.keys.toList())
        assertEquals(1, v.invalidSecrets) // the bad id only
        assertEquals(1, v.invalidTotal)
    }

    @Test
    fun `the global budget of the secrets is spent on private keys first, then passphrases, passwords and certificates`() {
        // 64 KiB values: 256 of them fill the 16 MiB budget exactly.
        val value = "x".repeat(64 * 1024)
        val perSection = BackupLimits.MAX_SECRETS_TOTAL_BYTES / value.length
        assertEquals(256, perSection)
        val privateKeys = LinkedHashMap<String, String>()
        for (i in 0 until perSection - 2) privateKeys["k$i"] = value // all fit, two values of room left
        val credentials = linkedMapOf("c1" to value, "c2" to value, "c3" to value) // only the first fits
        val v = secrets(
            credentials = credentials,
            keyPassphrases = linkedMapOf("p-small" to "pp"),
            privateKeys = privateKeys,
            certificates = linkedMapOf("cert-big" to value, "cert-small" to "c"),
        )
        assertEquals(perSection - 2, v.document.privateKeys.size)
        assertEquals(listOf("p-small"), v.document.keyPassphrases.keys.toList())
        assertEquals(listOf("c1"), v.document.credentials.keys.toList())
        assertEquals(2, v.invalidSecrets)
        // The certificate that does not fit counts as a certificate; a smaller one that fits is kept.
        assertEquals(listOf("cert-small"), v.document.certificates.keys.toList())
        assertEquals(1, v.invalidCertificates)
        assertEquals(3, v.invalidTotal)
    }

    @Test
    fun `the invalid counters add up and the text form holds no content`() {
        val v = validOrFail(
            sampleDocument(snippets = listOf(sampleSnippet(id = "")), knownHosts = emptyList()).copy(
                hosts = listOf(baseHost.copy(port = 0)),
                sshKeys = listOf(baseKey.copy(id = "")),
                tunnels = listOf(baseTunnel),
                credentials = linkedMapOf("" to "FIXTURE-pw"),
            ),
        )
        assertEquals(1, v.invalidSnippets)
        assertEquals(1, v.invalidHosts)
        assertEquals(1, v.invalidSshKeys)
        assertEquals(1, v.invalidTunnels)
        assertEquals(1, v.invalidSecrets)
        assertEquals(5, v.invalidTotal)
        assertFalse(v.toString().contains("FIXTURE"))
    }
}
