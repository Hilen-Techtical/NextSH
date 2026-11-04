// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the pure screen helpers of `ScreenBounds.kt`.
 *
 * [screenFor] is exercised with hand-written rectangles, no AWT global access.
 * The layout is the reporter's real one: a laptop whose usable area is
 * 1536×816 (1920×1080 at 125 % minus the taskbar) and a 27" 2560×1440 at
 * 100 % placed ABOVE it and shifted left, hence in negative coordinates. Its
 * usable area is 2560×1392 (a taskbar is shown on that screen too).
 */
class ScreenBoundsTest {

    private val laptop = Rectangle(0, 0, 1536, 816)
    private val monitor = Rectangle(-338, -1440, 2560, 1392)
    private val screens = listOf(laptop, monitor)

    // ── screenFor ─────────────────────────────────────────────────────────────

    @Test
    fun `a window on the 27 inch screen belongs to it`() {
        val window = Rectangle(-200, -1300, 1280, 800)

        assertSame(monitor, screenFor(window, screens))
    }

    @Test
    fun `a window on the laptop belongs to it`() {
        val window = Rectangle(154, 82, 1228, 652)

        assertSame(laptop, screenFor(window, screens))
    }

    @Test
    fun `a maximized laptop window belongs to the laptop despite its invisible frame`() {
        // Usable area plus the 7 px invisible frame on each side.
        val maximized = Rectangle(-7, -7, 1550, 830)

        assertSame(laptop, screenFor(maximized, screens))
    }

    @Test
    fun `a maximized window overflowing onto a neighbor stays on its own screen`() {
        // Side by side this time, so the 7 px frame really does overlap the
        // left neighbor: the intersection AREA decides, not the mere contact.
        val left = Rectangle(-1920, 0, 1920, 1032)
        val right = Rectangle(0, 0, 1536, 816)
        val maximizedRight = Rectangle(-7, -7, 1550, 830)

        assertSame(right, screenFor(maximizedRight, listOf(left, right)))
        assertSame(right, screenFor(maximizedRight, listOf(right, left)))
    }

    @Test
    fun `a straddling window belongs to the screen holding the larger part`() {
        val a = Rectangle(0, 0, 1536, 816)
        val b = Rectangle(1536, 0, 1920, 1032)

        // 536 px on a, 464 px on b.
        assertSame(a, screenFor(Rectangle(1000, 100, 1000, 600), listOf(a, b)))
        // 336 px on a, 664 px on b.
        assertSame(b, screenFor(Rectangle(1200, 100, 1000, 600), listOf(a, b)))
    }

    @Test
    fun `the first screen wins when the overlap areas are equal`() {
        val a = Rectangle(0, 0, 1000, 800)
        val b = Rectangle(1000, 0, 1000, 800)
        val centered = Rectangle(800, 100, 400, 400)

        assertSame(a, screenFor(centered, listOf(a, b)))
        assertSame(b, screenFor(centered, listOf(b, a)))
    }

    @Test
    fun `a window overlapping no screen has none`() {
        assertNull(screenFor(Rectangle(5000, 5000, 1228, 652), screens))
    }

    @Test
    fun `a minimized window parked far away has no screen`() {
        // What Windows reports for a minimized window.
        assertNull(screenFor(Rectangle(-32000, -32000, 160, 28), screens))
    }

    @Test
    fun `a window that only touches a screen edge has none`() {
        // Starts exactly where the laptop ends: zero overlap.
        assertNull(screenFor(Rectangle(1536, 0, 500, 500), listOf(laptop)))
    }

    @Test
    fun `an empty rectangle has no screen`() {
        assertNull(screenFor(Rectangle(100, 100, 0, 0), screens))
    }

    @Test
    fun `no screen at all gives none`() {
        assertNull(screenFor(Rectangle(154, 82, 1228, 652), emptyList()))
    }

    // ── usableScreenBounds ────────────────────────────────────────────────────

    @Test
    fun `usableScreenBounds never throws and only returns non empty rectangles`() {
        // Headless CI gives an empty list, a desktop session gives one entry
        // per screen: both are valid, the contract is "no exception".
        val bounds = usableScreenBounds()

        assertTrue(bounds.none { it.isEmpty }, "an empty usable area must be filtered out: $bounds")
    }
}
