// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests of [SoftwareOsrPanel.setKeyboardBlocked] on a bare panel (no browser attached, no
 * window). Skipped when the panel cannot be built in this environment.
 */
class SoftwareOsrPanelFocusTest {

    private fun panelOrSkip(): SoftwareOsrPanel {
        val panel = try {
            SoftwareOsrPanel()
        } catch (e: Throwable) {
            assumeTrue("SoftwareOsrPanel cannot be built here: ${e.javaClass.simpleName}", false)
            throw e
        }
        return panel
    }

    @Test
    fun `a new panel can take the focus`() {
        assertTrue(panelOrSkip().isFocusable)
    }

    @Test
    fun `blocking makes the panel non-focusable and unblocking restores it`() {
        val panel = panelOrSkip()

        panel.setKeyboardBlocked(true)
        assertFalse(panel.isFocusable)

        panel.setKeyboardBlocked(false)
        assertTrue(panel.isFocusable)
    }

    @Test
    fun `blocking twice or unblocking twice is harmless`() {
        val panel = panelOrSkip()

        panel.setKeyboardBlocked(true)
        panel.setKeyboardBlocked(true)
        assertFalse(panel.isFocusable)
        panel.setKeyboardBlocked(false)
        panel.setKeyboardBlocked(false)
        assertTrue(panel.isFocusable)
    }

    @Test
    fun `unblocking with a focus restore on a panel that is not showing does not fail`() {
        val panel = panelOrSkip()
        panel.setKeyboardBlocked(true)

        panel.setKeyboardBlocked(false, restoreFocus = true)

        assertTrue(panel.isFocusable)
    }

    @Test
    fun `without a browser, blocking does not fail`() {
        val panel = panelOrSkip()
        panel.detachBrowser()

        panel.setKeyboardBlocked(true)

        assertFalse(panel.isFocusable)
    }
}