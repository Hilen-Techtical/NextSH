// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.network

/**
 * [host] designates this very device: `localhost` (with or without a final dot), any address of
 * 127.0.0.0/8, `::1`, or an IPv4 loopback written as an IPv6 mapped address (`::ffff:127.0.0.1`).
 * Such a host needs no network to be reached, which is what the offline gate of the tunnel
 * reconnection asks.
 *
 * Purely textual, with no name resolution (no DNS, so no blocking and no answer that depends on the
 * network). A name other than `localhost` is never local here, even if it resolves to 127.0.0.1.
 * Square brackets around an IPv6 literal are accepted. The tunnel browser keeps its own, stricter
 * rule (`isLoopbackHost`): it does not trust the whole of 127/8 as the origin of a forwarded page.
 */
fun isLocalDeviceHost(host: String?): Boolean {
    var h = host?.trim()?.lowercase() ?: return false
    if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
    h = h.trimEnd('.')
    if (h.isEmpty()) return false
    if (h == "localhost") return true
    if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
    val mapped = MAPPED_PREFIXES.firstOrNull { h.startsWith(it) }
    if (mapped != null) return isLoopbackIpv4(h.removePrefix(mapped))
    return isLoopbackIpv4(h)
}

private val MAPPED_PREFIXES = listOf("::ffff:", "0:0:0:0:0:ffff:")

/** A dotted quad of four decimal fields 0 to 255 whose first field is 127. */
private fun isLoopbackIpv4(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    val numbers = parts.map { part ->
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
        part.toInt()
    }
    return numbers.all { it in 0..255 } && numbers[0] == 127
}
