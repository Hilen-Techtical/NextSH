// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

/** Longest cause chain walked by [errorClass] and [safeStackTrace]. */
internal const val MAX_CAUSE_DEPTH = 8

/** Frames printed per throwable by [safeStackTrace] before eliding the rest. */
internal const val MAX_FRAMES_PER_THROWABLE = 40

private const val CHAIN_SEPARATOR = "<-"
private const val CHAIN_ELLIPSIS = "..."

/** Returned by [errorClass] when the throwable cannot even be described. */
internal const val UNKNOWN_ERROR_CLASS = "Throwable"

/** Returned by [safeStackTrace] when the trace cannot be built. */
internal const val UNAVAILABLE_STACK_TRACE = "Throwable (stack trace unavailable)"

/**
 * Class chain of [t] for a log line, without any message: `TransportException`, or
 * `TransportException<-SocketTimeoutException` when it has causes.
 *
 * Exception messages are never safe to log (they quote hostnames, paths, peer text), the
 * class chain is. Bounded to [MAX_CAUSE_DEPTH] and cycle safe: a longer or looping chain
 * ends with `<-...`. An anonymous class falls back on its stable JVM name, for example
 * `HostImportService$import$1`.
 *
 * Never throws: a throwable whose class or cause cannot be read gives
 * [UNKNOWN_ERROR_CLASS].
 *
 * Usage: `Logger.w(TAG, "Sync push failed (${errorClass(e)})")`.
 */
fun errorClass(t: Throwable): String = failClosed(UNKNOWN_ERROR_CLASS) {
    val chain = causeChain(t)
    val names = chain.throwables.joinToString(CHAIN_SEPARATOR) { neutralizeControlChars(throwableClassName(it)) }
    if (chain.truncated) names + CHAIN_SEPARATOR + CHAIN_ELLIPSIS else names
}

/**
 * Stack trace of [t] and its causes for debug output, with class names and frames only:
 * no message, no `toString()` of the throwable. Layout follows the JVM one:
 *
 * ```
 * fr.techtical.nextsh.Foo$TransportException
 *     at fr.techtical.nextsh.Foo.connect(Foo.kt:42)
 *     ... 3 more
 * Caused by: java.net.SocketTimeoutException
 *     at java.net.Socket.connect(Socket.java:633)
 *     ... 12 more
 * ```
 *
 * Frames shared with the enclosing trace are elided, at most [MAX_FRAMES_PER_THROWABLE]
 * frames are printed per throwable, the chain is bounded by [MAX_CAUSE_DEPTH] and cycle safe.
 * Suppressed exceptions are not printed. Never throws: a trace that cannot be built gives
 * [UNAVAILABLE_STACK_TRACE].
 */
fun safeStackTrace(t: Throwable): String = failClosed(UNAVAILABLE_STACK_TRACE) {
    val chain = causeChain(t)
    val out = StringBuilder()
    var enclosing: Array<StackTraceElement> = emptyArray()
    chain.throwables.forEachIndexed { index, current ->
        if (index > 0) out.append('\n').append("Caused by: ")
        out.append(neutralizeControlChars(current.javaClass.name))
        val frames = current.stackTrace
        val inCommon = framesInCommon(frames, enclosing)
        val own = frames.size - inCommon
        val printed = minOf(own, MAX_FRAMES_PER_THROWABLE)
        for (i in 0 until printed) out.append("\n\tat ").append(formatFrame(frames[i]))
        val elided = frames.size - printed
        if (elided > 0) out.append("\n\t... ").append(elided).append(" more")
        enclosing = frames
    }
    if (chain.truncated) out.append('\n').append("Caused by: ").append(CHAIN_ELLIPSIS)
    out.toString()
}

/** Simple class name of [t], or the last segment of its JVM name for an anonymous class. */
internal fun throwableClassName(t: Throwable): String {
    val simple = t::class.simpleName
    if (!simple.isNullOrEmpty()) return simple
    return t.javaClass.name.substringAfterLast('.')
}

private class CauseChain(val throwables: List<Throwable>, val truncated: Boolean)

private fun causeChain(t: Throwable): CauseChain {
    val seen = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
    var current: Throwable? = t
    while (current != null) {
        val node: Throwable = current
        if (seen.any { it === node } || seen.size == MAX_CAUSE_DEPTH) return CauseChain(seen, truncated = true)
        seen += node
        current = node.cause
    }
    return CauseChain(seen, truncated = false)
}

private fun framesInCommon(frames: Array<StackTraceElement>, enclosing: Array<StackTraceElement>): Int {
    var m = frames.size - 1
    var n = enclosing.size - 1
    while (m >= 0 && n >= 0 && frames[m] == enclosing[n]) {
        m--
        n--
    }
    return frames.size - 1 - m
}

// Rebuilt by hand: StackTraceElement.toString() may prepend class loader and module names.
private fun formatFrame(e: StackTraceElement): String {
    val location = when {
        e.isNativeMethod -> "Native Method"
        e.fileName == null -> "Unknown Source"
        e.lineNumber >= 0 -> "${e.fileName}:${e.lineNumber}"
        else -> e.fileName
    }
    return neutralizeControlChars("${e.className}.${e.methodName}($location)")
}
