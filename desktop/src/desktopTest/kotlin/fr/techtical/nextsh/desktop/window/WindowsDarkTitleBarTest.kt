// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Win32 constants [WindowsDarkTitleBar] passes to
 * `SetWindowThemeAttribute`. The native call itself needs a live Windows
 * window and is not exercised here; a wrong bit, on the other hand, fails
 * silently at runtime (0x1 hides the title text instead of the icon), so the
 * values are checked against Uxtheme.h.
 */
class WindowsDarkTitleBarTest {

    @Test
    fun `WTA_NONCLIENT constant equals 1`() {
        assertEquals(1, Uxtheme.WTA_NONCLIENT)
    }

    @Test
    fun `WTNCA_NODRAWICON constant equals 0x2`() {
        assertEquals(0x00000002, Uxtheme.WTNCA_NODRAWICON)
    }
}
