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
     * SECURITY: the thrown [SyncCodecException] never carries decrypted content. A
     * kotlinx.serialization error message embeds an excerpt of the JSON it failed on (host
     * labels, hostnames, usernames, tunnel paths), and a cause keeps that message alive in any
     * stack trace. So the decode stage reports only the cause's class name and drops the cause.
     * The crypto stage keeps its cause: a java.util.Base64 message may quote one on-wire byte
     * in hex (public wire data), the other crypto messages are fixed text, and a failed GCM
     * tag check never releases plaintext.
     *
     * @throws SyncCodecException on AES-GCM authentication failure, malformed Base64, or
     * invalid JSON content.
     */
    fun decrypt(payload: SyncPayload, sharedSecret: ByteArray): SyncBundle {
        require(sharedSecret.size == 32) { "sharedSecret must be 32 bytes (AES-256)" }

        val plaintext = try {
            val iv = urlSafeDecoder.decode(payload.iv)
            val ciphertextWithTag = urlSafeDecoder.decode(payload.ciphertext)

            val secretKey = SecretKeySpec(sharedSecret, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertextWithTag)
        } catch (e: Exception) {
            throw SyncCodecException("Decryption failed (${e.safeTypeName()})", e)
        }

        return try {
            json.decodeFromString(SyncBundle.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // No cause on purpose: see the SECURITY note on this function.
            throw SyncCodecException("Sync bundle decoding failed (${e.safeTypeName()})")
        }
    }
}

/**
 * Failure of a sync encrypt or decrypt step.
 *
 * Callers log [message] and the stack trace, so neither may contain decrypted or decoded
 * payload text. Build the message from fixed text plus [safeTypeName] only.
 */
class SyncCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Class name of this throwable, for exception messages and logs. Deliberately never its
 * `message`: for a kotlinx.serialization failure the message embeds an excerpt of the decoded
 * JSON, which in the sync path is user data.
 */
internal fun Throwable.safeTypeName(): String = this::class.simpleName ?: "Exception"
