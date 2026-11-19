// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The random secret that an intent carries to prove it comes from the app itself.
 *
 * `MainActivity` is exported (launcher), so any app can start it with any extras. What the app does
 * on behalf of an intent (restart tunnels from a notification, open a host or a tunnel from a
 * launcher shortcut) is therefore decided by a token that only the app could have put there, never
 * by the ids in the extras. This object is the one place where a token is made and compared, for
 * [PendingAuthResume] (one-time, in memory) and for the launcher shortcuts (per install, stored by
 * `ShortcutTokenStore`).
 *
 * A token is 32 bytes from [SecureRandom], in URL-safe Base64 without padding ([LENGTH] characters).
 * It is a secret for as long as it is valid: it never goes to a log, a Bundle or saved state.
 */
internal object SecretToken {

    private const val BYTES = 32
    private const val MAX_ASCII = 127

    /** What 32 bytes make in Base64 without padding. */
    const val LENGTH = 43

    fun generate(random: SecureRandom): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The form in which a token is kept and compared. */
    fun toBytes(token: String): ByteArray = token.toByteArray(Charsets.US_ASCII)

    /**
     * Whether [candidate] is the token whose bytes are [expected]. Null and empty never match, nor
     * does text beyond ASCII: [toBytes] would turn each such character into `?`, and a character a
     * token does not have must not be able to stand for one that [expected] has.
     * Constant time: the comparison does not tell how much of a guess was right.
     */
    fun matches(expected: ByteArray, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        if (candidate.any { it.code > MAX_ASCII }) return false
        return MessageDigest.isEqual(expected, toBytes(candidate))
    }

    /** Whether [text] has the shape of a token made by [generate] (a stored value is checked on load). */
    fun isWellFormed(text: String?): Boolean =
        text != null && text.length == LENGTH && text.all(::isTokenChar)

    private fun isTokenChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}
