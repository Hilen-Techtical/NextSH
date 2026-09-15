// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.typeahead.TypeAheadTerminalModel.LineWithCursorX
import com.jediterm.core.typeahead.TypeAheadTerminalModel.ShellType

/**
 * Minimal no-op [TypeAheadTerminalModel] for the headless Compose renderer.
 *
 * JediTerm's `TerminalStarter` requires a `TerminalTypeAheadManager` even
 * when type-ahead is disabled: passing `null` triggers an NPE in the
 * emulator loop. This implementation reports `isTypeAheadEnabled() = false`
 * so the manager skips all prediction work; the other methods are unreachable
 * in that mode but must compile.
 *
 * Type-ahead (showing typed characters speculatively before the shell echoes
 * them) is a UX nicety that's nice-to-have but not required for a working
 * terminal. Enabling it later requires a real model that observes the
 * shared `TerminalTextBuffer` and predicts cursor movement: out of scope
 * for the MVP renderer.
 */
class NoOpTypeAheadModel : TypeAheadTerminalModel {
    override fun insertCharacter(p0: Char, p1: Int) = Unit
    override fun removeCharacters(p0: Int, p1: Int) = Unit
    override fun moveCursor(p0: Int) = Unit
    override fun forceRedraw() = Unit
    override fun clearPredictions() = Unit
    override fun lock() = Unit
    override fun unlock() = Unit
    override fun isUsingAlternateBuffer(): Boolean = false
    override fun getCurrentLineWithCursor(): LineWithCursorX = LineWithCursorX(StringBuffer(), 0)
    override fun getTerminalWidth(): Int = 80
    override fun isTypeAheadEnabled(): Boolean = false
    override fun getLatencyThreshold(): Long = 100L
    override fun getShellType(): ShellType = ShellType.Unknown
}
