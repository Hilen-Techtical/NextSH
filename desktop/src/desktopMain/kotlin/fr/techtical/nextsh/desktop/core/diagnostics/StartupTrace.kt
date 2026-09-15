// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.diagnostics

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Centralised startup timing instrumentation for the Desktop launch path.
 *
 * Two log shapes:
 *  - `[startup +Xms] <label>`: checkpoint relative to t0 (process start)
 *  - `[startup async] <label> in Yms`: a measured duration outside the linear path
 *
 * Output sinks:
 *  - Always: `println` (visible when launching from a terminal)
 *  - When `NEXTSH_STARTUP_LOG=1`: also appended to `<NEXTSH_HOME or ~/.nextsh>/startup.log`
 *    so packaged builds without a console can still be profiled.
 *
 * **All file I/O runs on a daemon thread.** The wall-clock timestamp
 * for each line is captured on the calling thread BEFORE handing off,
 * so the log reflects when the mark fired: not when the daemon got
 * around to flushing it. This matters because the first file write on
 * a cold install (mkdirs ~/.nextsh + FileWriter create on a directory
 * Windows Defender has just been told about) can block the caller for
 * ~2 s, which would otherwise pollute every cold-start measurement.
 *
 * t0 is captured at the first reference to this object (JVM class init).
 * Call sites are expected to fire [mark] very early in `fun main()` so
 * the offset stays meaningful.
 */
object StartupTrace {

    private val t0Nanos = System.nanoTime()
    private val fileEnabled = System.getenv("NEXTSH_STARTUP_LOG") == "1"
    private val tsFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * Single-threaded daemon executor. Daemon so it doesn't keep the
     * JVM alive after `main()` returns; single-threaded so writes stay
     * ordered. Only allocated when file logging is on: zero overhead
     * for production users who never set the env var.
     */
    private val ioExecutor: ExecutorService? = if (fileEnabled) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "nextsh-startup-trace").apply { isDaemon = true }
        }
    } else {
        null
    }

    @Volatile
    private var writer: PrintWriter? = null

    init {
        if (ioExecutor != null) {
            // Best-effort flush at JVM exit. 2 s timeout is enough to
            // drain the typical handful of marks queued during startup;
            // we don't want to delay the user's app close beyond that.
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    ioExecutor.shutdown()
                    try {
                        ioExecutor.awaitTermination(2, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    writer?.flush()
                    writer?.close()
                },
            )
        }
    }

    fun mark(label: String) {
        val ms = (System.nanoTime() - t0Nanos) / 1_000_000
        emit("[startup +${ms}ms] $label")
    }

    fun async(label: String, durationMs: Long) {
        emit("[startup async] $label in ${durationMs}ms")
    }

    private fun emit(line: String) {
        println(line)
        val exec = ioExecutor ?: return
        // Capture the wall-clock timestamp NOW, on the calling thread.
        // If we deferred this to the daemon, the log would show when
        // the daemon flushed (possibly seconds later on cold disk),
        // not when the mark actually fired.
        val ts = LocalDateTime.now().format(tsFormat)
        try {
            exec.execute { writeToFile(ts, line) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor was shutdown: happens after the shutdown hook
            // fires. Silently drop late marks; they aren't part of the
            // startup path anyway.
        }
    }

    private fun writeToFile(ts: String, line: String) {
        try {
            val w = writer ?: openWriter().also { writer = it } ?: return
            w.println("$ts  $line")
            w.flush()
        } catch (_: Exception) {
            // Diagnostic facility: never propagate a logging failure.
        }
    }

    private fun openWriter(): PrintWriter? {
        return try {
            val home = System.getenv("NEXTSH_HOME").takeUnless { it.isNullOrBlank() }
                ?: "${System.getProperty("user.home")}/.nextsh"
            val dir = File(home)
            if (!dir.exists()) dir.mkdirs()
            PrintWriter(FileWriter(File(dir, "startup.log"), /* append = */ true))
        } catch (_: Exception) {
            null
        }
    }
}
