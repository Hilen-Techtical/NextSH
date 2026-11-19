// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import fr.techtical.nextsh.shared.util.RelativeTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The delay before the displayed time is read again. Past a day the regular rhythm is one day, which
 * does not line up with the 7 days where "6 d ago" turns into a date: the delay is cut at the limit,
 * or the text would stay a day late. Pure JVM, `now` is given.
 */
class RelativeTimeTickTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    /** An arbitrary instant: only the age (`now - timestamp`) counts. */
    private val now = 1_800_000_000_000L

    /** The delay for an item of this age. */
    private fun delayAt(ageMs: Long) = nextTickDelayMs(now - ageMs, now)

    @Test
    fun `the rhythm below five days is the regular one, seconds then minutes then hours then days`() {
        val ages = listOf(
            0L, 1L, 9_999L, 59_999L, 60_000L, 3_599_999L, 3_600_000L, 86_399_999L,
            day, day + 1, 3 * day, 5 * day,
        )
        ages.forEach { age ->
            assertEquals(RelativeTime.nextRefreshDelayMs(age), delayAt(age), "age $age")
        }
        assertEquals(10 * second, delayAt(30 * second))
        assertEquals(minute, delayAt(5 * minute))
        assertEquals(hour, delayAt(3 * hour))
        assertEquals(day, delayAt(3 * day))
    }

    @Test
    fun `a day before the limit is a full day, and never more than the time left`() {
        val age = 3 * day
        assertTrue(delayAt(age) <= day)
        assertTrue(delayAt(age) <= RELATIVE_TIME_LIMIT_MS)
        // Exactly one day left: the day is just enough.
        assertEquals(day, delayAt(RELATIVE_TIME_LIMIT_MS - day))
    }

    @Test
    fun `6 days 23 hours leaves one hour, not a day`() {
        assertEquals(hour, delayAt(RELATIVE_TIME_LIMIT_MS - hour))
        assertTrue(delayAt(6 * day + 23 * hour) <= hour)
    }

    @Test
    fun `the delay stops at the limit`() {
        assertEquals(30 * second, delayAt(RELATIVE_TIME_LIMIT_MS - 30 * second))
        assertEquals(minute, delayAt(RELATIVE_TIME_LIMIT_MS - minute))
    }

    @Test
    fun `right before the limit the delay is the minimum, not a busy loop`() {
        assertEquals(MIN_TICK_DELAY_MS, delayAt(RELATIVE_TIME_LIMIT_MS - 1))
        assertEquals(MIN_TICK_DELAY_MS, delayAt(RELATIVE_TIME_LIMIT_MS - 500))
        assertEquals(MIN_TICK_DELAY_MS, delayAt(RELATIVE_TIME_LIMIT_MS - MIN_TICK_DELAY_MS))
    }

    @Test
    fun `past the limit the date does not change, the rhythm is one day`() {
        assertEquals(day, delayAt(RELATIVE_TIME_LIMIT_MS))
        assertEquals(day, delayAt(RELATIVE_TIME_LIMIT_MS + 1))
        assertEquals(day, delayAt(8 * day))
        assertEquals(day, delayAt(212 * day))
    }

    @Test
    fun `an item in the future keeps the rhythm of an age of zero`() {
        assertEquals(RelativeTime.nextRefreshDelayMs(0L), delayAt(-3 * hour))
        assertEquals(RelativeTime.nextRefreshDelayMs(0L), delayAt(-1))
        assertEquals(RelativeTime.nextRefreshDelayMs(0L), delayAt(-30 * day))
    }

    @Test
    fun `timestamp zero is a date, one day`() {
        assertEquals(day, nextTickDelayMs(0L, now))
    }

    @Test
    fun `for any age the delay is between the minimum and a day, and never skips the limit`() {
        var age = -8 * day
        while (age <= 8 * day) {
            val delay = delayAt(age)
            assertTrue(delay in MIN_TICK_DELAY_MS..day, "age $age: $delay")
            if (age in 0 until RELATIVE_TIME_LIMIT_MS) {
                // Waking up after the delay is at the limit at the latest, unless the minimum applies.
                assertTrue(age + delay <= RELATIVE_TIME_LIMIT_MS || delay == MIN_TICK_DELAY_MS, "age $age: $delay")
            }
            age += 997_001L
        }
    }
}
