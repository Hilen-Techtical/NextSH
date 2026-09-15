// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Garde-fous de la paire Techtical.
 *
 * Techtical Light a été redessiné le 2026-09-09 : encre sur le fond beige, or
 * et bordeaux aux mêmes emplacements que dans Techtical Dark, et un contraste
 * mesuré pour chaque couleur. Ces tests fixent ce qui a été décidé, pour que
 * le prochain retoucheur sache ce qu'il casse.
 */
class TerminalThemesTest {

    private val light = TERMINAL_THEMES.getValue(TerminalThemeId.TECHTICAL_LIGHT)
    private val dark = TERMINAL_THEMES.getValue(TerminalThemeId.TECHTICAL_DARK)

    @Test
    fun `Techtical Light vient juste apres Techtical Dark dans le selecteur`() {
        assertEquals(
            listOf(TerminalThemeId.TECHTICAL_DARK, TerminalThemeId.TECHTICAL_LIGHT),
            TerminalThemeId.entries.take(2),
        )
    }

    @Test
    fun `le fond beige est conserve et l encre demandee est le premier plan`() {
        assertEquals(0xFFF5F0E8.toInt(), light.background)
        assertEquals(0xFF1A1A1A.toInt(), light.foreground)
        assertEquals(0xFFA8862E.toInt(), light.cursor)
    }

    @Test
    fun `le vert ANSI du clair porte le bordeaux comme dans le sombre`() {
        assertEquals(0xFF8B1A2F.toInt(), light.ansi[2])
        assertEquals(dark.ansi[10], light.ansi[10])
    }

    @Test
    fun `toute couleur normale du clair est lisible en corps normal sur son fond`() {
        val normal = (0..7).map { light.ansi[it] } + light.foreground
        normal.forEachIndexed { index, color ->
            val ratio = contrast(color, light.background)
            assertTrue(ratio >= 4.5, "couleur $index (${hex(color)}) : ${"%.2f".format(ratio)}:1, attendu 4,5:1 au moins")
        }
    }

    @Test
    fun `toute couleur vive du clair est lisible en gras sur son fond`() {
        (8..15).forEach { index ->
            val ratio = contrast(light.ansi[index], light.background)
            assertTrue(ratio >= 3.0, "couleur $index (${hex(light.ansi[index])}) : ${"%.2f".format(ratio)}:1, attendu 3:1 au moins")
        }
    }

    @Test
    fun `le texte selectionne reste lisible`() {
        val selection = blendOver(light.selectionBg, light.background)
        assertTrue(contrast(light.foreground, selection) >= 4.5)
    }

    // ── WCAG 2.x ──────────────────────────────────────────────────────────────

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Compose une couleur ARGB translucide sur un fond opaque. */
    private fun blendOver(top: Int, base: Int): Int {
        val alpha = ((top ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val b = (base shr shift) and 0xFF
            return (t * alpha + b * (1 - alpha)).toInt()
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
}
