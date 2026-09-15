// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.Dp
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import fr.techtical.nextsh.shared.util.Logger
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.SwingUtilities

private const val TAG = "PopupWindowStyle"

/**
 * Hard-clips the AWT window itself to a rounded rectangle matching the
 * Compose card clip inside it.
 *
 * ## Why this exists
 * NextSH forces `skiko.renderApi = OPENGL` (see Main.kt) to work around the
 * DirectX DXGI_SCALING_STRETCH white-flash bug, but skiko's OpenGL redrawer
 * on Windows does NOT support per-pixel window transparency. Every popup
 * window in this module is created with `transparent = true`, yet the area
 * outside the Compose `clip(RoundedCornerShape(...))` renders as OPAQUE
 * BLACK: the visible symptom is a black tip at each rounded corner.
 * `Window.setShape` is enforced by AWT regardless of the render API, so the
 * corner tips are simply no longer part of the window.
 *
 * `transparent = true` stays on the windows: if the render API ever moves
 * back to DirectX, real per-pixel alpha resumes and the shape becomes a
 * harmless double clip.
 *
 * Radius unit: AWT user-space coordinates are DIP-scaled on Windows HiDPI,
 * same as Compose dp, so the dp value is used directly. The shape tracks
 * window resizes (the theme picker resizes itself to its content).
 */
@Composable
fun PopupRoundedCorners(window: Window, radius: Dp) {
    DisposableEffect(window, radius) {
        val arcDiameter = radius.value * 2f
        fun applyShape() {
            runCatching {
                window.shape = RoundRectangle2D.Float(
                    0f,
                    0f,
                    window.width.toFloat(),
                    window.height.toFloat(),
                    arcDiameter,
                    arcDiameter,
                )
            }.onFailure { e -> Logger.w(TAG, "window shape failed: ${e.message}") }
        }

        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyShape()
        }
        applyShape()
        window.addComponentListener(resizeListener)
        onDispose {
            window.removeComponentListener(resizeListener)
            runCatching { window.shape = null }
        }
    }
}

/**
 * Minimal JNA binding for the `user32.dll` extended-style calls used by
 * [WindowsToolWindow]. Same pattern as [Dwmapi] in WindowsDarkTitleBar.kt.
 */
private interface User32 : Library {
    fun GetWindowLongW(hwnd: Pointer, nIndex: Int): Int
    fun SetWindowLongW(hwnd: Pointer, nIndex: Int, dwNewLong: Int): Int
    fun SetWindowPos(
        hwnd: Pointer,
        hwndInsertAfter: Pointer?,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        uFlags: Int,
    ): Boolean

    companion object {
        val INSTANCE: User32 by lazy { Native.load("user32", User32::class.java) }

        const val GWL_EXSTYLE = -20
        const val WS_EX_TOOLWINDOW = 0x00000080
        const val WS_EX_APPWINDOW = 0x00040000
        const val SWP_NOSIZE = 0x0001
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_FRAMECHANGED = 0x0020
    }
}

/**
 * Removes the Windows taskbar button of a top-level popup window by adding
 * `WS_EX_TOOLWINDOW` (and stripping `WS_EX_APPWINDOW`) to its extended style.
 *
 * The Cmd+K palette is a Compose [androidx.compose.ui.window.Window], a
 * JFrame without an owner, so Windows gives it a taskbar button. AWT's own
 * `Window.setType(UTILITY)` cannot be used: Compose has already made the
 * frame displayable by the time application code runs, and `setType` throws
 * on a displayable window. Flipping the extended style through user32 works
 * on a live HWND; `SWP_FRAMECHANGED` asks the shell to re-read the style.
 *
 * No-op on non-Windows platforms (owned dialogs are taskbar-free everywhere
 * else in this module; only the palette needs this).
 */
object WindowsToolWindow {

    /** `true` only on Windows: all operations are no-ops on other platforms. */
    val isPlatformSupported: Boolean =
        System.getProperty("os.name", "").lowercase(Locale.US).contains("windows")

    /** Idempotent: calling twice is harmless. Defers until the HWND exists. */
    fun apply(window: Window) {
        if (!isPlatformSupported) return
        SwingUtilities.invokeLater {
            val hwnd = runCatching {
                val ptr = Native.getWindowPointer(window)
                if (ptr == null || ptr == Pointer.NULL) null else ptr
            }.getOrNull()
            if (hwnd == null) {
                Logger.w(TAG, "HWND null: toolwindow style not applied")
                return@invokeLater
            }
            runCatching {
                val u32 = User32.INSTANCE
                val exStyle = u32.GetWindowLongW(hwnd, User32.GWL_EXSTYLE)
                val newStyle =
                    (exStyle or User32.WS_EX_TOOLWINDOW) and User32.WS_EX_APPWINDOW.inv()
                if (newStyle != exStyle) {
                    u32.SetWindowLongW(hwnd, User32.GWL_EXSTYLE, newStyle)
                    u32.SetWindowPos(
                        hwnd,
                        null,
                        0,
                        0,
                        0,
                        0,
                        User32.SWP_NOMOVE or User32.SWP_NOSIZE or User32.SWP_NOZORDER or
                            User32.SWP_NOACTIVATE or User32.SWP_FRAMECHANGED,
                    )
                }
            }.onFailure { e -> Logger.w(TAG, "toolwindow style failed: ${e.message}") }
        }
    }
}
