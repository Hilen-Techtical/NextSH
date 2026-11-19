// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The terminal draws with the bundled JetBrains Mono, like the Desktop terminal. Left to the
 * system "monospace", the glyphs depend on the maker, the chosen system font and the language:
 * on ColorOS in English, DroidSansMono made the "i" and the "l" look bold.
 *
 * Source-level guard (no instrumented test for the terminal view): the terminal view built by the
 * screen gets the typeface, and gets it after its text size, since the Termux view only creates its
 * renderer in setTextSize and setTypeface reuses the size of that renderer.
 */
class TerminalFontGuardTest {

    private val factory: String by lazy {
        val source = SourceGuardSupport.mainSource("ui/sessions/TerminalScreen.kt")
        val start = source.indexOf("PasteSafeTerminalView(ctx)")
        assertTrue(start >= 0, "TerminalScreen.kt no longer builds PasteSafeTerminalView(ctx): update this guard")
        source.substring(start, source.indexOf("update =", start).takeIf { it > start } ?: source.length)
    }

    @Test
    fun `the terminal view gets the bundled JetBrains Mono`() {
        assertTrue(
            Regex("""ResourcesCompat\.getFont\(\s*ctx\s*,\s*R\.font\.jetbrains_mono_regular\s*\)""").containsMatchIn(factory),
            "the TerminalView must be given R.font.jetbrains_mono_regular, not the system monospace",
        )
        assertTrue(factory.contains("setTypeface("), "the font must be applied with setTypeface")
    }

    @Test
    fun `the typeface is set after the text size`() {
        val size = factory.indexOf("setTextSize(")
        val typeface = factory.indexOf("setTypeface(")
        assertTrue(size >= 0 && typeface > size, "setTypeface must come after setTextSize, which creates the renderer")
    }
}
