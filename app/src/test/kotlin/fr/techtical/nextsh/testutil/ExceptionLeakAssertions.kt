// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import org.junit.jupiter.api.Assertions.assertFalse

/**
 * Recognizable fake secret. If it shows up in an exception, decrypted content leaked.
 * Same idea as the helper of the `:shared` tests, which is internal to that module's test
 * source set and so cannot be reused here.
 */
internal const val LEAK_MARKER = "secret-marker.example"

/**
 * Asserts that [LEAK_MARKER] appears nowhere a caller could log or display it: the message, the
 * full stack trace text (which prints every cause message), and each message in the cause chain.
 */
internal fun assertNoPayloadLeak(e: Throwable) {
    assertFalse(e.message.orEmpty().contains(LEAK_MARKER), "message leaks the content: ${e.message}")
    assertFalse(
        e.stackTraceToString().contains(LEAK_MARKER),
        "stack trace leaks the content (a cause kept the decrypted text)",
    )
    var cause: Throwable? = e.cause
    while (cause != null) {
        assertFalse(cause.message.orEmpty().contains(LEAK_MARKER), "cause message leaks the content")
        cause = cause.cause
    }
}
