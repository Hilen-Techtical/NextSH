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
