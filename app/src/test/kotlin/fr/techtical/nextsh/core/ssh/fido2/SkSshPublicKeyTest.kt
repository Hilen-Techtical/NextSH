// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh.fido2

import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class SkSshPublicKeyTest {

    @Test
    fun `fromAuthorizedKeysLine returns null for invalid format`() {
        assertNull(SkSshPublicKey.fromAuthorizedKeysLine(""))
        assertNull(SkSshPublicKey.fromAuthorizedKeysLine("ssh-ed25519 AAAA..."))
        assertNull(SkSshPublicKey.fromAuthorizedKeysLine("invalid"))
    }

    @Test
    fun `fromAuthorizedKeysLine returns null for unsupported key type`() {
        assertNull(SkSshPublicKey.fromAuthorizedKeysLine("ssh-rsa AAAA... comment"))
    }

    @Test
    fun `fromBlob returns null for empty blob`() {
        assertNull(SkSshPublicKey.fromBlob(ByteArray(0)))
    }

    @Test
    fun `fromBlob returns null for malformed blob`() {
        assertNull(SkSshPublicKey.fromBlob(byteArrayOf(0, 0, 0, 1, 42)))
    }

    @Test
    fun `toBlob and fromBlob roundtrip for SK_ECDSA_256`() {
        val keyData = ByteArray(65) { (it % 256).toByte() } // fake EC point
        val original = SkSshPublicKey(SkKeyType.SK_ECDSA_256, keyData, "ssh:")

        val blob = original.toBlob()
        val parsed = SkSshPublicKey.fromBlob(blob)

        assertNotNull(parsed)
        assertEquals(SkKeyType.SK_ECDSA_256, parsed!!.keyType)
        assertArrayEquals(keyData, parsed.rawKeyData)
        assertEquals("ssh:", parsed.application)

        blob.fill(0) // cleanup
        original.close()
        parsed.close()
    }

    @Test
    fun `toBlob and fromBlob roundtrip for SK_ED25519`() {
        val keyData = ByteArray(32) { (it % 256).toByte() } // fake Ed25519 key
        val original = SkSshPublicKey(SkKeyType.SK_ED25519, keyData, "ssh:")

        val blob = original.toBlob()
        val parsed = SkSshPublicKey.fromBlob(blob)

        assertNotNull(parsed)
        assertEquals(SkKeyType.SK_ED25519, parsed!!.keyType)
        assertArrayEquals(keyData, parsed.rawKeyData)
        assertEquals("ssh:", parsed.application)

        blob.fill(0)
        original.close()
        parsed.close()
    }

    @Test
    fun `toAuthorizedKeysLine produces parseable output`() {
        val keyData = ByteArray(32) { (it % 256).toByte() }
        val key = SkSshPublicKey(SkKeyType.SK_ED25519, keyData, "ssh:")

        val line = key.toAuthorizedKeysLine()

        assertTrue(line.startsWith("sk-ssh-ed25519@openssh.com "))
        val parsed = SkSshPublicKey.fromAuthorizedKeysLine(line)
        assertNotNull(parsed)
        assertEquals(SkKeyType.SK_ED25519, parsed!!.keyType)

        key.close()
        parsed.close()
    }

    @Test
    fun `close wipes rawKeyData`() {
        val keyData = ByteArray(32) { 0xFF.toByte() }
        val key = SkSshPublicKey(SkKeyType.SK_ED25519, keyData, "ssh:")

        key.close()

        assertTrue(keyData.all { it == 0.toByte() })
    }
}
