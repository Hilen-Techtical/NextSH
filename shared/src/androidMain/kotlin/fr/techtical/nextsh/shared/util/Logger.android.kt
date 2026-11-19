// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import timber.log.Timber

/**
 * Android actual: forwards to Timber, the tree planted by the app decides what reaches
 * logcat. Messages are cleaned here first ([LogScrubber.clean]) and tags scrubbed
 * ([LogScrubber.scrub], control characters included), in every build type.
 *
 * Throwables are forwarded unchanged so that the app tree keeps control of them: the
 * release tree is responsible for reducing a throwable to its class chain ([errorClass])
 * and never printing its message. Note that Timber appends the full stack trace, messages
 * included, to the message it hands to `Tree.log`. Callers must still not pass a throwable
 * whose message carries user data (hostname, path, label, peer text): a debug tree prints
 * it in full. When in doubt, log `errorClass(e)` in the message and pass no throwable.
 * When the message itself is dropped as a secret ([LogScrubber.REDACTED_LINE]), the
 * throwable is not forwarded either: only its class chain is logged.
 */
actual object Logger {
    actual fun d(tag: String, message: String) { tree(tag).d(LogScrubber.clean(message)) }
    actual fun w(tag: String, message: String) { tree(tag).w(LogScrubber.clean(message)) }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val safe = LogScrubber.clean(message)
        when {
            throwable == null -> tree(tag).e(safe)
            // A line that carried a secret may come with an exception quoting it: keep the
            // class chain only, never hand that throwable to a tree.
            safe == LogScrubber.REDACTED_LINE -> tree(tag).e("$safe [${errorClass(throwable)}]")
            else -> tree(tag).e(throwable, safe)
        }
    }

    private fun tree(tag: String): Timber.Tree = Timber.tag(LogScrubber.scrub(tag))
}
