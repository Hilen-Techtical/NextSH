// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import java.awt.Color
import java.awt.Component
import java.awt.Container

/** NearBlack (0x0F0F0F), the colour of the NextSH chrome. */
internal val NEAR_BLACK_BACKDROP = Color(0x0F, 0x0F, 0x0F)

/**
 * Sets [color] as the background of [root] and of every AWT component below
 * it, recursively.
 *
 * ## Why every component
 * Whatever Compose has not painted yet shows the AWT/Swing background
 * underneath: the frame, its root/layered/content panes, and the heavyweight
 * canvas Skia draws into. Any one of them left at the default light grey can
 * be the one that shows through (seen as white) while the window is shown or
 * resized before Compose repaints. Harmless on every platform: it only
 * changes a colour that Compose paints over.
 *
 * Call it on the EDT, from the composition of the window content, once the
 * window is displayable (its component tree exists).
 */
internal fun paintBackdrop(root: Container, color: Color = NEAR_BLACK_BACKDROP) {
    root.background = color
    for (child: Component in root.components) {
        if (child is Container) {
            paintBackdrop(child, color)
        } else {
            child.background = color
        }
    }
}
