// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.lock.AutoLockDelay
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app is visible while at least one instance of the main screen is started. The count is what
 * lets a rotation (the new instance starts before the old one is gone) or a relaunch overlap
 * without the app reading as hidden.
 */
class AppVisibilityTest {

    private val tracker = ActivityVisibilityTracker()

    @Test
    fun `a new tracker says hidden, nothing is on screen yet`() {
        assertFalse(tracker.isVisible())
    }

    @Test
    fun `one started instance makes the app visible, its stop hides it`() {
        tracker.onActivityStarted()
        assertTrue(tracker.isVisible())

        tracker.onActivityStopped()
        assertFalse(tracker.isVisible())
    }

    @Test
    fun `two overlapping instances keep the app visible until both have stopped`() {
        tracker.onActivityStarted()
        tracker.onActivityStarted()

        tracker.onActivityStopped()
        assertTrue(tracker.isVisible(), "the second instance is still on screen")

        tracker.onActivityStopped()
        assertFalse(tracker.isVisible())
    }

    @Test
    fun `a stray stop does not hide the next start`() {
        tracker.onActivityStopped()
        tracker.onActivityStopped()
        assertFalse(tracker.isVisible())

        tracker.onActivityStarted()
        assertTrue(tracker.isVisible(), "the count never went below zero")
    }

    @Test
    fun `the app can become visible again after it was hidden`() {
        repeat(3) {
            tracker.onActivityStarted()
            assertTrue(tracker.isVisible())
            tracker.onActivityStopped()
            assertFalse(tracker.isVisible())
        }
    }

    @Test
    fun `starts and stops from several threads balance out`() {
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                go.await()
                repeat(perThread) {
                    tracker.onActivityStarted()
                    // A read from another thread, the way the SSH transport asks.
                    tracker.isVisible()
                    tracker.onActivityStopped()
                }
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertFalse(tracker.isVisible(), "every start was matched by a stop")
    }

    @Test
    fun `the interface is satisfied by a lambda for the tests of other classes`() {
        var shown = true
        val visibility = AppVisibility { shown }

        assertTrue(visibility.isVisible())
        shown = false
        assertFalse(visibility.isVisible())
    }

    // ── A recreation for a configuration change ───────────────────────────────

    private var nowMs = 10_000L
    private val clocked = ActivityVisibilityTracker { nowMs }

    @Test
    fun `a rotation does not read as hidden between the stop of the old instance and the start of the new one`() {
        clocked.onActivityStarted()

        clocked.onActivityStopped(changingConfigurations = true)

        assertTrue(clocked.isVisible(), "the framework stops the old instance before it starts the new one")
        clocked.onActivityStarted()
        assertTrue(clocked.isVisible())
    }

    @Test
    fun `the grace runs out if the new instance never comes`() {
        clocked.onActivityStarted()
        clocked.onActivityStopped(changingConfigurations = true)

        nowMs += ActivityVisibilityTracker.CONFIG_CHANGE_GRACE_MS - 1
        assertTrue(clocked.isVisible(), "still within the grace")

        nowMs += 1
        assertFalse(clocked.isVisible(), "the grace is over: nobody started")
    }

    @Test
    fun `leaving the app right after a rotation is seen at once, the grace does not outlive the new instance`() {
        clocked.onActivityStarted()
        clocked.onActivityStopped(changingConfigurations = true)
        clocked.onActivityStarted()

        // The user presses Home 100 ms after the rotation: an ordinary stop, hidden now.
        nowMs += 100
        clocked.onActivityStopped(changingConfigurations = false)

        assertFalse(clocked.isVisible(), "no leftover grace from the rotation")
    }

    @Test
    fun `an ordinary stop gives no grace`() {
        clocked.onActivityStarted()

        clocked.onActivityStopped(changingConfigurations = false)

        assertFalse(clocked.isVisible())
        clocked.onActivityStarted()
        clocked.onActivityStopped()
        assertFalse(clocked.isVisible(), "the default is an ordinary stop")
    }

    @Test
    fun `a recreation while another instance is up changes nothing`() {
        clocked.onActivityStarted()
        clocked.onActivityStarted()

        clocked.onActivityStopped(changingConfigurations = true)
        assertTrue(clocked.isVisible())

        clocked.onActivityStopped(changingConfigurations = false)
        assertFalse(clocked.isVisible(), "the last instance left for good")
    }

    @Test
    fun `a stray stop that claims a recreation does not make a hidden app visible`() {
        // Nothing was started: there is no screen that could come back.
        clocked.onActivityStopped(changingConfigurations = true)

        assertFalse(clocked.isVisible())
    }

    // ── Coming and going, as the lock hears of it ─────────────────────────────

    @Test
    fun `only the first start is a return and only the last stop is an absence`() {
        assertTrue(tracker.onActivityStarted(), "0 to 1: the app comes to the foreground")
        assertFalse(tracker.onActivityStarted(), "1 to 2: a relaunch, the app was there")

        assertFalse(tracker.onActivityStopped(), "2 to 1: the old instance stops, the app is still there")
        assertTrue(tracker.onActivityStopped(), "1 to 0: the app leaves the foreground")
    }

