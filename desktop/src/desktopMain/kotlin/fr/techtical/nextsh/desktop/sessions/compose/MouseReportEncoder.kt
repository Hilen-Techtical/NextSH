// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

import com.jediterm.terminal.emulator.mouse.MouseButtonCodes
import com.jediterm.terminal.emulator.mouse.MouseButtonModifierFlags
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode

/**
 * xterm mouse-button identity. Values are the library's own [MouseButtonCodes]
 * constants (LEFT=0, MIDDLE=1, RIGHT=2) so the mapping stays tied to a single
 * canonical source instead of duplicating magic numbers.
 */
enum class MouseReportButton(internal val code: Int) {
    LEFT(MouseButtonCodes.LEFT),
    MIDDLE(MouseButtonCodes.MIDDLE),
    RIGHT(MouseButtonCodes.RIGHT),
}

/**
 * A single mouse interaction the renderer wants to forward to the remote
 * shell as an xterm mouse-tracking report.
 *
 * Shift is intentionally NOT modelled here: per xterm convention, a
 * Shift-held click/drag/wheel always bypasses reporting and is handled
 * locally (selection / scrollback) by the caller ([ComposeTerminalRenderer])
 * *before* the event ever reaches [MouseReportEncoder.encode].
 */
sealed interface MouseReportEvent {
    val ctrl: Boolean
    val meta: Boolean

    /** Button pressed down. */
    data class Press(
        val button: MouseReportButton,
        override val ctrl: Boolean = false,
        override val meta: Boolean = false,
    ) : MouseReportEvent

    /**
     * Button released. [button] is the button that was released: SGR keeps
     * this identity on the wire; legacy X10 discards it (see
     * [MouseReportEncoder] class doc).
     */
    data class Release(
        val button: MouseReportButton,
        override val ctrl: Boolean = false,
        override val meta: Boolean = false,
    ) : MouseReportEvent

    /** Pointer moved while [button] is held down (mode 1002/1003). */
    data class Drag(
        val button: MouseReportButton,
        override val ctrl: Boolean = false,
        override val meta: Boolean = false,
    ) : MouseReportEvent

    /** Bare pointer movement, no button held (mode 1003 only). */
    data class Move(
        override val ctrl: Boolean = false,
        override val meta: Boolean = false,
    ) : MouseReportEvent

    /** Wheel notch. [up] = scrolled away from the user. */
    data class Wheel(
        val up: Boolean,
        override val ctrl: Boolean = false,
        override val meta: Boolean = false,
    ) : MouseReportEvent
}

/**
 * Pure, side-effect-free encoder for xterm mouse-tracking escape sequences.
 * No Compose / AWT / jediterm-ui dependency: this is the one piece of the
 * mouse-reporting feature that is directly unit-testable (see
 * `MouseReportEncoderTest`).
 *
 * **Two wire formats:**
 *  - **SGR (mode 1006, [MouseFormat.MOUSE_FORMAT_SGR])**:
 *    `ESC[<Cb;Cx;CyM` on press/drag/wheel, `ESC[<Cb;Cx;Cym` on release. Cb
 *    keeps the real button identity even on release (the trailing letter
 *    alone disambiguates press vs release), and coordinates are plain
 *    decimal text: no 8-bit byte-encoding limit, so no 223 clamp.
 *  - **Legacy X10 (every other [MouseFormat], including JediTerm's default)**:
 *    `ESC[M` + three bytes, each `32 + value`. This byte-packed form can
 *    only carry values 0..223 (`0xFF` is reserved), so button/column/row are
 *    clamped to [X10_MAX_COORD]. Button identity is lost on release (xterm
 *    sends the sentinel [MouseButtonCodes.RELEASE] for every legacy release,
 *    regardless of which button was actually let go: a genuine limitation
 *    of the 1970s-vintage protocol, not a bug here).
 *
 * **Mode gating** ([shouldReport]) mirrors xterm's reporting ladder: NORMAL
 * (1000) reports press/release/wheel only; BUTTON_MOTION (1002) adds drag;
 * ALL_MOTION (1003) adds bare hover move. HILITE (1001) and FOCUS (1004) are
 * not implemented (no highlight-tracking round-trip, no focus-in/out
 * reporting): [shouldReport] returns `false` for both so the caller falls
 * back to local behaviour rather than sending a report the app didn't ask
 * the renderer to support.
 */
object MouseReportEncoder {

    /** Legacy X10 packs `32 + value` into one byte; 0xFF is reserved, so the max encodable value is 255 - 32. */
    private const val X10_MAX_COORD = 223

    /** xterm ctlseqs Cb for "no button" (used for X10 release and bare all-motion moves), same sentinel as [MouseButtonCodes.RELEASE]. */
    private const val NONE_BUTTON = MouseButtonCodes.RELEASE

