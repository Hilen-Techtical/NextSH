// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import java.awt.Rectangle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [mapToPhysical], the pure logical to physical conversion of
 * [WindowsRestoreBounds].
 *
 * The native part (reading and writing the `WINDOWPLACEMENT` of a real HWND)
 * needs a shown Windows window and is not covered here. What is covered is the
 * arithmetic that decides WHICH rectangle gets written, on the reporter's real
 * screens: a laptop whose usable area is 1536×816 logical (1920×1020 physical
 * at 125 %) and a 27" 2560×1392 usable area at 100 %.
 *
 * Rounding convention: `x`, `y`, `width` and `height` are rounded
 * independently, half up (`Math.round`). At 125 % an origin of 154 is 192.5
 * physical pixels and becomes 193.
 */
class WindowsRestoreBoundsTest {

    /** Usable area of the laptop, logical (what the window is created with). */
    private val laptopLogical = Rectangle(0, 0, 1536, 816)

    /** The same area as Windows reports it in `rcNormalPosition`, at 125 %. */
    private val laptopPhysical = Rectangle(0, 0, 1920, 1020)

    /** Default floating geometry on the laptop: 80 % of the usable area, centered. */
    private val laptopFloating = Rectangle(154, 82, 1228, 652)

    /** Usable area of the 27" (above and left of the laptop, so negative coordinates). */
    private val monitorLogical = Rectangle(-338, -1440, 2560, 1392)

    /** A floating geometry on the 27": 80 % of its width, offset inside it. */
    private val monitorFloating = Rectangle(-138, -1340, 2048, 1113)

    // ── 125 % laptop ──────────────────────────────────────────────────────────

    @Test
    fun `the default floating geometry maps to physical pixels at 125 percent`() {
        // x = round(154 * 1.25 = 192.5) = 193 ; y = round(82 * 1.25 = 102.5) = 103
        // w = 1228 * 1.25 = 1535 ; h = 652 * 1.25 = 815
        assertEquals(
            Rectangle(193, 103, 1535, 815),
            mapToPhysical(laptopFloating, laptopLogical, laptopPhysical),
        )
    }

    @Test
    fun `the reference rectangle maps onto its own physical image`() {
        assertEquals(laptopPhysical, mapToPhysical(laptopLogical, laptopLogical, laptopPhysical))
    }

    @Test
    fun `a floating geometry centered in the logical area stays centered in the physical one`() {
        val physical = mapToPhysical(laptopFloating, laptopLogical, laptopPhysical)!!

        // The margins on the left/right differ by at most 1 px (half-up rounding).
        val left = physical.x - laptopPhysical.x
        val right = laptopPhysical.x + laptopPhysical.width - (physical.x + physical.width)
        assertTrue(abs(left - right) <= 1, "left=$left right=$right")
    }

    // ── 27" at 100 % ──────────────────────────────────────────────────────────

    @Test
    fun `at 100 percent with the same origin the mapping is the identity`() {
        assertEquals(
            monitorFloating,
            mapToPhysical(monitorFloating, monitorLogical, monitorLogical),
        )
    }

    @Test
    fun `at 100 percent a shifted physical origin is a pure translation`() {
        // Physical origin (-422, -1440) instead of (-338, -1440): dx = -84, dy = 0.
        val shifted = Rectangle(-422, -1440, 2560, 1392)

        assertEquals(
            Rectangle(-222, -1340, 2048, 1113),
            mapToPhysical(monitorFloating, monitorLogical, shifted),
        )
    }

    @Test
    fun `a workspace origin below the screen top is honored`() {
        // Taskbar on top: the usable area starts at y = 40 logical, but the
        // workspace coordinates of rcNormalPosition start at 0.
        val logical = Rectangle(0, 40, 1920, 1040)
        val physical = Rectangle(0, 0, 1920, 1040)

        assertEquals(
            Rectangle(320, 120, 1280, 800),
            mapToPhysical(Rectangle(320, 160, 1280, 800), logical, physical),
        )
    }

    // ── other scales and rounding ─────────────────────────────────────────────

