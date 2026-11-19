// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.auth.HwKeyResult
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sheet turns the answer of the key into an SSH signature. SkSshSignature refuses an answer
 * without the user-present flag by throwing: a key that breaks the protocol must not take the app
 * down with an exception thrown from the sheet.
 */
class Fido2SignatureOfAnswerTest {

    private fun answer(flags: Byte, signature: ByteArray = ByteArray(64) { 0x42 }) = HwKeyResult.Success(
        flags = flags,
        counter = 7u,
        signature = signature,
        credentialId = ByteArray(16) { 0x11 },
    )

    @Test
    fun `an answer with the user present flag makes a signature`() {
        val signature = sshSignatureOf(SkKeyType.SK_ED25519, answer(SkSshSignature.FLAG_USER_PRESENT))

        assertNotNull(signature)
        assertEquals(SkKeyType.SK_ED25519, signature!!.keyType)
        assertEquals(7u, signature.counter)
        signature.close()
    }

    @Test
    fun `an answer without the user present flag makes none and does not throw`() {
        val raw = ByteArray(64) { 0x42 }
        val result = answer(flags = 0x04, signature = raw) // user verified only

        val signature = sshSignatureOf(SkKeyType.SK_ED25519, result)

        assertNull(signature)
        assertTrue(raw.all { it == 0.toByte() }, "the answer is wiped even when it makes no signature")
    }

    @Test
    fun `the answer is wiped once its signature is built`() {
        val raw = ByteArray(64) { 0x42 }
        val result = answer(SkSshSignature.FLAG_USER_PRESENT, raw)

        val signature = sshSignatureOf(SkKeyType.SK_ED25519, result)

        assertTrue(raw.all { it == 0.toByte() }, "the signature owns a copy")
        assertTrue(signature!!.rawSignature.any { it != 0.toByte() }, "and it is intact")
        signature.close()
    }
}
