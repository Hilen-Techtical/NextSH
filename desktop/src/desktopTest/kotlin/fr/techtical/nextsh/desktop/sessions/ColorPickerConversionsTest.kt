// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure HSV ↔ RGB conversion helpers in [ColorPicker.kt].
 *
 * All three functions (`rgbToHsv`, `hsvToArgb`, `argbToHsv`) are `internal`, so
 * they are reachable from this desktopTest source set without any production-code
 * changes. The Android implementation in `:app` is byte-identical to the Desktop
 * one (same algorithm, same constant values), Desktop coverage is sufficient.
 *
 * Covers:
 * - Full round-trip: `rgbToHsv` → `hsvToArgb` recovers the original ARGB for a
 *   representative set of colors (pure primaries, black, white, mid-gray, a few
 *   arbitrary hues).
 * - `argbToHsv` → `hsvToArgb` identity.
 * - Hue 360 ≡ 0 (wrap-around handled correctly).
 * - `opaque(...)` forces alpha 0xFF: tested via the `argbToHsv` + `hsvToArgb`
 *   pipeline because `opaque` itself is `private` in this file; the final output
 *   of `hsvToArgb` always carries alpha 0xFF by contract.
 */
class ColorPickerConversionsTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Assert that `rgbToHsv` followed by `hsvToArgb` recovers [expectedArgb]
     * (with alpha 0xFF). The [label] is printed on failure for easier diagnosis.
     */
    private fun assertRoundTrip(expectedArgb: Int, label: String) {
        val r = (expectedArgb shr 16) and 0xFF
        val g = (expectedArgb shr 8) and 0xFF
        val b = expectedArgb and 0xFF
        val hsv = rgbToHsv(r, g, b)
        val recovered = hsvToArgb(hsv[0], hsv[1], hsv[2])
        assertEquals(expectedArgb, recovered, "$label: round-trip mismatch (R=$r G=$g B=$b → H=${hsv[0]} S=${hsv[1]} V=${hsv[2]})")
    }

    // ── round-trip: rgbToHsv → hsvToArgb ─────────────────────────────────────

    @Test
    fun `round-trip pure red 0xFFFF0000`() {
        assertRoundTrip(0xFFFF0000.toInt(), "pure red")
    }

    @Test
    fun `round-trip pure green 0xFF00FF00`() {
        assertRoundTrip(0xFF00FF00.toInt(), "pure green")
    }

    @Test
    fun `round-trip pure blue 0xFF0000FF`() {
        assertRoundTrip(0xFF0000FF.toInt(), "pure blue")
    }

    @Test
    fun `round-trip white 0xFFFFFFFF`() {
        assertRoundTrip(0xFFFFFFFF.toInt(), "white")
    }

    @Test
    fun `round-trip black 0xFF000000`() {
        assertRoundTrip(0xFF000000.toInt(), "black")
    }

    @Test
    fun `round-trip mid-gray 0xFF808080`() {
        assertRoundTrip(0xFF808080.toInt(), "mid-gray")
    }

    @Test
    fun `round-trip yellow 0xFFFFFF00`() {
        assertRoundTrip(0xFFFFFF00.toInt(), "yellow")
    }

    @Test
    fun `round-trip cyan 0xFF00FFFF`() {
        assertRoundTrip(0xFF00FFFF.toInt(), "cyan")
    }

    @Test
    fun `round-trip magenta 0xFFFF00FF`() {
        assertRoundTrip(0xFFFF00FF.toInt(), "magenta")
    }

    @Test
    fun `round-trip arbitrary orange 0xFFFF8000`() {
        assertRoundTrip(0xFFFF8000.toInt(), "orange")
    }

    @Test
    fun `round-trip arbitrary teal 0xFF008080`() {
        assertRoundTrip(0xFF008080.toInt(), "teal")
    }

    @Test
    fun `round-trip arbitrary indigo 0xFF4B0082`() {
        assertRoundTrip(0xFF4B0082.toInt(), "indigo")
    }

    // ── argbToHsv → hsvToArgb identity ───────────────────────────────────────

    @Test
    fun `argbToHsv then hsvToArgb is identity for pure red`() {
        val argb = 0xFFFF0000.toInt()
        val hsv = argbToHsv(argb)
        assertEquals(argb, hsvToArgb(hsv[0], hsv[1], hsv[2]))
    }

    @Test
    fun `argbToHsv then hsvToArgb is identity for arbitrary color 0xFF3A7BCD`() {
        val argb = 0xFF3A7BCD.toInt()
        val hsv = argbToHsv(argb)
        assertEquals(argb, hsvToArgb(hsv[0], hsv[1], hsv[2]))
    }

    // ── hue wrap-around: 360 ≡ 0 ─────────────────────────────────────────────

    @Test
    fun `hsvToArgb with hue 360 equals hue 0 (wrap-around)`() {
        val at0 = hsvToArgb(0f, 1f, 1f)
        val at360 = hsvToArgb(360f, 1f, 1f)
        assertEquals(at0, at360, "hue 360 must wrap to 0, same result as hue 0f")
    }

    @Test
    fun `hsvToArgb with hue 720 equals hue 0 (double wrap)`() {
        val at0 = hsvToArgb(0f, 1f, 1f)
        val at720 = hsvToArgb(720f, 1f, 1f)
        assertEquals(at0, at720, "hue 720 must wrap to 0")
    }

    // ── alpha is always 0xFF ──────────────────────────────────────────────────

    @Test
    fun `hsvToArgb always produces fully-opaque alpha`() {
        val colors = listOf(
            Triple(0f, 0f, 0f),   // black
            Triple(0f, 0f, 1f),   // white
            Triple(120f, 1f, 1f), // green
            Triple(240f, 0.5f, 0.75f), // arbitrary blue shade
        )
        for ((h, s, v) in colors) {
            val argb = hsvToArgb(h, s, v)
            val alpha = (argb ushr 24) and 0xFF
            assertEquals(0xFF, alpha, "alpha must be 0xFF for hsvToArgb($h, $s, $v)")
        }
    }

    // ── HSV channel ranges ────────────────────────────────────────────────────

    @Test
    fun `rgbToHsv hue is in 0 until 360`() {
        val samples = listOf(
            Triple(255, 0, 0),
            Triple(0, 255, 0),
            Triple(0, 0, 255),
            Triple(128, 64, 192),
        )
        for ((r, g, b) in samples) {
            val hsv = rgbToHsv(r, g, b)
            val hue = hsv[0]
            assertTrue(hue >= 0f && hue < 360f, "Hue must be in [0, 360) for RGB($r,$g,$b), got $hue")
        }
    }

    @Test
    fun `rgbToHsv saturation and value are in 0 to 1`() {
        val samples = listOf(
            Triple(255, 0, 0),
            Triple(0, 0, 0),
            Triple(255, 255, 255),
            Triple(100, 200, 50),
        )
        for ((r, g, b) in samples) {
            val hsv = rgbToHsv(r, g, b)
            val sat = hsv[1]
            val value = hsv[2]
            assertTrue(sat in 0f..1f, "Saturation out of range for RGB($r,$g,$b): $sat")
            assertTrue(value in 0f..1f, "Value out of range for RGB($r,$g,$b): $value")
        }
    }
}
