// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val IV_SIZE = 12
private const val TAG_BITS = 128
private const val INNER_KEY_SIZE = 32

private val urlSafeEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val urlSafeDecoder: Base64.Decoder = Base64.getUrlDecoder()

actual object CredentialCodec {

    actual val HKDF_CRED_SALT: String = "nextsh-cred-v1"
    actual val HKDF_CRED_INFO: String = "credential-sync"

    actual fun deriveInnerKey(sharedSecret: ByteArray): ByteArray {
        require(sharedSecret.size == 32) { "sharedSecret must be 32 bytes" }
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(
            HKDFParameters(
                sharedSecret,
                HKDF_CRED_SALT.toByteArray(Charsets.UTF_8),
                HKDF_CRED_INFO.toByteArray(Charsets.UTF_8),
            ),
        )
        val derived = ByteArray(INNER_KEY_SIZE)
        hkdf.generateBytes(derived, 0, INNER_KEY_SIZE)
        return derived
    }

    actual fun encrypt(plaintext: ByteArray, innerKey: ByteArray): Pair<String, String> {
        require(innerKey.size == INNER_KEY_SIZE) { "innerKey must be 32 bytes (derive via deriveInnerKey)" }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(innerKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return urlSafeEncoder.encodeToString(iv) to urlSafeEncoder.encodeToString(ciphertextWithTag)
    }

    actual fun decrypt(ivBase64: String, ciphertextBase64: String, innerKey: ByteArray): ByteArray {
        require(innerKey.size == INNER_KEY_SIZE) { "innerKey must be 32 bytes (derive via deriveInnerKey)" }
        return try {
            val iv = urlSafeDecoder.decode(ivBase64)
            val ciphertextWithTag = urlSafeDecoder.decode(ciphertextBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(innerKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertextWithTag)
        } catch (e: Exception) {
            throw SyncCodecException("Credential decrypt failed: ${e.message}", e)
        }
    }
}
