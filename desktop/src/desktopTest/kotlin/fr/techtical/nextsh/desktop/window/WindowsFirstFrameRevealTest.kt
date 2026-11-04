// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import com.sun.jna.Pointer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WindowsFirstFrameReveal]: the scheduling of the reveal
 * ([awaitFirstFrameThenReveal]) under virtual time, and the cloak call
 * ([applyCloak]) against a fake `DwmSetWindowAttribute`.
 *
 * No real window is created: the native side (the HWND of a shown AWT window
 * and the real `dwmapi.dll`) needs a display and is not covered. What is
 * covered is the decision logic: WHEN the cloak is lifted, and that it is
 * lifted, with [onRevealed][awaitFirstFrameThenReveal] called exactly once,
 * on every path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowsFirstFrameRevealTest {

    /**
     * Records what the reveal does and WHEN (virtual time of [scope]), with
     * fakes for the window and the frame clock.
     */
    private class Harness(private val scope: TestScope) {
        val events = mutableListOf<String>()
        val traces = mutableListOf<String>()
        private val firstAt = mutableMapOf<String, Long>()

        fun record(event: String) {
            events += event
            firstAt.getOrPut(event) { scope.currentTime }
        }

        fun timeOf(event: String): Long = firstAt.getValue(event)

        /**
         * Runs the reveal. The fake window starts showing at virtual time
         * [showingFrom] (ms); [awaitFrame] is the fake frame clock.
         */
        suspend fun reveal(
            cloaked: Boolean = true,
            showingFrom: Long = 0L,
            uncloak: () -> Unit = { record("uncloak") },
            awaitFrame: suspend () -> Unit,
        ) {
            awaitFirstFrameThenReveal(
                cloaked = cloaked,
                isShowing = { scope.currentTime >= showingFrom },
                awaitFrame = {
                    record("awaitFrame")
                    awaitFrame()
                },
                uncloak = uncloak,
                onRevealed = { record("revealed") },
                trace = { traces += it },
            )
        }
    }

    // ── Reveal after the first frame ──────────────────────────────────────────

    @Test
    fun `the cloak is lifted after the first frame and the settle delay`() = runTest {
        val h = Harness(this)

        h.reveal { delay(120); h.record("frame") }

        assertEquals(listOf("awaitFrame", "frame", "uncloak", "revealed"), h.events)
        assertEquals(120L, h.timeOf("frame"))
        // The frame clock ticks at the START of a frame: the image needs the
        // settle delay to reach DWM before the cloak goes.
        assertEquals(120L + REVEAL_PRESENT_SETTLE_MS, h.timeOf("uncloak"))
        assertEquals(h.timeOf("uncloak"), h.timeOf("revealed"))
    }

    @Test
    fun `no frame is awaited before the window is showing`() = runTest {
        val h = Harness(this)

        h.reveal(showingFrom = 400L) { h.record("frame") }

        // Polled every 50 ms: noticed at the first poll at or after 400 ms.
        assertEquals(400L, h.timeOf("awaitFrame"))
        assertEquals(400L + REVEAL_PRESENT_SETTLE_MS, h.timeOf("uncloak"))
        assertEquals(1, h.events.count { it == "revealed" })
    }

    @Test
    fun `the trace says the first frame was rendered`() = runTest {
        val h = Harness(this)

        h.reveal { h.record("frame") }

        assertTrue(h.traces.any { "first frame rendered" in it }, h.traces.toString())
        assertFalse(h.traces.any { "no first frame" in it }, h.traces.toString())
    }

    // ── Safety delay ──────────────────────────────────────────────────────────

    @Test
    fun `the cloak is lifted at the safety delay when no frame ever comes`() = runTest {
        val h = Harness(this)

        h.reveal(showingFrom = 300L) { awaitCancellation() }

        assertEquals(listOf("awaitFrame", "uncloak", "revealed"), h.events)
        // At most REVEAL_FRAME_TIMEOUT_MS between the show and the uncloak.
        assertEquals(300L + REVEAL_FRAME_TIMEOUT_MS, h.timeOf("uncloak"))
    }

    @Test
    fun `a frame later than the safety delay does not hold the cloak`() = runTest {
        val h = Harness(this)

        h.reveal { delay(10_000); h.record("frame") }

        assertEquals(listOf("awaitFrame", "uncloak", "revealed"), h.events)
        assertEquals(REVEAL_FRAME_TIMEOUT_MS, h.timeOf("uncloak"))
        assertTrue(h.traces.any { "no first frame" in it }, h.traces.toString())
    }

    @Test
    fun `the cloak is lifted when the window never shows`() = runTest {
        val h = Harness(this)

        h.reveal(showingFrom = Long.MAX_VALUE) { h.record("frame") }

        // Nothing to wait a frame for: straight to the uncloak at the ceiling.
        assertEquals(listOf("uncloak", "revealed"), h.events)
        assertEquals(REVEAL_SHOW_TIMEOUT_MS, h.timeOf("uncloak"))
    }

    // ── Failure and cancellation ──────────────────────────────────────────────

    @Test
    fun `the cloak is lifted and nothing is thrown when the frame wait fails`() = runTest {
        val h = Harness(this)

        // An exception escaping would fail this test (and crash the app).
        h.reveal { throw IllegalStateException("clock gone") }

        assertEquals(listOf("awaitFrame", "uncloak", "revealed"), h.events)
    }

    @Test
    fun `the cloak is lifted and the cancellation is rethrown when the caller is cancelled`() = runTest {
        val h = Harness(this)

        val job = launch { h.reveal { awaitCancellation() } }
        advanceTimeBy(100L)
        assertEquals(listOf("awaitFrame"), h.events)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(listOf("awaitFrame", "uncloak", "revealed"), h.events)
    }

    @Test
    fun `the cloak is lifted when the caller is cancelled before the window shows`() = runTest {
        val h = Harness(this)

        val job = launch { h.reveal(showingFrom = Long.MAX_VALUE) { h.record("frame") } }
        advanceTimeBy(500L)
        job.cancelAndJoin()

        assertEquals(listOf("uncloak", "revealed"), h.events)
    }

    @Test
    fun `onRevealed is still called when the uncloak itself fails`() = runTest {
        val h = Harness(this)

        h.reveal(uncloak = { throw IllegalStateException("dwm gone") }) { h.record("frame") }

        assertEquals(listOf("awaitFrame", "frame", "revealed"), h.events)
    }

    // ── Nothing was cloaked ───────────────────────────────────────────────────

    @Test
    fun `without a cloak nothing is waited for or lifted and onRevealed is called once`() = runTest {
        val h = Harness(this)

        h.reveal(cloaked = false) { h.record("frame") }

        assertEquals(listOf("revealed"), h.events)
        assertEquals(0L, currentTime)
    }

    // ── applyCloak ────────────────────────────────────────────────────────────

    private val hwnd = Pointer(0x1234L)

    @Test
    fun `the cloak is a no-op outside Windows`() {
        var touched = false

        val result = applyCloak(
            cloaked = true,
            platformSupported = false,
            resolveHwnd = { touched = true; hwnd },
            setAttribute = { _, _, _ -> touched = true; 0 },
        )

        assertFalse(result)
        assertFalse(touched, "neither the handle nor DWM may be touched off Windows")
    }

    @Test
    fun `a window without a native handle is not cloaked`() {
        var called = false

        val result = applyCloak(
            cloaked = true,
            platformSupported = true,
            resolveHwnd = { null },
            setAttribute = { _, _, _ -> called = true; 0 },
        )

        assertFalse(result)
        assertFalse(called)
    }

    @Test
    fun `cloaking sets DWMWA_CLOAK to one on the window handle`() {
        val calls = mutableListOf<Triple<Pointer, Int, Int>>()

        val result = applyCloak(
            cloaked = true,
            platformSupported = true,
            resolveHwnd = { hwnd },
            setAttribute = { h, attribute, value -> calls += Triple(h, attribute, value); 0 },
        )

        assertTrue(result)
        assertEquals(listOf(Triple(hwnd, 13, 1)), calls)
    }

    @Test
    fun `uncloaking sets DWMWA_CLOAK to zero`() {
        val calls = mutableListOf<Pair<Int, Int>>()

        val result = applyCloak(
            cloaked = false,
            platformSupported = true,
            resolveHwnd = { hwnd },
            setAttribute = { _, attribute, value -> calls += attribute to value; 0 },
        )

        assertTrue(result)
        assertEquals(listOf(Dwmapi.DWMWA_CLOAK to 0), calls)
    }

    @Test
    fun `a refusing DWM means the window is not cloaked`() {
        val result = applyCloak(
            cloaked = true,
            platformSupported = true,
            resolveHwnd = { hwnd },
            setAttribute = { _, _, _ -> 0x80070057.toInt() }, // E_INVALIDARG
        )

        assertFalse(result)
    }

    @Test
    fun `a native failure is reported as not cloaked and never thrown`() {
        assertFalse(
            applyCloak(
                cloaked = true,
                platformSupported = true,
                resolveHwnd = { hwnd },
                setAttribute = { _, _, _ -> throw UnsatisfiedLinkError("dwmapi") },
            ),
        )
        assertFalse(
            applyCloak(
                cloaked = true,
                platformSupported = true,
                resolveHwnd = { throw IllegalStateException("no peer") },
                setAttribute = { _, _, _ -> 0 },
            ),
        )
    }

    @Test
    fun `the DWMWA_CLOAK attribute number is the documented one`() {
        assertEquals(13, Dwmapi.DWMWA_CLOAK)
    }
}
