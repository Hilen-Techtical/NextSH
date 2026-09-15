// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import fr.techtical.nextsh.ui.theme.TerminalThemePalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Documente la cause de la perte de theme signalee en test manuel : ouvrir ou
 * fermer le clavier pendant un nano faisait revenir la palette par defaut.
 *
 * ncurses emet la sequence de reinitialisation des couleurs a chaque redessin
 * complet, donc a chaque redimensionnement du terminal. L'emulateur remet alors
 * sa palette par defaut et previent l'application, qui doit reposer le theme de
 * l'hote.
 *
 * Ce test couvre le mecanisme et la table de couleurs. Le branchement du
 * callback sur la vue Android n'est pas couvrable ici, il demande un appareil.
 */
class TerminalPaletteResetTest {

    private val palette = TerminalThemePalette(
        background  = 0xFF0F0F0F.toInt(),
        foreground  = 0xFFC9A84C.toInt(),
        cursor      = 0xFFE0C878.toInt(),
        selectionBg = 0xFF333333.toInt(),
        ansi        = IntArray(16) { 0xFF100000.toInt() + it },
    )

    /** Compte les notifications de changement de couleurs emises par l'emulateur. */
    private class RecordingOutput : TerminalOutput() {
        var colorChanges = 0
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() { colorChanges++ }
    }

    private fun emulator(output: TerminalOutput) =
        TerminalEmulator(output, 80, 24, 12, 24, 48, null)

    private fun feed(emulator: TerminalEmulator, sequence: String) {
        val bytes = sequence.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    @Test
    fun `la sequence de reinitialisation ncurses efface le theme de l hote`() {
        val output = RecordingOutput()
        val emulator = emulator(output)
        val colors = emulator.mColors.mCurrentColors
        applyPaletteTo(colors, palette)

        // OSC 104 sans parametre : ce qu'emet la capacite orig_colors de ncurses.
        feed(emulator, "]104")

        assertNotEquals(
            palette.foreground,
            colors[TextStyle.COLOR_INDEX_FOREGROUND],
            "sans reaction de l'application, le theme choisi est perdu",
        )
        assertTrue(output.colorChanges > 0, "l'emulateur doit prevenir l'application")
    }

    @Test
    fun `reposer la palette apres la reinitialisation restaure le theme`() {
        val output = RecordingOutput()
        val emulator = emulator(output)
        val colors = emulator.mColors.mCurrentColors
        applyPaletteTo(colors, palette)

        feed(emulator, "]104")
        // Ce que fait desormais le callback onPaletteOverridden.
        applyPaletteTo(colors, palette)

        assertEquals(palette.foreground, colors[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(palette.background, colors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(palette.cursor, colors[TextStyle.COLOR_INDEX_CURSOR])
        for (i in 0..15) assertEquals(palette.ansi[i], colors[i], "couleur ANSI $i")
    }

    @Test
    fun `les couleurs au dela des seize index ANSI ne sont pas ecrasees`() {
        val output = RecordingOutput()
        val emulator = emulator(output)
        val colors = emulator.mColors.mCurrentColors

        // Un programme distant definit la couleur 33 pour son propre usage.
        val custom = 0xFF123456.toInt()
        colors[33] = custom
        applyPaletteTo(colors, palette)

        assertEquals(custom, colors[33], "reposer le theme ne doit pas toucher la palette etendue")
    }
}
