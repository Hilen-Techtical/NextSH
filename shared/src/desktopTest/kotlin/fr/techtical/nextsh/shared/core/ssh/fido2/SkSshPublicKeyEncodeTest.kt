// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests unitaires pour [SkSshPublicKey.toOpenSshWireFormat] et
 * [SkSshPublicKey.toOpenSshPublicKeyString] : encodage sk-ssh-ed25519.
 *
 * Valide :
 *  1. Round-trip encode → decode (wire format).
 *  2. Structure octet par octet du wire format.
 *  3. Format de la chaîne OpenSSH complète.
 *  4. Validation de la taille de la clé brute.
 *  5. Cohérence avec [SkSshPublicKey.toBlob] (encodage doit être identique).
 */
class SkSshPublicKeyEncodeTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val ed25519KeyBytes = ByteArray(32) { (it * 7 + 13).toByte() }
    private val application = "ssh:"

    // ── Round-trip tests ──────────────────────────────────────────────────────

    @Test
    fun `toOpenSshWireFormat produces blob parseable by fromBlob`() {
        val wire = SkSshPublicKey.toOpenSshWireFormat(application, ed25519KeyBytes)
        val parsed = SkSshPublicKey.fromBlob(wire)

        assertNotNull(parsed, "fromBlob should parse the wire format produced by toOpenSshWireFormat")
        assertEquals(SkKeyType.SK_ED25519, parsed!!.keyType, "keyType mismatch")
        assertTrue(ed25519KeyBytes.contentEquals(parsed.rawKeyData), "rawKeyData mismatch")
        assertEquals(application, parsed.application, "application mismatch")

        parsed.close()
    }

    @Test
    fun `toOpenSshWireFormat round-trip with custom rpId`() {
        val customRpId = "ssh:nextsh.techtical.fr"
        val wire = SkSshPublicKey.toOpenSshWireFormat(customRpId, ed25519KeyBytes)
        val parsed = SkSshPublicKey.fromBlob(wire)

        assertNotNull(parsed)
        assertEquals(customRpId, parsed!!.application, "custom rpId must survive round-trip")
        parsed.close()
    }

    // ── Wire format structure ─────────────────────────────────────────────────

    @Test
    fun `toOpenSshWireFormat encodes correct SSH string structure`() {
        val wire = SkSshPublicKey.toOpenSshWireFormat(application, ed25519KeyBytes)

        // Manually parse the SSH wire format
        // Each "string" field is: 4-byte BE length + data
        var offset = 0

        fun readStr(): ByteArray {
            val len = ((wire[offset].toInt() and 0xFF) shl 24) or
                ((wire[offset + 1].toInt() and 0xFF) shl 16) or
                ((wire[offset + 2].toInt() and 0xFF) shl 8) or
                (wire[offset + 3].toInt() and 0xFF)
            offset += 4
            val data = wire.sliceArray(offset until offset + len)
            offset += len
            return data
        }

        val typeBytes = readStr()
        val keyBytes = readStr()
        val appBytes = readStr()

        assertEquals(
            "sk-ssh-ed25519@openssh.com",
            String(typeBytes, Charsets.UTF_8),
            "First field should be key type string",
        )
        assertTrue(
            ed25519KeyBytes.contentEquals(keyBytes),
            "Second field should be raw 32-byte Ed25519 key",
        )
        assertEquals(
            application,
            String(appBytes, Charsets.UTF_8),
            "Third field should be application string",
        )
        assertEquals(wire.size, offset, "Should have consumed all bytes in the wire format")
    }

    // ── Coherence with toBlob ─────────────────────────────────────────────────

    @Test
    fun `toOpenSshWireFormat produces same bytes as SkSshPublicKey toBlob`() {
        val key = SkSshPublicKey(SkKeyType.SK_ED25519, ed25519KeyBytes.copyOf(), application)
        val blob = key.toBlob()
        val wire = SkSshPublicKey.toOpenSshWireFormat(application, ed25519KeyBytes)

        assertTrue(blob.contentEquals(wire), "toOpenSshWireFormat must produce the same bytes as toBlob")

        blob.fill(0)
        key.close()
    }

    // ── toOpenSshPublicKeyString ──────────────────────────────────────────────

    @Test
    fun `toOpenSshPublicKeyString starts with correct key type`() {
        val line = SkSshPublicKey.toOpenSshPublicKeyString(application, ed25519KeyBytes)

        assertTrue(
            line.startsWith("sk-ssh-ed25519@openssh.com "),
            "Public key string should start with key type: $line",
        )
    }

    @Test
    fun `toOpenSshPublicKeyString is parseable by fromAuthorizedKeysLine`() {
        val line = SkSshPublicKey.toOpenSshPublicKeyString(application, ed25519KeyBytes, "test-comment")

        val parsed = SkSshPublicKey.fromAuthorizedKeysLine(line)
        assertNotNull(parsed, "fromAuthorizedKeysLine should parse the output of toOpenSshPublicKeyString")
        assertEquals(SkKeyType.SK_ED25519, parsed!!.keyType)
        assertTrue(ed25519KeyBytes.contentEquals(parsed.rawKeyData))
        assertEquals(application, parsed.application)

        parsed.close()
    }

    @Test
    fun `toOpenSshPublicKeyString default comment is nextsh-fido2`() {
        val line = SkSshPublicKey.toOpenSshPublicKeyString(application, ed25519KeyBytes)
        val parts = line.split(" ")
        assertEquals(3, parts.size, "Should have 3 space-separated parts: type base64 comment")
        assertEquals("nextsh-fido2", parts[2], "Default comment should be 'nextsh-fido2'")
    }

    @Test
    fun `toOpenSshPublicKeyString blob is valid Base64`() {
        val line = SkSshPublicKey.toOpenSshPublicKeyString(application, ed25519KeyBytes)
        val b64 = line.split(" ")[1]
        val decoded = Base64.getDecoder().decode(b64)
        assertTrue(decoded.isNotEmpty(), "Base64 blob should decode to non-empty bytes")
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `toOpenSshWireFormat throws for key not 32 bytes`() {
        assertFailsWith<IllegalArgumentException>("Should reject 31-byte key") {
            SkSshPublicKey.toOpenSshWireFormat(application, ByteArray(31))
        }
        assertFailsWith<IllegalArgumentException>("Should reject 33-byte key") {
            SkSshPublicKey.toOpenSshWireFormat(application, ByteArray(33))
        }
        assertFailsWith<IllegalArgumentException>("Should reject empty key") {
            SkSshPublicKey.toOpenSshWireFormat(application, ByteArray(0))
        }
    }

    @Test
    fun `toOpenSshWireFormat accepts exactly 32 bytes`() {
        val wire = SkSshPublicKey.toOpenSshWireFormat(application, ByteArray(32))
        assertTrue(wire.isNotEmpty())
    }
}
