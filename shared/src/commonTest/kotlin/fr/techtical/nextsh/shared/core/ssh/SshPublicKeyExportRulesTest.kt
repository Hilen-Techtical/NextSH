// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SshPublicKeyExportRulesTest {

    private val proven = SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-D")
    private val key = SshKey(id = "k", label = "Laptop", keyType = SshKeyType.ED25519, publicKey = "ssh-ed25519 KEY-D laptop")

    @Test
    fun `a stored line that matches its private key is copied as is`() {
        val export = assertIs<SshPublicKeyExport.Verified>(SshPublicKeyExportRules.resolve(key, proven))
        assertEquals("ssh-ed25519 KEY-D laptop", export.line)
    }

    @Test
    fun `a stored line of another key is replaced by the proven one, comment kept, row corrected`() {
        val wrong = key.copy(publicKey = "ssh-ed25519 KEY-T laptop", keyType = SshKeyType.RSA_4096)
        val export = assertIs<SshPublicKeyExport.Corrected>(SshPublicKeyExportRules.resolve(wrong, proven))
        assertEquals("ssh-ed25519 KEY-D laptop", export.line)
        assertEquals(export.line, export.correctedKey.publicKey)
        assertEquals(SshKeyType.ED25519, export.correctedKey.keyType)
        assertEquals("Laptop", export.correctedKey.label)
        assertTrue(export.hadStoredLine)
    }

    @Test
    fun `an empty stored line is filled from the proven key without the mismatch notice`() {
        val export = assertIs<SshPublicKeyExport.Corrected>(SshPublicKeyExportRules.resolve(key.copy(publicKey = ""), proven))
        assertEquals("ssh-ed25519 KEY-D nextsh-imported", export.line)
        assertFalse(export.hadStoredLine)
    }

    @Test
    fun `the real key of an inconsistent file is used`() {
        val export = SshPublicKeyExportRules.resolve(key.copy(publicKey = "ssh-ed25519 KEY-T x"), SshPublicKeyDerivation.Inconsistent(proven))
        assertEquals("ssh-ed25519 KEY-D x", assertIs<SshPublicKeyExport.Corrected>(export).line)
    }

    @Test
    fun `a private key that cannot be read gives the stored line with a notice`() {
        for (derivation in listOf(
            null,
            SshPublicKeyDerivation.NeedsPassphrase,
            SshPublicKeyDerivation.WrongPassphrase,
            SshPublicKeyDerivation.TooLarge,
            SshPublicKeyDerivation.TooCostly,
            SshPublicKeyDerivation.Unsupported,
            SshPublicKeyDerivation.UnsupportedEncryption, // like Unsupported for a copy
            SshPublicKeyDerivation.Unreadable,
        )) {
            val export = assertIs<SshPublicKeyExport.Unverified>(SshPublicKeyExportRules.resolve(key, derivation), "$derivation")
            assertEquals("ssh-ed25519 KEY-D laptop", export.line)
        }
        assertSame(SshPublicKeyExport.Unavailable, SshPublicKeyExportRules.resolve(key.copy(publicKey = " "), null))
    }

    @Test
    fun `a hardware key gives its stored line without a check`() {
        val sk = key.copy(keyType = SshKeyType.SK_ED25519, publicKey = "sk-ssh-ed25519@openssh.com KEY-SK yubikey ")
        assertEquals("sk-ssh-ed25519@openssh.com KEY-SK yubikey", assertIs<SshPublicKeyExport.Hardware>(SshPublicKeyExportRules.resolve(sk, proven)).line)
        val bio = key.copy(isBiometric = true, keyType = SshKeyType.ECDSA_256)
        assertIs<SshPublicKeyExport.Hardware>(SshPublicKeyExportRules.resolve(bio, null))
        assertSame(SshPublicKeyExport.Unavailable, SshPublicKeyExportRules.resolve(sk.copy(publicKey = ""), null))
    }

    @Test
    fun `a stored line on two lines never goes out as it is`() {
        val twoLines = "ssh-ed25519 KEY-D laptop\nssh-ed25519 KEY-T third-party"
        val stored = key.copy(publicKey = twoLines)
        val outputs = listOf(
            SshPublicKeyExportRules.resolve(stored, proven),
            SshPublicKeyExportRules.resolve(stored, null),
            SshPublicKeyExportRules.resolve(stored, SshPublicKeyDerivation.NeedsPassphrase),
            SshPublicKeyExportRules.resolve(stored.copy(keyType = SshKeyType.SK_ED25519), null),
            SshPublicKeyExportRules.resolve(stored.copy(publicKey = "ssh-ed25519 KEY-T x\nssh-ed25519 KEY-D y"), proven),
        )
        for (export in outputs) {
            val line = when (export) {
                is SshPublicKeyExport.Verified -> export.line
                is SshPublicKeyExport.Corrected -> export.line
                is SshPublicKeyExport.Unverified -> export.line
                is SshPublicKeyExport.Hardware -> export.line
                SshPublicKeyExport.Unavailable -> ""
            }
            assertTrue(line.isNotEmpty(), "$export")
            assertTrue(line.none { it.isISOControl() }, "$export")
        }
        // The proven key comes first; the rest of the old text can only be comment.
        assertEquals("ssh-ed25519 KEY-D laptop ssh-ed25519 KEY-T third-party", assertIs<SshPublicKeyExport.Verified>(outputs[0]).line)
        assertEquals("ssh-ed25519 KEY-D laptop ssh-ed25519 KEY-T third-party", assertIs<SshPublicKeyExport.Unverified>(outputs[1]).line)
        assertIs<SshPublicKeyExport.Hardware>(outputs[3])
        assertEquals("ssh-ed25519 KEY-D x ssh-ed25519 KEY-D y", assertIs<SshPublicKeyExport.Corrected>(outputs[4]).line)
    }

    @Test
    fun `a verified line is rebuilt from the proven key`() {
        val export = SshPublicKeyExportRules.resolve(key.copy(publicKey = "  ssh-ed25519   KEY-D \tlaptop  "), proven)
        assertEquals("ssh-ed25519 KEY-D laptop", assertIs<SshPublicKeyExport.Verified>(export).line)
    }

    @Test
    fun `the text forms print no key`() {
        val wrong = key.copy(publicKey = "ssh-ed25519 KEY-T laptop")
        for (export in listOf(
            SshPublicKeyExportRules.resolve(key, proven),
            SshPublicKeyExportRules.resolve(wrong, proven),
            SshPublicKeyExportRules.resolve(key, null),
        )) {
            assertFalse(export.toString().contains("KEY-"))
        }
        assertEquals("Derived", proven.toString())
        assertEquals("Inconsistent", SshPublicKeyDerivation.Inconsistent(proven).toString())
    }

    @Test
    fun `two derivations of the same key are equal`() {
        assertEquals(proven, SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-D"))
        assertEquals(proven.hashCode(), SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-D").hashCode())
        assertFalse(proven == SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-T"))
        assertEquals("ssh-ed25519 KEY-D", proven.blobKey)
        assertEquals("ssh-ed25519 KEY-D a b", proven.line("a\tb"))
    }
}
