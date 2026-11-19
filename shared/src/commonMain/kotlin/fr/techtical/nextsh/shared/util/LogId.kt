// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short, per-process fingerprint of an identifier, for log lines only.
 *
 * Entity UUIDs, device ids and credential ids are never logged raw. [of] returns the
 * first 8 hex chars of HMAC-SHA256(salt, id) where the salt is 32 random bytes drawn
 * once per process: two lines of the same run that mention the same id can be
 * correlated, but the value changes at every launch and on every device, and cannot be
 * recomputed from the id alone (a plain hash prefix could, by hashing candidate ids).
 *
 * Usage: `Logger.w(TAG, "Credential skipped id=${LogId.of(syncId)}")`.
 */
object LogId {

    /** Returned for a null or blank id. */
    const val NONE = "-"

    /** Length of the returned fingerprint, in hex chars. */
    const val LENGTH = 8

    private const val ALGORITHM = "HmacSHA256"
    private const val SALT_BYTES = 32
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * The salted key, drawn on first use. Kept apart from [LogId] so that a failure while
     * drawing it surfaces inside [failClosed] in [of] instead of in the caller.
     */
    private object Salt {
        val key: SecretKeySpec = run {
            val salt = ByteArray(SALT_BYTES)
            SecureRandom().nextBytes(salt)
            // SecretKeySpec keeps its own copy: wipe the temporary array.
            SecretKeySpec(salt, ALGORITHM).also { salt.fill(0) }
        }
    }

    /**
     * Fingerprint of [id]: 8 lowercase hex chars, or [NONE] when [id] is null or blank.
     * Never throws: if the HMAC cannot be computed, [NONE] is returned too, never the id.
     */
    fun of(id: String?): String = failClosed(NONE) {
        if (id.isNullOrBlank()) {
            NONE
        } else {
            // A Mac instance is not thread safe: one per call, the key is shared.
            val mac = Mac.getInstance(ALGORITHM)
            mac.init(Salt.key)
            val digest = mac.doFinal(id.toByteArray(Charsets.UTF_8))
            buildString(LENGTH) {
                for (i in 0 until LENGTH / 2) {
                    val b = digest[i].toInt() and 0xFF
                    append(HEX_DIGITS[b ushr 4])
                    append(HEX_DIGITS[b and 0x0F])
                }
            }
        }
    }
}
