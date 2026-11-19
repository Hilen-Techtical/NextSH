// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The holds the transfer service keeps while a system file picker is open, off the framework: what
 * they count, what they give back, and how many times. The service decides to stop from this count
 * (no hold, no transfer, nothing queued), so a hold that is lost or given back twice either keeps
 * the app in the foreground for ever or lets the connection die in the picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickerHoldsTest {

    /** The session references taken and given back, in order, as a fake session manager would count them. */
    private class Sessions(private val alive: Set<String> = setOf("s1", "s2")) {
        val acquired = mutableListOf<String>()
        val released = mutableListOf<String>()
        fun acquire(id: String): Boolean = (id in alive).also { if (it) acquired += id }
        fun release(id: String) {
            released += id
        }
    }

    private val timeout = 1_000L

    private fun TestScope.newHolds(
        sessions: Sessions,
        expired: MutableList<Unit> = mutableListOf(),
    ) = PickerHolds(
        scope = backgroundScope,
        acquireSession = sessions::acquire,
        releaseSession = sessions::release,
        timeoutMs = timeout,
        onExpired = { expired += Unit },
    )

    @Test
    fun `a new set of holds is empty`() = runTest {
        val holds = newHolds(Sessions())

        assertTrue(holds.isEmpty())
        assertEquals(0, holds.count)
    }

    @Test
    fun `a hold keeps the service up and one reference on its session until it is released`() = runTest {
        val sessions = Sessions()
        val holds = newHolds(sessions)

        holds.acquire("h1", "s1")

        assertFalse(holds.isEmpty())
        assertEquals(listOf("s1"), sessions.acquired)
        assertEquals(emptyList<String>(), sessions.released)

        assertTrue(holds.release("h1"))

        assertTrue(holds.isEmpty())
        assertEquals(listOf("s1"), sessions.released)
    }

    @Test
    fun `a hold is released once, a second release is a no-op`() = runTest {
        val sessions = Sessions()
        val holds = newHolds(sessions)
        holds.acquire("h1", "s1")

        assertTrue(holds.release("h1"))
        assertFalse(holds.release("h1"))

        assertEquals(listOf("s1"), sessions.released)
    }

    @Test
    fun `releasing a hold this service never took changes nothing`() = runTest {
        // The service was destroyed and recreated while the picker was open: the release that reaches
        // the new instance names a hold it does not know.
        val sessions = Sessions()
        val holds = newHolds(sessions)
        holds.acquire("h1", "s1")

        assertFalse(holds.release("unknown"))

        assertEquals(1, holds.count)
        assertEquals(emptyList<String>(), sessions.released)
    }

    @Test
    fun `taking the same hold twice counts it once and takes one reference`() = runTest {
        val sessions = Sessions()
        val holds = newHolds(sessions)

        holds.acquire("h1", "s1")
        holds.acquire("h1", "s1")

        assertEquals(1, holds.count)
        assertEquals(listOf("s1"), sessions.acquired)
        holds.release("h1")
        assertEquals(listOf("s1"), sessions.released)
    }

    @Test
    fun `two pickers are two holds, and the service stays up until both are given back`() = runTest {
        val sessions = Sessions()
        val holds = newHolds(sessions)
        holds.acquire("h1", "s1")
        holds.acquire("h2", "s2")

        holds.release("h1")

        assertFalse(holds.isEmpty(), "the second picker still needs the service")
        assertEquals(listOf("s1"), sessions.released)

        holds.release("h2")

        assertTrue(holds.isEmpty())
        assertEquals(listOf("s1", "s2"), sessions.released)
    }

    @Test
    fun `a session that is already gone is not referenced, and nothing is given back for it`() = runTest {
        val sessions = Sessions(alive = emptySet())
        val holds = newHolds(sessions)

        holds.acquire("h1", "gone")

        // The hold still keeps the service up for the picker, the screen says the session is gone.
        assertEquals(1, holds.count)
        assertEquals(emptyList<String>(), sessions.acquired)
        holds.release("h1")
        assertEquals(emptyList<String>(), sessions.released, "a reference never taken is never given back")
    }

    @Test
    fun `a hold with no session keeps the service up and references nothing`() = runTest {
        val sessions = Sessions()
        val holds = newHolds(sessions)

        holds.acquire("h1", null)
        holds.acquire("h2", " ")

        assertEquals(2, holds.count)
        assertEquals(emptyList<String>(), sessions.acquired)
        holds.releaseAll()
        assertEquals(emptyList<String>(), sessions.released)
    }

    @Test
    fun `a hold nobody gave back is released by the guard after the timeout, and the service is told`() = runTest {
        val sessions = Sessions()
        val expired = mutableListOf<Unit>()
        val holds = newHolds(sessions, expired)
        holds.acquire("h1", "s1")

        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals(1, holds.count, "not before the timeout")
        assertEquals(0, expired.size)

        advanceTimeBy(2)
        runCurrent()

        assertTrue(holds.isEmpty())
        assertEquals(listOf("s1"), sessions.released)
        assertEquals(1, expired.size, "the service looks whether it can stop")
    }

    @Test
    fun `a hold given back in time is not released again by the guard`() = runTest {
        val sessions = Sessions()
        val expired = mutableListOf<Unit>()
        val holds = newHolds(sessions, expired)
        holds.acquire("h1", "s1")
        advanceTimeBy(timeout / 2)
        runCurrent()

        holds.release("h1")
        advanceTimeBy(timeout * 2)
        runCurrent()

        assertEquals(listOf("s1"), sessions.released, "one release, the guard did not add a second")
        assertEquals(0, expired.size, "an expiry that releases nothing tells nobody")
    }

    @Test
    fun `the guard of one hold does not release another`() = runTest {
        val sessions = Sessions()
        val holds = newHolds(sessions)
        holds.acquire("h1", "s1")
        advanceTimeBy(timeout / 2)
        runCurrent()
        holds.acquire("h2", "s2")

        advanceTimeBy(timeout / 2 + 1)
        runCurrent()

        assertEquals(1, holds.count, "the first expired, the second has its own timeout")
        assertEquals(listOf("s1"), sessions.released)
    }

    @Test
    fun `releaseAll gives every reference back once and the guards do not give them back again`() = runTest {
        val sessions = Sessions()
        val expired = mutableListOf<Unit>()
        val holds = newHolds(sessions, expired)
        holds.acquire("h1", "s1")
        holds.acquire("h2", "s2")

        holds.releaseAll()
        advanceTimeBy(timeout * 2)
        runCurrent()

        assertTrue(holds.isEmpty())
        assertEquals(setOf("s1", "s2"), sessions.released.toSet())
        assertEquals(2, sessions.released.size)
        assertEquals(0, expired.size)
    }

    @Test
    fun `the default guard timeout is ten minutes`() {
        assertEquals(10 * 60_000L, PICKER_HOLD_TIMEOUT_MS)
    }
}
