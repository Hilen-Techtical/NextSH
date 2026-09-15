// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val urlSafeEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val urlSafeDecoder: Base64.Decoder = Base64.getUrlDecoder()

// ignoreUnknownKeys: old peers must be able to decode new envelopes silently skipping
// fields they don't know about (forward compat). New peers rely on `@Serializable`
// defaults for fields old peers don't emit (backward compat).
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Encrypts and decrypts [SyncBundle] instances as [SyncPayload] wire objects.
 *
 * Cipher: AES-256-GCM, random 12-byte IV per call, 128-bit authentication tag.
 * The GCM tag is appended to the ciphertext by the JVM provider and is therefore
 * included in [SyncPayload.ciphertext] transparently.
 */
object SyncBundleCodec {

    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /**
     * Serialises [bundle] to JSON, then encrypts with AES-256-GCM using [sharedSecret].
     * A fresh random IV is generated for every call.
     */
    fun encrypt(bundle: SyncBundle, senderDeviceId: String, sharedSecret: ByteArray): SyncPayload {
        require(sharedSecret.size == 32) { "sharedSecret must be 32 bytes (AES-256)" }

        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val plaintext = json.encodeToString(SyncBundle.serializer(), bundle)
            .toByteArray(Charsets.UTF_8)

        val secretKey = SecretKeySpec(sharedSecret, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext)

        return SyncPayload(
            senderDeviceId = senderDeviceId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            iv = urlSafeEncoder.encodeToString(iv),
            ciphertext = urlSafeEncoder.encodeToString(ciphertextWithTag),
        )
    }

    /**
     * Decrypts [payload] using [sharedSecret] and deserialises the resulting JSON.
     *
     * @throws SyncCodecException on AES-GCM authentication failure, malformed Base64, or
     * invalid JSON content.
     */
    fun decrypt(payload: SyncPayload, sharedSecret: ByteArray): SyncBundle {
        require(sharedSecret.size == 32) { "sharedSecret must be 32 bytes (AES-256)" }

        return try {
            val iv = urlSafeDecoder.decode(payload.iv)
            val ciphertextWithTag = urlSafeDecoder.decode(payload.ciphertext)

            val secretKey = SecretKeySpec(sharedSecret, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertextWithTag)

            json.decodeFromString(SyncBundle.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SyncCodecException("Decryption failed: ${e.message}", e)
        }
    }
}

class SyncCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
