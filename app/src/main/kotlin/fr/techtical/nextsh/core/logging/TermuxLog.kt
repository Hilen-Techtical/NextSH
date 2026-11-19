// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.logging

import fr.techtical.nextsh.shared.util.LogScrubber
import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber

/**
 * Forwards the log calls of the Termux fork (`TerminalSessionClient` and `TerminalViewClient`
 * log callbacks) to Timber.
 *
 * Termux text is not ours: it can carry what the remote host sent (window titles, escape
 * sequence payloads) or a path. Every message goes through [LogScrubber.clean] here, whatever
 * Timber tree is planted, and a throwable is handed to Timber with the cleaned message only (the
 * trees reduce it to its class chain or to a message-free stack trace).
 */
internal object TermuxLog {

    fun error(tag: String, message: String) { Timber.tag(tag).e(LogScrubber.clean(message)) }

    fun warn(tag: String, message: String) { Timber.tag(tag).w(LogScrubber.clean(message)) }

    fun info(tag: String, message: String) { Timber.tag(tag).i(LogScrubber.clean(message)) }

    fun debug(tag: String, message: String) { Timber.tag(tag).d(LogScrubber.clean(message)) }

    fun verbose(tag: String, message: String) { Timber.tag(tag).v(LogScrubber.clean(message)) }

    fun stackTraceWithMessage(tag: String, message: String, e: Exception) {
        Timber.tag(tag).e("${LogScrubber.clean(message)} (${errorClass(e)})")
    }

    fun stackTrace(tag: String, e: Exception) { Timber.tag(tag).e("Termux error (${errorClass(e)})") }
}
