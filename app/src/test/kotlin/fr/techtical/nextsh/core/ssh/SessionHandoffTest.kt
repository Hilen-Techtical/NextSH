// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The hand-over of a session from the host list to the screen that opens it: whoever claims it
 * owns the reference of the list, and a session nobody claims is released, exactly once, whatever
 * route the announcement took.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHandoffTest {

    private val window = 1_000L
    private val sessionManager = mockk<SshSessionManager>(relaxed = true)

    /** Whether the app is on screen, as [AppVisibility] tells it. */
    private var visible = true

    private fun TestScope.newHandoff(window: Long = this@SessionHandoffTest.window, hiddenCapMs: Long = 60_000L) =
        SessionHandoff(
            sessionManager,
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            window,
            AppVisibility { visible },
            hiddenCapMs,
        )

    @Test
    fun `a session that is claimed in time is the claimant's and is never released here`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        runCurrent()

        assertTrue(handoff.claim("s1"))
        advanceUntilIdle()

        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `a session nobody claims is released once its window has passed`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        runCurrent()

        advanceTimeBy(window - 1)
        runCurrent()
        verify(exactly = 0) { sessionManager.release(any()) }

        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 1) { sessionManager.release("s1") }
    }

    @Test
    fun `a claim after the window fails, and the release was the hand-over's`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        advanceUntilIdle()

        assertFalse(handoff.claim("s1"), "released already: the claimant owns nothing")
        verify(exactly = 1) { sessionManager.release("s1") }
    }

    @Test
    fun `a session can be claimed once`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        runCurrent()

        assertTrue(handoff.claim("s1"))
        assertFalse(handoff.claim("s1"), "the second claimant owns nothing, or the reference would be given back twice")
        advanceUntilIdle()
        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `a session that was never offered cannot be claimed`() = runTest {
        val handoff = newHandoff()

        assertFalse(handoff.claim("nobody"))
        advanceUntilIdle()
        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `sessions are offered and claimed apart`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        handoff.offer("s2")
        runCurrent()

        assertTrue(handoff.claim("s2"))
        advanceUntilIdle()

        verify(exactly = 1) { sessionManager.release("s1") }
        verify(exactly = 0) { sessionManager.release("s2") }
    }

    @Test
    fun `offering the same session twice still releases its single reference once`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(window / 2)
        handoff.offer("s1")
        runCurrent()

        advanceUntilIdle()

        verify(exactly = 1) { sessionManager.release("s1") }
    }

    // ── The window only runs while the app is on screen ───────────────────────

    @Test
    fun `a window that ends while the app is hidden starts again instead of releasing`() = runTest {
        val handoff = newHandoff()
        visible = false
        handoff.offer("s1")
        runCurrent()

        // Home was pressed: three windows go by, and the session is still on offer.
        advanceTimeBy(window * 3)
        runCurrent()

        verify(exactly = 0) { sessionManager.release(any()) }
        assertTrue(handoff.claim("s1"), "the receiver finds it when the app is back")
    }

    @Test
    fun `back on screen the receiver gets a full window of a visible app before the release`() = runTest {
        val handoff = newHandoff()
        visible = false
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(window * 2 + window / 2)
        runCurrent()

        // The app returns just before the next timer: that timer must not release at once.
        visible = true
        advanceTimeBy(window / 2)
        runCurrent()
        verify(exactly = 0) { sessionManager.release(any()) }

        // Nobody claims for a whole window of a visible app: released, once.
        advanceUntilIdle()
        verify(exactly = 1) { sessionManager.release("s1") }
    }

    @Test
    fun `a return just before a look does not shorten the window, the receiver still gets a full one`() = runTest {
        // The case the look of a whole window would have missed: Home at 1 s, back at 14.9 s, and
        // the look at 15 s releasing 100 ms after the return.
        val fifteenSeconds = 15_000L
        val handoff = newHandoff(window = fifteenSeconds)
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(1_000)
        visible = false
        advanceTimeBy(13_900) // t = 14.9 s: back on screen, between two looks
        visible = true
        advanceTimeBy(100)
        runCurrent() // t = 15 s: the look finds the app back after a hidden one

        verify(exactly = 0) { sessionManager.release(any()) }
        // Not before a whole window of a visible app has passed since the return.
        advanceTimeBy(14_000)
        runCurrent() // t = 29 s: 14 s seen
        verify(exactly = 0) { sessionManager.release(any()) }
        assertTrue(handoff.claim("s1"), "the receiver found it in time")
    }

    @Test
    fun `the full window of a visible app is still needed after the return, then the session is released`() = runTest {
        val fifteenSeconds = 15_000L
        val handoff = newHandoff(window = fifteenSeconds)
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(1_000)
        visible = false
        advanceTimeBy(13_900)
        visible = true
        advanceTimeBy(100)
        runCurrent()

        advanceTimeBy(14_999)
        runCurrent()
        verify(exactly = 0) { sessionManager.release(any()) }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 1) { sessionManager.release("s1") }
    }

    @Test
    fun `an offer is released after the cap of hidden time`() = runTest {
        val handoff = newHandoff(hiddenCapMs = 10_000L)
        visible = false
        handoff.offer("s1")
        runCurrent()

        advanceTimeBy(9_999)
        runCurrent()
        verify(exactly = 0) { sessionManager.release(any()) }
        advanceTimeBy(1)
        runCurrent()

        verify(exactly = 1) { sessionManager.release("s1") }
        assertFalse(handoff.claim("s1"), "the receiver that comes back later owns nothing and reconnects")
    }

    @Test
    fun `the cap counts hidden time over all the looks, a return does not give it back`() = runTest {
        // A window of 5 s, a cap of 10 s: 6 s hidden, 3 s back (not enough to expire), 4 s hidden again.
        val handoff = newHandoff(window = 5_000L, hiddenCapMs = 10_000L)
        visible = false
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent() // looks at 1..6 s: 6 s hidden
        visible = true
        advanceTimeBy(3_000)
        runCurrent() // looks at 7, 8, 9 s: the first counts for nothing, 2 s seen in a row
        verify(exactly = 0) { sessionManager.release(any()) }
        visible = false
        advanceTimeBy(3_000)
        runCurrent() // looks at 10, 11, 12 s: 9 s hidden in all
        verify(exactly = 0) { sessionManager.release(any()) }
        advanceTimeBy(1_000)
        runCurrent() // look at 13 s: 10 s hidden in all

        verify(exactly = 1) { sessionManager.release("s1") }
    }

    @Test
    fun `a session claimed after a long absence is the claimant's and is never released here`() = runTest {
        val handoff = newHandoff()
        visible = false
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(window * 5)
        runCurrent()
        visible = true

        assertTrue(handoff.claim("s1"))
        advanceUntilIdle()

        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `the window is restarted by a new offer, not shortened by an earlier one`() = runTest {
        val handoff = newHandoff()
        handoff.offer("s1")
        runCurrent()
        advanceTimeBy(window - 100)
        handoff.offer("s1")
        runCurrent()

        advanceTimeBy(window - 100)
        runCurrent()
        verify(exactly = 0) { sessionManager.release(any()) }
        assertTrue(handoff.claim("s1"))
    }
}