    /** xterm ctlseqs wheel Cb values. Not modelled by [MouseButtonCodes] (whose SCROLLUP/SCROLLDOWN constants encode a different, ambiguous convention), so kept as named literals matching the protocol directly. */
    private const val WHEEL_UP_CB = 64
    private const val WHEEL_DOWN_CB = 65

    private const val MOTION_BIT = MouseButtonModifierFlags.MOUSE_BUTTON_MOTION_FLAG
    private const val CTRL_BIT = MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG
    private const val META_BIT = MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG

    /**
     * Whether [event] is covered by [mode]. A `false` result means "don't
     * report": the caller should let local behaviour (selection, scroll)
     * handle the event instead.
     */
    fun shouldReport(mode: MouseMode, event: MouseReportEvent): Boolean = when (mode) {
        MouseMode.MOUSE_REPORTING_NORMAL ->
            event !is MouseReportEvent.Drag && event !is MouseReportEvent.Move
        MouseMode.MOUSE_REPORTING_BUTTON_MOTION ->
            event !is MouseReportEvent.Move
        MouseMode.MOUSE_REPORTING_ALL_MOTION -> true
        else -> false // NONE, HILITE, FOCUS: not implemented, never report
    }

    /**
     * Encodes [event] at 1-based cell [column]/[row] (xterm reports the
     * top-left cell as column 1, row 1: callers convert from pixel
     * coordinates via their own cell metrics before calling this). Returns
     * `null` when [shouldReport] rejects [event] for [mode]: treat that as
     * "let local behaviour handle it", not as an encoding error.
     */
    fun encode(
        mode: MouseMode,
        format: MouseFormat,
        event: MouseReportEvent,
        column: Int,
        row: Int,
    ): ByteArray? {
        if (!shouldReport(mode, event)) return null
        val col = column.coerceAtLeast(1)
        val rowClamped = row.coerceAtLeast(1)
        return if (format == MouseFormat.MOUSE_FORMAT_SGR) {
            encodeSgr(event, col, rowClamped)
        } else {
            encodeX10(event, col, rowClamped)
        }
    }

    // ── SGR (mode 1006) ─────────────────────────────────────────────────────

    private fun encodeSgr(event: MouseReportEvent, column: Int, row: Int): ByteArray {
        val cb = sgrButtonCode(event)
        val suffix = if (event is MouseReportEvent.Release) 'm' else 'M'
        return "\u001B[<$cb;$column;$row$suffix".toByteArray(Charsets.US_ASCII)
    }

    private fun sgrButtonCode(event: MouseReportEvent): Int {
        val base = when (event) {
            is MouseReportEvent.Press -> event.button.code
            // SGR keeps the real button on release: only the trailing 'm'
            // (see encodeSgr) signals "released".
            is MouseReportEvent.Release -> event.button.code
            is MouseReportEvent.Drag -> event.button.code + MOTION_BIT
            is MouseReportEvent.Move -> NONE_BUTTON + MOTION_BIT
            is MouseReportEvent.Wheel -> if (event.up) WHEEL_UP_CB else WHEEL_DOWN_CB
        }
        return base + modifierBits(event)
    }

    // ── Legacy X10 (default) ────────────────────────────────────────────────

    private fun encodeX10(event: MouseReportEvent, column: Int, row: Int): ByteArray {
        val cb = x10ButtonCode(event)
        val cx = column.coerceAtMost(X10_MAX_COORD)
        val cy = row.coerceAtMost(X10_MAX_COORD)
        return byteArrayOf(
            0x1B, '['.code.toByte(), 'M'.code.toByte(),
            (32 + cb).toByte(),
            (32 + cx).toByte(),
            (32 + cy).toByte(),
        )
    }

    private fun x10ButtonCode(event: MouseReportEvent): Int {
        val base = when (event) {
            is MouseReportEvent.Press -> event.button.code
            // Legacy X10 has no room to carry button identity on release:
            // every release reports the same sentinel, regardless of button.
            is MouseReportEvent.Release -> NONE_BUTTON
            is MouseReportEvent.Drag -> event.button.code + MOTION_BIT
            is MouseReportEvent.Move -> NONE_BUTTON + MOTION_BIT
            is MouseReportEvent.Wheel -> if (event.up) WHEEL_UP_CB else WHEEL_DOWN_CB
        }
        return base + modifierBits(event)
    }

    private fun modifierBits(event: MouseReportEvent): Int {
        var bits = 0
        if (event.ctrl) bits = bits or CTRL_BIT
        if (event.meta) bits = bits or META_BIT
        return bits
    }
}
