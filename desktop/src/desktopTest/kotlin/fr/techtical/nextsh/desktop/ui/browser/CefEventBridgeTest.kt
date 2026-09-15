// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import java.awt.Cursor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JCEF hands [cefCursorToAwt] a value that is already an AWT Cursor ID
 * (see the Kdoc on [cefCursorToAwt] for the native conversion trail).
 * These tests pin that identity mapping and verify the out-of-range fallback.
 */
class CefEventBridgeTest {

    @Test
    fun `AWT DEFAULT id maps to DEFAULT cursor`() {
        assertEquals(Cursor.DEFAULT_CURSOR, cefCursorToAwt(Cursor.DEFAULT_CURSOR).type)
    }

    @Test
    fun `AWT HAND id maps to HAND cursor`() {
        // 12 = Cursor.HAND_CURSOR. Clickable elements send this when rendered to AWT.
        assertEquals(Cursor.HAND_CURSOR, cefCursorToAwt(Cursor.HAND_CURSOR).type)
    }

    @Test
    fun `AWT TEXT id maps to TEXT cursor`() {
        assertEquals(Cursor.TEXT_CURSOR, cefCursorToAwt(Cursor.TEXT_CURSOR).type)
    }

    @Test
    fun `AWT W_RESIZE id maps to W_RESIZE cursor`() {
        // 10 = Cursor.W_RESIZE_CURSOR. Visually a horizontal double arrow,
        // previously mis-mapped to S_RESIZE by the old CEF-native table.
        assertEquals(Cursor.W_RESIZE_CURSOR, cefCursorToAwt(Cursor.W_RESIZE_CURSOR).type)
    }

    @Test
    fun `AWT MOVE id maps to MOVE cursor`() {
        // 13 = Cursor.MOVE_CURSOR, the last predefined AWT cursor.
        assertEquals(Cursor.MOVE_CURSOR, cefCursorToAwt(Cursor.MOVE_CURSOR).type)
    }

    @Test
    fun `out of range value falls back to DEFAULT cursor`() {
        assertEquals(Cursor.DEFAULT_CURSOR, cefCursorToAwt(9999).type)
    }

    @Test
    fun `negative value falls back to DEFAULT cursor`() {
        assertEquals(Cursor.DEFAULT_CURSOR, cefCursorToAwt(-1).type)
    }
}
