// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// Unit tests for [TechticalTerminalSettings]'s mutable font-size holder: the
// fix for the "terminalFontSize is written but never consumed" bug: the
// Compose renderer used to hard-code `14.sp` regardless of
// DesktopSettingsStore. [setFontSize] / [currentFontSize] mirror the existing
// [TechticalTerminalSettings.setPalette] / [currentPalette] mutable-holder
// pattern, so this suite is the plain-Kotlin (no Compose runtime, no UI)
// slice of that fix: the constructor's initial value, and the live
// setter/getter round-trip that TerminalScreen's dedicated LaunchedEffect
// relies on for propagation to already-open sessions.
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import kotlin.test.Test
import kotlin.test.assertEquals

class TechticalTerminalSettingsTest {

    private fun testPalette(background: Int = 0x000000) = TerminalThemePalette(
        background  = background,
        foreground  = 0xFFFFFF,
        cursor      = 0xFFFFFF,
        selectionBg = 0x333333,
        ansi        = IntArray(16),
    )

    @Test
    fun `default font size is 14 when not specified at construction`() {
        val settings = TechticalTerminalSettings(initialPalette = testPalette())
        assertEquals(14, settings.currentFontSize())
    }

    @Test
    fun `constructor honours an explicit initial font size`() {
        val settings = TechticalTerminalSettings(
            initialPalette = testPalette(),
            initialFontSize = 18,
        )
        assertEquals(18, settings.currentFontSize())
    }

    @Test
    fun `setFontSize updates currentFontSize`() {
        val settings = TechticalTerminalSettings(initialPalette = testPalette())
        settings.setFontSize(20)
        assertEquals(20, settings.currentFontSize())
    }

    @Test
    fun `setFontSize is a plain live overwrite, not accumulating state`() {
        val settings = TechticalTerminalSettings(initialPalette = testPalette(), initialFontSize = 10)
        settings.setFontSize(16)
        settings.setFontSize(12)
        assertEquals(12, settings.currentFontSize())
    }

    @Test
    fun `font size and palette holders are independent`() {
        // Regression guard: the font-size AtomicInteger was added alongside the
        // pre-existing palette AtomicReference: a copy/paste mistake could
        // wire setFontSize to clobber the palette ref (or vice-versa) since
        // both follow the same construction pattern.
        val initialPalette = testPalette(background = 0x111111)
        val settings = TechticalTerminalSettings(initialPalette = initialPalette, initialFontSize = 14)

        settings.setFontSize(22)
        assertEquals(initialPalette, settings.currentPalette())
        assertEquals(22, settings.currentFontSize())

        val newPalette = testPalette(background = 0x222222)
        settings.setPalette(newPalette)
        assertEquals(newPalette, settings.currentPalette())
        assertEquals(22, settings.currentFontSize())
    }
}
