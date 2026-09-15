// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import java.awt.Cursor

/**
 * Maps the `cursorType` value JCEF hands to [org.cef.handler.CefRenderHandler.onCursorChange]
 * to an AWT [Cursor].
 *
 * Important: the int we receive is **already an AWT Cursor ID**, JCEF's native
 * glue converts the CEF `cef_cursor_type_t` enum to Java AWT's cursor constants
 * before invoking the Java side (observed in `CefBrowserOsr$9.run()`, which does
 * `new Cursor(cursorType)` directly). The previous implementation re-mapped with
 * the CEF native enum values (CT_POINTER=0, CT_CROSS=1, CT_HAND=2, …) which
 * shifted every cursor by several slots: CEF was asking for HAND (AWT id 12)
 * but we translated it as the `else` fallback (DEFAULT), and Chromium's W_RESIZE
 * (AWT id 10) was shown as S_RESIZE.
 *
 * AWT defines 14 predefined cursors numbered 0–13 ([Cursor.DEFAULT_CURSOR] …
 * [Cursor.MOVE_CURSOR]). Values outside that range fall back to the default
 * cursor rather than throwing, so any future Chromium cursor type that JCEF
 * forwards without a mapping degrades gracefully.
 */
fun cefCursorToAwt(cursorType: Int): Cursor =
    if (cursorType in Cursor.DEFAULT_CURSOR..Cursor.MOVE_CURSOR) {
        Cursor.getPredefinedCursor(cursorType)
    } else {
        Cursor.getDefaultCursor()
    }
