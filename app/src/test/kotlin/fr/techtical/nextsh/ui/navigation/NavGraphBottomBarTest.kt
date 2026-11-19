// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a press on the bottom bar does, and how a tunnel shortcut reaches the tunnel list, from the
 * routes of the back stack.
 *
 * The Hosts tab never navigates to the host list with `restoreState`: the back stack may have saved
 * a stack of other screens under it (a tunnel shortcut left it so), which the restore would push
 * back on top, and the tab would seem dead. And nothing but a tab is ever popped with `saveState`:
 * a terminal or a form so popped is parked, alive, where nothing restores it.
 */
class NavGraphBottomBarTest {

    private val host = Routes.HostList.route
    private val tunnels = Routes.TunnelList.route
    private val vault = Routes.Vault.route
    private val settings = Routes.Settings.route
    private val terminal = Routes.Sessions.route
    private val hostDetail = Routes.HostDetail.route
    private val tunnelConfig = Routes.TunnelConfig.route
    private val keyImport = Routes.KeyImport.route

    /** A back stack from the bottom up, as the routes `NavGraph` reads from the controller. */
    private class Stack(vararg val routes: String) {
        val current: String get() = routes.last()
        val previous: String? get() = routes.getOrNull(routes.size - 2)
        val hostListOnBackStack: Boolean get() = Routes.HostList.route in routes
        override fun toString() = routes.joinToString(" > ")
    }

    private fun bar(target: String, stack: Stack) =
        bottomBarAction(target, stack.current, stack.previous, stack.hostListOnBackStack)

    private fun shortcut(stack: Stack) = tunnelShortcutNavigation(stack.current, stack.previous)

    private val tabShapes = listOf(Stack(host), Stack(host, tunnels), Stack(host, vault), Stack(host, settings))
    private val notTabShapes = listOf(
        Stack(host, terminal),
        Stack(host, terminal, tunnels),
        Stack(host, tunnels, tunnelConfig),
        Stack(host, hostDetail),
        Stack(host, keyImport),
        Stack(host, vault, keyImport),
    )

    // ── The shape a tab press leaves ──────────────────────────────────────────

    @Test
    fun `the host list alone, or one tab on it, is the shape of a tab press`() {
        for (stack in tabShapes) assertTrue(isTabShape(stack.current, stack.previous), "$stack")
    }

    @Test
    fun `a terminal or a form on screen or under the tab is not that shape`() {
        for (stack in notTabShapes) assertFalse(isTabShape(stack.current, stack.previous), "$stack")
    }

    @Test
    fun `without the host list there is no tab shape, as after the unlock of a restart of tunnels`() {
        assertFalse(isTabShape(tunnels, null), "[tunnels]")
        assertFalse(isTabShape(host, tunnels), "[tunnels > host], the host list is not the root")
        assertFalse(isTabShape(null, null))
    }

    // ── The tunnel shortcut ───────────────────────────────────────────────────

    @Test
    fun `on a tab shape the tunnel shortcut pops like a tab`() {
        for (stack in tabShapes) assertEquals(TunnelShortcutNavigation.TAB_STYLE, shortcut(stack), "$stack")
    }

    @Test
    fun `anywhere else the tunnel shortcut goes on top and saves nothing, so a terminal stays alive under it`() {
        for (stack in notTabShapes) assertEquals(TunnelShortcutNavigation.ON_TOP, shortcut(stack), "$stack")
    }

    @Test
    fun `without the host list the tunnel shortcut replaces the list in place`() {
        assertEquals(TunnelShortcutNavigation.ON_TOP, shortcut(Stack(tunnels)))
        assertEquals(TunnelShortcutNavigation.ON_TOP, shortcut(Stack(tunnels, host)))
    }

    // ── The Hosts tab ─────────────────────────────────────────────────────────

    @Test
    fun `the Hosts tab pops back to the host list and saves the tab that is directly on it`() {
        for (stack in listOf(Stack(host, tunnels), Stack(host, vault), Stack(host, settings))) {
            assertEquals(BottomBarAction.POP_TO_HOST_LIST_SAVING, bar(host, stack), "$stack")
        }
    }

    @Test
    fun `the Hosts tab closes, and does not save, what lies between the tab and the host list`() {
        // A tunnel list that a shortcut opened over a terminal: saved, the terminal would stay connected out of reach.
        assertEquals(BottomBarAction.POP_TO_HOST_LIST_CLOSING, bar(host, Stack(host, terminal, tunnels)))
        assertEquals(BottomBarAction.POP_TO_HOST_LIST_CLOSING, bar(host, Stack(host, hostDetail, tunnels)))
    }

    @Test
    fun `the Hosts tab does nothing on the host list`() {
        assertEquals(BottomBarAction.STAY, bar(host, Stack(host)))
        assertEquals(BottomBarAction.STAY, bar(host, Stack(tunnels, host)))
    }

    @Test
    fun `the Hosts tab without the host list on the back stack puts it there, restoring nothing`() {
        // The unlock of a restart of tunnels leaves the tunnel list alone: a stale or null mapping of the
        // host list would make a restoring navigation dead or wrong.
        assertEquals(BottomBarAction.SHOW_HOST_LIST, bar(host, Stack(tunnels)))
    }

    // ── The other tabs ────────────────────────────────────────────────────────

    @Test
    fun `the other tabs navigate with their saved state from a tab shape`() {
        for (target in listOf(tunnels, vault, settings)) {
            for (stack in tabShapes.filter { it.current != target }) {
                assertEquals(BottomBarAction.NAVIGATE, bar(target, stack), "$target from $stack")
            }
        }
    }

    @Test
    fun `the other tabs close the screens between the tab and the host list instead of saving them`() {
        // The tab on screen is the tunnel list, over a terminal or a form.
        for (target in listOf(vault, settings)) {
            assertEquals(BottomBarAction.NAVIGATE_CLOSING, bar(target, Stack(host, terminal, tunnels)), target)
            assertEquals(BottomBarAction.NAVIGATE_CLOSING, bar(target, Stack(host, hostDetail, tunnels)), target)
        }
    }

    @Test
    fun `the other tabs navigate as before when there is no host list to pop up to`() {
        for (target in listOf(vault, settings)) {
            assertEquals(BottomBarAction.NAVIGATE, bar(target, Stack(tunnels)), target)
            assertEquals(BottomBarAction.NAVIGATE, bar(target, Stack(tunnels, host)), target)
        }
        assertEquals(BottomBarAction.NAVIGATE, bar(tunnels, Stack(tunnels, host)), "from the host list above a tunnel list")
    }

    @Test
    fun `a press on the tab that is on screen does nothing, so that it never closes the terminal under it`() {
        for (stack in tabShapes + Stack(host, terminal, tunnels) + Stack(host, hostDetail, tunnels) + Stack(tunnels)) {
            assertEquals(BottomBarAction.STAY, bar(stack.current, stack), "$stack")
        }
    }
}
