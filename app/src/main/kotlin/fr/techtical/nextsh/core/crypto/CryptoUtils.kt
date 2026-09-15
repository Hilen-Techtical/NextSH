// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH = 128 // bits

/**
 * Utilitaires cryptographiques bas-niveau.
 * - Chiffrement AES-256-GCM pour le vault export
 * - Dérivation passphrase → clé AES via PBKDF2
 * - Secure wipe de CharArray/ByteArray
 */
@Singleton
class CryptoUtils @Inject constructor() {

    /** Chiffre des données avec AES-256-GCM. Retourne IV + ciphertext concaténés. */
    fun encryptAesGcm(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv              // 12 bytes auto-generated
        val ciphertext = cipher.doFinal(plaintext)

        // Format : [IV_SIZE(1)][IV][CIPHERTEXT]
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    /** Déchiffre des données AES-256-GCM. Le format attendu est [IV_SIZE(1)][IV][CIPHERTEXT]. */
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
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(ciphertext)
    }

    /**
     * Dérive une clé AES-256 depuis une passphrase via PBKDF2.
     * Utilisé pour le backup chiffré .nextsh
     */
    fun deriveKeyFromPassphrase(passphrase: CharArray, salt: ByteArray): ByteArray {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, 210_000, 256)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Génère un sel aléatoire cryptographiquement sûr */
    fun generateSalt(size: Int = 32): ByteArray {
        val salt = ByteArray(size)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** Efface de manière sécurisée un CharArray (credentials en mémoire) */
    fun secureWipe(data: CharArray) {
        data.fill('\u0000')
    }

    /** Efface de manière sécurisée un ByteArray (clés en mémoire) */
    fun secureWipe(data: ByteArray) {
        data.fill(0)
    }
}
