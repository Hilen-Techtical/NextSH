// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bounds how long the server may stay silent during an authentication that cannot rely on SSHJ's
 * own wait.
 *
 * Why SSHJ's wait is not enough for a FIDO2 key. `SSHClient.auth` waits for the server's answer
 * on a promise bounded by `Transport.timeoutMs`, and the handler of `SkAuthMethod`, where the user
 * touches the key, runs on the transport's reader thread while that wait is running. The wait has
 * to outlast the longest prompt, so it can no longer be short ([FIDO2_AUTH_TRANSPORT_TIMEOUT_MS]),
 * and nothing else bounds a server that goes quiet: SSHJ's reader ignores the socket read timeout
 * and keeps reading, so a network that drops silently after the signature was sent, with no FIN or
 * RST, would pin the thread of `client.auth` for ever (and the tunnel card in STARTING).
 *
 * The watchdog is the server-side clock:
 *  - [arm]ed when the authentication starts, to bound the wait for the server to accept the key;
 *  - [disarm]ed while the user works with the key, whose own clock is the prompt's deadline;
 *  - [arm]ed again when the prompt ends (the signature is handed to SSHJ, or there is none and the
 *    authentication is about to fail);
 *  - [close]d as soon as the authentication returns, whatever the outcome.
 *
 * When it expires it calls [onExpire], which closes the connection: that wakes `client.auth` with
 * a transport exception, and [expired] tells the caller why. [onExpire] runs outside the lock of
 * the watchdog, so it can call back into it (or block on something that does) without a deadlock.
 * Its timer is a coroutine of [scope], which should not be the scope of a coroutine that can be
 * cancelled while `client.auth` is blocking: the timer would die with it.
 *
 * The expiry can race the end of the authentication: `client.auth` may return successfully just as
 * the timer fires. [close] therefore says whether the watchdog had expired, and the caller must then
 * treat the attempt as cut (its connection is being closed), not as a success.
 *
 * @param timeoutMs how long the server may take to answer, from each [arm].
 */
internal class AuthWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val onExpire: () -> Unit,
) {
    private val lock = Any()

    /**
     * Which arming the pending timer belongs to. A timer that is not the current one does nothing,
     * even if it was already running when it was replaced.
     */
    private var generation = 0L
    private var timer: Job? = null
    private var closed = false

    /**
     * Test hook, called by a timer that has waited its whole delay, right before it takes the lock
     * to expire the watchdog: the instant at which a [disarm] or a [close] can still overtake it.
     */
    @androidx.annotation.VisibleForTesting
    internal var onTimerElapsed: (() -> Unit)? = null

    /** True once the server stayed silent for [timeoutMs] and the watchdog decided to call [onExpire]. */
    @Volatile
    var expired = false
        private set

    /** Starts the countdown, or restarts it. No effect once the watchdog is closed or has expired. */
    fun arm() {
        synchronized(lock) {
            if (closed || expired) return
            timer?.cancel()
            val mine = ++generation
            // A child of the scope of the authentication: cancelled by disarm and close, since the
            // scope would otherwise wait for a pending timer before it could complete.
            timer = scope.launch {
                delay(timeoutMs)
                onTimerElapsed?.invoke()
                fire(mine)
            }
        }
    }

    /** Stops the countdown (the user is at work). It can be [arm]ed again. */
    fun disarm() {
        synchronized(lock) { stopTimer() }
    }

    /**
     * Ends the watchdog for good: the authentication returned. Returns true if it had already
     * expired, i.e. if the connection was cut (or is being cut) under an authentication that came
     * back anyway.
     */
    fun close(): Boolean {
        synchronized(lock) {
            closed = true
            stopTimer()
            return expired
        }
    }

    private fun stopTimer() {
        generation++
        timer?.cancel()
        timer = null
    }

    private fun fire(mine: Long) {
        // Decided under the lock, done outside it: [onExpire] closes a connection, which can wake
        // other threads that call back into this watchdog.
        synchronized(lock) {
            if (closed || expired || mine != generation) return
            expired = true
            timer = null
        }
        onExpire()
    }
}
