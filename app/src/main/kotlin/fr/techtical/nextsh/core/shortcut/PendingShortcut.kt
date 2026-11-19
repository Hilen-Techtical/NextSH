// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The launcher shortcut that `MainActivity` took from an intent (already checked against the token
 * of this install, see [readShortcutRequest]) and that `NavGraph` has not acted on yet.
 *
 * Why a singleton and not a field of the activity. A shortcut tapped on the lock screen waits for
 * the unlock, and the activity can be recreated meanwhile (dark mode, language, font size). The
 * recreated activity does not read the extras again, see [readShortcutRequest]: a request kept in the
 * activity would be lost, and one re-read from the intent would act a second time. Held here, it
 * survives the recreation and is spent once. Like the tunnels of `PendingAuthResume`, it lives in
 * memory only: after the death of the process there is nothing to act on, and nothing is written
 * to disk, to a Bundle or to a log.
 *
 * - [offer] puts the request of an incoming intent. The newest intent replaces the pending request,
 *   and an intent without a valid shortcut ([offer] with null: the launcher icon, a notification, a
 *   forged intent) clears it: the user has moved on.
 * - [consume] is the compare and clear: it spends the request only if it is still the one the caller
 *   saw, and only once. It is also where the request expires, see [TTL_MS].
 * - [dropExpired] forgets a request that waited too long. The activity calls it each time it comes
 *   up. Expiry is lazy, applied whenever the holder is touched, and [consume] applies it too, so a
 *   user who stays on the lock screen for an hour does not get the shortcut after the unlock.
 *
 * The clock is the monotonic one of the JVM, injectable for tests.
 */
@Singleton
class PendingShortcut internal constructor(
    private val clock: () -> Long,
) {

    @Inject
    constructor() : this({ System.nanoTime() / NANOS_PER_MILLI })

    private val lock = Any()

    /** When [request] was last offered, on [clock]. */
    private var offeredAtMs = 0L

    private val _request = MutableStateFlow<ShortcutRequest?>(null)

    /**
     * The request waiting to act, or null. `NavGraph` follows it: it acts as soon as the vault is
     * unlocked. It can be an expired request until the next [consume] or [dropExpired] clears it;
     * [consume] is what says whether it may still act.
     */
    val request: StateFlow<ShortcutRequest?> = _request.asStateFlow()

    /**
     * Replaces the pending request by [request] and starts its time to live again. Null clears the
     * pending request. Offering the same request twice (the user tapped twice) renews it.
     */
    fun offer(request: ShortcutRequest?) {
        synchronized(lock) {
            _request.value = request
            offeredAtMs = clock()
        }
    }

    /**
     * Spends [expected]. True if it is the pending request and it has not expired: it is cleared and
     * the caller acts, once. False for anything else, and then nothing changes, except that an
     * expired [expected] is cleared without acting.
     */
    fun consume(expected: ShortcutRequest): Boolean = synchronized(lock) {
        if (_request.value != expected) return@synchronized false
        val fresh = !isExpired()
        _request.value = null
        fresh
    }

    /**
     * Forgets the pending request if it has waited [TTL_MS] or more. The activity calls it each time
     * it comes up, so that a shortcut abandoned on the lock screen does not act, or replace a newer
     * one, much later.
     */
    fun dropExpired() {
        synchronized(lock) {
            if (_request.value != null && isExpired()) _request.value = null
        }
    }

    /** Called with [lock] held. */
    private fun isExpired(): Boolean = clock() - offeredAtMs >= TTL_MS

    companion object {
        /**
         * How long a shortcut may wait for the unlock. The tap is the consent, but it was given to
         * what the user saw: a connection or a tunnel that starts long after, from a lock screen the
         * user walked away from, is a surprise. Two minutes cover a slow unlock (the fingerprint read
         * again, a call that interrupts) and no more. Shorter than the ten minutes of
         * `PendingAuthResume.READY_TTL_MS`, whose request is a notification the user chose to act on,
         * where a shortcut is a launcher tap that is expected to act at once.
         */
        const val TTL_MS = 2 * 60_000L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
