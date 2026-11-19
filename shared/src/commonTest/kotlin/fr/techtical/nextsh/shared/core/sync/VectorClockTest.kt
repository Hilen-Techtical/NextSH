// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VectorClockTest {

    private val a = VectorClock.EMPTY.tick("A", 100)
    private val b = VectorClock.EMPTY.tick("B", 100)

    @Test
    fun `supersede strictly dominates two concurrent clocks`() {
        assertTrue(a.concurrentWith(b))

        val result = a.supersede(b, "A", 500)

        assertTrue(result.dominates(a))
        assertTrue(result.dominates(b))
    }

    @Test
    fun `supersede still moves forward when this clock already dominates the other`() {
        val older = a
        val newer = a.tick("A", 200)

        val result = newer.supersede(older, "A", 300)

        assertTrue(result.dominates(newer), "the merge alone would equal the newer clock")
        assertTrue(result.dominates(older))
    }

    @Test
    fun `supersede increments a counter that is ahead of the wall clock`() {
        val ahead = VectorClock(mapOf("A" to 10_000L))

        val result = ahead.supersede(b, "A", 500)

        assertEquals(10_001L, result.entries["A"])
        assertEquals(100L, result.entries["B"])
    }

    @Test
    fun `supersede called again with the same arguments keeps moving forward`() {
        val first = a.supersede(b, "A", 500)

        val second = first.supersede(b, "A", 500)

        assertTrue(second.dominates(first))
    }

    @Test
    fun `two devices that supersede the same pair end concurrent, never equal`() {
        val byA = a.supersede(b, "A", 500)
        val byB = b.supersede(a, "B", 500)

        assertNotEquals(byA, byB)
        assertTrue(byA.concurrentWith(byB))
    }
}
