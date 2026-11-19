// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import fr.techtical.nextsh.shared.util.LogScrubber
import fr.techtical.nextsh.shared.util.Logger
import kotlin.test.assertTrue

/**
 * Tokens the [LogScrubber] substitutes for a value it recognised, plus its whole-line
 * replacement. The desktop Logger scrubs every line, so a leak from a call site never shows
 * as the raw value in a captured line: it shows as one of these tokens.
 */
private val SCRUB_TOKENS: List<String> = listOf(
    LogScrubber.IP,
    LogScrubber.HOST,
    LogScrubber.PATH,
    LogScrubber.URI,
    LogScrubber.FINGERPRINT,
    LogScrubber.PEM,
    LogScrubber.ID,
    LogScrubber.BASE64,
    LogScrubber.HEX,
    LogScrubber.SECRET,
    LogScrubber.VALUE,
    LogScrubber.REDACTED_LINE,
)

/**
 * `Logger.capture` with debug logging forced on, so that a test sees the `Logger.d` lines too
 * whatever the way the test JVM was started. The previous debug setting is restored.
 */
internal inline fun captureLogs(block: () -> Unit): List<String> {
    val wasDebug = Logger.isDebugEnabled()
    Logger.setDebugEnabled(true)
    try {
        return Logger.capture(block)
    } finally {
        Logger.setDebugEnabled(wasDebug)
    }
}

/**
 * The captured lines written under one of [tags] (`D/Tag: message`, `W/Tag: ...`, `E/Tag: ...`).
 * The Logger sink is global to the JVM: a thread left over from another test may log while a
 * test captures, so a test that counts lines or scans them for scrubber tokens must first
 * narrow them to the tags of the code it exercises.
 */
internal fun List<String>.taggedBy(vararg tags: String): List<String> =
    filter { line -> tags.any { tag -> line.length > 2 && line.startsWith("$tag:", startIndex = 2) } }

/**
 * Asserts that no captured log line needed the scrubber: the call sites emitted fixed text,
 * names, counts, codes and [fr.techtical.nextsh.shared.util.LogId] fingerprints only. Use it
 * on the output of `Logger.capture { ... }` together with explicit `none { contains(value) }`
 * checks for values the scrubber cannot recognise by shape (a label, a user name).
 */
internal fun assertNothingScrubbed(lines: List<String>) {
    val offending = lines.filter { line -> SCRUB_TOKENS.any { line.contains(it) } }
    assertTrue(offending.isEmpty(), "log lines carried a value the scrubber had to mask: $offending")
}
