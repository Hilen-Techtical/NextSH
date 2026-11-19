// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.logging

import android.util.Log
import fr.techtical.nextsh.shared.util.LogScrubber
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.shared.util.safeStackTrace
import timber.log.Timber

/**
 * Where the Timber trees of the app turn a log call into the text that reaches logcat.
 *
 * Log policy (all build types, debug included): a line carries fixed text, operation and state
 * names, counts, sizes, durations, numeric codes, algorithm and transport names and exception
 * CLASS names. Call sites are responsible for that. The trees below are the safety net:
 *
 * - [LogScrubber.clean] masks what still looks like a host, an address, a path, a fingerprint,
 *   an id or a secret assignment (a line with an unmistakable secret becomes a fixed notice);
 * - a throwable never reaches logcat with its message. Timber appends `"\n" + stack trace` to the
 *   message it hands to a tree, and that trace prints `Throwable.toString()` of every cause, so
 *   the appended text is cut off first. A release line then gets `[ErrorClass<-CauseClass]`, a
 *   debug line gets [safeStackTrace] (class names and frames only).
 */
internal object LogRendering {

    /** Tag used by a tree when the call site set none. */
    const val DEFAULT_TAG = "NextSH"

    private const val FALLBACK_MESSAGE = "Error"

    /** Start of a stack frame line, or of a cause line, in a printed stack trace. */
    private val TRACE_MARKERS = listOf("\n\tat ", "\nCaused by:")

    /**
     * [message] as the call site wrote it, without the stack trace Timber appended for [t]. Timber
     * hands a tree `message + "\n" + trace`, or `trace` alone when the call had no message, and the
     * first line of the trace is `Throwable.toString()`: the class name and the message of the
     * exception, or whatever a custom `toString()` prints.
     *
     * Cuts, from the most to the least precise:
     * 1. the exact trace, as the JVM prints it now;
     * 2. the first line that starts with the throwable class name, then with its `toString()`;
     * 3. a message that starts with one of them is only the trace: dropped whole;
     * 4. last resort, when the trace does not look like what we print (the exception changed since,
     *    a wrapper reshaped it): everything from the first stack frame or `Caused by:` line on goes,
     *    and so do the lines before it, which hold the `toString()` and cannot be told apart from a
     *    multi-line call-site text. Only the first line of the message is kept, and nothing when
     *    there is no line before the `toString()`.
     */
    fun messageWithoutTrace(message: String, t: Throwable?): String {
        if (t == null) return message
        val trace = t.stackTraceToString()
        if (message == trace) return ""
        if (message.endsWith("\n$trace")) return message.substring(0, message.length - trace.length - 1)

        val markers = listOf(t.javaClass.name, t.toString()).filter { it.isNotEmpty() }
        for (marker in markers) {
            val cut = message.indexOf("\n$marker")
            if (cut >= 0) return message.substring(0, cut)
        }
        if (markers.any { message.startsWith(it) }) return ""

        val frame = TRACE_MARKERS.map { message.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (frame != null) {
            val head = message.substring(0, frame)
            val firstLineEnd = head.indexOf('\n')
            return if (firstLineEnd < 0) "" else head.substring(0, firstLineEnd)
        }
        return message
    }

    /** [text] up to its first line break (`\n` or `\r`), without it. */
    private fun firstLine(text: String): String {
        val end = text.indexOfFirst { it == '\n' || it == '\r' }
        return if (end < 0) text else text.substring(0, end)
    }

    /**
     * Text of a release line: the first line of the cleaned message (a second line is never fixed
     * call-site text worth keeping, and is where a pasted trace or a peer text would sit), then the
     * throwable class chain only.
     */
    fun release(message: String, t: Throwable?): String {
        val base = LogScrubber.clean(firstLine(messageWithoutTrace(message, t)))
        if (t == null) return base
        return "${base.ifBlank { FALLBACK_MESSAGE }} [${errorClass(t)}]"
    }

    /** Text of a debug line: cleaned message, then the stack trace without any message. */
    fun debug(message: String, t: Throwable?): String {
        val base = LogScrubber.clean(messageWithoutTrace(message, t))
        if (t == null) return base
        return "${base.ifBlank { FALLBACK_MESSAGE }}\n${safeStackTrace(t)}"
    }
}

/** Receives the final text of a release line. Replaced by a recorder in tests. */
internal typealias LogSink = (priority: Int, tag: String, message: String) -> Unit

private val androidLogSink: LogSink = { priority, tag, message -> Log.println(priority, tag, message) }

/**
 * Tree of release builds. Drops everything below INFO before Timber formats the message (so a
 * `d(...)` costs nothing and its arguments are never turned into text), then logs the cleaned
 * message and, for a throwable, its class chain only.
 */
internal class ReleaseLogTree(private val sink: LogSink = androidLogSink) : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        sink(priority, tag ?: LogRendering.DEFAULT_TAG, LogRendering.release(message, t))
    }
}

/**
 * Tree of debug builds. Keeps the automatic class tag and the chunking of [Timber.DebugTree] but
 * applies the same policy as release: scrubbed message, and for a throwable a stack trace made of
 * class names and frames only ([safeStackTrace]), never `Throwable.toString()`.
 */
internal class DebugLogTree : Timber.DebugTree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, LogRendering.debug(message, t), null)
    }
}
