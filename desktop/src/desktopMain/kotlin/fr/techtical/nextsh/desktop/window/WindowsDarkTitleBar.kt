// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import fr.techtical.nextsh.shared.util.Logger
import java.awt.Window
import java.util.Locale
import javax.swing.SwingUtilities

private const val TAG = "WindowsDarkTitleBar"

/**
 * Minimal JNA binding for `DwmSetWindowAttribute` (`dwmapi.dll`), used
 * to opt into the OS-rendered dark title bar plus, on Windows 11 22H2+,
 * a custom caption / border color matching the NextSH NearBlack chrome.
 *
 * Available since Windows 10 build 18985 (DARK_MODE only). The
 * CAPTION_COLOR / BORDER_COLOR attributes require Windows 11 22H2+;
 * older versions silently ignore those DwmSetWindowAttribute calls,
 * which is the desired fallback (just stay on default dark titlebar).
 */
internal interface Dwmapi : Library {
    /**
     * @return `S_OK` (0) on success, an `HRESULT` error code otherwise.
     *   We treat any non-zero as a soft failure and log without throwing.
     */
    fun DwmSetWindowAttribute(
        hwnd: Pointer,
        dwAttribute: Int,
        pvAttribute: IntByReference,
        cbAttribute: Int,
    ): Int

    companion object {
        val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }

        /**
         * Cloaking: the window stays shown as far as the application is
         * concerned (`isVisible`, `isShowing`, placement) but DWM composes
         * nothing of it on screen. BOOL, 4 bytes. See [WindowsFirstFrameReveal].
         */
        const val DWMWA_CLOAK = 13

        /** Dark mode title bar: Windows 10 build 18985+ / Windows 11. */
        const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

        /** Border color: Windows 11 22H2+. COLORREF (0x00BBGGRR). */
        const val DWMWA_BORDER_COLOR = 34

        /** Title bar fill color: Windows 11 22H2+. COLORREF (0x00BBGGRR). */
        const val DWMWA_CAPTION_COLOR = 35
    }
}

/**
 * Minimal JNA binding for `SetWindowThemeAttribute` (`uxtheme.dll`), used
 * to stop the OS from drawing the window icon in the title bar. Same
 * pattern as [Dwmapi].
 */
internal interface Uxtheme : Library {
    /**
     * [pvAttribute] models the `WTA_OPTIONS { DWORD dwFlags; DWORD dwMask; }`
     * struct as two ints (8 bytes): JNA passes a primitive array as a pointer
     * to its contents.
     *
     * @return `S_OK` (0) on success, an `HRESULT` error code otherwise.
     *   Same soft-failure handling as [Dwmapi.DwmSetWindowAttribute].
     */
    fun SetWindowThemeAttribute(
        hwnd: Pointer,
        eAttribute: Int,
        pvAttribute: IntArray,
        cbAttribute: Int,
    ): Int

    companion object {
        val INSTANCE: Uxtheme by lazy { Native.load("uxtheme", Uxtheme::class.java) }

        /** `WINDOWTHEMEATTRIBUTETYPE` selecting the non-client area options. */
        const val WTA_NONCLIENT = 1

        /**
         * Do not draw the window icon in the title bar (Uxtheme.h). Not 0x1:
         * that bit is `WTNCA_NODRAWCAPTION`, which hides the title text and
         * keeps the icon, the exact opposite of what we want.
         */
        const val WTNCA_NODRAWICON = 0x2
    }
}

/**
 * Applies the NextSH dark-mode + NearBlack-painted title bar to a top-level
 * AWT [Window]. Safe on every platform: no-op if not Windows; safe to call
 * before the window is shown: deferred via `SwingUtilities.invokeLater`
 * until the AWT peer (HWND) exists.
 *
 * ## Why this exists
 * NextSH uses `Window(undecorated = false)` so the OS handles drag, edge
 * resize, snap layouts, Win+Arrow shortcuts, accessibility, etc.: every
 * single one of those was previously re-implemented with AWT MouseListeners
 * (`TitleBarDragController`, `WindowEdgeResize`) to bypass Compose
 * Multiplatform 1.6.11 / 1.7.x bugs around undecorated windows on Windows
 * + NVIDIA + DWM. Returning to the OS-decorated path means losing all those
 * bug-prone workarounds, but the default OS title bar is light-themed even
 * inside a dark Compose app: that's the visual seam this helper closes.
 *
 * On Windows 10 1809–22H1 only `DWMWA_USE_IMMERSIVE_DARK_MODE` (attr 20)
 * applies and we get the standard dark title bar. On Windows 11 22H2+ we
 * additionally paint the caption + border in [NextShTitleBarColor.NearBlack]
 * (0x0F0F0F) so the OS title bar is visually continuous with the Compose
 * brand strip rendered directly underneath. The user perceives a single
 * unified bar with min/max/close handled natively by the OS.
 *
 * ## Title bar icon
 * The helper also hides the window icon in the title bar. The window keeps
 * its `icon` (the 512 px NextSH logo PNG) because the taskbar button and
 * Alt+Tab need it, but AWT downscales that same image to 16 px for the title
 * bar, where it renders pixelated; the "NextSH" title alone is enough there.
 * Only the icon drawing is suppressed (`WTNCA_NODRAWICON`); `WTNCA_NOSYSMENU`
 * is deliberately left off so the system menu functionality is not touched.
 */
