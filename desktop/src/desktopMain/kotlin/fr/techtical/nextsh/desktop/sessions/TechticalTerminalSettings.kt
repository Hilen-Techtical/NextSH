// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import com.jediterm.core.Color
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import fr.techtical.nextsh.desktop.theme.defaultResolvedTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Terminal palette/style holder with a **mutable palette** that stays live
 * for the whole session, so runtime theme swaps propagate to both ANSI-coded
 * runs AND already-written "default-style" cells (MOTD, last-login line,
 * etc.).
 *
 * Plain Kotlin class: **no jediterm-ui dependency**. Before the Swing
 * renderer removal this implemented jediterm-ui's `SettingsProvider` (via
 * `DefaultSettingsProvider`) so it could be handed straight to
 * `JediTermWidget`; now that [fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalSession]
 * is the only renderer, only the pieces it actually reads survive:
 * [getDefaultStyle] and [setPalette] / [currentPalette]. Everything else
 * (the jediterm-ui-only surface (`getTerminalColorPalette`, `getTerminalFont`,
 * `getTerminalFontSize`, the `MutableColorPalette` ANSI-index resolver) AND
 * the Swing-only `awtBackground()` pre-paint helper (the `JediTermWidget`
 * AWT heavyweight-surface pre-paint it fed no longer exists: the Compose
 * `Box` background is set directly from [TerminalThemePalette] in
 * `ComposeTerminalRenderer`)) was dropped along with the `jediterm-ui`
 * Gradle dependency. ANSI index 0..15 resolution for the Compose renderer
 * goes through [TerminalThemePalette.ansi] directly in
 * `ComposeTerminalRenderer.resolveCompose`, not through jediterm's
 * `ColorPalette` abstraction.
 *
 * `defaultFg` / `defaultBg` are stable [TerminalColor] instances backed by a
 * `Supplier<Color>` lambda that reads from the atomic [paletteRef]. The
 * default [TextStyle] holds these stable instances, so every cell written
 * with the default style (past OR future) resolves its color dynamically at
 * paint time: no RGB is ever frozen at write time. Without this supplier
 * trick, a `TextStyle(RGB, RGB)` created at write time would snapshot the
 * palette, and older cells would keep the old colors after a theme swap.
 *
 * [fontSizeRef] follows the same mutable-holder pattern as [paletteRef] so
 * the font size configured in Settings (`DesktopSettingsStore.terminalFontSize`)
 * stays associated with the session for its whole lifetime and survives tab
 * switches. Unlike the palette, the Compose renderer cannot re-read this
 * value on every draw frame to get live updates for free (text layout
 * (`remember`) only recomputes when the enclosing composable recomposes)
 * so `ComposeTerminalRenderer` also takes the font size as a composable
 * parameter (observed from `DesktopSettingsStore.settings` in
 * `TerminalScreen`) for the actual live re-measure. [setFontSize] /
 * [currentFontSize] keep this holder as the source of truth for the initial
 * value handed to new sessions and for any non-Compose reader.
 */
class TechticalTerminalSettings(
    initialPalette: TerminalThemePalette = defaultResolvedTheme().palette,
    initialFontSize: Int = 14,
) {

    private val paletteRef = AtomicReference(initialPalette)
    private val fontSizeRef = AtomicInteger(initialFontSize)

    private val defaultFg: TerminalColor = TerminalColor {
        val p = paletteRef.get()
        Color(p.foreground.red(), p.foreground.green(), p.foreground.blue())
    }
    private val defaultBg: TerminalColor = TerminalColor {
        val p = paletteRef.get()
        Color(p.background.red(), p.background.green(), p.background.blue())
    }
    private val defaultStyle = TextStyle(defaultFg, defaultBg)

    fun setPalette(palette: TerminalThemePalette) {
        paletteRef.set(palette)
    }

    /**
     * Snapshot of the current [TerminalThemePalette]. Used by the Compose
     * renderer to resolve theme-driven colours (default background, default
     * foreground, cursor, ANSI 0..15) at draw time. Reading the ref each
     * time guarantees that a theme switch propagates on the next
     * recomposition.
     */
    fun currentPalette(): TerminalThemePalette = paletteRef.get()

    fun getDefaultStyle(): TextStyle = defaultStyle

    /** Updates the live font size (raw sp value), e.g. on a Settings slider change. */
    fun setFontSize(size: Int) {
        fontSizeRef.set(size)
    }

    /** Current font size (raw sp value) for this session. */
    fun currentFontSize(): Int = fontSizeRef.get()
}

private fun Int.red(): Int = (this shr 16) and 0xFF
private fun Int.green(): Int = (this shr 8) and 0xFF
private fun Int.blue(): Int = this and 0xFF
