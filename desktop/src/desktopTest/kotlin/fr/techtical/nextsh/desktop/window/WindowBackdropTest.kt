// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [paintBackdrop]. Only lightweight Swing components are
 * built (no frame, nothing is shown), so this runs on a machine without a
 * display.
 */
class WindowBackdropTest {

    private val light = Color(240, 240, 240)

    private fun panel(vararg children: Component): JPanel =
        JPanel().apply {
            background = light
            children.forEach { add(it) }
        }

    private fun allComponents(root: Container): List<Component> =
        listOf<Component>(root) + root.components.flatMap { child ->
            if (child is Container) allComponents(child) else listOf(child)
        }

    @Test
    fun `the backdrop is NearBlack`() {
        assertEquals(Color(0x0F, 0x0F, 0x0F), NEAR_BLACK_BACKDROP)
    }

    @Test
    fun `the root and every nested component get the backdrop`() {
        val leaf = JLabel("leaf").apply { background = light }
        val inner = panel(leaf, panel(JLabel("deep").apply { background = light }))
        val root = panel(inner, panel())

        paintBackdrop(root)

        val all = allComponents(root)
        assertEquals(6, all.size) // root, inner, leaf, nested panel, deep, empty panel
        all.forEach { assertEquals(NEAR_BLACK_BACKDROP, it.background, it.toString()) }
    }

    @Test
    fun `a given colour is applied instead of the default one`() {
        val root = panel(JLabel("x"))
        val red = Color(200, 0, 0)

        paintBackdrop(root, red)

        allComponents(root).forEach { assertEquals(red, it.background) }
    }

    @Test
    fun `an empty container is painted without error`() {
        val root = JPanel().apply { background = light }

        paintBackdrop(root)

        assertEquals(NEAR_BLACK_BACKDROP, root.background)
    }
}
