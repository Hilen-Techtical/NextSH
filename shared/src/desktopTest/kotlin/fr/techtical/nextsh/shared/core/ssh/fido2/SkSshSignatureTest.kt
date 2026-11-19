// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh.fido2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkSshSignatureTest {

    @Test
    fun `create validates FLAG_USER_PRESENT must be set`() {
        val rawSig = ByteArray(64) { 1 }
        assertFailsWith<IllegalArgumentException> {
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
