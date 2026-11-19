// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The full decision table of [BackupKeyRules]. Key material is plain text here (the rules never read a key):
 * `KEY-D` is the key proven from the file's private key, `KEY-L` the local one, `KEY-T` a third party's.
 */
class BackupKeyRulesTest {

    private val proven = SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-D")
    private val lineD = "ssh-ed25519 KEY-D file-comment"
    private val lineL = "ssh-ed25519 KEY-L local-comment"
    private val lineT = "ssh-ed25519 KEY-T third-party"

    private val local = SshKey(id = "k", label = "Local label", keyType = SshKeyType.ED25519, publicKey = lineL)
    private val file = SshKey(id = "k", label = "File label", keyType = SshKeyType.ED25519, publicKey = lineD)

    private fun decide(
        incoming: SshKey? = file,
        local: SshKey? = this.local,
        filePrivateKey: Boolean = true,
        filePassphrase: Boolean = true,
        localPrivateKey: LocalPrivateKey = LocalPrivateKey.ABSENT,
        derivation: SshPublicKeyDerivation? = proven,
    ) = BackupKeyRules.decide(incoming, local, filePrivateKey, filePassphrase, localPrivateKey, derivation)

    // ---- no live local key: the file wins ----

    @Test
    fun `a new key whose line matches its private key is taken as is`() {
        val d = decide(local = null)
        assertSame(file, d.key)
        assertTrue(d.writePrivateKey)
        assertTrue(d.writePassphrase)
        assertFalse(d.localKeyKept)
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `a new key whose line is another key gets the proven line and is counted`() {
        val d = decide(incoming = file.copy(publicKey = lineT), local = null)
        assertEquals("ssh-ed25519 KEY-D third-party", d.key?.publicKey) // the comment of the file is kept
        assertEquals("File label", d.key?.label)
        assertTrue(d.writePrivateKey)
        assertTrue(d.publicKeyRecomputed)
        assertFalse(d.localKeyKept)
    }

    @Test
    fun `a new key with an empty line is filled silently`() {
        val d = decide(incoming = file.copy(publicKey = "", keyType = SshKeyType.RSA_4096), local = null)
        assertEquals("ssh-ed25519 KEY-D nextsh-imported", d.key?.publicKey)
        assertEquals(SshKeyType.ED25519, d.key?.keyType)
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `a new key without a proven derivation is taken as is`() {
        for (derivation in listOf(
            null,
            SshPublicKeyDerivation.NeedsPassphrase,
            SshPublicKeyDerivation.Unsupported,
            SshPublicKeyDerivation.UnsupportedEncryption,
            SshPublicKeyDerivation.Unreadable,
            // TooCostly is not here: its private key is never written (tests below).
        )) {
            val d = decide(incoming = file.copy(publicKey = lineT), local = null, derivation = derivation)
            assertEquals(lineT, d.key?.publicKey, "$derivation")
            assertTrue(d.writePrivateKey)
            assertFalse(d.publicKeyRecomputed)
        }
    }

    @Test
    fun `a new hardware row of the file is never rewritten by a derivation`() {
        val sk = file.copy(keyType = SshKeyType.SK_ED25519, publicKey = "sk-ssh-ed25519@openssh.com KEY-SK", fido2CredentialId = "Y3JlZA==")
        val d = decide(incoming = sk, local = null)
        assertSame(sk, d.key)
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `a private key of the file without a row and without a local key is written`() {
        val d = decide(incoming = null, local = null)
        assertNull(d.key)
        assertTrue(d.writePrivateKey)
        assertTrue(d.writePassphrase)
    }

    // ---- a local hardware key keeps everything ----

    @Test
    fun `a local biometric key is only renamed and never gets a private key of the file`() {
        val bio = local.copy(keyType = SshKeyType.ECDSA_256, publicKey = "ecdsa-sha2-nistp256 KEY-BIO x", isBiometric = true, keystoreAlias = "nextsh_bio_k")
        val d = decide(local = bio)
        val key = d.key!!
        assertEquals("File label", key.label)
        assertEquals(bio.publicKey, key.publicKey)
        assertEquals(SshKeyType.ECDSA_256, key.keyType)
        assertTrue(key.isBiometric)
        assertEquals("nextsh_bio_k", key.keystoreAlias)
        assertFalse(d.writePrivateKey)
        assertFalse(d.writePassphrase)
        assertTrue(d.localKeyKept)
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `a local FIDO2 key keeps its public key and ids, the same key is not counted`() {
        val sk = local.copy(keyType = SshKeyType.SK_ED25519, publicKey = "sk-ssh-ed25519@openssh.com KEY-SK local", fido2CredentialId = "bG9jYWw=", fido2RpId = "ssh:")
        val same = decide(
            incoming = file.copy(keyType = SshKeyType.SK_ED25519, publicKey = "sk-ssh-ed25519@openssh.com KEY-SK other", fido2CredentialId = "ZmlsZQ==", fido2RpId = "x"),
            local = sk,
            filePrivateKey = false,
            filePassphrase = false,
            derivation = null,
        )
        assertEquals(sk.publicKey, same.key?.publicKey)
        assertEquals("bG9jYWw=", same.key?.fido2CredentialId)
        assertEquals("ssh:", same.key?.fido2RpId)
        assertEquals("File label", same.key?.label)
        assertFalse(same.localKeyKept)

        val other = decide(incoming = file.copy(publicKey = lineT), local = sk, filePrivateKey = false, derivation = null)
        assertEquals(sk.publicKey, other.key?.publicKey)
        assertTrue(other.localKeyKept)
        assertFalse(other.writePassphrase)
    }

    // ---- a local software key with a public line ----

    @Test
    fun `without a private key in the file the local key material stays and the label comes from the file`() {
        val d = decide(incoming = file.copy(publicKey = "ssh-ed25519 KEY-L another-comment"), filePrivateKey = false, derivation = null)
        assertEquals(lineL, d.key?.publicKey)
        assertEquals("File label", d.key?.label)
        assertFalse(d.localKeyKept) // same key, only the comment differs
        assertFalse(d.writePrivateKey)
    }

    @Test
    fun `without a private key in the file a third party public key is refused and counted`() {
        val withLocalPrivate = decide(
            incoming = file.copy(publicKey = lineT, keyType = SshKeyType.RSA_4096, fido2RpId = "x"),
            filePrivateKey = false,
            localPrivateKey = LocalPrivateKey.PRESENT,
            derivation = null,
        )
        assertEquals(lineL, withLocalPrivate.key?.publicKey)
        assertEquals(SshKeyType.ED25519, withLocalPrivate.key?.keyType)
        assertNull(withLocalPrivate.key?.fido2RpId)
        assertTrue(withLocalPrivate.localKeyKept)
        assertFalse(withLocalPrivate.writePassphrase) // the passphrase of the file is not the local key's
        assertFalse(withLocalPrivate.publicKeyRecomputed)

        val withoutLocalPrivate = decide(incoming = file.copy(publicKey = lineT), filePrivateKey = false, derivation = null)
        assertEquals(lineL, withoutLocalPrivate.key?.publicKey)
        assertTrue(withoutLocalPrivate.localKeyKept)
        assertTrue(withoutLocalPrivate.writePassphrase) // no local private key to protect
    }

    @Test
    fun `a local private key is never replaced by the file's one`() {
        val d = decide(incoming = file.copy(publicKey = lineT), localPrivateKey = LocalPrivateKey.PRESENT)
        assertFalse(d.writePrivateKey)
        assertFalse(d.writePassphrase)
        assertEquals(lineL, d.key?.publicKey)
        assertTrue(d.localKeyKept)
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `the same private key as the local one is written and the line stays when it is right`() {
        val sameKey = SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-L")
        val d = decide(incoming = file.copy(publicKey = lineL), localPrivateKey = LocalPrivateKey.SAME_AS_FILE, derivation = sameKey)
        assertTrue(d.writePrivateKey)
        assertTrue(d.writePassphrase)
        assertEquals(lineL, d.key?.publicKey)
        assertFalse(d.localKeyKept)
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `a wrong local line is repaired by the proven key of the same private key`() {
        val d = decide(incoming = file.copy(publicKey = lineL), localPrivateKey = LocalPrivateKey.SAME_AS_FILE)
        assertEquals("ssh-ed25519 KEY-D local-comment", d.key?.publicKey)
        assertTrue(d.writePrivateKey)
        assertTrue(d.publicKeyRecomputed)
        assertFalse(d.localKeyKept)
    }

    @Test
    fun `a private key written to an empty vault slot replaces the line only once proven`() {
        // Proven: the line becomes the proven key, with the software flags.
        val provenCase = decide(incoming = file.copy(publicKey = lineT, fido2RpId = "x"), localPrivateKey = LocalPrivateKey.ABSENT)
        assertEquals("ssh-ed25519 KEY-D third-party", provenCase.key?.publicKey)
        assertEquals(SshKeyType.ED25519, provenCase.key?.keyType)
        assertNull(provenCase.key?.fido2RpId)
        assertFalse(provenCase.key!!.isBiometric)
        assertTrue(provenCase.writePrivateKey)
        assertTrue(provenCase.publicKeyRecomputed)

        // The file's line is the proven key: taken with its comment, the local line was wrong.
        val fileRight = decide(localPrivateKey = LocalPrivateKey.ABSENT)
        assertEquals(lineD, fileRight.key?.publicKey)
        assertTrue(fileRight.publicKeyRecomputed)

        // Not proven (encrypted at import): written, the local line stays.
        val notProven = decide(incoming = file.copy(publicKey = lineL), derivation = SshPublicKeyDerivation.NeedsPassphrase)
        assertTrue(notProven.writePrivateKey)
        assertTrue(notProven.writePassphrase)
        assertEquals(lineL, notProven.key?.publicKey)
        assertFalse(notProven.localKeyKept)
        assertFalse(notProven.publicKeyRecomputed)

        // Not proven and the file claims another key: nothing says the private key goes with the local line,
        // so the empty slot is treated as taken. Not written, the local line stays, and it is counted.
        for (derivation in listOf(null, SshPublicKeyDerivation.NeedsPassphrase, SshPublicKeyDerivation.Unreadable)) {
            val otherClaim = decide(incoming = file.copy(publicKey = lineT), derivation = derivation)
            assertFalse(otherClaim.writePrivateKey, "$derivation")
            assertFalse(otherClaim.writePassphrase, "$derivation")
            assertEquals(lineL, otherClaim.key?.publicKey)
            assertTrue(otherClaim.localKeyKept)
            assertFalse(otherClaim.publicKeyRecomputed)
        }
    }

    @Test
    fun `an unproven private key with no line in the file is not written next to a local line and not counted`() {
        val d = decide(incoming = file.copy(publicKey = ""), derivation = SshPublicKeyDerivation.NeedsPassphrase)
        assertFalse(d.writePrivateKey)
        assertFalse(d.writePassphrase)
        assertEquals(lineL, d.key?.publicKey)
        assertFalse(d.localKeyKept) // the file offered no other key for sure
        assertFalse(d.publicKeyRecomputed)
    }

    @Test
    fun `a right local line next to a wrong line of the file counts nothing`() {
        val sameKey = SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-L")
        val d = decide(incoming = file.copy(publicKey = lineT), localPrivateKey = LocalPrivateKey.ABSENT, derivation = sameKey)
        assertTrue(d.writePrivateKey)
        assertEquals(lineL, d.key?.publicKey) // the local line is the proven key
        assertFalse(d.publicKeyRecomputed)
        assertFalse(d.localKeyKept)
    }

    @Test
    fun `the real key behind an inconsistent file counts as proven`() {
        val d = decide(incoming = file.copy(publicKey = lineL), derivation = SshPublicKeyDerivation.Inconsistent(proven))
        assertEquals("ssh-ed25519 KEY-D local-comment", d.key?.publicKey)
        assertTrue(d.publicKeyRecomputed)
    }

    @Test
    fun `a derivation is ignored when the file has no private key`() {
        val d = decide(incoming = file.copy(publicKey = lineT), filePrivateKey = false)
        assertEquals(lineL, d.key?.publicKey)
        assertFalse(d.publicKeyRecomputed)
    }

    // ---- a local software key without a public line (host imports) ----

    private val emptyLocal = local.copy(publicKey = "")

    @Test
    fun `an empty local line takes the file's key with the file's private key`() {
        val provenCase = decide(local = emptyLocal, incoming = file.copy(publicKey = lineT))
        assertEquals("ssh-ed25519 KEY-D third-party", provenCase.key?.publicKey)
        assertTrue(provenCase.publicKeyRecomputed) // the file's line was wrong

        val notProven = decide(local = emptyLocal, incoming = file.copy(publicKey = lineT), derivation = null)
        assertEquals(lineT, notProven.key?.publicKey)
        assertTrue(notProven.writePrivateKey)
        assertFalse(notProven.localKeyKept)
    }

    @Test
    fun `an empty local line without any private key takes the file's line`() {
        val d = decide(local = emptyLocal, incoming = file.copy(publicKey = lineT), filePrivateKey = false, derivation = null)
        assertEquals(lineT, d.key?.publicKey)
        assertFalse(d.localKeyKept)
    }

    @Test
    fun `an empty local line next to a local private key is never paired with an unproven line`() {
        val noFilePrivate = decide(
            local = emptyLocal,
            incoming = file.copy(publicKey = lineT),
            filePrivateKey = false,
            localPrivateKey = LocalPrivateKey.PRESENT,
            derivation = null,
        )
        assertEquals("", noFilePrivate.key?.publicKey)
        assertEquals("File label", noFilePrivate.key?.label)
        assertFalse(noFilePrivate.writePassphrase)
        assertFalse(noFilePrivate.localKeyKept)

        val otherFilePrivate = decide(local = emptyLocal, incoming = file.copy(publicKey = lineT), localPrivateKey = LocalPrivateKey.PRESENT)
        assertEquals("", otherFilePrivate.key?.publicKey)
        assertFalse(otherFilePrivate.writePrivateKey)
        assertTrue(otherFilePrivate.localKeyKept)
    }

    // ---- secrets without a row in the file ----

    @Test
    fun `a private key of the file without a row never replaces a local one`() {
        val d = decide(incoming = null, localPrivateKey = LocalPrivateKey.PRESENT)
        assertNull(d.key)
        assertFalse(d.writePrivateKey)
        assertFalse(d.writePassphrase)
        assertTrue(d.localKeyKept)
    }

    @Test
    fun `a private key of the file without a row repairs the local line only when proven`() {
        val repaired = decide(incoming = null, localPrivateKey = LocalPrivateKey.ABSENT)
        assertEquals("ssh-ed25519 KEY-D local-comment", repaired.key?.publicKey)
        assertEquals("Local label", repaired.key?.label)
        assertTrue(repaired.writePrivateKey)
        assertTrue(repaired.publicKeyRecomputed)

        // Not proven: nothing ties it to the local line, so it is not written (and not counted).
        val unchanged = decide(incoming = null, localPrivateKey = LocalPrivateKey.ABSENT, derivation = null)
        assertNull(unchanged.key)
        assertFalse(unchanged.writePrivateKey)
        assertFalse(unchanged.localKeyKept)

        val sameKey = SshPublicKeyDerivation.Derived(SshKeyType.ED25519, "ssh-ed25519", "KEY-L")
        assertNull(decide(incoming = null, derivation = sameKey).key)
    }

    // ---- passphrase follows its private key ----

    @Test
    fun `a passphrase is written with its private key or when the device has none`() {
        assertTrue(decide(filePrivateKey = true, localPrivateKey = LocalPrivateKey.ABSENT).writePassphrase)
        assertTrue(decide(filePrivateKey = true, localPrivateKey = LocalPrivateKey.SAME_AS_FILE).writePassphrase)
        assertFalse(decide(filePrivateKey = true, localPrivateKey = LocalPrivateKey.PRESENT).writePassphrase)
        assertTrue(decide(filePrivateKey = false, localPrivateKey = LocalPrivateKey.ABSENT, derivation = null).writePassphrase)
        assertFalse(decide(filePrivateKey = false, localPrivateKey = LocalPrivateKey.PRESENT, derivation = null).writePassphrase)
        assertFalse(decide(filePassphrase = false).writePassphrase)
    }

    // ---- helpers ----

    @Test
    fun `the derivation is asked only where it can change the outcome`() {
        val bio = local.copy(isBiometric = true)
        val sk = file.copy(keyType = SshKeyType.SK_ECDSA_256)
        assertTrue(BackupKeyRules.shouldDerive(file, null, true, LocalPrivateKey.ABSENT))
        // Written whatever the file's row: derived too, so that a key too costly to open is never written.
        assertTrue(BackupKeyRules.shouldDerive(sk, null, true, LocalPrivateKey.ABSENT))
        assertTrue(BackupKeyRules.shouldDerive(null, null, true, LocalPrivateKey.ABSENT))
        assertFalse(BackupKeyRules.shouldDerive(file, null, false, LocalPrivateKey.ABSENT))
        assertTrue(BackupKeyRules.shouldDerive(file, local, true, LocalPrivateKey.ABSENT))
        assertTrue(BackupKeyRules.shouldDerive(null, local, true, LocalPrivateKey.SAME_AS_FILE))
        assertFalse(BackupKeyRules.shouldDerive(file, local, true, LocalPrivateKey.PRESENT))
        assertFalse(BackupKeyRules.shouldDerive(file, bio, true, LocalPrivateKey.ABSENT))
        assertFalse(BackupKeyRules.shouldDerive(file, local, false, LocalPrivateKey.ABSENT))
    }

    @Test
    fun `the derivation is asked in every case where the private key of the file could be written`() {
        val rows = listOf(null, file, file.copy(publicKey = ""), file.copy(keyType = SshKeyType.SK_ED25519))
        val locals = listOf(null, local, local.copy(publicKey = ""), local.copy(isBiometric = true))
        val derivations = listOf(null, proven, SshPublicKeyDerivation.NeedsPassphrase, SshPublicKeyDerivation.UnsupportedEncryption)
        for ((i, incoming) in rows.withIndex()) for ((j, l) in locals.withIndex()) for (state in LocalPrivateKey.entries) {
            for (derivation in derivations) {
                val d = BackupKeyRules.decide(incoming, l, true, true, state, derivation)
                if (d.writePrivateKey) {
                    assertTrue(BackupKeyRules.shouldDerive(incoming, l, true, state), "row $i, local $j, $state, $derivation")
                }
            }
        }
    }

    // ---- a private key too costly to open ----

    @Test
    fun `a private key too costly to open is never written on a blank device, nor its passphrase`() {
        val d = decide(local = null, derivation = SshPublicKeyDerivation.TooCostly)
        assertFalse(d.writePrivateKey)
        assertFalse(d.writePassphrase)
        assertSame(file, d.key) // the row is restored, as if the file had no private key
        assertFalse(d.localKeyKept)
        assertFalse(d.publicKeyRecomputed)

        val withoutRow = decide(incoming = null, local = null, derivation = SshPublicKeyDerivation.TooCostly)
        assertFalse(withoutRow.writePrivateKey)
        assertFalse(withoutRow.writePassphrase)
        assertNull(withoutRow.key)
    }

    @Test
    fun `a private key too costly to open is never written next to a local line, nor its passphrase`() {
        val sameLine = decide(incoming = file.copy(publicKey = lineL), localPrivateKey = LocalPrivateKey.ABSENT, derivation = SshPublicKeyDerivation.TooCostly)
        assertFalse(sameLine.writePrivateKey)
        assertFalse(sameLine.writePassphrase)
        assertEquals(lineL, sameLine.key?.publicKey)
        assertFalse(sameLine.localKeyKept) // the refused private key is not counted
        assertFalse(sameLine.publicKeyRecomputed)

        // Another public line still counts by the rule of the public line, as without a private key.
        val otherLine = decide(incoming = file.copy(publicKey = lineT), localPrivateKey = LocalPrivateKey.ABSENT, derivation = SshPublicKeyDerivation.TooCostly)
        assertFalse(otherLine.writePrivateKey)
        assertFalse(otherLine.writePassphrase)
        assertEquals(lineL, otherLine.key?.publicKey)
        assertTrue(otherLine.localKeyKept)

        // Without a local line either: the file's line, no secret.
        val emptyLine = decide(local = local.copy(publicKey = ""), incoming = file.copy(publicKey = lineT), derivation = SshPublicKeyDerivation.TooCostly)
        assertFalse(emptyLine.writePrivateKey)
        assertFalse(emptyLine.writePassphrase)
        assertEquals(lineT, emptyLine.key?.publicKey)

        // An unsupported encryption is bounded in cost: written, unproven, as before.
        val unsupported = decide(incoming = file.copy(publicKey = lineL), derivation = SshPublicKeyDerivation.UnsupportedEncryption)
        assertTrue(unsupported.writePrivateKey)
        assertTrue(unsupported.writePassphrase)
    }

    @Test
    fun `the local private key state ignores line ends and surrounding whitespace`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\nBBBB\n-----END OPENSSH PRIVATE KEY-----\n"
        val crlf = pem.replace("\n", "\r\n")
        assertEquals(LocalPrivateKey.ABSENT, BackupKeyRules.localPrivateKeyState(null, pem))
        assertEquals(LocalPrivateKey.ABSENT, BackupKeyRules.localPrivateKeyState(null, null))
        assertEquals(LocalPrivateKey.SAME_AS_FILE, BackupKeyRules.localPrivateKeyState(pem, pem))
        assertEquals(LocalPrivateKey.SAME_AS_FILE, BackupKeyRules.localPrivateKeyState(crlf, "  $pem  "))
        assertEquals(LocalPrivateKey.PRESENT, BackupKeyRules.localPrivateKeyState(pem, pem.replace("BBBB", "CCCC")))
        assertEquals(LocalPrivateKey.PRESENT, BackupKeyRules.localPrivateKeyState(pem, null))
    }

    @Test
    fun `the text form of a decision prints no key`() {
        val text = decide().toString()
        assertFalse(text.contains("KEY-"))
        assertFalse(text.contains("label"))
    }
}
