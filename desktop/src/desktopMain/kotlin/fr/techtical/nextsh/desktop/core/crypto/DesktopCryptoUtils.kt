// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
private const val PBKDF2_ITERATIONS = 210_000
private const val PBKDF2_KEY_BITS = 256

/**
 * Desktop port of Android's `CryptoUtils`: AES-256-GCM + PBKDF2 + secure wipe.
 *
 * The algorithms, parameters and binary layout MUST match the Android
 * implementation exactly; this is what makes the `.nextsh` backup format
 * byte-compatible across platforms.
 *
 * - AES-256-GCM with 12-byte IV (cipher-generated), 128-bit tag
 * - Output format of [encryptAesGcm]: `[IV_LEN(1 byte)][IV][CIPHERTEXT||TAG]`
 * - PBKDF2-HMAC-SHA256, 210 000 iterations, 256-bit output
 * - `secureWipe` overwrites the array in place with zeros
 */
object DesktopCryptoUtils {

    /** Encrypt [plaintext] with AES-256-GCM and [key]. Returns `[IV_LEN(1)][IV][CIPHERTEXT||TAG]`. */
    fun encryptAesGcm(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv              // 12 bytes auto-generated
        val ciphertext = cipher.doFinal(plaintext)

        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    /** Decrypt data produced by [encryptAesGcm]. Throws on tamper / wrong key (AEAD). */
    fun decryptAesGcm(data: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(data.size >= 2) { "Encrypted data too short: must contain at least IV size + 1 byte" }

        val ivSize = data[0].toInt() and 0xFF
        require(ivSize in 1..64) { "Invalid IV size: $ivSize" }
        require(data.size >= 1 + ivSize + 1) { "Encrypted data too short for IV ($ivSize bytes) + ciphertext" }

        val iv = data.sliceArray(1..ivSize)
        val ciphertext = data.sliceArray((1 + ivSize) until data.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))

        return cipher.doFinal(ciphertext)
    }

    /**
     * Derive a 256-bit AES key from [passphrase] + [salt] using PBKDF2-HMAC-SHA256
     * at 210 000 iterations: byte-compatible with Android's `CryptoUtils.deriveKeyFromPassphrase`.
     */
    fun deriveKeyFromPassphrase(passphrase: CharArray, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Cryptographically strong random salt. Default 32 bytes matches Android. */
    fun generateSalt(size: Int = 32): ByteArray {
        val salt = ByteArray(size)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** Zeroise a byte array holding sensitive material (derived key, plaintext JSON, etc.). */
    fun secureWipe(data: ByteArray) {
        data.fill(0)
    }

    /** Zeroise a char array holding a passphrase. */
    fun secureWipe(data: CharArray) {
        data.fill('\u0000')
    }
}
