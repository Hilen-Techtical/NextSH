// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.datetime.Clock
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Per-request HMAC-SHA256 signing for sync API calls.
 *
 * Canonical string: "$method|$path|$timestamp|$bodyHashHex"
 * where method is lowercase, bodyHashHex is SHA-256 hex (64 chars lowercase).
 *
 * Verification uses constant-time byte comparison to prevent timing attacks.
 */
object HmacSigner {

    private const val TIMESTAMP_SKEW_MS = 5L * 60_000L

    /**
     * Returns lowercase hex HMAC-SHA256 over the canonical string
     * "$method|$path|$timestamp|$bodyHashHex".
     */
    fun sign(
        method: String,
        path: String,
        timestamp: Long,
        body: ByteArray,
        sharedSecret: ByteArray,
    ): String {
        val bodyHash = sha256Hex(body)
        val canonical = "${method.lowercase()}|$path|$timestamp|$bodyHash"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns true iff [expectedSignature] matches the HMAC for the given parameters
     * AND [timestamp] is within ±[TIMESTAMP_SKEW_MS] of [now].
     *
     * Uses constant-time comparison to prevent timing attacks.
     */
    fun verify(
        method: String,
        path: String,
        timestamp: Long,
        body: ByteArray,
        expectedSignature: String,
        sharedSecret: ByteArray,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_SKEW_MS) return false
        val computed = sign(method, path, timestamp, body, sharedSecret)
        return MessageDigest.isEqual(computed.toByteArray(Charsets.UTF_8), expectedSignature.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
