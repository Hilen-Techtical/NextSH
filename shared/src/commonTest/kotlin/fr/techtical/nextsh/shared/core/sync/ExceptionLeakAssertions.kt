// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlin.test.assertFalse

/** Recognizable fake payload text. If it shows up in an exception, decoded sync data leaked. */
internal const val LEAK_MARKER = "secret-marker.example"

/**
 * Asserts that [LEAK_MARKER] appears nowhere a caller could log it: the message, the full stack
 * trace text (which prints every cause message), and each message in the cause chain.
 */
internal fun assertNoPayloadLeak(e: Throwable) {
    assertFalse(e.message.orEmpty().contains(LEAK_MARKER), "message leaks payload: ${e.message}")
    assertFalse(
        e.stackTraceToString().contains(LEAK_MARKER),
        "stack trace leaks payload (a cause kept the decoded text)",
    )
    var cause: Throwable? = e.cause
    while (cause != null) {
        assertFalse(cause.message.orEmpty().contains(LEAK_MARKER), "cause message leaks payload")
        cause = cause.cause
    }
}