    @Test
    fun `a stray stop is no absence`() {
        assertFalse(tracker.onActivityStopped())
        assertFalse(tracker.onActivityStopped(changingConfigurations = true))
    }

    @Test
    fun `a rotation is a stop that the caller excludes, and the start that follows is a return with nothing to count`() {
        clocked.onActivityStarted()

        assertTrue(clocked.onActivityStopped(changingConfigurations = true), "it is the last stop: the caller tells a rotation apart")
        assertTrue(clocked.onActivityStarted(), "the new instance: the count was at 0, so it reads as a return")
    }

    /** What the activity does with them, with a real lock: delay zero, the harshest. */
    private fun activityStarts(lock: AppLock, tracker: ActivityVisibilityTracker) {
        if (tracker.onActivityStarted()) lock.onAppShown()
    }

    private fun activityStops(lock: AppLock, tracker: ActivityVisibilityTracker, rotation: Boolean = false) {
        val left = tracker.onActivityStopped(changingConfigurations = rotation)
        if (left && !rotation) lock.onAppHidden()
    }

    @Test
    fun `the stop of an old instance while a new one is up does not start the clock of the lock`() {
        val lock = AppLock { 0L }
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()
        val tracker = ActivityVisibilityTracker()

        activityStarts(lock, tracker) // the first instance
        activityStarts(lock, tracker) // a relaunch: the second starts before the first stops
        activityStops(lock, tracker) // the old one stops
        activityStarts(lock, tracker) // anything that starts again

        assertFalse(lock.isLocked(), "the app was never out of the foreground")
    }

    @Test
    fun `an absence of the only instance does start the clock, and the return locks`() {
        val lock = AppLock { 0L }
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()
        val tracker = ActivityVisibilityTracker()
        activityStarts(lock, tracker)

        activityStops(lock, tracker)
        activityStarts(lock, tracker)

        assertTrue(lock.isLocked())
    }

    @Test
    fun `a rotation never locks, however short the delay`() {
        val lock = AppLock { 0L }
        lock.setAutoLockDelay(AutoLockDelay.IMMEDIATELY)
        lock.unlock()
        val tracker = ActivityVisibilityTracker()
        activityStarts(lock, tracker)

        repeat(3) {
            activityStops(lock, tracker, rotation = true)
            activityStarts(lock, tracker)
        }

        assertFalse(lock.isLocked())
    }

    // ── Behind the lock screen ────────────────────────────────────────────────

    private val appLock = AppLock { 0L }
    private val seenByThePrompts = UnlockedAppVisibility(tracker, appLock)

    @Test
    fun `an app on screen and unlocked is visible to the prompts that need a person`() {
        tracker.onActivityStarted()
        appLock.unlock()

        assertTrue(seenByThePrompts.isVisible())
    }

    @Test
    fun `an app on screen but behind the lock screen counts as absent, like one in the background`() {
        tracker.onActivityStarted()
        appLock.unlock()
        appLock.lock()

        assertFalse(seenByThePrompts.isVisible())
    }

    @Test
    fun `an app that has not been unlocked since the process started counts as absent`() {
        tracker.onActivityStarted()

        assertFalse(seenByThePrompts.isVisible())
    }

    @Test
    fun `an app in the background is absent, unlocked or not`() {
        appLock.unlock()

        assertFalse(seenByThePrompts.isVisible())
    }

    @Test
    fun `the prompts see the app again as soon as it is unlocked`() {
        tracker.onActivityStarted()
        assertFalse(seenByThePrompts.isVisible())

        appLock.unlock()

        assertTrue(seenByThePrompts.isVisible())
    }

    // ── During the wipe of the vault ──────────────────────────────────────────

    @Test
    fun `an app on screen and unlocked counts as absent while the wipe of the vault has it closed`() {
        val gate = fr.techtical.nextsh.core.vault.VaultWipeGate()
        val seen = UnlockedAppVisibility(tracker, appLock, gate)
        tracker.onActivityStarted()
        appLock.unlock()
        assertTrue(seen.isVisible())

        for (state in listOf(
            fr.techtical.nextsh.core.vault.VaultWipeState.RUNNING,
            fr.techtical.nextsh.core.vault.VaultWipeState.FAILED,
            fr.techtical.nextsh.core.vault.VaultWipeState.DONE,
        )) {
            gate.set(state)

            assertFalse(seen.isVisible(), "$state: the prompts end with nobody on screen")
        }
    }

    @Test
    fun `the prompts see the app again once the wipe is over and the app restarted`() {
        val gate = fr.techtical.nextsh.core.vault.VaultWipeGate()
        val seen = UnlockedAppVisibility(tracker, appLock, gate)
        tracker.onActivityStarted()
        appLock.unlock()
        gate.set(fr.techtical.nextsh.core.vault.VaultWipeState.DONE)
        assertFalse(seen.isVisible())

        gate.acknowledgeDone()

        assertTrue(seen.isVisible())
    }
}
