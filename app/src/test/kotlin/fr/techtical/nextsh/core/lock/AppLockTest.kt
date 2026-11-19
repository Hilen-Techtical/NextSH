// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.lock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When NextSH locks itself: from the start of the process, when asked, and when it stayed out of the
 * foreground for at least the delay of the settings. The clock is injected, so that "five minutes" is
 * a number and not a wait.
 */
class AppLockTest {

    private var now = 1_000_000L
    private val lock = AppLock { now }

    private fun minutes(count: Long) = count * 60_000L

    /** The app leaves the foreground, stays [away] ms, and comes back. */
    private fun leaveAndReturnAfter(away: Long) {
        lock.onAppHidden()
        now += away
        lock.onAppShown()
    }

    // ── The start ─────────────────────────────────────────────────────────────

    @Test
    fun `the app is locked from the start of the process`() {
        assertTrue(lock.isLocked())
        assertTrue(lock.locked.value)
    }

    @Test
    fun `coming to the foreground for the first time does not unlock`() {
        lock.onAppShown()

        assertTrue(lock.isLocked())
    }

    // ── unlock and lock ───────────────────────────────────────────────────────

    @Test
    fun `unlock opens the app and lock covers it again`() {
        lock.unlock()
        assertFalse(lock.isLocked())
        assertFalse(lock.locked.value)

        lock.lock()
        assertTrue(lock.isLocked())
        assertTrue(lock.locked.value)
    }

    @Test
    fun `locking a locked app and unlocking an open one change nothing`() {
        lock.lock()
        lock.lock()
        assertTrue(lock.isLocked())

        lock.unlock()
        lock.unlock()
        assertFalse(lock.isLocked())
    }

    // ── The delay ─────────────────────────────────────────────────────────────

    @Test
    fun `the delay is five minutes until the settings say otherwise`() {
        lock.unlock()

        leaveAndReturnAfter(minutes(5) - 1)
        assertFalse(lock.isLocked(), "one millisecond short of five minutes")

        leaveAndReturnAfter(minutes(5))
        assertTrue(lock.isLocked(), "five minutes is enough: the delay is reached, not exceeded")
    }

    @Test
    fun `the delay of the settings is the one that counts, each of them`() {
        for (delay in AutoLockDelay.entries) {
            lock.setAutoLockDelay(delay)

            lock.unlock()
            if (delay.millis > 0) {
                leaveAndReturnAfter(delay.millis - 1)
                assertFalse(lock.isLocked(), "$delay: just under")
            }
            leaveAndReturnAfter(delay.millis)
            assertTrue(lock.isLocked(), "$delay: reached")
        }
    }

    @Test
    fun `with the delay at zero any absence locks`() {
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()

        leaveAndReturnAfter(0)

        assertTrue(lock.isLocked())
    }

    @Test
    fun `a delay changed while the app is away applies when it comes back`() {
        lock.unlock()
        lock.onAppHidden()
        now += minutes(2)

        lock.setAutoLockDelay(AutoLockDelay.ONE_MINUTE)
        lock.onAppShown()

        assertTrue(lock.isLocked())
    }

    @Test
    fun `an app that was never out of the foreground is never locked by the clock`() {
        lock.unlock()

        now += minutes(60)
        lock.onAppShown()

        assertFalse(lock.isLocked(), "a rotation stops and starts the activity without telling onAppHidden")
    }

    @Test
    fun `a return forgets the moment of the absence, so the next start does not count it again`() {
        lock.unlock()
        leaveAndReturnAfter(minutes(1))
        assertFalse(lock.isLocked())

        now += minutes(60)
        lock.onAppShown()

        assertFalse(lock.isLocked())
    }

    @Test
    fun `a second stop before the return does not push the lock back`() {
        lock.unlock()
        lock.onAppHidden()
        now += minutes(4)
        lock.onAppHidden()
        now += minutes(1)

        lock.onAppShown()

        assertTrue(lock.isLocked(), "five minutes since the first stop")
    }

    // ── The unlock forgets the moment the app was hidden ──────────────────────

    @Test
    fun `an unlock forgets the absence, the screen of the phone code stops the activity while it is up`() {
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.onAppHidden() // the screen where the phone asks for its code stops the activity

        lock.unlock() // the answer of the prompt, before the return
        lock.onAppShown() // the return

        assertFalse(lock.isLocked(), "unlocked right after, with the delay at zero, would be a lock the user cannot get out of")
    }

    @Test
    fun `the other order works too, the return first and the unlock after`() {
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.onAppHidden()

        lock.onAppShown() // locked already: stays so
        assertTrue(lock.isLocked())
        lock.unlock()

        assertFalse(lock.isLocked())
    }

    // ── The vault dialog and the screen of the phone code ─────────────────────

    @Test
    fun `an absence while the vault dialog is up is not noted, the code screen stops the activity without the owner leaving`() {
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()

        lock.onAppHidden(ownerPromptOpen = true) // the screen of the phone code stops the activity
        lock.onAppShown() // the return, whichever side of the answer of the dialog it falls

        assertFalse(lock.isLocked(), "locked right after a confirmation of the owner, with the delay at zero")
    }

    @Test
    fun `an absence with no vault dialog up is noted as ever`() {
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()

        lock.onAppHidden(ownerPromptOpen = false)
        lock.onAppShown()

        assertTrue(lock.isLocked())
    }

    @Test
    fun `an absence is noted by default, a caller that says nothing about a dialog starts the clock`() {
        lock.setAutoLockDelay(AutoLockDelay.ONE_MINUTE)
        lock.unlock()

        lock.onAppHidden()
        now += minutes(1)
        lock.onAppShown()

        assertTrue(lock.isLocked())
    }

    @Test
    fun `a stop that is not noted does not stop a later one from being noted`() {
        lock.unlock()
        lock.onAppHidden(ownerPromptOpen = true)
        lock.onAppShown()

        leaveAndReturnAfter(minutes(5))

        assertTrue(lock.isLocked())
    }

    @Test
    fun `an absence noted when the vault dialog ends, with the app still away, locks after the delay from that moment`() {
        lock.setAutoLockDelay(AutoLockDelay.ONE_MINUTE)
        lock.unlock()
        lock.onAppHidden(ownerPromptOpen = true) // the owner left by Home with the dialog up: not noted
        now += minutes(10)

        lock.onAppHidden() // the dialog ends in the background: noted now
        now += minutes(1) - 1
        lock.onAppShown()
        assertFalse(lock.isLocked(), "just under the delay since the end of the dialog")

        lock.onAppHidden()
        now += minutes(1)
        lock.onAppShown()
        assertTrue(lock.isLocked())
    }

    // ── The state flow ────────────────────────────────────────────────────────

    @Test
    fun `the state flow follows lock and unlock`() {
        val seen = mutableListOf<Boolean>()

        seen += lock.locked.value
        lock.unlock()
        seen += lock.locked.value
        leaveAndReturnAfter(minutes(30))
        seen += lock.locked.value

        assertEquals(listOf(true, false, true), seen)
    }
}
