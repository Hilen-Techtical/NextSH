// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How the restart of the tunnels that waited for the user (the tap on the "authentication required"
 * notification) reaches the tunnel list, from the routes of the back stack. On the shape a tab press
 * leaves it pops like the Tunnels tab. Anywhere else it goes on top and saves nothing: a terminal
 * popped with `saveState` stays connected where nothing restores it.
 */
class NavGraphResumeNavigationTest {

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
        override fun toString() = routes.joinToString(" > ")
    }

    private fun resume(stack: Stack, pending: Boolean = true) =
        resumeNavigation(pending, stack.current, stack.previous, locked = false)

    @Test
    fun `nothing to resume, nowhere to go, whatever is on screen`() {
        val stacks = listOf(
            Stack(host), Stack(host, tunnels), Stack(host, vault), Stack(host, terminal),
            Stack(host, hostDetail), Stack(host, terminal, tunnels),
        )
        for (stack in stacks) {
            assertEquals(ResumeNavigation.NONE, resume(stack, pending = false), "$stack")
        }
    }

    @Test
    fun `before the unlock screen is passed the resume waits, the unlock itself sends the user to the list`() {
        for (route in listOf(Routes.VaultUnlock.route, Routes.Onboarding.route)) {
            assertEquals(ResumeNavigation.NONE, resumeNavigation(true, route, null, locked = false), route)
        }
        assertEquals(ResumeNavigation.NONE, resumeNavigation(true, null, null, locked = false), "no destination yet")
    }

    @Test
    fun `already on the tunnel list there is nowhere to go, over a terminal too`() {
        for (stack in listOf(Stack(host, tunnels), Stack(host, terminal, tunnels), Stack(tunnels))) {
            assertEquals(ResumeNavigation.NONE, resume(stack), "$stack")
        }
    }

    @Test
    fun `on the shape a tab press leaves it the list opens like the Tunnels tab`() {
        for (stack in listOf(Stack(host), Stack(host, vault), Stack(host, settings))) {
            assertEquals(ResumeNavigation.TAB_STYLE, resume(stack), "$stack")
        }
    }

    @Test
    fun `over a terminal or a form the list opens on top and saves nothing, so the terminal stays alive`() {
        val stacks = listOf(
            Stack(host, terminal),
            Stack(host, hostDetail),
            Stack(host, keyImport),
            Stack(host, vault, keyImport),
            Stack(host, tunnels, tunnelConfig),
        )
        for (stack in stacks) {
            assertEquals(ResumeNavigation.ON_TOP, resume(stack), "$stack")
        }
    }

    @Test
    fun `a host list above a tunnel list is not the root of a tab press, the list opens on top`() {
        // What the unlock of an earlier restart of tunnels left, then the Hosts tab.
        assertEquals(ResumeNavigation.ON_TOP, resume(Stack(tunnels, host)))
    }
}
