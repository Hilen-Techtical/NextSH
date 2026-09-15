// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser

/**
 * Lightweight Swing panel that serves as the render target for CEF's software OSR path.
 *
 * Chromium delivers BGRA frames via [SoftwareOsrRenderHandler.onPaint]. This panel
 * copies each frame into a [BufferedImage] and paints it with [Graphics.drawImage],
 * bypassing JOGL entirely. This is safe on Windows HDR10 / WCG displays where the
 * JOGL OpenGL profile selection conflicts with the AWT pixel format negotiation.
 *
 * Thread-safety notes:
 * - [mainBuffer] and [popupBuffer] are written under [frameLock] from the CEF IO thread
 *   and read under [frameLock] from the EDT inside [paintComponent]. The lock is a plain
 *   `Any()` (i.e. a monitor), fine for low-contention frame updates.
 * - [browser] is `@Volatile` so that event listeners started before [attachBrowser] returns
 *   can safely observe null without stale-read risks.
 * - [popupRect] and [popupVisible] are `@Volatile` because they are written from the CEF
 *   IO thread and read on the EDT without a common lock.
 */
class SoftwareOsrPanel : JPanel() {

    /** Attached post-construction because [CefBrowserOsrWithHandler] needs `this` as its component. */
    @Volatile private var browser: CefBrowser? = null

    private var mainBuffer: BufferedImage? = null
    private var popupBuffer: BufferedImage? = null

    @Volatile private var popupRect: Rectangle? = null
    @Volatile private var popupVisible: Boolean = false

    /** Guards [mainBuffer] and [popupBuffer] against concurrent access between CEF IO and EDT. */
    private val frameLock = Any()

    /**
     * Reusable int array to copy BGRA pixels into, shared by [updateMainFrame] and
     * [updatePopupFrame]. Guarded by [frameLock]. Grown on demand, never shrunk.
     * Avoids allocating ~8 MB per frame on a 1920×1080 surface at 60 fps.
     */
    private var pixelScratch: IntArray = IntArray(0)

