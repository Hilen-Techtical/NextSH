// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import java.awt.AWTError
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Usable area (screen minus taskbar / dock) of EVERY connected screen, in the
 * AWT screen coordinate space.
 *
 * `GraphicsEnvironment.maximumWindowBounds` only ever describes the PRIMARY
 * screen (see the note on `computeCmdKPosition` in `CmdK.kt`), so it cannot
 * tell which screen a window was last on. This walks `screenDevices` instead
 * and removes the OS chrome with `Toolkit.getScreenInsets`, the same pattern
 * as `usableBoundsOf` in `CmdK.kt`. `GraphicsConfiguration.bounds` lives in
 * the same coordinate space as `ComposeWindow.bounds` and `WindowPosition`,
 * so the result can be compared with a window rectangle and fed back into a
 * `WindowPosition` without any density conversion. Screens left of or above
 * the primary one have negative coordinates.
 *
 * Defensive by design: a headless environment, an AWT failure or a screen
 * whose insets swallow its whole area never throws, they just yield fewer (or
 * no) rectangles. An empty list means "unknown", and the caller falls back to
 * the primary screen.
 */
internal fun usableScreenBounds(): List<Rectangle> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    return try {
        val toolkit = Toolkit.getDefaultToolkit()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { device ->
                val gc = device.defaultConfiguration
                val insets = toolkit.getScreenInsets(gc)
                val bounds = gc.bounds
                Rectangle(
                    bounds.x + insets.left,
                    bounds.y + insets.top,
                    bounds.width - insets.left - insets.right,
                    bounds.height - insets.top - insets.bottom,
                )
            }
            .filterNot { it.isEmpty }
    } catch (_: Exception) {
        emptyList()
    } catch (_: AWTError) {
        emptyList()
    }
}

/**
 * The screen of [screens] that holds most of [rect], or null when [rect]
 * overlaps none of them (or is empty).
 *
 * "Most" is the AREA of the intersection, not the position of the window
 * center: a maximized window overflows its screen by the 7 px invisible frame
 * and can therefore touch a neighbor, which must not steal it. On equal areas
 * the first screen of the list wins. Pure: no AWT global access, so it is
 * testable with hand-written rectangles.
 */
internal fun screenFor(rect: Rectangle, screens: List<Rectangle>): Rectangle? {
    var best: Rectangle? = null
    var bestArea = 0L
    for (screen in screens) {
        val overlap = rect.intersection(screen)
        if (overlap.isEmpty) continue
        val area = overlap.width.toLong() * overlap.height
        if (area > bestArea) {
            best = screen
            bestArea = area
        }
    }
    return best
}
