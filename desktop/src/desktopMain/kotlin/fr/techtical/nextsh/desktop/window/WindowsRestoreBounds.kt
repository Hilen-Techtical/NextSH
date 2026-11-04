// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Window
import java.util.Locale
import kotlin.math.abs

private const val TAG = "WindowsRestoreBounds"

/** Delay between two polls of the native window state. */
private const val POLL_INTERVAL_MS = 50L

/**
 * How long to wait for the window to appear on screen. Generous on purpose:
 * on a cold start the main content composes for a second or more before AWT
 * shows the window, and the polling itself costs next to nothing.
 */
private const val SHOW_TIMEOUT_MS = 30_000L

/** How long, once the window is on screen, Windows gets to maximize it. */
private const val MAXIMIZE_TIMEOUT_MS = 3_000L

/** Plausible logical to physical scale (DPI 50 % to 400 %). Anything else is not trusted. */
private val PLAUSIBLE_SCALE = 0.5..4.0

/** The horizontal and vertical scales of one screen are the same; a larger gap means a wrong reference. */
private const val MAX_SCALE_AXIS_GAP = 0.05

/**
 * Sets the rectangle a Windows window RESTORES to, without touching the fact
 * that it is currently maximized.
 *
 * ## Why this exists
 * NextSH always starts maximized. If the window is created at its floating
 * size and then maximized, Windows shows it at the floating size first and
 * enlarges it a few dozen milliseconds later, before Compose has drawn
 * anything: the user sees a blank frame ("white flash"). So the window is
 * created at the usable area of its screen instead (it opens already at its
 * final size), which leaves the OS with the WRONG rectangle to restore to.
 * Once the window is on screen and maximized, [applyWhenMaximized] rewrites
 * only `rcNormalPosition` of its `WINDOWPLACEMENT` to the user's floating
 * geometry: invisible while maximized, effective on the next un-maximize.
 *
 * Safe on every platform: no-op if not Windows. Never throws: any failure is
 * logged (no sensitive data) and the window simply keeps the restore
 * rectangle it was created with.
 */
object WindowsRestoreBounds {

    /** `true` only on Windows: all operations are no-ops on other platforms. */
    val isPlatformSupported: Boolean =
        System.getProperty("os.name", "").lowercase(Locale.US).contains("windows")

