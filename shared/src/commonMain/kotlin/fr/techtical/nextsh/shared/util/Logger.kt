// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

/**
 * Logger of the shared module.
 *
 * Rule for every level, debug included: a message carries fixed text, operation and state
 * names, counts, sizes, durations, numeric codes, algorithm and transport names and
 * exception class names only. Never a secret, a user-owned value (label, hostname, IP,
 * port, username, key label, alias, fingerprint, rpId, path, file name, URI, device name),
 * peer or server provided text, `e.message` / `localizedMessage`, or a raw id.
 *
 * - ids: [LogId.of]
 * - exceptions: [errorClass] in the message; pass [e]'s `throwable` only when its message
 *   cannot carry user data
 *
 * Both actuals also run every message and tag through [LogScrubber] as a safety net, and
 * never throw.
 */
expect object Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