    @Test
    fun `a scale of 200 percent doubles offsets and sizes`() {
        val logical = Rectangle(0, 0, 1280, 720)
        val physical = Rectangle(0, 0, 2560, 1440)

        assertEquals(
            Rectangle(202, 102, 2002, 1202),
            mapToPhysical(Rectangle(101, 51, 1001, 601), logical, physical),
        )
    }

    @Test
    fun `halves are rounded up independently on each component`() {
        val logical = Rectangle(0, 0, 1000, 600)
        val physical = Rectangle(0, 0, 1500, 900)

        // 1.5 -> 2, 1.5 -> 2, 1498.5 -> 1499, 898.5 -> 899
        assertEquals(
            Rectangle(2, 2, 1499, 899),
            mapToPhysical(Rectangle(1, 1, 999, 599), logical, physical),
        )
    }

    @Test
    fun `a window on a screen with a negative origin keeps its offset sign`() {
        val logical = Rectangle(-1280, -720, 1280, 720)
        val physical = Rectangle(-2560, -1440, 2560, 1440)

        // offset (+160, +90) from the logical origin -> (+320, +180) from the physical one
        assertEquals(
            Rectangle(-2240, -1260, 1600, 900),
            mapToPhysical(Rectangle(-1120, -630, 800, 450), logical, physical),
        )
    }

    // ── references that cannot be trusted: apply nothing ──────────────────────

    @Test
    fun `an empty logical reference gives null`() {
        assertNull(mapToPhysical(laptopFloating, Rectangle(0, 0, 0, 816), laptopPhysical))
        assertNull(mapToPhysical(laptopFloating, Rectangle(0, 0, 1536, 0), laptopPhysical))
        assertNull(mapToPhysical(laptopFloating, Rectangle(), laptopPhysical))
    }

    @Test
    fun `an empty physical reference gives null`() {
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 0, 1020)))
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 1920, 0)))
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle()))
    }

    @Test
    fun `a negative reference size gives null`() {
        assertNull(mapToPhysical(laptopFloating, Rectangle(0, 0, -1536, 816), laptopPhysical))
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 1920, -1020)))
    }

    @Test
    fun `an empty target gives null`() {
        assertNull(mapToPhysical(Rectangle(154, 82, 0, 652), laptopLogical, laptopPhysical))
        assertNull(mapToPhysical(Rectangle(154, 82, 1228, 0), laptopLogical, laptopPhysical))
    }

    @Test
    fun `a scale above 4 gives null`() {
        // A physical window five times the logical one: not a DPI scale.
        val physical = Rectangle(0, 0, 1536 * 5, 816 * 5)

        assertNull(mapToPhysical(laptopFloating, laptopLogical, physical))
    }

    @Test
    fun `a scale below one half gives null`() {
        // The window as AWT creates it by default (133×38) instead of the
        // expected usable area: the reference is not what we think it is.
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 133, 38)))
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 767, 407)))
    }

    @Test
    fun `the scale bounds 0_5 and 4_0 are accepted`() {
        val half = mapToPhysical(Rectangle(100, 100, 800, 400), Rectangle(0, 0, 1600, 900), Rectangle(0, 0, 800, 450))
        val four = mapToPhysical(Rectangle(100, 100, 800, 400), Rectangle(0, 0, 1600, 900), Rectangle(0, 0, 6400, 3600))

        assertEquals(Rectangle(50, 50, 400, 200), half)
        assertEquals(Rectangle(400, 400, 3200, 1600), four)
    }

    @Test
    fun `horizontal and vertical scales that disagree give null`() {
        // 1.25 horizontally but 2.0 vertically: DPI scaling is uniform, so the
        // physical rectangle is not the image of the logical one.
        assertNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 1920, 1632)))
    }

    @Test
    fun `a one pixel rounding gap between the axes is tolerated`() {
        // 1920×1021 physical for 1536×816 logical: 1.25 vs 1.2512.
        assertNotNull(mapToPhysical(laptopFloating, laptopLogical, Rectangle(0, 0, 1920, 1021)))
    }
}
