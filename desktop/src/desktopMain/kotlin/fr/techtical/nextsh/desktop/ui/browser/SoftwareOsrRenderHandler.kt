// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import java.awt.Point
import java.awt.Rectangle
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser
import org.cef.callback.CefDragData
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import org.cef.misc.CefRange

/**
 * CEF render handler that bridges Chromium's software OSR callbacks to [SoftwareOsrPanel].
 *
 * All methods in this class are called on CEF's IO thread. Any Swing mutation (cursor,
 * repaint) is dispatched to the EDT via [SwingUtilities.invokeLater].
 *
 * This handler intentionally emits no log output in the hot paths (onPaint, onCursorChange,
 * getViewRect) to avoid both log noise and the risk of leaking coordinates or pixel metadata.
 */
class SoftwareOsrRenderHandler(
    private val panel: SoftwareOsrPanel,
) : CefRenderHandler {

    /**
     * Returns the panel's current pixel dimensions as the viewport rectangle CEF should
     * render into. A non-zero default of 800×600 is returned while the panel is still
     * being laid out, so Chromium never receives a zero-sized viewport and rejects the
     * first paint.
     */
    override fun getViewRect(browser: CefBrowser): Rectangle {
        val w = if (panel.width > 0) panel.width else 800
        val h = if (panel.height > 0) panel.height else 600
        return Rectangle(0, 0, w, h)
    }

    /**
     * Must fill [screenInfo] AND return true for Chromium to honour the device
     * scale factor. Returning false causes CEF to ignore [getDeviceScaleFactor]
     * and render the buffer at [getViewRect] size regardless of HiDPI: so every
     * frame ends up upscaled by Skiko, which is the blurriness we were chasing.
     *
     * With a filled [screenInfo], Chromium renders the buffer at
     *   viewRect.width × deviceScaleFactor, viewRect.height × deviceScaleFactor
     * which matches the physical pixel surface of the panel exactly, allowing
     * [SoftwareOsrPanel.paintComponent] to blit it 1:1 with no resampling.
     */
    override fun getScreenInfo(browser: CefBrowser, screenInfo: CefScreenInfo): Boolean {
        val scale = getDeviceScaleFactor(browser)
        val physW = (panel.width.coerceAtLeast(1) * scale).toInt()
        val physH = (panel.height.coerceAtLeast(1) * scale).toInt()
        val bounds = Rectangle(0, 0, physW, physH)
        screenInfo.Set(scale, 32, 8, false, bounds, bounds)
        return true
    }

    /**
     * Converts a panel-relative point to screen coordinates.
     *
     * [panel.locationOnScreen] can throw [java.awt.IllegalComponentStateException] if the
     * panel is not yet showing (first layout pass). The [runCatching] fallback returns
     * the origin so CEF gets a valid non-null point rather than an exception propagating
     * into native code.
     */
    override fun getScreenPoint(browser: CefBrowser, viewPoint: Point): Point {
        val loc = runCatching { panel.locationOnScreen }.getOrNull() ?: Point(0, 0)
        return Point(loc.x + viewPoint.x, loc.y + viewPoint.y)
    }

    /**
     * The scale factor is queried on every paint call; delegating to the graphics
     * configuration rather than caching ensures correctness after the window moves
     * between monitors with different DPI settings.
     */
    override fun getDeviceScaleFactor(browser: CefBrowser): Double =
        panel.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    override fun onPopupShow(browser: CefBrowser, show: Boolean) {
        panel.setPopupVisible(show)
    }

    override fun onPopupSize(browser: CefBrowser, size: Rectangle) {
        panel.setPopupRect(size)
    }

    /**
     * Dispatches the frame to the correct buffer based on whether [popup] is set.
     *
     * [dirtyRects] is forwarded to [SoftwareOsrPanel.updateMainFrame] so that
     * only the changed region is scheduled for repaint on idle pages.
     */
    override fun onPaint(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRects: Array<Rectangle>,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
    ) {
        if (popup) {
            panel.updatePopupFrame(buffer, width, height)
        } else {
            panel.updateMainFrame(buffer, width, height, dirtyRects)
        }
    }

    /**
     * Applies the CEF cursor to the panel on the EDT.
     *
     * Returns true to indicate we handled the change; returning false would let
     * the platform apply a default OS cursor.
     */
    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean {
        val cursor = cefCursorToAwt(cursorType)
        SwingUtilities.invokeLater { panel.cursor = cursor }
        return true
    }

    /**
     * Declines drag-and-drop initiation.
     *
     * Implementing full DnD requires wiring a [java.awt.dnd.DragSource] and
     * [java.awt.datatransfer.Transferable] which is a substantial surface.
     * Returning false tells Chromium to fall back to text selection without a
     * drag ghost, which is acceptable for admin panel UIs served over local tunnels.
     */
    override fun startDragging(
        browser: CefBrowser,
        dragData: CefDragData,
        mask: Int,
        x: Int,
        y: Int,
    ): Boolean = false

    /** No-op while [startDragging] returns false: CEF will not call this in practice. */
    override fun updateDragCursor(browser: CefBrowser, operation: Int) {}

    /**
     * IME composition range changes are not wired in this first pass.
     * ASCII keyboard input flows through the standard [KeyEvent] path in
     * [SoftwareOsrPanel]. Full CJK / right-to-left IME support can be added
     * later by calling [CefBrowser.ImeSetComposition] from an InputMethod bridge.
     */
    override fun OnImeCompositionRangeChanged(
        browser: CefBrowser,
        range: CefRange,
        bounds: Array<Rectangle>,
    ) {}

    /** Text selection changes are not surfaced to the UI in this pass. */
    override fun OnTextSelectionChanged(
        browser: CefBrowser,
        selectedText: String,
        range: CefRange,
    ) {}
}
