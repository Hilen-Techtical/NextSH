// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import fr.techtical.nextsh.core.shortcut.ShortcutRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a launcher shortcut acts: never before the unlock screen is passed, at once on an app that
 * is open (it used to do nothing there and fire at the next unlock), not at all when a restart of
 * tunnels has priority, and exactly once.
 */
class NavGraphShortcutTest {

    private val host = ShortcutRequest.ConnectHost("h1")
    private val tunnel = ShortcutRequest.StartTunnel("t1")

    private val screensOfAnOpenApp = listOf(
        Routes.HostList.route,
        Routes.HostDetail.route,
        Routes.Sessions.route,
        Routes.Vault.route,
        Routes.Settings.route,
        Routes.SftpBrowser.route,
        Routes.TunnelConfig.route,
        Routes.SnippetList.route,
        Routes.KnownHosts.route,
        Routes.TunnelList.route,
        Routes.TunnelList.createRoute(),
        Routes.TunnelList.createRoute("t1"),
    )

    private val screensBeforeUnlock = listOf(Routes.VaultUnlock.route, Routes.Onboarding.route, null)

    // ── Nothing pending ───────────────────────────────────────────────────────

    @Test
    fun `no shortcut pending, nothing to do, anywhere`() {
        for (route in screensOfAnOpenApp + screensBeforeUnlock) {
            for (resumePending in listOf(false, true)) {
                assertEquals(ShortcutStep.None, decideShortcut(null, resumePending, route, locked = false), "$route $resumePending")
            }
        }
    }

    // ── Locked ────────────────────────────────────────────────────────────────

    @Test
    fun `before the unlock screen is passed the shortcut waits, and stays pending`() {
        for (route in screensBeforeUnlock) {
            for (request in listOf(host, tunnel)) {
                val step = decideShortcut(request, resumePending = false, route = route, locked = false)

                assertEquals(ShortcutStep.Wait, step, "$route $request")
                assertFalse(step.consumesRequest, "it acts after the unlock")
            }
        }
    }

    @Test
    fun `a pending restart of tunnels does not change that the shortcut waits behind the unlock`() {
        for (route in screensBeforeUnlock) {
            assertEquals(ShortcutStep.Wait, decideShortcut(host, resumePending = true, route = route, locked = false), "$route")
        }
    }

    // ── Unlocked ──────────────────────────────────────────────────────────────

    @Test
    fun `on an open app the shortcut acts at once, from any screen`() {
        for (route in screensOfAnOpenApp) {
            assertEquals(ShortcutStep.Open(host), decideShortcut(host, resumePending = false, route = route, locked = false), "$route")
            assertEquals(ShortcutStep.Open(tunnel), decideShortcut(tunnel, resumePending = false, route = route, locked = false), "$route")
        }
    }

    @Test
    fun `a pending restart of tunnels wins, the shortcut is dropped and does not act`() {
        for (route in screensOfAnOpenApp) {
            for (request in listOf(host, tunnel)) {
                val step = decideShortcut(request, resumePending = true, route = route, locked = false)

                assertEquals(ShortcutStep.Drop, step, "$route $request")
                assertTrue(step.consumesRequest, "dropped, not kept for later")
            }
        }
    }

    // ── Exactly once ──────────────────────────────────────────────────────────

    @Test
    fun `a step that acts or drops uses the request up, one that waits does not`() {
        assertTrue(ShortcutStep.Open(host).consumesRequest)
        assertTrue(ShortcutStep.Drop.consumesRequest)
        assertFalse(ShortcutStep.Wait.consumesRequest)
        assertFalse(ShortcutStep.None.consumesRequest)
    }

    /** What the effect of the nav graph does with the pending request, one evaluation at a time. */
    private class Pending(var request: ShortcutRequest?) {
        fun evaluate(resumePending: Boolean, route: String?): ShortcutStep {
            val step = decideShortcut(request, resumePending, route, locked = false)
            if (step.consumesRequest) request = null
            return step
        }
    }

    @Test
    fun `a shortcut taken at the lock screen acts once after the unlock and never again`() {
        val pending = Pending(host)

        assertEquals(ShortcutStep.Wait, pending.evaluate(false, Routes.VaultUnlock.route))
        assertEquals(ShortcutStep.Wait, pending.evaluate(false, Routes.VaultUnlock.route), "it waits as long as it takes")
        assertEquals(ShortcutStep.Open(host), pending.evaluate(false, Routes.HostList.route))

        // The user leaves, comes back, the screens change: nothing fires later.
        for (route in screensOfAnOpenApp + Routes.VaultUnlock.route) {
            assertEquals(ShortcutStep.None, pending.evaluate(false, route), route)
        }
    }

    @Test
    fun `a shortcut taken on an open app acts once, not at the next unlock`() {
        val pending = Pending(tunnel)

        assertEquals(ShortcutStep.Open(tunnel), pending.evaluate(false, Routes.Settings.route))

        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.Settings.route))
        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.VaultUnlock.route))
        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.HostList.route), "a later unlock does not replay it")
    }

    @Test
    fun `a shortcut dropped for a restart of tunnels does not come back once the restart is done`() {
        val pending = Pending(host)

        assertEquals(ShortcutStep.Wait, pending.evaluate(true, Routes.VaultUnlock.route))
        assertEquals(ShortcutStep.Drop, pending.evaluate(true, Routes.TunnelList.createRoute()))

        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.HostList.route))
    }

    @Test
    fun `the newest shortcut replaces the pending one`() {
        val pending = Pending(host)
        assertEquals(ShortcutStep.Wait, pending.evaluate(false, Routes.VaultUnlock.route))

        pending.request = tunnel

        assertEquals(ShortcutStep.Open(tunnel), pending.evaluate(false, Routes.HostList.route))
        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.HostList.route))
    }

    // ── Open the terminal (the notification of the terminal service) ──────────

    private val terminal = ShortcutRequest.OpenTerminal

    @Test
    fun `a tap on the terminal notification waits behind the unlock screen, and stays pending`() {
        for (route in screensBeforeUnlock) {
            for (resumePending in listOf(false, true)) {
                val step = decideShortcut(terminal, resumePending = resumePending, route = route, locked = false)

                assertEquals(ShortcutStep.Wait, step, "$route $resumePending")
                assertFalse(step.consumesRequest, "it acts after the unlock")
            }
        }
    }

    @Test
    fun `on an open app a tap on the terminal notification acts at once, from any screen`() {
        for (route in screensOfAnOpenApp) {
            assertEquals(ShortcutStep.Open(terminal), decideShortcut(terminal, resumePending = false, route = route, locked = false), "$route")
        }
    }

    @Test
    fun `a pending restart of tunnels wins over a tap on the terminal notification`() {
        for (route in screensOfAnOpenApp) {
            val step = decideShortcut(terminal, resumePending = true, route = route, locked = false)

            assertEquals(ShortcutStep.Drop, step, route)
            assertTrue(step.consumesRequest, "dropped, not kept for later")
        }
    }

    @Test
    fun `a tap on the terminal notification at the lock screen opens the terminal once after the unlock`() {
        val pending = Pending(terminal)

        assertEquals(ShortcutStep.Wait, pending.evaluate(false, Routes.VaultUnlock.route))
        assertEquals(ShortcutStep.Open(terminal), pending.evaluate(false, Routes.HostList.route))

        for (route in screensOfAnOpenApp + Routes.VaultUnlock.route) {
            assertEquals(ShortcutStep.None, pending.evaluate(false, route), route)
        }
    }
}
