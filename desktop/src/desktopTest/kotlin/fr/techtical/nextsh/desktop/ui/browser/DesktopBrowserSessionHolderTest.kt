// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBrowserSessionHolderTest {

    @Test
    fun `initial state has null visible and active tunnel ids`() {
        val holder = DesktopBrowserSessionHolder()
        assertNull(holder.visibleTunnelId.value)
        assertNull(holder.activeTunnelId.value)
        assertFalse(holder.hasSession())
    }

    @Test
    fun `show sets both visible and active tunnel ids`() {
        val holder = DesktopBrowserSessionHolder()
        holder.show("tunnel-1")
        assertEquals("tunnel-1", holder.visibleTunnelId.value)
        assertEquals("tunnel-1", holder.activeTunnelId.value)
        assertTrue(holder.hasSession())
        assertTrue(holder.hasSession("tunnel-1"))
    }

    @Test
    fun `hide clears visible but keeps active tunnel id`() {
        val holder = DesktopBrowserSessionHolder()
        holder.show("tunnel-1")
        holder.hide()
        assertNull(holder.visibleTunnelId.value)
        assertEquals("tunnel-1", holder.activeTunnelId.value)
        assertTrue(holder.hasSession())
    }

    @Test
    fun `close resets both ids and invokes onDispose with the registered browser instance`() {
        val holder = DesktopBrowserSessionHolder()
        val fakeBrowser = object {}
        holder.setKcefBrowser(fakeBrowser)
        holder.show("tunnel-2")

        var disposedWith: Any? = "sentinel"
        holder.onDispose = { disposedWith = it }

        holder.close()

        assertNull(holder.visibleTunnelId.value)
        assertNull(holder.activeTunnelId.value)
        assertFalse(holder.hasSession())
        assertEquals(fakeBrowser, disposedWith, "onDispose must receive the browser reference")
        assertNull(holder.getKcefBrowser(), "kcefBrowser must be cleared after close")
    }
}
