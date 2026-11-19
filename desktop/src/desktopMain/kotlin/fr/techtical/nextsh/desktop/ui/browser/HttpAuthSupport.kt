// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

/** How long a challenge for the realm the user just dismissed is auto-cancelled. */
internal const val AUTH_CANCEL_COOLDOWN_MS = 5_000L

internal const val AUTH_REALM_MAX_LENGTH = 80

// The realm is bounded before any per-character work: a hostile server can send a
// header of arbitrary size.
internal const val AUTH_REALM_RAW_LIMIT = 512

private val AUTH_REALM_ELLIPSIS = 0x2026.toChar()

/**
 * The origin Chromium reports for a challenge raised by the tunnelled service on
 * [localPort]: scheme, loopback IP literal and port, nothing else. The port is omitted
 * for 80 because that is how Chromium serializes an origin on its default port. Built the
 * same way as the URL of [fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath].
 */
internal fun tunnelAuthOrigin(localPort: Int): String =
    if (localPort == 80) "http://127.0.0.1" else "http://127.0.0.1:$localPort"

/** `host:port` form shown in the sign-in panel: the origin without its scheme. */
internal fun displayAuthOrigin(origin: String): String = origin.removePrefix("http://")

internal fun isLoopbackHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "localhost" || h == "127.0.0.1" || h == "[::1]" || h == "::1"
}

/**
 * True for an http(s) origin whose host is a loopback name or address, whatever its port. A
 * challenge from such an origin that is not the tunnel's own (another port, https, `localhost`
 * instead of the IP literal) is refused, but the user is told why.
 */
internal fun isLoopbackAuthOrigin(origin: String?): Boolean {
    if (origin.isNullOrEmpty()) return false
    return try {
        val uri = java.net.URI(origin)
        (uri.scheme == "http" || uri.scheme == "https") && isLoopbackHost(uri.host)
    } catch (_: Exception) {
        false
    }
}

/**
 * The realm comes straight from the server's `WWW-Authenticate` header and ends up in
 * a panel the user trusts. Besides ISO controls, every code point of the Unicode
 * general categories Control, Format (bidi overrides and isolates, LRM/RLM/ALM,
 * zero-width characters, BOM, soft hyphen), Line separator and Paragraph separator is
 * dropped, so the text cannot be visually reordered, hidden or spread over several
 * lines. The result is trimmed and capped; null when nothing printable is left.
 */
internal fun sanitizeAuthRealm(raw: String?): String? {
    if (raw == null) return null
    val bounded = if (raw.length <= AUTH_REALM_RAW_LIMIT) {
        raw
    } else {
        var end = AUTH_REALM_RAW_LIMIT
        // Do not cut a surrogate pair in half.
        if (Character.isHighSurrogate(raw[end - 1])) end--
        raw.substring(0, end)
    }
    val filtered = StringBuilder(bounded.length)
    var i = 0
    while (i < bounded.length) {
        val cp = bounded.codePointAt(i)
        i += Character.charCount(cp)
        if (!isHiddenOrControlCodePoint(cp)) filtered.appendCodePoint(cp)
    }
    val cleaned = filtered.toString().trim()
    if (cleaned.isEmpty()) return null
    if (cleaned.length <= AUTH_REALM_MAX_LENGTH) return cleaned
    var end = AUTH_REALM_MAX_LENGTH
    if (Character.isHighSurrogate(cleaned[end - 1])) end--
    return cleaned.substring(0, end) + AUTH_REALM_ELLIPSIS
}

private fun isHiddenOrControlCodePoint(cp: Int): Boolean {
    if (Character.isISOControl(cp)) return true
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        -> true
        else -> false
    }
}
