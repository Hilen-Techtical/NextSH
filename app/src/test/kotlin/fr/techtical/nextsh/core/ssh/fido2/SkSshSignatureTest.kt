// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh.fido2

import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import net.schmizz.sshj.common.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SkSshSignatureTest {

    @Test
    fun `create validates FLAG_USER_PRESENT must be set`() {
        val rawSig = ByteArray(64) { 1 }
        assertThrows<IllegalArgumentException> {
            SkSshSignature.create(
                keyType = SkKeyType.SK_ECDSA_256,
                rawSignature = rawSig,
                flags = 0x00,
                counter = 1u,
            )
        }
    }

    @Test
    fun `create succeeds with FLAG_USER_PRESENT set`() {
        val rawSig = ByteArray(64) { 1 }
        val sig = SkSshSignature.create(
            keyType = SkKeyType.SK_ECDSA_256,
            rawSignature = rawSig,
            flags = SkSshSignature.FLAG_USER_PRESENT,
            counter = 42u,
        )

        assertEquals(SkKeyType.SK_ECDSA_256, sig.keyType)
        assertEquals(SkSshSignature.FLAG_USER_PRESENT, sig.flags)
        assertEquals(42u, sig.counter)
        sig.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `encodeInner produces correct wire format`() {
        val rawSig = byteArrayOf(1, 2, 3, 4)
        val sig = SkSshSignature.create(
            keyType = SkKeyType.SK_ED25519,
            rawSignature = rawSig,
            flags = 0x05, // USER_PRESENT + USER_VERIFIED
            counter = 256u,
        )

        val inner = sig.encodeInner()
        val buf = Buffer.PlainBuffer(inner)

        // string raw_signature
        val decodedSig = buf.readStringAsBytes()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decodedSig)

        // byte flags
        assertEquals(0x05.toByte(), buf.readByte())

        // uint32 counter
        assertEquals(256L, buf.readUInt32())

        inner.fill(0)
        sig.close()
    }

    @Test
    fun `encodeSignatureBlob produces the PROTOCOL u2f layout for ed25519`() {
        val rawSig = ByteArray(64) { it.toByte() }
        val sig = SkSshSignature.create(SkKeyType.SK_ED25519, rawSig.copyOf(), 0x05, 256u)

        val blob = sig.encodeSignatureBlob()
        val buf = Buffer.PlainBuffer(blob)

        assertEquals("sk-ssh-ed25519@openssh.com", buf.readString())
        assertArrayEquals(rawSig, buf.readStringAsBytes())
        assertEquals(0x05.toByte(), buf.readByte())
        assertEquals(256L, buf.readUInt32())
        assertEquals(0, buf.available())
        sig.close()
    }

    @Test
    fun `encodeSignatureBlob converts the ecdsa DER signature to mpint r and s`() {
        // SEQUENCE { INTEGER 0x8001, INTEGER 7 }
        val der = byteArrayOf(0x30, 0x08, 0x02, 0x03, 0x00, 0x80.toByte(), 0x01, 0x02, 0x01, 0x07)
        val sig = SkSshSignature.create(SkKeyType.SK_ECDSA_256, der, SkSshSignature.FLAG_USER_PRESENT, 1u)

        val buf = Buffer.PlainBuffer(sig.encodeSignatureBlob())

        assertEquals("sk-ecdsa-sha2-nistp256@openssh.com", buf.readString())
        val rs = Buffer.PlainBuffer(buf.readStringAsBytes())
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte(), 0x01), rs.readStringAsBytes())
        assertArrayEquals(byteArrayOf(0x07), rs.readStringAsBytes())
        assertEquals(0, rs.available())
        sig.close()
    }

    @Test
    fun `encodeSignatureBlob rejects malformed signatures`() {
        val badEcdsa = SkSshSignature.create(
            SkKeyType.SK_ECDSA_256, ByteArray(64) { 1 }, SkSshSignature.FLAG_USER_PRESENT, 1u,
        )
        assertThrows<IllegalArgumentException> { badEcdsa.encodeSignatureBlob() }

        val badEd25519 = SkSshSignature.create(
            SkKeyType.SK_ED25519, ByteArray(63), SkSshSignature.FLAG_USER_PRESENT, 1u,
        )
        assertThrows<IllegalArgumentException> { badEd25519.encodeSignatureBlob() }
    }

    @Test
    fun `close wipes rawSignature`() {
        val rawSig = ByteArray(64) { 0xFF.toByte() }
        val sig = SkSshSignature.create(
            keyType = SkKeyType.SK_ECDSA_256,
            rawSignature = rawSig,
            flags = SkSshSignature.FLAG_USER_PRESENT,
            counter = 1u,
        )

        sig.close()

        assertTrue(rawSig.all { it == 0.toByte() })
    }
}
