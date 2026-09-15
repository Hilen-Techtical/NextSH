// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {

    // ── bucket ───────────────────────────────────────────────────────────────

    @Test
    fun `bucket at 0ms returns Bucket(0, SECONDS)`() {
        assertEquals(RelativeTime.Bucket(0, RelativeTime.Unit.SECONDS), RelativeTime.bucket(0L))
    }

    @Test
    fun `bucket at 59999ms returns SECONDS`() {
        // 59 999 ms = 59 s (tronqué)
        assertEquals(RelativeTime.Bucket(59, RelativeTime.Unit.SECONDS), RelativeTime.bucket(59_999L))
    }

    @Test
    fun `bucket at 60000ms transitions to MINUTES`() {
        // 60 000 ms = 60 s = 1 min
        assertEquals(RelativeTime.Bucket(1, RelativeTime.Unit.MINUTES), RelativeTime.bucket(60_000L))
    }

    @Test
    fun `bucket at 3599999ms returns MINUTES`() {
        // 59 min 59 s = 3 599 s → 59 min
        assertEquals(RelativeTime.Bucket(59, RelativeTime.Unit.MINUTES), RelativeTime.bucket(3_599_999L))
    }

    @Test
    fun `bucket at 3600000ms transitions to HOURS`() {
        // 1 h = 3 600 s
        assertEquals(RelativeTime.Bucket(1, RelativeTime.Unit.HOURS), RelativeTime.bucket(3_600_000L))
    }

    @Test
    fun `bucket at 23h59min returns HOURS`() {
        // 23 h 59 min = 86 340 s → 23 h
        assertEquals(RelativeTime.Bucket(23, RelativeTime.Unit.HOURS), RelativeTime.bucket(86_340_000L))
    }

    @Test
    fun `bucket at 86400000ms transitions to DAYS`() {
        // 1 j = 86 400 s
        assertEquals(RelativeTime.Bucket(1, RelativeTime.Unit.DAYS), RelativeTime.bucket(86_400_000L))
    }

    @Test
    fun `bucket at 10 days returns DAYS`() {
        assertEquals(RelativeTime.Bucket(10, RelativeTime.Unit.DAYS), RelativeTime.bucket(864_000_000L))
    }

    @Test
    fun `bucket with negative age clamped to Bucket(0, SECONDS)`() {
        assertEquals(RelativeTime.Bucket(0, RelativeTime.Unit.SECONDS), RelativeTime.bucket(-5_000L))
    }

    // ── nextRefreshDelayMs ───────────────────────────────────────────────────

    @Test
    fun `refresh delay at 59999ms is 10000`() {
        assertEquals(10_000L, RelativeTime.nextRefreshDelayMs(59_999L))
    }

    @Test
    fun `refresh delay at 60000ms transitions to 60000`() {
        assertEquals(60_000L, RelativeTime.nextRefreshDelayMs(60_000L))
    }

    @Test
    fun `refresh delay at 3599999ms is 60000`() {
        assertEquals(60_000L, RelativeTime.nextRefreshDelayMs(3_599_999L))
    }

    @Test
    fun `refresh delay at 3600000ms transitions to 3600000`() {
        assertEquals(3_600_000L, RelativeTime.nextRefreshDelayMs(3_600_000L))
    }

    @Test
    fun `refresh delay at 86399999ms is 3600000`() {
        assertEquals(3_600_000L, RelativeTime.nextRefreshDelayMs(86_399_999L))
    }

    @Test
    fun `refresh delay at 86400000ms transitions to 86400000`() {
        assertEquals(86_400_000L, RelativeTime.nextRefreshDelayMs(86_400_000L))
    }

    @Test
    fun `refresh delay with negative age is 10000`() {
        assertEquals(10_000L, RelativeTime.nextRefreshDelayMs(-1_000L))
    }
}
