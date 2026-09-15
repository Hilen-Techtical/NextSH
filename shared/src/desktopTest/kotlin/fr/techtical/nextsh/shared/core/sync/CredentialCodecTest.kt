// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CredentialCodecTest {

    private val sharedSecret: ByteArray = ByteArray(32).also { it.indices.forEach { i -> it[i] = i.toByte() } }

    @Test
    fun `HKDF derivation is deterministic across calls`() = runTest {
        val k1 = CredentialCodec.deriveInnerKey(sharedSecret)
        val k2 = CredentialCodec.deriveInnerKey(sharedSecret)
        assertContentEquals(k1, k2, "Same shared secret must yield the same inner key: both peers must derive identically")
        assertEquals(32, k1.size)
    }

    @Test
    fun `HKDF inner key differs from shared secret (domain separation)`() = runTest {
        val inner = CredentialCodec.deriveInnerKey(sharedSecret)
        assertFalse(inner.contentEquals(sharedSecret), "Inner key must not equal the outer transport key: domain separation broken")
    }

    @Test
    fun `different shared secrets produce different inner keys`() = runTest {
        val otherSecret = ByteArray(32).also { it.indices.forEach { i -> it[i] = (255 - i).toByte() } }
        val k1 = CredentialCodec.deriveInnerKey(sharedSecret)
        val k2 = CredentialCodec.deriveInnerKey(otherSecret)
        assertFalse(k1.contentEquals(k2))
    }

    @Test
    fun `encrypt decrypt roundtrip recovers plaintext exactly`() = runTest {
        val inner = CredentialCodec.deriveInnerKey(sharedSecret)
        val plaintext = "super-secret-password-42".toByteArray(Charsets.UTF_8)

        val (ivB64, ctB64) = CredentialCodec.encrypt(plaintext, inner)
        val decrypted = CredentialCodec.decrypt(ivB64, ctB64, inner)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `two encryptions of same plaintext produce different ciphertexts (random IV)`() = runTest {
        val inner = CredentialCodec.deriveInnerKey(sharedSecret)
        val plaintext = "password".toByteArray()
        val (iv1, ct1) = CredentialCodec.encrypt(plaintext, inner)
        val (iv2, ct2) = CredentialCodec.encrypt(plaintext, inner)
        assertFalse(iv1 == iv2)
        assertFalse(ct1 == ct2)
    }

    @Test
    fun `decrypt with wrong inner key throws SyncCodecException`() = runTest {
        val inner = CredentialCodec.deriveInnerKey(sharedSecret)
        val wrong = ByteArray(32).also { it.indices.forEach { i -> it[i] = (i + 1).toByte() } }
        val (iv, ct) = CredentialCodec.encrypt("payload".toByteArray(), inner)
        assertFailsWith<SyncCodecException> { CredentialCodec.decrypt(iv, ct, wrong) }
    }

    @Test
    fun `decrypt with tampered ciphertext throws SyncCodecException`() = runTest {
        val inner = CredentialCodec.deriveInnerKey(sharedSecret)
        val (iv, ct) = CredentialCodec.encrypt("payload".toByteArray(), inner)
        // Tamper a single character in the Base64 ciphertext body
        val tamperedBytes = java.util.Base64.getUrlDecoder().decode(ct).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val tamperedCt = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedBytes)
        assertFailsWith<SyncCodecException> { CredentialCodec.decrypt(iv, tamperedCt, inner) }
    }

    @Test
    fun `deriveInnerKey rejects secrets that are not 32 bytes`() = runTest {
        assertFailsWith<IllegalArgumentException> { CredentialCodec.deriveInnerKey(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { CredentialCodec.deriveInnerKey(ByteArray(64)) }
    }
}
