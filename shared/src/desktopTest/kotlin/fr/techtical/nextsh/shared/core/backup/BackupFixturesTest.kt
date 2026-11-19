// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.AuthType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads the shared fixtures of `testdata/backup/` and checks the counters written in its README.
 * The platform tests read the same files (encrypted as `.nextsh`) and expect the same numbers.
 */
class BackupFixturesTest {

    private fun decodeFixture(name: String): BackupDecodeResult = BackupCodec.decode(BackupFixtures.read(name))

    private fun validated(name: String): ValidatedBackup =
        validOrFail(decodeFixture(name).decodedOrFail().document) { KnownHostPortable.fromPortable(it.keyType, it.publicKey) != null }

    @Test
    fun `v1 without version`() {
        val decoded = decodeFixture("v1-sans-version.json").decodedOrFail()
        val d = decoded.document
        assertEquals(1, d.version)
        assertEquals(2, d.hosts.size)
        assertEquals(1, d.sshKeys.size)
        assertEquals(1, d.tunnels.size)
        assertEquals(1, d.credentials.size)
        assertEquals(1, d.privateKeys.size)
        assertEquals(0, d.keyPassphrases.size)
        assertEquals(0, d.certificates.size)
        assertTrue(d.snippets.isEmpty() && d.customThemes.isEmpty() && d.knownHosts.isEmpty())
        assertEquals(null, d.settings)
        assertFalse(decoded.hasUnknownSections)
        assertEquals("DRACULA", d.hosts[1].terminalTheme)
    }

    @Test
    fun `v2 complete`() {
        val decoded = decodeFixture("v2-complet.json").decodedOrFail()
        val d = decoded.document
        assertEquals(2, d.version)
        assertEquals(3, d.hosts.size)
        assertEquals(AuthType.CERTIFICATE, d.hosts[2].authType)
        assertEquals(1, d.sshKeys.size)
        assertEquals(2, d.tunnels.size)
        assertEquals(2, d.credentials.size)
        assertEquals(1, d.privateKeys.size)
        assertEquals(1, d.keyPassphrases.size)
        assertEquals(1, d.certificates.size)
        assertEquals(3, d.snippets.size)
        assertEquals(1, d.customThemes.size)
        assertEquals(5, d.knownHosts.size)
        assertEquals("DESKTOP", d.producer?.platform)
        assertFalse(decoded.hasUnknownSections)
        // The accent and the control characters of the commands survive.
        assertTrue(d.snippets[0].label.contains(Char(0xE9)))
        assertEquals("df -h\n\tdu -sh /var/log", d.snippets[1].command)
        // The host that uses the custom theme points at it.
        assertEquals(d.customThemes[0].id, d.hosts[2].terminalTheme)
    }

    @Test
    fun `v2 complete is fully valid and every known host is readable by SSHJ`() {
        val v = validated("v2-complet.json")
        assertEquals(0, v.invalidTotal)
        assertEquals(3, v.document.snippets.size)
        assertEquals(1, v.document.customThemes.size)
        assertEquals(5, v.document.knownHosts.size)
        val settings = assertNotNull(v.document.settings)
        assertEquals(5, settings.countFor(BackupPlatform.ANDROID))
        assertEquals(3, settings.countFor(BackupPlatform.DESKTOP))
        assertEquals(
            setOf("ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"),
            v.document.knownHosts.map { it.keyType }.toSet(),
        )
    }

    @Test
    fun `v2 complete survives an encode and decode round trip`() {
        val original = decodeFixture("v2-complet.json").decodedOrFail().document
        val again = BackupCodec.decode(BackupCodec.encode(original)).decodedOrFail().document
        assertEquals(original, again)
    }

    @Test
    fun `v2 with unknown sections imports the known part and flags the rest`() {
        val decoded = decodeFixture("v2-sections-inconnues.json").decodedOrFail()
        assertTrue(decoded.hasUnknownSections)
        assertEquals(setOf("futureSection", "anotherFutureSection"), decoded.unknownTopLevelKeys)
        assertEquals(1, decoded.document.hosts.size)
        assertEquals(1, decoded.document.credentials.size)
        assertEquals(1, decoded.document.snippets.size)
        assertEquals(0, validated("v2-sections-inconnues.json").invalidTotal)
    }

    @Test
    fun `v1 with an invalid core is reduced to its valid entries and every rule is counted`() {
        val decoded = decodeFixture("v1-coeur-invalide.json").decodedOrFail()
        val d = decoded.document
        assertEquals(1, d.version)
        assertEquals(11, d.hosts.size)
        assertEquals(5, d.sshKeys.size)
        assertEquals(7, d.tunnels.size)
        assertEquals(5, d.credentials.size)
        assertEquals(3, d.privateKeys.size)
        assertEquals(2, d.keyPassphrases.size)

        val v = validated("v1-coeur-invalide.json")
        assertEquals(6, v.invalidHosts)
        assertEquals(3, v.invalidSshKeys)
        assertEquals(4, v.invalidTunnels)
        assertEquals(5, v.invalidSecrets)
        assertEquals(0, v.invalidSnippets + v.invalidThemes + v.invalidKnownHosts + v.invalidSettings + v.invalidCertificates)
        assertEquals(18, v.invalidTotal)

        val kept = v.document
        assertEquals(listOf("h-ok-1", "h-ok-2", "h-ok-3", "h-label-cleaned", "h-user-cleaned"), kept.hosts.map { it.id })
        assertEquals("Line break", kept.hosts[3].label) // a control char in a label is replaced, not refused
        assertEquals("ro ot", kept.hosts[4].username) // same for a user name: the host is kept
        assertEquals("Valid host", kept.hosts[0].label) // the first duplicate wins
        assertEquals(listOf("key-ok", "key-multiline"), kept.sshKeys.map { it.id })
        assertEquals("Valid key", kept.sshKeys[0].label)
        // A public key on two lines is cleaned to one line, the second key becomes comment text.
        assertEquals("ssh-ed25519 AAAA first ssh-ed25519 BBBB second", kept.sshKeys[1].publicKey)
        assertEquals(listOf("t-ok", "t-socks", "t-orphan"), kept.tunnels.map { it.id })
        assertEquals(listOf("cred-ok", "cred-empty"), kept.credentials.keys.toList())
        assertEquals(listOf("key-ok"), kept.privateKeys.keys.toList())
        assertEquals(listOf("key-ok"), kept.keyPassphrases.keys.toList())
    }

    @Test
    fun `v3 is refused as unsupported`() {
        val result = decodeFixture("v3.json")
        assertEquals(3, assertIs<BackupDecodeResult.UnsupportedVersion>(result).version)
    }
}
