// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.preferences

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The scrollback choices of the settings screen, and what Termux does with them. */
class ScrollbackStepsTest {

    private class SilentOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    /** Active transcript rows of an emulator built with [transcriptRows] after [lines] lines of output. */
    private fun transcriptAfter(transcriptRows: Int, lines: Int): Int {
        val emulator = TerminalEmulator(SilentOutput(), 80, 24, 12, 24, transcriptRows, null)
        val bytes = "x\r\n".repeat(lines).toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        return emulator.screen.activeTranscriptRows
    }

    @Test
    fun `the steps are the five agreed ones and the default is one of them`() {
        assertEquals(listOf(1000, 2000, 5000, 10_000, 20_000), SCROLLBACK_STEPS)
        assertEquals(5000, DEFAULT_SCROLLBACK_LINES)
        assertTrue(DEFAULT_SCROLLBACK_LINES in SCROLLBACK_STEPS)
    }

    @Test
    fun `every step is inside the bounds of Termux`() {
        assertTrue(SCROLLBACK_STEPS.max() <= TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX)
        assertTrue(SCROLLBACK_STEPS.min() >= TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN)
    }

    @Test
    fun `a step stays as it is`() {
        SCROLLBACK_STEPS.forEach { assertEquals(it, nearestScrollbackStep(it)) }
    }

    @Test
    fun `a value out of the steps is brought to the nearest one`() {
        assertEquals(20_000, nearestScrollbackStep(100_000))
        assertEquals(20_000, nearestScrollbackStep(37_412))
        assertEquals(10_000, nearestScrollbackStep(7600))
        assertEquals(5000, nearestScrollbackStep(7400))
        assertEquals(1000, nearestScrollbackStep(1))
        assertEquals(1000, nearestScrollbackStep(-50))
    }

    @Test
    fun `a value halfway between two steps goes to the smaller one`() {
        assertEquals(2000, nearestScrollbackStep(3500))
    }

    @Test
    fun `an emulator built with the largest step keeps more than the 2000 lines of its fallback`() {
        val largest = SCROLLBACK_STEPS.max()

        assertTrue(transcriptAfter(largest, 3000) > 2000, "Termux falls back to 2000 lines in silence out of its bounds")
        assertTrue(transcriptAfter(2000, 3000) <= 2000)
    }
}