    /**
     * Waits until [window] is showing AND maximized by Windows, then applies
     * ONCE the restore rectangle [target] returns, and finishes. Call it from
     * the EDT (a Compose `LaunchedEffect` is fine): the polling suspends with
     * [delay] and cancels with its caller.
     *
     * Do NOT assume the window is shown when a `LaunchedEffect` starts: the
     * effect runs while the content composes, before AWT shows the window.
     * The state is therefore polled every [POLL_INTERVAL_MS]:
     *  1. up to [SHOW_TIMEOUT_MS] for `window.isShowing`;
     *  2. then up to [MAXIMIZE_TIMEOUT_MS] for `WINDOWPLACEMENT.showCmd` to be
     *     `SW_SHOWMAXIMIZED` (Windows maximizes the window a little after
     *     showing it).
     * If a deadline expires, nothing is applied. In particular a window the
     * user restored before Windows maximized it is left alone.
     *
     * Only `rcNormalPosition` is rewritten. `flags`, `showCmd` (still
     * maximized: no visible change), `ptMinPosition` and `ptMaxPosition` are
     * kept as Windows reported them.
     *
     * @param refLogical the logical rectangle the window was created with
     *   (the usable area of its screen). The `rcNormalPosition` read from the
     *   OS describes exactly that rectangle in physical pixels, which gives
     *   the logical to physical mapping without guessing the DPI: see
     *   [mapToPhysical].
     * @param target the logical restore rectangle to apply (the floating
     *   geometry, on the same screen as [refLogical]). Evaluated at the
     *   moment of application, so it reflects the latest value.
     */
    suspend fun applyWhenMaximized(window: Window, refLogical: Rectangle, target: () -> Rectangle) {
        if (!isPlatformSupported) return
        try {
            val startMs = System.currentTimeMillis()
            var shownAtMs = -1L
            while (true) {
                val nowMs = System.currentTimeMillis()
                if (shownAtMs < 0 && window.isShowing) shownAtMs = nowMs
                val expired = if (shownAtMs < 0) {
                    nowMs - startMs > SHOW_TIMEOUT_MS
                } else {
                    nowMs - shownAtMs > MAXIMIZE_TIMEOUT_MS
                }
                if (expired) {
                    Logger.w(TAG, "window not shown and maximized in time, restore bounds left as is")
                    return
                }
                if (shownAtMs >= 0 && applyIfMaximized(window, refLogical, target)) return
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // JNA load failure, no native peer, AWT error: never let it reach the UI.
            Logger.w(TAG, "restore bounds not applied: ${e.message}")
        }
    }

    /**
     * One attempt. Returns `false` when the window is not maximized yet (poll
     * again), `true` once the attempt is over, applied or deliberately given
     * up (unusable reference, rejected by the OS): retrying would not help.
     */
    private fun applyIfMaximized(window: Window, refLogical: Rectangle, target: () -> Rectangle): Boolean {
        val pointer = Native.getWindowPointer(window)
        if (pointer == null || pointer == Pointer.NULL) return false
        val hwnd = HWND(pointer)

        val placement = WinUser.WINDOWPLACEMENT()
        placement.length = placement.size()
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue()) return false
        if (placement.showCmd != WinUser.SW_SHOWMAXIMIZED) return false

        val current = placement.rcNormalPosition
        val refPhysical = Rectangle(current.left, current.top, current.right - current.left, current.bottom - current.top)
        val physical = mapToPhysical(target(), refLogical, refPhysical)
        if (physical == null) {
            Logger.w(TAG, "restore bounds not applied: unusable reference (logical=$refLogical physical=$refPhysical)")
            return true
        }
        if (physical == refPhysical) return true

        // Only the normal (restore) rectangle changes; everything else in the
        // structure is written back exactly as read.
        current.left = physical.x
        current.top = physical.y
        current.right = physical.x + physical.width
        current.bottom = physical.y + physical.height
        if (!User32.INSTANCE.SetWindowPlacement(hwnd, placement).booleanValue()) {
            Logger.w(TAG, "SetWindowPlacement refused the restore bounds")
        }
        return true
    }
}

/**
 * Maps [target], a rectangle in logical AWT units, to physical pixels, from
 * one reference rectangle known in both spaces: [refLogical] (logical) and
 * [refPhysical] (the same area in physical pixels). No DPI value is needed:
 * the scale is `refPhysical.size / refLogical.size`, and the offset of [target]
 * from the reference origin is scaled and added to the physical origin, so a
 * pure translation between the two spaces (negative coordinates, a taskbar
 * shifting the workspace origin) is handled too.
 *
 * Each of `x`, `y`, `width` and `height` is rounded independently to the
 * nearest integer, halves going up ([Math.round]). E.g. at 125 %, a logical
 * origin of 154 gives 192.5 and so 193.
 *
 * Returns null, meaning "apply nothing", when the mapping cannot be trusted:
 *  - a reference rectangle is empty (zero or negative width or height), or
 *    [target] is;
 *  - a scale falls outside [0.5, 4.0] (both bounds accepted);
 *  - the horizontal and vertical scales differ by more than 0.05: DPI scaling
 *    is uniform, so a gap means [refPhysical] is not the image of
 *    [refLogical] (e.g. the window was not at the expected bounds when read).
 *
 * Pure: no AWT or native access.
 */
internal fun mapToPhysical(target: Rectangle, refLogical: Rectangle, refPhysical: Rectangle): Rectangle? {
    if (refLogical.width <= 0 || refLogical.height <= 0) return null
    if (refPhysical.width <= 0 || refPhysical.height <= 0) return null
    if (target.width <= 0 || target.height <= 0) return null

    val scaleX = refPhysical.width.toDouble() / refLogical.width
    val scaleY = refPhysical.height.toDouble() / refLogical.height
    if (scaleX !in PLAUSIBLE_SCALE || scaleY !in PLAUSIBLE_SCALE) return null
    if (abs(scaleX - scaleY) > MAX_SCALE_AXIS_GAP) return null

    return Rectangle(
        refPhysical.x + Math.round((target.x - refLogical.x) * scaleX).toInt(),
        refPhysical.y + Math.round((target.y - refLogical.y) * scaleY).toInt(),
        Math.round(target.width * scaleX).toInt(),
        Math.round(target.height * scaleY).toInt(),
    )
}