    init {
        background = Color(0x0F, 0x0F, 0x0F) // NearBlack, matches Techtical dark theme
        isOpaque = true
        isFocusable = true
        // Tab must reach Chromium, not cycle Swing focus.
        focusTraversalKeysEnabled = false

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                browser?.sendMouseEvent(e)
            }
            override fun mouseReleased(e: MouseEvent) { browser?.sendMouseEvent(e) }
            override fun mouseEntered(e: MouseEvent) { browser?.sendMouseEvent(e) }
            override fun mouseExited(e: MouseEvent) { browser?.sendMouseEvent(e) }
            override fun mouseClicked(e: MouseEvent) { browser?.sendMouseEvent(e) }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) { browser?.sendMouseEvent(e) }
            override fun mouseDragged(e: MouseEvent) { browser?.sendMouseEvent(e) }
        })

        addMouseWheelListener { e -> browser?.sendMouseWheelEvent(e) }

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { browser?.sendKeyEvent(e) }
            override fun keyReleased(e: KeyEvent) { browser?.sendKeyEvent(e) }
            override fun keyTyped(e: KeyEvent) { browser?.sendKeyEvent(e) }
        })

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { browser?.setFocus(true) }
            override fun focusLost(e: FocusEvent) { browser?.setFocus(false) }
        })

        // Notify Chromium so it re-queries getViewRect and schedules a new frame at the
        // correct resolution. wasResized(w, h) is called with the updated dimensions.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val b = browser ?: return
                val w = if (width > 0) width else 800
                val h = if (height > 0) height else 600
                b.wasResized(w, h)
            }
        })

        // When the panel moves between monitors (e.g. dragging the window to a HiDPI
        // screen), the device scale factor may change. notifyScreenInfoChanged() triggers
        // a new getDeviceScaleFactor() + getScreenInfo() query on the CEF side.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
                browser?.notifyScreenInfoChanged()
            }
        }
    }

    /** Call once after the OSR browser is created with this panel as its component. */
    fun attachBrowser(b: CefBrowser) {
        browser = b
    }

    /**
     * `addNotify` is the canonical AWT hook that fires when the component becomes
     * displayable: i.e. when `graphicsConfiguration` first becomes non-null and
     * therefore when our [SoftwareOsrRenderHandler.getDeviceScaleFactor] can return
     * the real HiDPI ratio instead of the 1.0 fallback. We kick a screenInfo
     * notification here so Chromium re-queries the scale factor right away and
     * produces a buffer sized for the actual physical surface; without this the
     * first frames stay at stale scale factor 1.0 and look blurry on HiDPI
     * displays until the next resize or focus event triggers a re-query.
     */
    override fun addNotify() {
        super.addNotify()
        browser?.notifyScreenInfoChanged()
    }

    /** Call when the browser is being disposed so stale events are dropped cleanly. */
    fun detachBrowser() {
        browser = null
    }

    /**
     * Receives a full-page BGRA frame from the render handler and schedules a repaint.
     *
     * Called on the CEF IO thread. Copying into [mainBuffer] under [frameLock] avoids
     * a torn read in [paintComponent] which runs on the EDT.
     *
     * When [dirtyRects] is non-empty we repaint only the union of changed regions,
     * which matters on pages where only a cursor blink or caret updates each tick.
     */
    fun updateMainFrame(buffer: ByteBuffer, width: Int, height: Int, dirtyRects: Array<Rectangle>?) {
        synchronized(frameLock) {
            val target = requireImageOf(mainBuffer, width, height).also { mainBuffer = it }
            pixelScratch = ensurePixelScratch(pixelScratch, width * height)
            copyBgraBufferIntoArgbImage(buffer, target, width, height, pixelScratch)
        }
        if (dirtyRects.isNullOrEmpty()) {
            SwingUtilities.invokeLater { repaint() }
        } else {
            val union = dirtyRects.reduce { a, b -> a.union(b) }
            SwingUtilities.invokeLater { repaint(union) }
        }
    }

    /**
     * Receives a popup layer frame (dropdown menus, date-pickers, …).
     *
     * The popup is composited on top of the main frame in [paintComponent] at the
     * position supplied by [setPopupRect].
     */
    fun updatePopupFrame(buffer: ByteBuffer, width: Int, height: Int) {
        synchronized(frameLock) {
            val target = requireImageOf(popupBuffer, width, height).also { popupBuffer = it }
            pixelScratch = ensurePixelScratch(pixelScratch, width * height)
            copyBgraBufferIntoArgbImage(buffer, target, width, height, pixelScratch)
        }
        SwingUtilities.invokeLater {
            val r = popupRect ?: return@invokeLater
            repaint(r)
        }
    }

    fun setPopupRect(rect: Rectangle?) {
        popupRect = rect
    }

    fun setPopupVisible(visible: Boolean) {
        popupVisible = visible
        if (!visible) {
            SwingUtilities.invokeLater { repaint() }
        }
    }

    override fun paintComponent(g: Graphics) {
        // super fills the panel with the NearBlack background before we draw frames.
        super.paintComponent(g)
        val g2 = g as? Graphics2D ?: return

        synchronized(frameLock) {
            val main = mainBuffer ?: return
            val popup = if (popupVisible) popupBuffer else null
            val rect = if (popupVisible) popupRect else null

            val original = g2.transform
            val scaleX = original.scaleX
            val scaleY = original.scaleY

            if (scaleX <= 0.0 || scaleY <= 0.0) {
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g2.drawImage(main, 0, 0, width, height, null)
                if (popup != null && rect != null) {
                    g2.drawImage(popup, rect.x, rect.y, rect.width, rect.height, null)
                }
                return
            }

            // Cancel the HiDPI AWT transform so our coordinates are physical pixels.
            // With Chromium now rendering at (width * scaleX) × (height * scaleY)
            // thanks to the CefScreenInfo fix, drawing the buffer to those exact
            // physical dimensions is a 1:1 blit with zero resampling.
            g2.scale(1.0 / scaleX, 1.0 / scaleY)

            val physicalWidth = (width * scaleX).toInt()
            val physicalHeight = (height * scaleY).toInt()

            // NEAREST is appropriate here: the buffer is expected to match
            // physicalWidth × physicalHeight exactly (native blit). On a
            // transient mismatch during resize, NEAREST keeps the frame honest
            // rather than blurring it for a few frames.
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )

            g2.drawImage(main, 0, 0, physicalWidth, physicalHeight, null)
            if (popup != null && rect != null) {
                val px = (rect.x * scaleX).toInt()
                val py = (rect.y * scaleY).toInt()
                val pw = (rect.width * scaleX).toInt()
                val ph = (rect.height * scaleY).toInt()
                g2.drawImage(popup, px, py, pw, ph, null)
            }

            g2.transform = original
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(800, 600)

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns [existing] unchanged if its dimensions already match, otherwise
     * allocates a new [BufferedImage]. Reuse avoids per-frame GC churn on stable
     * page layouts where width and height remain constant.
     */
    private fun requireImageOf(existing: BufferedImage?, width: Int, height: Int): BufferedImage =
        if (existing != null && existing.width == width && existing.height == height) {
            existing
        } else {
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        }

    companion object {

        /**
         * Returns [existing] unchanged if it already has enough capacity, otherwise
         * allocates a new [IntArray]. Shared by main + popup to avoid 4 MB+ allocs
         * per frame at 60 fps on a 1920×1080 surface.
         */
        private fun ensurePixelScratch(existing: IntArray, minSize: Int): IntArray =
            if (existing.size >= minSize) existing else IntArray(minSize)

        /**
         * Copies a CEF BGRA pixel buffer into [dst] (TYPE_INT_ARGB).
         *
         * CEF delivers the [src] buffer via JNI `NewDirectByteBuffer`, which
         * produces a `ByteBuffer` in the platform's **native byte order**, not
         * the Java default of `BIG_ENDIAN`. On every Desktop target NextSH
         * supports (Windows/Linux/macOS x86-64 and ARM64-LE) the native order is
         * little-endian. With pixels laid out in memory as B G R A (B at the
         * lowest address), reading four consecutive bytes as an int on a
         * little-endian buffer yields `[B | G<<8 | R<<16 | A<<24] = 0xAARRGGBB`,
         * which is exactly the packed format `TYPE_INT_ARGB` expects. The
         * `IntArray` returned by `asIntBuffer().get()` can therefore feed
         * [BufferedImage.setRGB] directly with no per-pixel shuffle.
         *
         * This path would need a byte-swap on a big-endian JVM or on any future
         * code path that constructs its own `ByteBuffer` in `BIG_ENDIAN` mode.
         */
        private fun copyBgraBufferIntoArgbImage(
            src: ByteBuffer,
            dst: BufferedImage,
            width: Int,
            height: Int,
            scratch: IntArray,
        ) {
            // Duplicate the buffer so we don't mutate the position of the one JCEF
            // retains for its own bookkeeping, then rewind to guarantee we read
            // from the start regardless of the incoming position.
            //
            // LITTLE_ENDIAN is enforced explicitly rather than relied-upon-default.
            // The assumption here is that CEF is built with BGRA_8888 as its OSR
            // color type (default for JCEF 137.x on Windows/Linux/macOS). Under
            // BGRA, CEF writes each pixel as the byte sequence [B, G, R, A] in
            // memory. Reading those four bytes as a 32-bit int in little-endian
            // gives 0xAARRGGBB, which matches BufferedImage.TYPE_INT_ARGB exactly.
            // Some JVM / JCEF builds return the ByteBuffer in default BIG_ENDIAN
            // ordering, which would yield 0xBBGGRRAA and a visibly miscoloured
            // frame (red channel read as blue, etc.). Forcing LE here is
            // belt-and-suspenders: zero cost on native-LE buffers, and it
            // guarantees correct colour on the mis-ordered ones.
            //
            // If CEF is ever rebuilt with RGBA_8888 instead of BGRA_8888, even
            // after this fix the channels will still be swapped (red read as blue)
            // because the byte layout would be [R, G, B, A]. In that case a
            // 32-bit int shuffle is needed on top of the endianness fix. Detect
            // with a solid-red test page (#FF0000): if it renders blue, it's
            // an RGBA build, not an endianness issue.
            val intBuf = src.duplicate().apply {
                order(ByteOrder.LITTLE_ENDIAN)
                rewind()
            }.asIntBuffer()
            val count = width * height
            intBuf.get(scratch, 0, count)
            dst.setRGB(0, 0, width, height, scratch, 0, width)
        }
    }
}
