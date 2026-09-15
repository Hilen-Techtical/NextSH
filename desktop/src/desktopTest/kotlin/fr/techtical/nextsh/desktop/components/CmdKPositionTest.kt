// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [computeCmdKPosition]: the pure, multi-screen-aware
 * position calculation for the CmdK palette.
 *
 * `computeCmdKPosition` is `internal`, reachable from this desktopTest
 * source set without any production-code changes (same pattern as
 * [fr.techtical.nextsh.desktop.sessions.ColorPickerConversionsTest]).
 *
 * Covers the scenarios the fix specifically targets:
 * - a secondary monitor at negative coordinates (left-of-primary layout),
 *   which the previous `GraphicsEnvironment.maximumWindowBounds`-based
 *   implementation could not handle (primary-screen-only);
 * - clamping to the carrying screen's edges when the naive centered
 *   position would spill past them;
 * - an anchor window LARGER than the screen carrying it (maximized across a
 *   virtual desktop, or a stale geometry restored after a resolution drop):
 *   the centered position then lands far past the right/bottom edge and only
 *   the clamp keeps the palette on screen, including on a negative-origin
 *   secondary monitor, where the two corrections have to compose;
 * - a screen smaller than the palette itself, where the clamp must not
 *   throw (`coerceIn` requires `min <= max`).
 *
 * Every expectation below is a LITERAL point, deliberately not a re-derivation
 * of `computeCmdKPosition`'s own formula: a test that recomputes
 * `anchor.x + (anchor.width - CMDK_WIDTH_DP) / 2` passes by construction and
 * would survive the formula being changed underneath it.
 *
 * NOT covered, by design: `usableBoundsOf(window)`, which picks the screen and
 * subtracts the OS insets. It reads `Window.graphicsConfiguration` and
 * `Toolkit.getScreenInsets`, i.e. real AWT device state that a unit test
 * cannot fabricate headlessly. `computeCmdKPosition` was split out of it
 * precisely so the decision logic is testable while the AWT lookup stays a
 * thin, inspectable wrapper.
 */
class CmdKPositionTest {

    @Test
    fun `centers horizontally and sits at one quarter height on a simple primary screen`() {
        val anchor = Rectangle(100, 100, 1280, 800)
        val screen = Rectangle(0, 0, 1920, 1080)

        val point = computeCmdKPosition(anchor, screen)

        // Centered: 100 + (1280 - 640) / 2 = 420 ; one quarter down: 100 + 800 / 4 = 300.
        // Both sit well inside [0, 1280] x [0, 600], so no clamping applies.
        assertEquals(420, point.x)
        assertEquals(300, point.y)
    }

    @Test
    fun `anchors on a secondary monitor at negative coordinates instead of the primary screen`() {
        // Secondary display to the left of the primary one, e.g. a 1920x1080
        // monitor whose origin sits at x = -1920 relative to the primary
        // monitor's (0,0). The main NextSH window lives entirely on it.
        val anchor = Rectangle(-1900, 50, 1600, 900)
        val screen = Rectangle(-1920, 0, 1920, 1040) // insets trimmed (40px taskbar)

        val point = computeCmdKPosition(anchor, screen)

        // -1900 + (1600 - 640) / 2 = -1420 ; 50 + 900 / 4 = 275. Negative x is
        // the whole point: the old primary-only maximumWindowBounds calculation
        // clamped into [0, ...] and dragged the palette onto the wrong monitor.
        assertEquals(-1420, point.x)
        assertEquals(275, point.y)
    }

    @Test
    fun `clamps to the screen's right and bottom edges when the naive position would overflow`() {
        // Anchor window pinned to the far right/bottom of a small screen:
        // centering + 1-quarter-height would push the palette past the
        // screen's usable bounds without clamping.
        val screen = Rectangle(0, 0, 800, 600)
        val anchor = Rectangle(700, 550, 750, 550) // deliberately spills off `screen`

        val point = computeCmdKPosition(anchor, screen)

        // Naive: x = 700 + (750 - 640) / 2 = 755, y = 550 + 550 / 4 = 687.
        // Clamped to maxX = 800 - 640 = 160 and maxY = 600 - 480 = 120.
        assertEquals(160, point.x)
        assertEquals(120, point.y)
    }

    @Test
    fun `clamps an anchor window larger than its own screen back inside that screen`() {
        // Window geometry wider AND taller than the usable area, restored
        // from a saved snapshot taken on a bigger display, or maximized across
        // a virtual desktop. Centering on it puts the palette far off-screen.
        val screen = Rectangle(0, 0, 1280, 800)
        val anchor = Rectangle(0, 0, 3000, 2000)

        val point = computeCmdKPosition(anchor, screen)

        // Naive: x = (3000 - 640) / 2 = 1180, y = 2000 / 4 = 500.
        // Clamped to maxX = 1280 - 640 = 640 and maxY = 800 - 480 = 320.
        assertEquals(640, point.x)
        assertEquals(320, point.y)
    }

    @Test
    fun `clamps an oversized anchor while staying on a negative-coordinate secondary screen`() {
        // The two corrections composed: the carrying screen has a negative
        // origin AND the window is wider than it. The clamp must pull the
        // palette back to the secondary screen's right edge (which is still a
        // NEGATIVE x), never onto the primary display.
        val screen = Rectangle(-1920, 0, 1920, 1040)
        val anchor = Rectangle(-1920, 0, 4000, 2000)

        val point = computeCmdKPosition(anchor, screen)

        // Naive: x = -1920 + (4000 - 640) / 2 = -240 (already on the primary
        // screen), y = 2000 / 4 = 500.
        // Clamped to maxX = -1920 + 1920 - 640 = -640 ; maxY = 1040 - 480 = 560
        // leaves y untouched.
        assertEquals(-640, point.x)
        assertEquals(500, point.y)
        assertTrue(point.x + CMDK_WIDTH_DP <= screen.x + screen.width, "palette must not spill onto the primary screen")
    }

    @Test
    fun `clamps to the screen origin without throwing when the screen is smaller than the palette`() {
        // A screen (or usable area after insets) smaller than the palette
        // itself. `maxX`/`maxY` would fall below `minX`/`minY` without the
        // defensive `coerceAtLeast(min)`: this must not crash `coerceIn`.
        val screen = Rectangle(10, 20, 400, 300) // smaller than 640x480
        val anchor = Rectangle(10, 20, 400, 300)

        val point = computeCmdKPosition(anchor, screen)

        // Both axes collapse onto the screen origin (maxX/maxY are pulled up
        // to minX/minY), so the palette overflows, but predictably, from the
        // top-left corner, instead of crashing.
        assertEquals(10, point.x)
        assertEquals(20, point.y)
    }

    @Test
    fun `pure function is deterministic for identical inputs`() {
        val anchor = Rectangle(0, 0, 1440, 900)
        val screen = Rectangle(0, 0, 1440, 900)

        val first = computeCmdKPosition(anchor, screen)
        val second = computeCmdKPosition(anchor, screen)

        assertEquals(first, second)
    }
}
