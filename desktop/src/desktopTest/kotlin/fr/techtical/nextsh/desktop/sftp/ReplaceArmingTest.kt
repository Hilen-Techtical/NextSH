// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When "Replace" can be used: [runReplaceArming], on a virtual clock. Only the window that has
 * the focus may have it armed, and only [REPLACE_ARM_DELAY_MS] after its first frame; the
 * moment the focus goes, it is disarmed; when the focus comes back, the whole wait starts again.
 *
 * Why it matters: the windows of two waiting questions are stacked at the same place. The
 * delay of the one underneath must not run out while the user answers the one on top, or the
 * second click of a double click lands on an armed "Replace" of a question never read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceArmingTest {

    /** A first frame takes this long to be drawn. */
    private val frameMs = 16L

    private val delayMs = REPLACE_ARM_DELAY_MS

    /** What the dialog would see: the last state given to `setArmed`, and every state it was given. */
    private class Armed {
        var now = false
        val history = mutableListOf<Boolean>()
        fun set(value: Boolean) {
            now = value
            history += value
        }
    }

    /** Starts the arming on [focus], in the background of the test. */
    private fun TestScope.arm(
        focus: MutableStateFlow<Boolean>,
        armed: Armed,
        frame: suspend () -> Unit = { delay(frameMs) },
    ) {
        backgroundScope.launch {
            runReplaceArming(
                windowFocused = focus,
                awaitFirstFrame = frame,
                delayMs = delayMs,
                setArmed = armed::set,
            )
        }
        runCurrent()
    }

    @Test
    fun `a window without the focus is never armed`() = runTest {
        val focus = MutableStateFlow(false)
        val armed = Armed()
        arm(focus, armed)

        advanceTimeBy(60_000L)

        assertFalse(armed.now)
        assertTrue(armed.history.none { it }, "armed at some point: ${armed.history}")
    }

    @Test
    fun `a window that has the focus is armed after its first frame and the delay, not before`() = runTest {
        val focus = MutableStateFlow(true)
        val armed = Armed()
        arm(focus, armed)

        advanceTimeBy(frameMs + delayMs - 1)
        assertFalse(armed.now, "armed before the delay ended")

        advanceTimeBy(2L)
        assertTrue(armed.now, "not armed once the delay ended")
    }

    @Test
    fun `the delay is counted from the first frame, which is awaited first`() = runTest {
        val frame = CompletableDeferred<Unit>()
        val focus = MutableStateFlow(true)
        val armed = Armed()
        arm(focus, armed, frame = { frame.await() })

        // No frame yet: the window cannot be seen, whatever the time.
        advanceTimeBy(60_000L)
        assertFalse(armed.now, "armed before the window was drawn")

        frame.complete(Unit)
        runCurrent()
        advanceTimeBy(delayMs - 1)
        assertFalse(armed.now, "armed before the delay, counted from the frame, ended")
        advanceTimeBy(2L)
        assertTrue(armed.now)
    }

    @Test
    fun `losing the focus disarms at once`() = runTest {
        val focus = MutableStateFlow(true)
        val armed = Armed()
        arm(focus, armed)
        advanceTimeBy(frameMs + delayMs + 10L)
        assertTrue(armed.now)

        focus.value = false
        runCurrent()

        assertFalse(armed.now, "still armed with no focus")
    }

    @Test
    fun `getting the focus back arms again only after a whole new delay`() = runTest {
        val focus = MutableStateFlow(true)
        val armed = Armed()
        arm(focus, armed)
        advanceTimeBy(frameMs + delayMs + 10L)
        focus.value = false
        runCurrent()

        focus.value = true
        runCurrent()
        advanceTimeBy(delayMs)
        assertFalse(armed.now, "armed again without waiting a new delay after the frame")

        advanceTimeBy(frameMs + 1L)
        assertTrue(armed.now)
    }

    @Test
    fun `losing the focus during the delay gives that wait up`() = runTest {
        val focus = MutableStateFlow(true)
        val armed = Armed()
        arm(focus, armed)
        advanceTimeBy(frameMs + 200L)
        assertFalse(armed.now)

        focus.value = false
        runCurrent()
        advanceTimeBy(60_000L)

        assertTrue(armed.history.none { it }, "armed by a wait that was given up: ${armed.history}")
    }

    @Test
    fun `a window that gets the focus back during the delay waits the full delay again`() = runTest {
        val focus = MutableStateFlow(true)
        val armed = Armed()
        arm(focus, armed)
        advanceTimeBy(frameMs + 400L)
        focus.value = false
        runCurrent()
        focus.value = true
        runCurrent()

        // 400 ms of the first wait are behind us: they do not count.
        advanceTimeBy(frameMs + 150L)
        assertFalse(armed.now, "the time before the focus was lost was counted")

        advanceTimeBy(delayMs)
        assertTrue(armed.now)
    }

    @Test
    fun `two windows are never armed at the same time when only one has the focus`() = runTest {
        // Two stacked windows: the one on top has the focus, the other does not.
        val onTop = MutableStateFlow(true)
        val underneath = MutableStateFlow(false)
        val topArmed = Armed()
        val bottomArmed = Armed()
        arm(onTop, topArmed)
        arm(underneath, bottomArmed)

        advanceTimeBy(60_000L)
        assertTrue(topArmed.now)
        assertFalse(bottomArmed.now, "the window underneath got armed while it never had the focus")

        // The window on top is answered and closed: the other one gets the focus, then its own delay.
        onTop.value = false
        underneath.value = true
        runCurrent()
        assertFalse(topArmed.now)
        assertFalse(bottomArmed.now, "armed the instant it got the focus")
        advanceTimeBy(frameMs + delayMs + 1L)
        assertTrue(bottomArmed.now)
        assertEquals(false, topArmed.now)
    }
}
