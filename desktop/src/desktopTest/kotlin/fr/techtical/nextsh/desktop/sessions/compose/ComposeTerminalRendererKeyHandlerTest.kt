// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

// ──────────────────────────────────────────────────────────────────────────────
// We target [shouldSendPrintable]: the pure modifier-combination decision
// function pulled out of [handleKeyEvent] step 6, rather than driving
// [handleKeyEvent] end-to-end. Compose Desktop's `KeyEvent` wraps the AWT
// event in an `InternalKeyEvent` instance that is `internal` to
// `androidx.compose.ui`, so a test cannot construct a Compose KeyEvent that
// satisfies the modifier accessors (`isCtrlPressed`, `utf16CodePoint`, …).
//
// The full handleKeyEvent path is exercised by manual validation on Windows
// (see plan: `passons-sur-des-correctifs-iridescent-stardust.md`, section
// "Validation manuelle").
// ──────────────────────────────────────────────────────────────────────────────

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeTerminalRendererKeyHandlerTest {

    // ── Bug 1 - AltGr (Ctrl+Alt) + printable codepoint → admitted ────────────

    /**
     * On Windows AZERTY, AltGr+6 produces '|' (0x7C). Win32 / Java AWT report
     * the chord as Ctrl+Alt simultaneously; CMP propagates both modifiers,
     * and `utf16CodePoint` already holds the composed pipe character.
     * Step 6 must admit this: that is the core of Bug 1.
     */
    @Test
    fun altGrPipe_isAdmitted() {
        assertTrue(
            shouldSendPrintable(cp = '|'.code, isCtrl = true, isAlt = true, isMeta = false),
        )
    }

    /** AltGr+0 → '@', AltGr+4 → '{', AltGr+8 → '\' on Windows AZERTY. */
    @Test
    fun altGrCommonAzertyChars_areAllAdmitted() {
        val printables = listOf('@', '#', '[', ']', '{', '}', '~', '\\', '|', '€')
        for (ch in printables) {
            assertTrue(
                shouldSendPrintable(cp = ch.code, isCtrl = true, isAlt = true, isMeta = false),
                "AltGr-composed '$ch' (cp=${ch.code}) should be admitted",
            )
        }
    }

    // ── Bug 1 regression guards ──────────────────────────────────────────────

    /**
     * A genuine Ctrl+Alt+<letter> chord that the OS does NOT compose arrives
     * with `utf16CodePoint == 0xFFFF` (CHAR_UNDEFINED). Must be rejected so no
     * ghost char reaches the shell: the chord falls through to step 5
     * (JediTerm's `getCodeForKey`) instead.
     */
    @Test
    fun ctrlAltUndefinedCodepoint_isRejected() {
        assertFalse(
            shouldSendPrintable(cp = 0xFFFF, isCtrl = true, isAlt = true, isMeta = false),
        )
    }

    /** Dead-key / pending IME composition reports `cp == 0`. */
    @Test
    fun ctrlAltZeroCodepoint_isRejected() {
        assertFalse(
            shouldSendPrintable(cp = 0, isCtrl = true, isAlt = true, isMeta = false),
        )
    }

    /** Pure Ctrl chord (no Alt) is handled by step 4c / step 5: step 6 skips. */
    @Test
    fun ctrlOnlyPrintable_isRejected() {
        assertFalse(
            shouldSendPrintable(cp = 'c'.code, isCtrl = true, isAlt = false, isMeta = false),
        )
    }

    /** Meta chord (Cmd on macOS, Win on Windows) is never a printable input. */
    @Test
    fun metaPrintable_isRejected() {
        assertFalse(
            shouldSendPrintable(cp = 'a'.code, isCtrl = false, isAlt = false, isMeta = true),
        )
    }

    /** Even with AltGr semantics, Meta as a modifier blocks the input. */
    @Test
    fun altGrPlusMeta_isRejected() {
        assertFalse(
            shouldSendPrintable(cp = '|'.code, isCtrl = true, isAlt = true, isMeta = true),
        )
    }

    /**
     * Control codes (ASCII 0x00..0x1F, 0x7F) must never be sent via the
     * printable-fallback path even if a buggy layout claimed they were
     * composed under AltGr.
     */
    @Test
    fun isoControlCodepoint_isRejected() {
        for (ctrl in listOf(0x07, 0x08, 0x0A, 0x0D, 0x1B, 0x7F)) {
            assertFalse(
                shouldSendPrintable(cp = ctrl, isCtrl = true, isAlt = true, isMeta = false),
                "Control code 0x${ctrl.toString(16)} must be rejected",
            )
        }
    }

    // ── Baseline: ordinary printable input still works ───────────────────────

    @Test
    fun plainPrintableLetter_isAdmitted() {
        assertTrue(
            shouldSendPrintable(cp = 'a'.code, isCtrl = false, isAlt = false, isMeta = false),
        )
    }

    @Test
    fun shiftedAzertyChar_isAdmitted() {
        // Shift+key on AZERTY produces composed chars like '?', ':', '/', etc.
        // Shift is not a modifier passed to shouldSendPrintable (it doesn't gate
        // printable input); the composed codepoint speaks for itself.
        assertTrue(
            shouldSendPrintable(cp = '?'.code, isCtrl = false, isAlt = false, isMeta = false),
        )
    }

    @Test
    fun altOnlyPrintable_isAdmitted() {
        // Bare Alt (no Ctrl) on Linux/macOS keyboards composes printables (e.g.
        // Alt+5 → '[' on a US Mac layout). With isCtrl=false, the condition
        // collapses to `!isCtrl`, so the char passes.
        assertTrue(
            shouldSendPrintable(cp = '['.code, isCtrl = false, isAlt = true, isMeta = false),
        )
    }
}
