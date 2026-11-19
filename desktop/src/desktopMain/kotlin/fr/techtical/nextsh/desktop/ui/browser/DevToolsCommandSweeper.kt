// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.shared.util.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Works around a leak in JCEF's `CefDevToolsClient`.
 *
 * The client keeps one entry per command in its private `queuedCommands_` map (command id to
 * the future of its result). The entry is created when the command is sent and completed when
 * the result arrives, but never removed: until the browser closes, every command leaves one
 * (small) entry behind. With the `*` Fetch pattern every request of every page costs a
 * `Fetch.continueRequest`, so the map grows with the browsing session.
 *
 * The sweeper reads that map through reflection and removes the entries whose future is done.
 * An entry is removed only after it has been seen done for [MIN_AGE_MS]: the result of a
 * command is delivered right after its id, but a consumer that has not asked for the entry
 * yet must never find it gone, or its command would never complete. Entries that never
 * complete (a command targeting a request that disappeared) are left alone.
 *
 * Fully guarded: [forClient] gives null when the field is missing, null or not a map, and any
 * failure while sweeping switches the sweeper off for good. Nothing here can break the
 * browser, the worst case is the leak coming back.
 *
 * Thread safety: the map is the synchronized wrapper JCEF creates, whose lock is itself; it
 * is locked while iterating, as the wrapper requires.
 */
internal class DevToolsCommandSweeper private constructor(
    private val queue: MutableMap<Any?, Any?>,
    private val clock: () -> Long,
) {
    private val firstSeenDone = HashMap<Any?, Long>()
    private var lastSweepAt = Long.MIN_VALUE
    private var disabled = false

    /** True once a failure switched the sweeper off. */
    val isDisabled: Boolean
        @Synchronized get() = disabled

    /** Sweeps at most once per [SWEEP_INTERVAL_MS]. Cheap to call after every command. */
    @Synchronized
    fun maybeSweep() {
        if (disabled) return
        val now = clock()
        if (lastSweepAt != Long.MIN_VALUE && now - lastSweepAt in 0 until SWEEP_INTERVAL_MS) return
        lastSweepAt = now
        sweep(now)
    }

    /** Removes the entries seen done for at least [MIN_AGE_MS]. Returns how many were removed. */
    @Synchronized
    fun sweep(now: Long = clock()): Int {
        if (disabled) return 0
        return try {
            var removed = 0
            synchronized(queue) {
                val entries = queue.entries.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    val future = entry.value as? CompletableFuture<*> ?: continue
                    if (!future.isDone) {
                        firstSeenDone.remove(entry.key)
                        continue
                    }
                    val since = firstSeenDone.getOrPut(entry.key) { now }
                    if (now - since >= MIN_AGE_MS) {
                        entries.remove()
                        firstSeenDone.remove(entry.key)
                        removed++
                    }
                }
                // Forget the keys JCEF itself removed (it clears the map on close).
                firstSeenDone.keys.retainAll { it in queue }
            }
            removed
        } catch (_: Exception) {
            disabled = true
            // Fixed text: an exception message could say anything.
            Logger.w(TAG, "devtools command queue sweeping disabled")
            0
        }
    }

    companion object {
        private const val TAG = "DevToolsSweeper"
        private val absentLogged = AtomicBoolean(false)

        /** Name of the field in `org.cef.browser.CefDevToolsClient`. */
        internal const val FIELD_NAME = "queuedCommands_"

        /** Done entries are removed once they were done for this long. */
        internal const val MIN_AGE_MS = 2_000L

        /** [maybeSweep] does nothing more often than this. */
        internal const val SWEEP_INTERVAL_MS = 1_000L

        /**
         * The sweeper for [client]'s command queue, or null when it cannot be reached (field
         * absent, not accessible, null, or not a map): the caller then simply goes without.
         */
        fun forClient(client: Any, clock: () -> Long = System::currentTimeMillis): DevToolsCommandSweeper? {
            val sweeper = try {
                val field = client.javaClass.getDeclaredField(FIELD_NAME)
                field.isAccessible = true
                forMap(field.get(client), clock)
            } catch (_: Exception) {
                null
            }
            if (sweeper == null && absentLogged.compareAndSet(false, true)) {
                // Once per process, fixed text: a JCEF update that renames the field is worth knowing.
                Logger.w(TAG, "devtools command queue not reachable, finished commands are not swept")
            }
            return sweeper
        }

        /** Lets a test see the "not reachable" line again. */
        internal fun resetLogForTest() {
            absentLogged.set(false)
        }

        /** The sweeper for [map] when it is a map, null otherwise. */
        @Suppress("UNCHECKED_CAST")
        fun forMap(map: Any?, clock: () -> Long = System::currentTimeMillis): DevToolsCommandSweeper? =
            (map as? MutableMap<Any?, Any?>)?.let { DevToolsCommandSweeper(it, clock) }
    }
}
