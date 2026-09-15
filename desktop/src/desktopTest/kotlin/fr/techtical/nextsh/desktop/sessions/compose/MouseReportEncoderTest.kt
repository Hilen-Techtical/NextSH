// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

// ──────────────────────────────────────────────────────────────────────────────
// [MouseReportEncoder] is a pure function (no Compose / AWT / jediterm-ui
// dependency), so it's exercised directly here rather than through the
// renderer's pointerInput plumbing: mirrors the existing
// [ComposeTerminalRendererKeyHandlerTest] strategy of targeting the pure
// helper pulled out of the Compose-specific event path.
// ──────────────────────────────────────────────────────────────────────────────

import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MouseReportEncoderTest {

    // ── shouldReport - mode ladder ────────────────────────────────────────────

    @Test
    fun none_neverReports() {
        val events = listOf(
            MouseReportEvent.Press(MouseReportButton.LEFT),
            MouseReportEvent.Release(MouseReportButton.LEFT),
            MouseReportEvent.Drag(MouseReportButton.LEFT),
            MouseReportEvent.Move(),
            MouseReportEvent.Wheel(up = true),
        )
        for (event in events) {
            assertFalse(
                MouseReportEncoder.shouldReport(MouseMode.MOUSE_REPORTING_NONE, event),
                "NONE must never report $event",
            )
        }
    }

    /** DEC private mode 1000: `CSI ?1000 h`. */
    @Test
    fun normal_reportsPressReleaseWheelOnly() {
        val mode = MouseMode.MOUSE_REPORTING_NORMAL
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Press(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Release(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Wheel(up = true)))
        assertFalse(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Drag(MouseReportButton.LEFT)))
        assertFalse(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Move()))
    }

    /**
     * DEC private mode 1002: `CSI ?1002 h`, "button-event tracking": drag
     * (a move WITH a button held) is reported, a bare hover move is not. This
     * is the mode tmux and vim turn on, and the boundary between the two kinds
     * of motion is the whole point of it.
     */
    @Test
    fun buttonMotion_addsDragButNotBareMove() {
        val mode = MouseMode.MOUSE_REPORTING_BUTTON_MOTION
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Press(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Release(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Drag(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Wheel(up = false)))
        assertFalse(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Move()))
    }

    /**
     * DEC private mode 1003: `CSI ?1003 h`, "any-event tracking": every
     * gesture reports, bare hover moves included (what an app enables to draw
     * hover highlights).
     */
    @Test
    fun allMotion_reportsEverythingIncludingBareMove() {
        val mode = MouseMode.MOUSE_REPORTING_ALL_MOTION
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Press(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Release(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Drag(MouseReportButton.LEFT)))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Move()))
        assertTrue(MouseReportEncoder.shouldReport(mode, MouseReportEvent.Wheel(up = true)))
    }

    @Test
    fun hiliteAndFocusModes_areNotImplemented_neverReport() {
        val event = MouseReportEvent.Press(MouseReportButton.LEFT)
        assertFalse(MouseReportEncoder.shouldReport(MouseMode.MOUSE_REPORTING_HILITE, event))
        assertFalse(MouseReportEncoder.shouldReport(MouseMode.MOUSE_REPORTING_FOCUS, event))
    }

    /**
     * [MouseReportEncoder.encode] must apply the same ladder as
     * [MouseReportEncoder.shouldReport]: a caller that skipped the explicit
     * gate check must not get bytes it would then send to an app that never
     * asked for them. One case per rung of the ladder that rejects something.
     */
    @Test
    fun encode_returnsNull_whenModeDoesNotCoverEvent() {
        // 1000 rejects drag…
        assertNull(
            MouseReportEncoder.encode(
                MouseMode.MOUSE_REPORTING_NORMAL,
                MouseFormat.MOUSE_FORMAT_SGR,
                MouseReportEvent.Drag(MouseReportButton.LEFT),
                column = 5,
                row = 5,
            ),
        )
        // …and 1000 rejects bare move too.
        assertNull(
            MouseReportEncoder.encode(
                MouseMode.MOUSE_REPORTING_NORMAL,
                MouseFormat.MOUSE_FORMAT_SGR,
                MouseReportEvent.Move(),
                column = 5,
                row = 5,
            ),
        )
        // 1002 accepts drag but still rejects bare move: the rung that
        // separates "button-event" from "any-event" tracking.
        assertNull(
            MouseReportEncoder.encode(
                MouseMode.MOUSE_REPORTING_BUTTON_MOTION,
                MouseFormat.MOUSE_FORMAT_SGR,
                MouseReportEvent.Move(),
                column = 5,
                row = 5,
            ),
        )
        // NONE rejects everything, press included.
        assertNull(
            MouseReportEncoder.encode(
                MouseMode.MOUSE_REPORTING_NONE,
                MouseFormat.MOUSE_FORMAT_SGR,
                MouseReportEvent.Press(MouseReportButton.LEFT),
                column = 5,
                row = 5,
            ),
        )
    }

    // ── SGR (mode 1006) encoding ────────────────────────────────────────────

    /**
     * Decodes an SGR report to its printable tail (`[<Cb;Cx;CyM`), stripping
     * the leading ESC (0x1B) byte for readable string-literal assertions
     * below. [sgr_startsWithEscByte] separately asserts the ESC prefix is
     * actually present on the wire.
     */
    private fun sgrString(bytes: ByteArray): String {
        assertEquals(0x1B, bytes[0].toInt(), "SGR report must start with ESC")
        return bytes.drop(1).toByteArray().toString(Charsets.US_ASCII)
    }

    @Test
    fun sgr_startsWithEscByte() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 1,
            row = 1,
        )!!
        assertEquals(0x1B, bytes[0].toInt())
    }

    @Test
    fun sgr_press_leftButton_encodesUppercaseM() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 10,
            row = 20,
        )
        assertEquals("[<0;10;20M", sgrString(bytes!!))
    }

    @Test
    fun sgr_release_leftButton_encodesLowercaseM_andKeepsButtonIdentity() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Release(MouseReportButton.RIGHT),
            column = 10,
            row = 20,
        )
        // Cb=2 (RIGHT) preserved on release: only the trailing letter changes.
        assertEquals("[<2;10;20m", sgrString(bytes!!))
    }

    @Test
    fun sgr_drag_addsMotionBit32() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_BUTTON_MOTION,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Drag(MouseReportButton.LEFT),
            column = 1,
            row = 1,
        )
        // 0 (LEFT) + 32 (motion) = 32
        assertEquals("[<32;1;1M", sgrString(bytes!!))
    }

    @Test
    fun sgr_bareMove_usesNoneButtonPlusMotion() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_ALL_MOTION,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Move(),
            column = 1,
            row = 1,
        )
        // 3 (no-button sentinel) + 32 (motion) = 35
        assertEquals("[<35;1;1M", sgrString(bytes!!))
    }

    @Test
    fun sgr_wheel_upIs64_downIs65() {
        val up = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Wheel(up = true), column = 3, row = 4,
        )
        val down = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Wheel(up = false), column = 3, row = 4,
        )
        assertEquals("[<64;3;4M", sgrString(up!!))
        assertEquals("[<65;3;4M", sgrString(down!!))
    }

    @Test
    fun sgr_ctrlAndMetaModifiers_addTheirBits() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Press(MouseReportButton.LEFT, ctrl = true, meta = true),
            column = 1,
            row = 1,
        )
        // 0 (LEFT) + 16 (ctrl) + 8 (meta) = 24
        assertEquals("[<24;1;1M", sgrString(bytes!!))
    }

    @Test
    fun sgr_doesNotClampLargeCoordinates() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 500,
            row = 300,
        )
        assertEquals("[<0;500;300M", sgrString(bytes!!))
    }

    // ── Legacy X10 encoding (default for every non-SGR format) ───────────────

    @Test
    fun x10_press_leftButton_atOrigin() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 1,
            row = 1,
        )
        assertContentEquals(
            byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (32 + 0).toByte(), (32 + 1).toByte(), (32 + 1).toByte()),
            bytes,
        )
    }

    @Test
    fun x10_release_alwaysReportsButtonSentinel3_regardlessOfWhichButton() {
        val left = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Release(MouseReportButton.LEFT), column = 1, row = 1,
        )!!
        val right = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Release(MouseReportButton.RIGHT), column = 1, row = 1,
        )!!
        // Both encode Cb=3 (32+3=35): X10 has no room to carry button identity on release.
        assertEquals(left[3], right[3])
        assertEquals((32 + 3).toByte(), left[3])
    }

    @Test
    fun x10_release_matchesExactly32Plus3() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Release(MouseReportButton.MIDDLE), column = 1, row = 1,
        )!!
        assertEquals((32 + 3).toByte(), bytes[3])
    }

    @Test
    fun x10_wheel_upIs64_downIs65() {
        val up = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Wheel(up = true), column = 1, row = 1,
        )
        val down = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL, MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Wheel(up = false), column = 1, row = 1,
        )
        assertEquals((32 + 64).toByte(), up!![3])
        assertEquals((32 + 65).toByte(), down!![3])
    }

    @Test
    fun x10_clampsCoordinatesTo223() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 9999,
            row = 9999,
        )!!
        // 32 + 223 = 255, the max byte value the legacy protocol can carry.
        assertEquals((32 + 223).toByte(), bytes[4])
        assertEquals((32 + 223).toByte(), bytes[5])
    }

    @Test
    fun x10_doesNotClampBelow223() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 100,
            row = 200,
        )!!
        assertEquals((32 + 100).toByte(), bytes[4])
        assertEquals((32 + 200).toByte(), bytes[5])
    }

    @Test
    fun x10_drag_addsMotionBit32() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_BUTTON_MOTION,
            MouseFormat.MOUSE_FORMAT_XTERM,
            MouseReportEvent.Drag(MouseReportButton.MIDDLE),
            column = 1,
            row = 1,
        )!!
        // 1 (MIDDLE) + 32 (motion) = 33
        assertEquals((32 + 33).toByte(), bytes[3])
    }

    // ── Coordinate floor ──────────────────────────────────────────────────────

    @Test
    fun encode_coercesNonPositiveCoordinatesToOne() {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Press(MouseReportButton.LEFT),
            column = 0,
            row = -5,
        )
        assertEquals("[<0;1;1M", sgrString(bytes!!))
    }

    // ── Button base codes (gauche=0, milieu=1, droite=2) ──────────────────────

    @Test
    fun buttonBaseCodes_matchXtermConvention() {
        assertEquals(0, encodePressCb(MouseReportButton.LEFT))
        assertEquals(1, encodePressCb(MouseReportButton.MIDDLE))
        assertEquals(2, encodePressCb(MouseReportButton.RIGHT))
    }

    /** Decodes the Cb field out of an SGR press report for [button] at (1,1). */
    private fun encodePressCb(button: MouseReportButton): Int {
        val bytes = MouseReportEncoder.encode(
            MouseMode.MOUSE_REPORTING_NORMAL,
            MouseFormat.MOUSE_FORMAT_SGR,
            MouseReportEvent.Press(button),
            column = 1,
            row = 1,
        )!!
        val s = sgrString(bytes) // "[<Cb;1;1M"
        return s.substringAfter("<").substringBefore(";").toInt()
    }
}
