// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Clipboard text must reach the terminal through TerminalEmulator.paste, never through a raw write.
 * Control characters are built from code points, never written raw in this source.
 */
class TerminalPasteTest {

    private val esc = 0x1B.toChar().toString()
    private val c1Csi = 0x9B.toChar().toString()

    private class RecordingOutput : TerminalOutput() {
        val sent = ByteArrayOutputStream()
        override fun write(data: ByteArray?, offset: Int, count: Int) {
            if (data != null) sent.write(data, offset, count)
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
        fun text(): String = sent.toString(Charsets.UTF_8)
    }

    private fun emulator(output: TerminalOutput) =
        TerminalEmulator(output, 80, 24, 12, 24, 48, null)

    private fun enableBracketedPaste(emulator: TerminalEmulator) {
        val bytes = "$esc[?2004h".toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    @Test
    fun `paste strips ESC and C1 controls and converts line breaks to CR`() {
        val output = RecordingOutput()
        val emulator = emulator(output)

        pasteClipboardText(emulator, "a${esc}[31mb${c1Csi}c\r\nd\ne")

        assertEquals("a[31mbc\rd\re", output.text())
    }

    @Test
    fun `paste is wrapped in bracketed paste markers when mode 2004 is on`() {
        val output = RecordingOutput()
        val emulator = emulator(output)
        enableBracketedPaste(emulator)

        pasteClipboardText(emulator, "rm -rf x\n")

        assertEquals("$esc[200~rm -rf x\r$esc[201~", output.text())
    }

    @Test
    fun `paste without mode 2004 sends no markers`() {
        val output = RecordingOutput()
        pasteClipboardText(emulator(output), "ls")

        assertEquals("ls", output.text())
    }

    @Test
    fun `empty or null text sends nothing`() {
        val output = RecordingOutput()
        val emulator = emulator(output)
        enableBracketedPaste(emulator)

        pasteClipboardText(emulator, "")
        pasteClipboardText(emulator, null)

        assertEquals("", output.text())
    }

    @Test
    fun `a single character is typing, even Enter or ESC`() {
        assertEquals(false, isKeyboardPaste("a"))
        assertEquals(false, isKeyboardPaste("\n"))
        assertEquals(false, isKeyboardPaste(esc))
        assertEquals(false, isKeyboardPaste(null))
        assertEquals(false, isKeyboardPaste(""))
    }

    @Test
    fun `multiline text from the keyboard is a paste`() {
        assertEquals(true, isKeyboardPaste("ls\nrm x"))
        assertEquals(true, isKeyboardPaste("ls\r\n"))
    }

    @Test
    fun `text with ESC or a C1 control from the keyboard is a paste`() {
        assertEquals(true, isKeyboardPaste("a${esc}[31m"))
        assertEquals(true, isKeyboardPaste("a${c1Csi}b"))
    }

    @Test
    fun `ordinary multi character text stays typing`() {
        assertEquals(false, isKeyboardPaste("hello world"))
        assertEquals(false, isKeyboardPaste("café 😀"))
    }

    @Test
    fun `a missing emulator is ignored`() {
        pasteClipboardText(null, "ls")
    }
}
