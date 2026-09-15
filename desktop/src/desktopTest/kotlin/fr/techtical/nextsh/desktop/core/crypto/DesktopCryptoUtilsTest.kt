// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import javax.crypto.AEADBadTagException

/**
 * Byte-level tests for the Desktop port of `CryptoUtils`.
 *
 * The format emitted by [DesktopCryptoUtils.encryptAesGcm] and the PBKDF2
 * parameters (SHA-256, 210 000 iterations, 256-bit output) must match Android
 * exactly: otherwise cross-platform `.nextsh` backups would fail to import.
 */
class DesktopCryptoUtilsTest {

    private val sampleKey: ByteArray = ByteArray(32) { it.toByte() }

    @Test
    fun `encrypt then decrypt roundtrip returns original plaintext`() {
        val plaintext = "Hello, NextSH vault!".toByteArray(Charsets.UTF_8)
        val ciphertext = DesktopCryptoUtils.encryptAesGcm(plaintext, sampleKey)
        val decrypted = DesktopCryptoUtils.decryptAesGcm(ciphertext, sampleKey)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypted output format is IV_LEN IV CIPHERTEXT`() {
        val plaintext = "x".toByteArray()
        val out = DesktopCryptoUtils.encryptAesGcm(plaintext, sampleKey)
        // First byte = IV length (12). Then 12 bytes of IV. Remainder = ciphertext+tag (16-byte tag min).
        assertEquals(12, out[0].toInt() and 0xFF, "IV length byte should be 12 for AES-GCM")
        assertTrue(out.size > 1 + 12, "output must include IV and ciphertext")
    }

    @Test
    fun `tampering with ciphertext causes AEAD failure`() {
        val plaintext = "sensitive".toByteArray()
        val ciphertext = DesktopCryptoUtils.encryptAesGcm(plaintext, sampleKey)
        // Flip one bit in the last byte (GCM tag): must fail authentication.
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<AEADBadTagException> {
            DesktopCryptoUtils.decryptAesGcm(ciphertext, sampleKey)
        }
    }

    @Test
    fun `decrypt with wrong key fails AEAD`() {
        val plaintext = "sensitive".toByteArray()
        val ciphertext = DesktopCryptoUtils.encryptAesGcm(plaintext, sampleKey)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        assertFailsWith<AEADBadTagException> {
            DesktopCryptoUtils.decryptAesGcm(ciphertext, wrongKey)
        }
    }

    @Test
    fun `pbkdf2 is deterministic for same passphrase and salt`() {
        val pass = "correct horse battery staple".toCharArray()
        val salt = ByteArray(32) { it.toByte() }
        val k1 = DesktopCryptoUtils.deriveKeyFromPassphrase(pass.copyOf(), salt)
        val k2 = DesktopCryptoUtils.deriveKeyFromPassphrase(pass.copyOf(), salt)
        assertContentEquals(k1, k2, "PBKDF2 must be deterministic for identical inputs")
        assertEquals(32, k1.size, "derived key must be 256-bit / 32 bytes")
    }

    @Test
    fun `pbkdf2 differs for different salts`() {
        val pass = "same-pass".toCharArray()
        val salt1 = ByteArray(32) { 1 }
        val salt2 = ByteArray(32) { 2 }
        val k1 = DesktopCryptoUtils.deriveKeyFromPassphrase(pass.copyOf(), salt1)
        val k2 = DesktopCryptoUtils.deriveKeyFromPassphrase(pass.copyOf(), salt2)
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test
    fun `wrong passphrase yields a different key and decrypt fails`() {
        val salt = DesktopCryptoUtils.generateSalt()
        val rightKey = DesktopCryptoUtils.deriveKeyFromPassphrase("right".toCharArray(), salt)
        val wrongKey = DesktopCryptoUtils.deriveKeyFromPassphrase("wrong".toCharArray(), salt)
        assertNotEquals(rightKey.toList(), wrongKey.toList())

        val ciphertext = DesktopCryptoUtils.encryptAesGcm("payload".toByteArray(), rightKey)
        assertFailsWith<AEADBadTagException> {
            DesktopCryptoUtils.decryptAesGcm(ciphertext, wrongKey)
        }
    }

    @Test
    fun `secureWipe zeroises byte array in place`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        DesktopCryptoUtils.secureWipe(bytes)
        assertContentEquals(ByteArray(5) { 0 }, bytes)
    }

    @Test
    fun `secureWipe zeroises char array in place`() {
        val chars = "secret".toCharArray()
        DesktopCryptoUtils.secureWipe(chars)
        assertContentEquals(CharArray(6) { '\u0000' }, chars)
    }

    @Test
    fun `generateSalt produces requested length`() {
        assertEquals(16, DesktopCryptoUtils.generateSalt(16).size)
        assertEquals(32, DesktopCryptoUtils.generateSalt().size)  // default
    }

    @Test
    fun `encrypt rejects wrong-sized key`() {
        val plaintext = "x".toByteArray()
        assertFailsWith<IllegalArgumentException> {
            DesktopCryptoUtils.encryptAesGcm(plaintext, ByteArray(16))
        }
    }
}
