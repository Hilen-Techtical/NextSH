// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import java.security.SecureRandom

private const val KEYSTORE_ALIAS = "nextsh_shared_master_v1"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128
private const val GCM_IV_LENGTH_BYTES = 12

actual class VaultKeyProvider(private val context: Context) {

    /** Ensures the Keystore key exists. Returns an empty placeholder (key never leaves the Keystore). */
    actual suspend fun getOrCreateMasterKey(): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(300)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            keyGenerator.generateKey()
        }
        return ByteArray(0) // opaque placeholder: key never leaves the Keystore
    }

    /** Encrypts with the Keystore AES/GCM key. Output: IV (12 bytes) + ciphertext + tag. */
    actual fun encrypt(plaintext: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv // Keystore generates the IV
        val ciphertext = cipher.doFinal(plaintext)

        return iv + ciphertext
    }

    /** Decrypts: extracts IV from first 12 bytes, decrypts the rest. */
    actual fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > GCM_IV_LENGTH_BYTES) { "Ciphertext too short" }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey

        val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val body = ciphertext.copyOfRange(GCM_IV_LENGTH_BYTES, ciphertext.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        return cipher.doFinal(body)
    }
}
