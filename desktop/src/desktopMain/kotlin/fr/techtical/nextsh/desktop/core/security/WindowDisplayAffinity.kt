// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.security

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Minimal JNA binding for [SetWindowDisplayAffinity] (user32.dll).
 *
 * Available on Windows 7+. [WDA_EXCLUDEFROMCAPTURE] requires Windows 10 2004+ (build 19041).
 */
internal interface User32DisplayAffinity : Library {
    /**
     * Sets the display affinity for a window: controls whether the window
     * contents are included in screen captures, recordings and sharing.
     *
     * @param hwnd    Handle to the window.
     * @param affinity One of [WDA_NONE], [WDA_MONITOR] or [WDA_EXCLUDEFROMCAPTURE].
     * @return true on success.
     */
    fun SetWindowDisplayAffinity(hwnd: Pointer, affinity: Int): Boolean

    companion object {
        val INSTANCE: User32DisplayAffinity by lazy {
            Native.load("user32", User32DisplayAffinity::class.java)
        }

        /** No protection: window is visible in captures (default). */
        const val WDA_NONE = 0x00000000

        /**
         * Window is only visible on the monitor it is displayed on.
         * Deprecated in favour of [WDA_EXCLUDEFROMCAPTURE] but works on Win7+.
         */
        const val WDA_MONITOR = 0x00000001

        /**
         * Window is excluded from all screen captures, recordings and screen sharing.
         * Requires Windows 10 2004+ (build 19041).
         */
        const val WDA_EXCLUDEFROMCAPTURE = 0x00000011
    }
}
