// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import com.jediterm.terminal.TerminalExecutorServiceManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless implementation of [TerminalExecutorServiceManager] for the
 * Compose-native terminal renderer migration (replaces the Swing-based
 * `JediTermExecutorServiceManager` which lives in jediterm-ui and is not
 * available when running without the Swing widget).
 *
 * Two executors per terminal session, both shut down by [shutdownWhenAllExecuted]:
 *  - **scheduled** (single-thread): used by [com.jediterm.terminal.TerminalStarter]
 *    to debounce resize events (delay before notifying the PTY).
 *  - **unbounded** (cached pool): used by `TerminalStarter.start()` to run the
 *    blocking emulator parse loop (one task per session) and by typeahead.
 *
 * Threads are daemon + named for diagnostic purposes (`jediterm-<sessionId>-<role>`)
 * so they don't keep the JVM alive on close, and so jstack output is usable.
 */
class ComposeExecutorServiceManager(
    private val sessionId: String,
) : TerminalExecutorServiceManager {

    private val unboundedSeq = AtomicInteger(0)

    private val scheduled: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "jediterm-$sessionId-scheduled").apply { isDaemon = true }
        }

    private val unbounded: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "jediterm-$sessionId-worker-${unboundedSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = scheduled

    override fun getUnboundedExecutorService(): ExecutorService = unbounded

    override fun shutdownWhenAllExecuted() {
        scheduled.shutdown()
        unbounded.shutdown()
        // Brief grace period so in-flight tasks (final emulator parse, last
        // resize notify) can complete before forcing termination. 5s is the
        // same budget JediTermExecutorServiceManager (Swing) uses.
        runCatching { scheduled.awaitTermination(5, TimeUnit.SECONDS) }
        runCatching { unbounded.awaitTermination(5, TimeUnit.SECONDS) }
        scheduled.shutdownNow()
        unbounded.shutdownNow()
    }
}
