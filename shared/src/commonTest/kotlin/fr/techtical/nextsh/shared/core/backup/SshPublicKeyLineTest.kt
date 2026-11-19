// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshPublicKeyLineTest {

    // Control and invisible characters are built from their code point, never written raw.
    private val nul = Char(0).toString()
    private val del = Char(0x7F).toString()
    private val c1 = Char(0x85).toString()
    private val rlo = Char(0x202E).toString()
    private val zwsp = Char(0x200B).toString()

    private val ed25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi user@laptop"

    @Test
    fun `legitimate lines are kept as they are`() {
        val lines = listOf(
            ed25519,
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ== deploy key",
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY= x",
            "sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29t yubikey",
            "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29t cert",
            "future-type@example.org AAAA",
            "ssh-ed25519 AAAA",
        )
        for (line in lines) assertEquals(line, SshPublicKeyLine.normalize(line), line)
    }

    @Test
    fun `an empty line stays empty`() {
        assertEquals("", SshPublicKeyLine.normalize(""))
        assertEquals("", SshPublicKeyLine.normalize("  \n\t "))
    }

    @Test
    fun `whitespace and line ends around the line are removed`() {
        assertEquals(ed25519, SshPublicKeyLine.normalize("  $ed25519 \r\n"))
        assertEquals(ed25519, SshPublicKeyLine.normalize("\n$ed25519\n"))
    }

    @Test
    fun `a control char inside becomes a space and the value holds on one line`() {
        assertEquals("ssh-ed25519 AAAA my key", SshPublicKeyLine.normalize("ssh-ed25519 AAAA my\tkey"))
        assertEquals("ssh-ed25519 AAAA a ssh-ed25519 BBBB b", SshPublicKeyLine.normalize("ssh-ed25519 AAAA a\nssh-ed25519 BBBB b"))
        assertEquals("ssh-ed25519 AAAA a b", SshPublicKeyLine.normalize("ssh-ed25519 AAAA a\rb"))
        assertEquals("ssh-ed25519 AAAA a b", SshPublicKeyLine.normalize("ssh-ed25519 AAAA a${c1}b"))
        assertEquals("ssh-ed25519 AAAA a b", SshPublicKeyLine.normalize("ssh-ed25519 AAAA a${del}b"))
        assertEquals("ssh-ed25519 AAAA a", SshPublicKeyLine.normalize("ssh-ed25519 AAAA a$nul"))
        val cleaned = SshPublicKeyLine.normalize("ssh-ed25519 AAAA a\nssh-rsa BBBB\r\nb")!!
        assertTrue(cleaned.none { it.isISOControl() })
    }

    @Test
    fun `a line over 64 KiB is refused, 64 KiB is accepted`() {
        assertEquals("x".repeat(64 * 1024), SshPublicKeyLine.normalize("x".repeat(64 * 1024)))
        assertNull(SshPublicKeyLine.normalize("x".repeat(64 * 1024 + 1)))
        assertNull(SshPublicKeyLine.normalize("x".repeat(5 * 1024 * 1024)))
        // Multi-byte chars count in UTF-8 bytes.
        assertNull(SshPublicKeyLine.normalize(Char(0xE9).toString().repeat(32 * 1024 + 1)))
    }

    @Test
    fun `the blob key ignores the comment and the separators`() {
        assertEquals("ssh-ed25519 AAAA", SshPublicKeyLine.blobKey("ssh-ed25519 AAAA comment one"))
        assertEquals("ssh-ed25519 AAAA", SshPublicKeyLine.blobKey("ssh-ed25519   AAAA"))
        assertEquals("ssh-ed25519 AAAA", SshPublicKeyLine.blobKey(" ssh-ed25519\tAAAA\tother"))
        assertEquals(
            SshPublicKeyLine.blobKey("ssh-ed25519 AAAA a"),
            SshPublicKeyLine.blobKey("ssh-ed25519 AAAA b"),
        )
        assertFalse(SshPublicKeyLine.blobKey("ssh-ed25519 AAAA a") == SshPublicKeyLine.blobKey("ssh-ed25519 BBBB a"))
        assertNull(SshPublicKeyLine.blobKey(""))
        assertNull(SshPublicKeyLine.blobKey("ssh-ed25519"))
        assertNull(SshPublicKeyLine.blobKey("   "))
    }

    @Test
    fun `the comment is everything after the second field, cleaned`() {
        assertEquals("user@laptop", SshPublicKeyLine.comment(ed25519))
        assertEquals("two words", SshPublicKeyLine.comment("ssh-ed25519 AAAA two words "))
        assertEquals("a b", SshPublicKeyLine.comment("ssh-ed25519 AAAA a${rlo}b"))
        assertEquals("a b", SshPublicKeyLine.comment("ssh-ed25519 AAAA a${zwsp}b"))
        assertNull(SshPublicKeyLine.comment("ssh-ed25519 AAAA"))
        assertNull(SshPublicKeyLine.comment("ssh-ed25519 AAAA   "))
        assertNull(SshPublicKeyLine.comment("ssh-ed25519"))
        assertNull(SshPublicKeyLine.comment(""))
    }

    @Test
    fun `a built line has one line and a default comment`() {
        assertEquals("ssh-ed25519 AAAA nextsh-imported", SshPublicKeyLine.build("ssh-ed25519", "AAAA", null))
        assertEquals("ssh-ed25519 AAAA nextsh-imported", SshPublicKeyLine.build("ssh-ed25519", "AAAA", " \t "))
        assertEquals("ssh-ed25519 AAAA my key", SshPublicKeyLine.build("ssh-ed25519", "AAAA", "my\tkey"))
        assertEquals("ssh-ed25519 AAAA a b", SshPublicKeyLine.build("ssh-ed25519", "AAAA", "a\nb"))
        val long = SshPublicKeyLine.build("ssh-ed25519", "AAAA", "y".repeat(10_000))
        assertEquals("ssh-ed25519 AAAA ".length + BackupLimits.MAX_FREE_TEXT_LENGTH, long.length)
        assertEquals(SshPublicKeyLine.normalize(long), long)
    }

    @Test
    fun `wire type names map to the stored key types`() {
        assertEquals(SshKeyType.ED25519, SshPublicKeyLine.keyTypeOf("ssh-ed25519"))
        assertEquals(SshKeyType.RSA_4096, SshPublicKeyLine.keyTypeOf("ssh-rsa"))
        assertEquals(SshKeyType.ECDSA_256, SshPublicKeyLine.keyTypeOf("ecdsa-sha2-nistp256"))
        assertEquals(SshKeyType.ECDSA_384, SshPublicKeyLine.keyTypeOf("ecdsa-sha2-nistp384"))
        assertEquals(SshKeyType.ECDSA_521, SshPublicKeyLine.keyTypeOf("ecdsa-sha2-nistp521"))
        assertEquals(SshKeyType.SK_ED25519, SshPublicKeyLine.keyTypeOf("sk-ssh-ed25519@openssh.com"))
        assertEquals(SshKeyType.SK_ECDSA_256, SshPublicKeyLine.keyTypeOf("sk-ecdsa-sha2-nistp256@openssh.com"))
        assertNull(SshPublicKeyLine.keyTypeOf("ssh-dss"))
        assertNull(SshPublicKeyLine.keyTypeOf("SSH-ED25519"))
    }

    // ---- SshKeyRules ----

    private val key = SshKey(id = "k1", label = "Laptop", keyType = SshKeyType.ED25519, publicKey = ed25519)

    @Test
    fun `a stored key is cleaned, not refused, for its label, public key and FIDO2 relying party`() {
        val cleaned = SshKeyRules.normalizeForStorage(
            key.copy(label = "my\tkey", publicKey = "$ed25519\nssh-rsa BBBB other\n", fido2CredentialId = "Y3JlZA==", fido2RpId = "ssh:\nx"),
        )!!
        assertEquals("my key", cleaned.label)
        assertEquals("$ed25519 ssh-rsa BBBB other", cleaned.publicKey)
        assertEquals("Y3JlZA==", cleaned.fido2CredentialId)
        assertEquals("ssh: x", cleaned.fido2RpId)
        assertEquals(key, SshKeyRules.normalizeForStorage(key))
    }

    @Test
    fun `a stored key is refused for its id, its size, its alias or its FIDO2 credential id`() {
        assertNull(SshKeyRules.normalizeForStorage(key.copy(id = "")))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(id = "x".repeat(129))))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(id = "a${nul}b")))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(publicKey = "x".repeat(64 * 1024 + 1))))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(keystoreAlias = "a".repeat(257))))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(keystoreAlias = "a${nul}b")))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(fido2CredentialId = "cred\nid")))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(fido2CredentialId = "c".repeat(4097))))
        assertNull(SshKeyRules.normalizeForStorage(key.copy(fido2RpId = "r".repeat(4097))))
        assertEquals("r".repeat(4096), SshKeyRules.normalizeForStorage(key.copy(fido2RpId = "r".repeat(4096)))?.fido2RpId)
    }

    @Test
    fun `hardware keys are the biometric and the sk keys`() {
        assertFalse(SshKeyRules.isHardwareKey(key))
        assertFalse(SshKeyRules.isHardwareKey(key.copy(keyType = SshKeyType.RSA_4096)))
        assertTrue(SshKeyRules.isHardwareKey(key.copy(isBiometric = true, keyType = SshKeyType.ECDSA_256)))
        assertTrue(SshKeyRules.isHardwareKey(key.copy(keyType = SshKeyType.SK_ED25519)))
        assertTrue(SshKeyRules.isHardwareKey(key.copy(keyType = SshKeyType.SK_ECDSA_256)))
    }
}