object WindowsDarkTitleBar {

    /** `true` only on Windows: all operations are no-ops on other platforms. */
    val isPlatformSupported: Boolean =
        System.getProperty("os.name", "").lowercase(Locale.US).contains("windows")

    /**
     * Apply dark mode + NextSH NearBlack to [window] and hide its title bar
     * icon (taskbar / Alt+Tab icon untouched). Idempotent: calling twice is
     * harmless.
     */
    fun apply(window: Window) {
        if (!isPlatformSupported) return

        val hwnd = getHwnd(window)
        if (hwnd == null) {
            // AWT peer not realized yet (window not shown). Defer to the
            // next EDT pass: by then the HWND is guaranteed to exist.
            SwingUtilities.invokeLater {
                val deferred = getHwnd(window)
                if (deferred != null) {
                    applyToHwnd(deferred)
                } else {
                    Logger.w(TAG, "HWND still null after invokeLater")
                }
            }
            return
        }
        applyToHwnd(hwnd)
    }

    private fun applyToHwnd(hwnd: Pointer) {
        // 1. Force dark mode title bar (Win 10 18985+ / Win 11)
        runCatching {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                Dwmapi.DWMWA_USE_IMMERSIVE_DARK_MODE,
                IntByReference(1),
                4,
            )
        }.onFailure { e ->
            Logger.w(TAG, "DWMWA_USE_IMMERSIVE_DARK_MODE failed: ${e.message}")
        }

        // 2. Win 11 22H2+ only: paint caption + border NearBlack to match
        //    the Compose brand strip beneath. Older Windows ignores these
        //    attributes silently (fall back to default dark titlebar).
        //    COLORREF is 0x00BBGGRR: NearBlack RGB(0x0F, 0x0F, 0x0F) →
        //    0x000F0F0F (R=G=B so byte order is irrelevant here).
        val nearBlackColorRef = 0x000F0F0F
        runCatching {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                Dwmapi.DWMWA_CAPTION_COLOR,
                IntByReference(nearBlackColorRef),
                4,
            )
        }
        runCatching {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                Dwmapi.DWMWA_BORDER_COLOR,
                IntByReference(nearBlackColorRef),
                4,
            )
        }

        // 3. Hide the title bar icon (see "Title bar icon" above).
        //    WTA_OPTIONS = { dwFlags, dwMask }: the same bit in both turns
        //    the flag on. SWP_FRAMECHANGED makes the already shown window
        //    repaint its non-client area right away.
        runCatching {
            val hr = Uxtheme.INSTANCE.SetWindowThemeAttribute(
                hwnd,
                Uxtheme.WTA_NONCLIENT,
                intArrayOf(Uxtheme.WTNCA_NODRAWICON, Uxtheme.WTNCA_NODRAWICON),
                8,
            )
            if (hr != 0) {
                Logger.w(TAG, "WTNCA_NODRAWICON rejected: HRESULT 0x${Integer.toHexString(hr)}")
                return@runCatching
            }
            User32.INSTANCE.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                User32.SWP_NOMOVE or User32.SWP_NOSIZE or User32.SWP_NOZORDER or
                    User32.SWP_NOACTIVATE or User32.SWP_FRAMECHANGED,
            )
        }.onFailure { e ->
            Logger.w(TAG, "Hiding the title bar icon failed: ${e.message}")
        }
    }

    private fun getHwnd(window: Window): Pointer? {
        return runCatching {
            val ptr = Native.getWindowPointer(window)
            if (ptr == null || ptr == Pointer.NULL) null else ptr
        }.getOrNull()
    }
}
