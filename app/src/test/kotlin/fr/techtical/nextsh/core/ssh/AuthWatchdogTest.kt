// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The watchdog is the server-side clock of a FIDO2 authentication: it cuts the connection when the
 * server stays silent, never while the user is at work with the key, and it leaves no timer behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthWatchdogTest {

    private var expirations = 0

    private fun TestScope.watchdog(timeoutMs: Long = 30_000) = AuthWatchdog(this, timeoutMs) { expirations++ }

    private fun TestScope.pendingTimers(): Int = coroutineContext.job.children.count { it.isActive }

    @Test
    fun `an armed watchdog expires after the timeout, once`() = runTest {
        val watchdog = watchdog()

        watchdog.arm()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, expirations, "just before the timeout")
        assertFalse(watchdog.expired)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, expirations)
        assertTrue(watchdog.expired)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, expirations, "it fires once")
    }

    @Test
    fun `a watchdog that was never armed does nothing`() = runTest {
        val watchdog = watchdog()

        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(0, expirations)
        assertFalse(watchdog.expired)
    }

    @Test
    fun `a disarmed watchdog does not expire and leaves no timer`() = runTest {
        val watchdog = watchdog()
        watchdog.arm()
        advanceTimeBy(10_000)

        watchdog.disarm()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(0, expirations)
        assertEquals(0, pendingTimers())
    }

    @Test
    fun `arming again after a disarm gives a whole new timeout`() = runTest {
        // The user took 3 minutes with the key: the server's clock starts when the prompt ends.
        val watchdog = watchdog()
        watchdog.arm()
        watchdog.disarm()
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(0, expirations)

        watchdog.arm()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, expirations)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, expirations)
    }

    @Test
    fun `arming twice restarts the countdown instead of stacking two`() = runTest {
        val watchdog = watchdog()
        watchdog.arm()
        advanceTimeBy(20_000)
        watchdog.arm()
        advanceTimeBy(20_000) // 40 s after the first arm, 20 s after the second
        runCurrent()
        assertEquals(0, expirations, "the first timer was replaced")

        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, expirations, "30 s after the second arm")
        assertEquals(0, pendingTimers())
    }

    @Test
    fun `a closed watchdog never expires and can no longer be armed`() = runTest {
        val watchdog = watchdog()
        watchdog.arm()

        watchdog.close()
        watchdog.arm()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(0, expirations)
        assertFalse(watchdog.expired)
        assertEquals(0, pendingTimers(), "no timer outlives the authentication")
    }

    @Test
    fun `an expired watchdog stays expired and ignores later arms`() = runTest {
        val watchdog = watchdog()
        watchdog.arm()
        advanceTimeBy(30_001)
        runCurrent()

        watchdog.arm()
        watchdog.disarm()
        watchdog.close()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(1, expirations)
        assertTrue(watchdog.expired, "the caller can still tell why the connection was cut")
    }

    @Test
    fun `a timer that has waited its whole delay still loses to a disarm that gets the lock first`() = runTest {
        // The generation guard: the timer is past its delay, about to take the lock to expire the
        // watchdog, when the authentication moves on (the user's prompt opens) and disarms it.
        val watchdog = watchdog()
        watchdog.onTimerElapsed = { watchdog.disarm() }
        watchdog.arm()

        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(0, expirations, "the overtaken timer must not expire the watchdog")
        assertFalse(watchdog.expired)
    }

    @Test
    fun `a timer past its delay also loses to a re-arm, which is a new countdown`() = runTest {
        val watchdog = watchdog()
        var rearmed = false
        watchdog.onTimerElapsed = {
            if (!rearmed) {
                rearmed = true
                watchdog.arm() // the prompt ended and the server's clock starts again
            }
        }
        watchdog.arm()

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(0, expirations, "the first timer was replaced while it was about to fire")

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, expirations, "the new countdown expires on its own")
    }

    @Test
    fun `a timer past its delay loses to a close, and close says it had not expired`() = runTest {
        val watchdog = watchdog()
        var closedExpired: Boolean? = null
        watchdog.onTimerElapsed = { closedExpired = watchdog.close() }
        watchdog.arm()

        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(0, expirations)
        assertEquals(false, closedExpired)
    }

    @Test
    fun `close tells whether the watchdog had expired, so a success that raced it can be refused`() = runTest {
        val quiet = watchdog()
        quiet.arm()
        assertFalse(quiet.close(), "closed before the timeout")

        val expired = watchdog()
        expired.arm()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(expired.close(), "closed after the timeout: the connection was cut")
        assertTrue(expired.close(), "and it stays so")
    }

    @Test
    fun `the expiry is carried out outside the lock, so it can call back into the watchdog`() = runTest {
        // onExpire closes a connection, which wakes other threads that may call the watchdog (the
        // handler of the key prompt disarms and arms it). If the lock were held, the thread below
        // could not get in while the expiry waits for it.
        lateinit var watchdog: AuthWatchdog
        var calledBackWhileExpiring = false
        watchdog = AuthWatchdog(this, 30_000) {
            val other = kotlin.concurrent.thread(isDaemon = true) {
                watchdog.disarm()
                watchdog.arm()
            }
            other.join(5_000)
            calledBackWhileExpiring = !other.isAlive
        }
        watchdog.arm()

        advanceTimeBy(30_001)
        runCurrent()

        assertTrue(watchdog.expired)
        assertTrue(calledBackWhileExpiring, "another thread could use the watchdog while onExpire ran")
    }

    @Test
    fun `a disarm racing the end of the timer wins`() = runTest {
        // The timer is due at exactly 30 s; the disarm comes in the same instant, before it runs.
        val watchdog = watchdog()
        watchdog.arm()
        advanceTimeBy(30_000)

        watchdog.disarm()
        runCurrent()

        assertEquals(0, expirations)
        assertFalse(watchdog.expired)
    }
}
