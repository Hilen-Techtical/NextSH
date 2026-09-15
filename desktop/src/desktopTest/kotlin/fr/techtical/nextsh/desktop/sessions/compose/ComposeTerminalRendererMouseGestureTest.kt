// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

// ──────────────────────────────────────────────────────────────────────────────
// [MouseReportGestureTracker] and [WheelNotchAccumulator] are the two pure state
// machines pulled out of the renderer's mouse-reporting `pointerInput` block, so
// they are exercised directly here: same strategy as
// [ComposeTerminalRendererKeyHandlerTest] and [MouseReportEncoderTest]: a test
// cannot construct a Compose `PointerEvent` (its Desktop wrapper is `internal`
// to `androidx.compose.ui`), so the logic worth guarding lives outside it.
//
// The two regressions these tests pin down:
//  - a release bypassed by the Shift gate used to leave the tracker armed,
//    turning every subsequent move into a phantom Drag(LEFT);
//  - the reporting path used to emit/drop wheel events one-for-one, so a
//    trackpad's fractional deltas either vanished or over-reported.
// ──────────────────────────────────────────────────────────────────────────────

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeTerminalRendererMouseGestureTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun MouseReportGestureTracker.press(
        button: MouseReportButton? = MouseReportButton.LEFT,
        shift: Boolean = false,
        reporting: Boolean = true,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ) = onEvent(MouseGestureKind.PRESS, button, shift, reporting, ctrl, meta)

    private fun MouseReportGestureTracker.release(
        button: MouseReportButton? = MouseReportButton.LEFT,
        shift: Boolean = false,
        reporting: Boolean = true,
    ) = onEvent(MouseGestureKind.RELEASE, button, shift, reporting)

    private fun MouseReportGestureTracker.move(
        shift: Boolean = false,
        reporting: Boolean = true,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ) = onEvent(MouseGestureKind.MOVE, null, shift, reporting, ctrl, meta)

    // ── Nominal press / drag / release cycle ──────────────────────────────────

    @Test
    fun pressDragRelease_emitsTheFullSequence() {
        val tracker = MouseReportGestureTracker()

        assertEquals(MouseReportEvent.Press(MouseReportButton.LEFT), tracker.press())
        assertEquals(MouseReportButton.LEFT, tracker.pressedButton)

        assertEquals(MouseReportEvent.Drag(MouseReportButton.LEFT), tracker.move())

        assertEquals(MouseReportEvent.Release(MouseReportButton.LEFT), tracker.release())
        assertNull(tracker.pressedButton)

        // Once released, a bare move is a Move, never a Drag.
        assertEquals(MouseReportEvent.Move(), tracker.move())
    }

    @Test
    fun rightAndMiddleButtons_keepTheirIdentity() {
        val tracker = MouseReportGestureTracker()
        assertEquals(
            MouseReportEvent.Press(MouseReportButton.RIGHT),
            tracker.press(MouseReportButton.RIGHT),
        )
        assertEquals(MouseReportEvent.Drag(MouseReportButton.RIGHT), tracker.move())
        assertEquals(
            MouseReportEvent.Release(MouseReportButton.RIGHT),
            tracker.release(MouseReportButton.RIGHT),
        )

        assertEquals(
            MouseReportEvent.Press(MouseReportButton.MIDDLE),
            tracker.press(MouseReportButton.MIDDLE),
        )
        assertEquals(MouseReportButton.MIDDLE, tracker.pressedButton)
    }

    @Test
    fun modifiers_arePropagatedToTheReport() {
        val tracker = MouseReportGestureTracker()
        assertEquals(
            MouseReportEvent.Press(MouseReportButton.LEFT, ctrl = true, meta = true),
            tracker.press(ctrl = true, meta = true),
        )
        assertEquals(
            MouseReportEvent.Drag(MouseReportButton.LEFT, ctrl = true, meta = false),
            tracker.move(ctrl = true),
        )
    }

    // ── FIX 2 - the Shift gate must never strand the held-button state ────────

    /**
     * The regression itself: press without Shift (armed), then release while
     * Shift is held. No report is emitted (Shift = local behaviour), but the
     * tracker MUST disarm: otherwise every later move is encoded as
     * `Drag(LEFT)` and tmux/vim keep believing the button is down.
     */
    @Test
    fun releaseBypassedByShift_stillDisarms() {
        val tracker = MouseReportGestureTracker()
        tracker.press()
        assertEquals(MouseReportButton.LEFT, tracker.pressedButton)

        assertNull(tracker.release(shift = true), "Shift-held release must not report")
        assertNull(tracker.pressedButton, "Shift-held release must still disarm")

        // The phantom-drag symptom is gone: a plain move is a Move again.
        assertEquals(MouseReportEvent.Move(), tracker.move())
    }

    /**
     * Symmetrical half of the fix: a press *with* Shift belongs to the local
     * selection path, so it must not arm the tracker: otherwise releasing
     * Shift mid-drag would start reporting drags for a gesture the user began
     * as a local selection.
     */
    @Test
    fun pressWithShift_doesNotArm() {
        val tracker = MouseReportGestureTracker()
        assertNull(tracker.press(shift = true))
        assertNull(tracker.pressedButton)

        // Shift released mid-gesture: still a bare Move, not a Drag.
        assertEquals(MouseReportEvent.Move(), tracker.move())
    }

    /**
     * Same trap, other gate: an app that turns mouse tracking off while the
     * button is down (quitting vim mid-drag) must not leave the tracker armed
     * for the next app that turns it back on.
     */
    @Test
    fun releaseWhileReportingDisabled_stillDisarms() {
        val tracker = MouseReportGestureTracker()
        tracker.press()

        assertNull(tracker.release(reporting = false))
        assertNull(tracker.pressedButton)
        assertEquals(MouseReportEvent.Move(), tracker.move())
    }

    /**
     * A press that happens while tracking is off still arms the tracker: the
     * button IS physically down, so if the app enables tracking mid-gesture the
     * following moves are genuine drags (xterm tracks physical button state the
     * same way). Only the emission is suppressed.
     */
    @Test
    fun pressWhileReportingDisabled_armsWithoutEmitting() {
        val tracker = MouseReportGestureTracker()
        assertNull(tracker.press(reporting = false))
        assertEquals(MouseReportButton.LEFT, tracker.pressedButton)
        assertEquals(MouseReportEvent.Drag(MouseReportButton.LEFT), tracker.move())
    }

    @Test
    fun moveIsSuppressedByShiftAndByDisabledReporting() {
        val tracker = MouseReportGestureTracker()
        tracker.press()
        assertNull(tracker.move(shift = true))
        assertNull(tracker.move(reporting = false))
        // Neither gate disturbs the held state.
        assertEquals(MouseReportButton.LEFT, tracker.pressedButton)
        assertEquals(MouseReportEvent.Drag(MouseReportButton.LEFT), tracker.move())
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    /**
     * Release without a matching press (press swallowed by another handler, or
     * the pointer entered the pane already held): fall back to the button the
     * event itself carries so SGR still reports a real identity.
     */
    @Test
    fun releaseWithoutPress_fallsBackToTheEventButton() {
        val tracker = MouseReportGestureTracker()
        assertEquals(
            MouseReportEvent.Release(MouseReportButton.RIGHT),
            tracker.release(MouseReportButton.RIGHT),
        )
        assertNull(tracker.pressedButton)
    }

    @Test
    fun releaseWithNoButtonAtAll_reportsNothing() {
        val tracker = MouseReportGestureTracker()
        assertNull(tracker.release(button = null))
        assertNull(tracker.pressedButton)
    }

    /**
     * Back/Forward map to `null` (xterm has no code for them): they must not
     * arm the tracker nor disturb a button already held.
     */
    @Test
    fun unmappedButtonPress_leavesTheHeldStateUntouched() {
        val tracker = MouseReportGestureTracker()
        assertNull(tracker.press(button = null))
        assertNull(tracker.pressedButton)

        tracker.press(MouseReportButton.LEFT)
        assertNull(tracker.press(button = null))
        assertEquals(MouseReportButton.LEFT, tracker.pressedButton)
    }

    // ── FIX 3 - fractional wheel accumulation ─────────────────────────────────

    @Test
    fun wholeNotch_emitsImmediately() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(1, accumulator.accumulate(1f))
        assertEquals(-1, accumulator.accumulate(-1f))
    }

    @Test
    fun zeroDelta_emitsNothing() {
        assertEquals(0, WheelNotchAccumulator().accumulate(0f))
    }

    /** Trackpad: four 0.25 deltas must add up to exactly one notch, not four. */
    @Test
    fun fractionalDeltas_accumulateIntoASingleNotch() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(0, accumulator.accumulate(0.25f))
        assertEquals(0, accumulator.accumulate(0.25f))
        assertEquals(0, accumulator.accumulate(0.25f))
        assertEquals(1, accumulator.accumulate(0.25f))
        // Carry restarts from zero for the next notch.
        assertEquals(0, accumulator.accumulate(0.25f))
    }

    /** Same, upward: the sign convention matches the local scroll path. */
    @Test
    fun negativeFractionalDeltas_accumulateUpward() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(0, accumulator.accumulate(-0.5f))
        assertEquals(-1, accumulator.accumulate(-0.5f))
    }

    /** A fast flick must expand to one report per notch crossed, not one total. */
    @Test
    fun largeDelta_emitsOneNotchPerUnit() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(3, accumulator.accumulate(3f))
        assertEquals(-4, accumulator.accumulate(-4f))
    }

    /** The sub-unit remainder is carried over, never dropped. */
    @Test
    fun remainderIsCarriedToTheNextEvent() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(1, accumulator.accumulate(1.5f))   // 0.5 carried
        assertEquals(1, accumulator.accumulate(0.75f))  // 0.5 + 0.75 = 1.25
        assertEquals(0, accumulator.accumulate(0.5f))   // 0.25 + 0.5  = 0.75
        assertEquals(1, accumulator.accumulate(0.5f))   // 0.75 + 0.5  = 1.25
    }

    /** Direction reversal cancels the pending carry instead of stacking. */
    @Test
    fun reversingDirection_consumesTheCarry() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(0, accumulator.accumulate(0.75f))
        assertEquals(0, accumulator.accumulate(-0.75f))
        assertEquals(0, accumulator.accumulate(-0.5f))
        assertEquals(-1, accumulator.accumulate(-0.75f))
    }

    /**
     * A driver reporting an absurd delta must not make the UI thread emit
     * thousands of escape sequences; the surplus is dropped, not queued.
     */
    @Test
    fun absurdDelta_isCappedAndDoesNotQueueTheSurplus() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(16, accumulator.accumulate(10_000f))
        assertEquals(0, accumulator.accumulate(0.5f), "surplus must be dropped, not carried")
        assertEquals(1, accumulator.accumulate(0.5f))

        assertEquals(-16, accumulator.accumulate(-10_000f))
        assertEquals(0, accumulator.accumulate(-0.5f))
    }

    /** Non-finite deltas are ignored and must not poison the accumulator. */
    @Test
    fun nonFiniteDeltas_areIgnored() {
        val accumulator = WheelNotchAccumulator()
        assertEquals(0, accumulator.accumulate(Float.NaN))
        assertEquals(0, accumulator.accumulate(Float.POSITIVE_INFINITY))
        assertEquals(0, accumulator.accumulate(Float.NEGATIVE_INFINITY))
        assertEquals(1, accumulator.accumulate(1f), "state must survive a bogus delta")
    }
}
