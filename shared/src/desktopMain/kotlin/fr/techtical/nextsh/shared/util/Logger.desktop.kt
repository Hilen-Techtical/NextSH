// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import java.util.Collections

/**
 * Desktop actual: debug lines go to stdout, warnings and errors to stderr. On Linux both
 * usually end up in the systemd user journal, on disk, so every mode applies the same rule:
 *
 * - every message goes through [LogScrubber.clean]: a line carrying an unmistakable secret
 *   (PEM block, `password=`, `passphrase=`, `secret=`, `token=` ...) is replaced as a whole
 *   by [LogScrubber.REDACTED_LINE], other lines have their values masked;
 * - every tag goes through [LogScrubber.scrub]: values masked, never dropped as a whole;
 * - in both, line breaks and other control characters are neutralised, so a record is one
 *   line; the only line breaks printed are those between a debug message and its trace;
 * - a throwable is reduced to its class chain ([errorClass]) in release, and to class names
 *   plus frames ([safeStackTrace]) in debug. Its message is never printed.
 *
 * Debug mode only decides whether [d] prints and whether frames follow an error; it never
 * relaxes the scrubbing. No call ever throws: every helper fails closed.
 */
actual object Logger {

    enum class Level { DEBUG, WARN, ERROR }

    /** Test hook: receives each record exactly as it would be printed (already scrubbed). */
    fun interface Sink {
        fun write(level: Level, text: String)
    }

    @Volatile
    private var debugEnabled: Boolean = run {
        // 1. Explicit override via -Dnextsh.debug=true (highest priority).
        // 2. Otherwise: dev runs (no `compose.application.resources.dir`
        //    means we're NOT in a jpackage distribution → IDE / `gradle run`
        //    → emit debug logs by default for visibility).
        System.getProperty("nextsh.debug")?.toBoolean()
            ?: (System.getProperty("compose.application.resources.dir") == null)
    }

    /**
     * Test hook, not for production code: when non null, records go to this sink instead of
     * stdout / stderr. Always null in production. Prefer [capture], which restores the
     * previous sink. Public so that tests of other modules (`:desktop`) can use [capture].
     */
    @Volatile
    var sink: Sink? = null

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun isDebugEnabled(): Boolean = debugEnabled

    /**
     * Test helper, not for production code: runs [block] with a capturing [sink] and
     * returns the records it produced,
     * each one being the full printed text (`W/Tag: message`, stack trace lines included).
     * The previous sink is restored afterwards. Usable from a suspend test body.
     */
    inline fun capture(block: () -> Unit): List<String> {
        val previous = sink
        val records = Collections.synchronizedList(ArrayList<String>())
        sink = Sink { _, text -> records += text }
        try {
            block()
        } finally {
            sink = previous
        }
        return synchronized(records) { records.toList() }
    }

    actual fun d(tag: String, message: String) {
        if (!debugEnabled) return
        emit(Level.DEBUG, "D/${LogScrubber.scrub(tag)}: ${LogScrubber.clean(message)}")
    }

    actual fun w(tag: String, message: String) {
        emit(Level.WARN, "W/${LogScrubber.scrub(tag)}: ${LogScrubber.clean(message)}")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val line = "E/${LogScrubber.scrub(tag)}: ${LogScrubber.clean(message)}"
        val text = when {
            throwable == null -> line
            debugEnabled -> "$line [${errorClass(throwable)}]\n${safeStackTrace(throwable)}"
            else -> "$line [${errorClass(throwable)}]"
        }
        emit(Level.ERROR, text)
    }

    private fun emit(level: Level, text: String) {
        val target = sink
        when {
            target != null -> target.write(level, text)
            level == Level.DEBUG -> System.out.println(text)
            else -> System.err.println(text)
        }
    }
}
