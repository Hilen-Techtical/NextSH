// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException

class CryptoUtilsTest {

    private val cryptoUtils = CryptoUtils()

    @Test
    fun `round-trip encrypt and decrypt returns original plaintext`() {
        val passphrase = "test-passphrase".toCharArray()
        val salt = cryptoUtils.generateSalt()
        val key = cryptoUtils.deriveKeyFromPassphrase(passphrase, salt)
        val plaintext = "Hello, NextSH!".toByteArray()

        val encrypted = cryptoUtils.encryptAesGcm(plaintext, key)
        val decrypted = cryptoUtils.decryptAesGcm(encrypted, key)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with wrong key throws exception`() {
        val salt = cryptoUtils.generateSalt()
        val key1 = cryptoUtils.deriveKeyFromPassphrase("passphrase-one".toCharArray(), salt)
        val key2 = cryptoUtils.deriveKeyFromPassphrase("passphrase-two".toCharArray(), salt)
        val plaintext = "secret data".toByteArray()

        val encrypted = cryptoUtils.encryptAesGcm(plaintext, key1)

        assertThrows(AEADBadTagException::class.java) {
            cryptoUtils.decryptAesGcm(encrypted, key2)
        }
    }

    @Test
    fun `generateSalt returns different values on each call`() {
        val salt1 = cryptoUtils.generateSalt()
        val salt2 = cryptoUtils.generateSalt()

        assertFalse(salt1.contentEquals(salt2), "Two generated salts should not be equal")
    }

    @Test
    fun `secureWipe CharArray fills all elements with null character`() {
        val data = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

        cryptoUtils.secureWipe(data)

        assertTrue(data.all { it == '\u0000' }, "All characters should be wiped to null character")
    }

    @Test
    fun `secureWipe ByteArray fills all elements with zero`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)

        cryptoUtils.secureWipe(data)

        assertTrue(data.all { it == 0.toByte() }, "All bytes should be wiped to zero")
    }

    @Test
    fun `encryptAesGcm with wrong key size throws IllegalArgumentException`() {
        val wrongSizeKey = ByteArray(16) // AES-128, but we require AES-256 (32 bytes)
        val plaintext = "test".toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            cryptoUtils.encryptAesGcm(plaintext, wrongSizeKey)
        }
    }

    @Test
    fun `decryptAesGcm with wrong key size throws IllegalArgumentException`() {
        val salt = cryptoUtils.generateSalt()
        val validKey = cryptoUtils.deriveKeyFromPassphrase("passphrase".toCharArray(), salt)
        val plaintext = "test".toByteArray()
        val encrypted = cryptoUtils.encryptAesGcm(plaintext, validKey)

        val wrongSizeKey = ByteArray(16)

        assertThrows(IllegalArgumentException::class.java) {
            cryptoUtils.decryptAesGcm(encrypted, wrongSizeKey)
        }
    }

    @Test
    fun `generateSalt default size is 32 bytes`() {
        val salt = cryptoUtils.generateSalt()
        assertTrue(salt.size == 32, "Default salt size should be 32 bytes")
    }

    @Test
    fun `generateSalt with custom size returns correct length`() {
        val salt = cryptoUtils.generateSalt(size = 64)
        assertTrue(salt.size == 64, "Salt size should match requested size")
    }
}
