// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.tunnel

/**
 * Why a tunnel browser path was rejected. The platforms map each value to a localized
 * message, so keep their `when` expressions exhaustive.
 */
enum class TunnelBrowserPathError {
    /** More than [TunnelBrowserPath.MAX_LENGTH] characters, the leading slash included. */
    TOO_LONG,

    /** Empty, or not starting with "/" (a normalized path always does). */
    MISSING_LEADING_SLASH,

    /**
     * A character outside the allowed set: a space, a backslash, a control character, a
     * non-ASCII character, or a symbol RFC 3986 does not allow in a path (double quote,
     * angle brackets, caret, backtick, braces, vertical bar, square brackets).
     */
    INVALID_CHARACTER,

    /** A "%" that is not followed by two hexadecimal digits. */
    INVALID_PERCENT_ENCODING,

    /**
     * More than one "#". Only one fragment marker is legal in a URL, and java.net.URI, which
     * the desktop browser uses to recognize the tunnel's own URL, throws on a second one.
     */
    MULTIPLE_FRAGMENT_MARKERS,
}

/**
 * Pure helpers (no Android or JVM API) for the per-tunnel URL path opened by the in-app
 * browser of a LOCAL_FORWARD tunnel: `http://127.0.0.1:<localPort><browserPath>`.
 *
 * Security property: the scheme, host and port are fixed by this class, and the path is
 * only ever appended after them. A path that passes [validate] always begins with `/`,
 * which ends the URL authority, and it cannot contain `\` (read as `/` by browsers), a
 * space, a control character (tab and newline are stripped by URL parsers) or any
 * non-ASCII character. Whatever a synced device, an old backup or a user supplies, the
 * authority of the URL built by [buildBrowserUrl] stays exactly `127.0.0.1:<localPort>`.
 *
 * Allowed characters are the RFC 3986 path, query and fragment characters: ALPHA, DIGIT,
 * `-._~`, `!$&'()*+,;=`, `:@/?#`, and `%` only when followed by two hexadecimal digits.
 * At most one `#` is accepted, so that every accepted URL also parses with java.net.URI.
 *
 * Privacy: the path may carry a secret in its query string (token, API key, session id).
 * Never log the path or the built URL, and keep both out of error messages, analytics and
 * crash reports.
 */
object TunnelBrowserPath {

    /** The site root, also the value stored for "no specific path". */
    const val DEFAULT: String = "/"

    /** Maximum length of a normalized path, in characters, the leading slash included. */
    const val MAX_LENGTH: Int = 512

    private const val LOOPBACK_HOST = "127.0.0.1"

    // `$` is escaped so the literal is not read as a string template.
    private const val ALLOWED_PUNCTUATION = "-._~!\$&'()*+,;=:@/?#"

    /**
     * Turns user input into the canonical stored form: trims surrounding whitespace,
     * maps empty or blank input to [DEFAULT] and prepends `/` when missing
     * (`admin` gives `/admin`, `?x=1` gives `/?x=1`). Never throws, idempotent.
     * It does not validate: run [validate] on the result.
     */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> DEFAULT
            trimmed.startsWith('/') -> trimmed
            else -> "/$trimmed"
        }
    }

    /**
     * Checks a path that went through [normalize]. Returns null when it is valid, else the
     * first problem found: the length first, then the leading slash, then the characters
     * from left to right.
     *
     * A value that does not start with `/` (not normalized, or empty) is reported as
     * [TunnelBrowserPathError.MISSING_LEADING_SLASH], so a null result always means the path
     * is safe to append after the authority, whether or not the caller normalized it.
     */
    fun validate(path: String): TunnelBrowserPathError? {
        if (path.length > MAX_LENGTH) return TunnelBrowserPathError.TOO_LONG
        if (!path.startsWith('/')) return TunnelBrowserPathError.MISSING_LEADING_SLASH

        var hasFragmentMarker = false
        var i = 0
        while (i < path.length) {
            val c = path[i]
            if (c == '%') {
                val complete = i + 2 < path.length && isHexDigit(path[i + 1]) && isHexDigit(path[i + 2])
                if (!complete) return TunnelBrowserPathError.INVALID_PERCENT_ENCODING
                i += 3
            } else {
                if (c == '#') {
                    if (hasFragmentMarker) return TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS
                    hasFragmentMarker = true
                } else if (!isAllowed(c)) {
                    return TunnelBrowserPathError.INVALID_CHARACTER
                }
                i++
            }
        }
        return null
    }

    /**
     * Builds the URL the in-app browser opens: `http://127.0.0.1:<localPort><path>`.
     *
     * [browserPath] is normalized and validated first. A stored value that fails
     * validation (it may come from a sync peer or an old backup) falls back to the root,
     * `http://127.0.0.1:<localPort>/`. This function never throws.
     *
     * The URL is built by string concatenation only. Never resolve the path against a base
     * URI (URI.resolve, URL(base, spec) and the like): a path starting with `//` would then
     * be read as a network-path reference and replace the authority.
     *
     * [localPort] is interpolated as is, and an Int can only add digits and an optional
     * minus sign, so it cannot change the host. Range checking (1..65535) is the caller's
     * job: out of range values produce a URL that the browser engine refuses to load.
     *
     * The result may contain a secret from the query string: do not log it.
     */
    fun buildBrowserUrl(localPort: Int, browserPath: String): String {
        val normalized = normalize(browserPath)
        val path = if (validate(normalized) == null) normalized else DEFAULT
        return "http://$LOOPBACK_HOST:$localPort$path"
    }

    private fun isAllowed(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ALLOWED_PUNCTUATION

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
