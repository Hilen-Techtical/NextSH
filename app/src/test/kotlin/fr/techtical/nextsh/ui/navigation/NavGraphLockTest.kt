// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import fr.techtical.nextsh.core.shortcut.ShortcutRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the lock screen changes in the decisions of the navigation: a shortcut and a restart of
 * tunnels wait behind it as they wait behind the unlock screen of the launch, and the lock screen
 * itself is shown over the screens of an open app only.
 */
class NavGraphLockTest {

    private val host = ShortcutRequest.ConnectHost("h1")
    private val tunnel = ShortcutRequest.StartTunnel("t1")
    private val terminal = ShortcutRequest.OpenTerminal

    private val screensOfAnOpenApp = listOf(
        Routes.HostList.route,
        Routes.HostDetail.route,
        Routes.Sessions.route,
        Routes.Vault.route,
        Routes.Settings.route,
        Routes.SftpBrowser.route,
        Routes.TunnelConfig.route,
        Routes.TunnelList.route,
        Routes.TunnelList.createRoute(),
        Routes.TunnelList.createRoute("t1"),
    )

    private val screensBeforeUnlock = listOf(Routes.VaultUnlock.route, Routes.Onboarding.route, null)

    // ── A launcher shortcut ───────────────────────────────────────────────────

    @Test
    fun `a shortcut waits behind the lock screen, whatever screen is under it, and stays pending`() {
        for (route in screensOfAnOpenApp) {
            for (request in listOf(host, tunnel, terminal)) {
                val step = decideShortcut(request, resumePending = false, route = route, locked = true)

                assertEquals(ShortcutStep.Wait, step, "$route $request")
                assertFalse(step.consumesRequest, "it acts after the unlock")
            }
        }
    }

    @Test
    fun `a pending restart of tunnels does not change that the shortcut waits behind the lock screen`() {
        for (route in screensOfAnOpenApp) {
            assertEquals(ShortcutStep.Wait, decideShortcut(host, resumePending = true, route = route, locked = true), route)
        }
    }

    @Test
    fun `once unlocked the shortcut acts at once, from the screen the user was on`() {
        for (route in screensOfAnOpenApp) {
            assertEquals(ShortcutStep.Open(host), decideShortcut(host, resumePending = false, route = route, locked = false), route)
        }
    }

    @Test
    fun `no shortcut pending, nothing to do, locked or not`() {
        for (route in screensOfAnOpenApp + screensBeforeUnlock) {
            for (locked in listOf(false, true)) {
                assertEquals(ShortcutStep.None, decideShortcut(null, resumePending = true, route = route, locked = locked), "$route $locked")
            }
        }
    }

    @Test
    fun `before the unlock screen is passed the shortcut waits, locked or not`() {
        for (route in screensBeforeUnlock) {
            for (locked in listOf(false, true)) {
                assertEquals(ShortcutStep.Wait, decideShortcut(host, resumePending = false, route = route, locked = locked), "$route $locked")
            }
        }
    }

    @Test
    fun `unlocked, the decision is the one it always was`() {
        assertEquals(ShortcutStep.Open(host), decideShortcut(host, false, Routes.HostList.route, locked = false))
        assertEquals(ShortcutStep.Drop, decideShortcut(host, true, Routes.HostList.route, locked = false))
    }

    /** What the effect of the nav graph does with the pending request, one evaluation at a time. */
    private class Pending(var request: ShortcutRequest?) {
        fun evaluate(resumePending: Boolean, route: String?, locked: Boolean): ShortcutStep {
            val step = decideShortcut(request, resumePending, route, locked)
            if (step.consumesRequest) request = null
            return step
        }
    }

    @Test
    fun `a shortcut taken while the app was locked acts once after the unlock and never again`() {
        val pending = Pending(host)

        assertEquals(ShortcutStep.Wait, pending.evaluate(false, Routes.HostList.route, locked = true))
        assertEquals(ShortcutStep.Wait, pending.evaluate(false, Routes.Settings.route, locked = true), "it waits as long as it takes")
        assertEquals(ShortcutStep.Open(host), pending.evaluate(false, Routes.Settings.route, locked = false))
        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.Settings.route, locked = false), "spent")
        assertEquals(ShortcutStep.None, pending.evaluate(false, Routes.Settings.route, locked = true), "and not back with the next lock")
    }

    // ── A restart of the tunnels ──────────────────────────────────────────────

    @Test
    fun `a restart of tunnels does not take the user to the list while the lock screen is up`() {
        for (route in screensOfAnOpenApp) {
            assertFalse(shouldOpenTunnelListForResume(resumePending = true, route = route, locked = true), route)
        }
    }

    @Test
    fun `a restart of tunnels goes to the list once the app is unlocked, on any screen but the list`() {
        for (route in screensOfAnOpenApp.filterNot { it.startsWith("tunnel_list") }) {
            assertTrue(shouldOpenTunnelListForResume(resumePending = true, route = route, locked = false), route)
        }
    }

    @Test
    fun `nothing pending, no restart, locked or not`() {
        for (route in screensOfAnOpenApp) {
            for (locked in listOf(false, true)) {
                assertFalse(shouldOpenTunnelListForResume(resumePending = false, route = route, locked = locked))
            }
        }
    }

    @Test
    fun `the navigation of a restart is none while locked, whatever the shape of the back stack`() {
        val stacks = listOf(
            Routes.HostList.route to null,
            Routes.Settings.route to Routes.HostList.route,
            Routes.Sessions.route to Routes.HostList.route,
            Routes.HostDetail.route to Routes.HostList.route,
        )
        for ((current, previous) in stacks) {
            assertEquals(ResumeNavigation.NONE, resumeNavigation(true, current, previous, locked = true), "$current over $previous")
        }
    }

    @Test
    fun `the navigation of a restart is what it was once unlocked`() {
        assertEquals(ResumeNavigation.TAB_STYLE, resumeNavigation(true, Routes.HostList.route, null, locked = false))
        assertEquals(ResumeNavigation.TAB_STYLE, resumeNavigation(true, Routes.Settings.route, Routes.HostList.route, locked = false))
        assertEquals(ResumeNavigation.ON_TOP, resumeNavigation(true, Routes.Sessions.route, Routes.HostList.route, locked = false))
        assertEquals(ResumeNavigation.NONE, resumeNavigation(false, Routes.HostList.route, null, locked = false))
    }

    @Test
    fun `the restart waits for the unlock, then goes to the list`() {
        val route = Routes.Sessions.route
        val previous = Routes.HostList.route

        assertEquals(ResumeNavigation.NONE, resumeNavigation(true, route, previous, locked = true))
        assertEquals(ResumeNavigation.ON_TOP, resumeNavigation(true, route, previous, locked = false))
    }

    // ── The lock screen itself ────────────────────────────────────────────────

    @Test
    fun `the lock screen covers every screen of an open app`() {
        for (route in screensOfAnOpenApp) {
            assertTrue(shouldShowLockScreen(locked = true, onboardingCompleted = true, route = route), route)
        }
    }

    @Test
    fun `the lock screen is not shown when the app is not locked`() {
        for (route in screensOfAnOpenApp) {
            assertFalse(shouldShowLockScreen(locked = false, onboardingCompleted = true, route = route), route)
        }
    }

    @Test
    fun `the lock screen is not shown over the unlock screen of the launch, the route is the lock`() {
        assertFalse(shouldShowLockScreen(locked = true, onboardingCompleted = true, route = Routes.VaultUnlock.route))
    }

    @Test
    fun `the lock screen is not shown over the first launch, nor before the setting is read`() {
        assertFalse(shouldShowLockScreen(locked = true, onboardingCompleted = true, route = Routes.Onboarding.route))
        assertFalse(shouldShowLockScreen(locked = true, onboardingCompleted = false, route = Routes.HostList.route))
        assertFalse(shouldShowLockScreen(locked = true, onboardingCompleted = null, route = Routes.HostList.route))
    }

    @Test
    fun `the lock screen waits for the first destination`() {
        assertFalse(shouldShowLockScreen(locked = true, onboardingCompleted = true, route = null))
    }

    // ── The layer that hides the screens until the window is up ───────────────

    @Test
    fun `the layer hides every screen of an open app while the app is locked`() {
        for (route in screensOfAnOpenApp) {
            assertTrue(shouldCoverContent(locked = true, onboardingCompleted = true, route = route), route)
        }
    }

    @Test
    fun `the layer also hides the frames where the route is still null, the restored back stack is not known yet`() {
        assertTrue(shouldCoverContent(locked = true, onboardingCompleted = true, route = null))
        // ... where the lock screen window waits for a route.
        assertFalse(shouldShowLockScreen(locked = true, onboardingCompleted = true, route = null))
    }

    @Test
    fun `the layer is not drawn over the unlock screen of the launch, nor over the first launch, nor while unlocked`() {
        assertFalse(shouldCoverContent(locked = true, onboardingCompleted = true, route = Routes.VaultUnlock.route))
        assertFalse(shouldCoverContent(locked = true, onboardingCompleted = true, route = Routes.Onboarding.route))
        assertFalse(shouldCoverContent(locked = true, onboardingCompleted = false, route = Routes.HostList.route))
        assertFalse(shouldCoverContent(locked = true, onboardingCompleted = null, route = Routes.HostList.route))
        for (route in screensOfAnOpenApp + screensBeforeUnlock) {
            assertFalse(shouldCoverContent(locked = false, onboardingCompleted = true, route = route), "$route")
        }
    }

    @Test
    fun `wherever the lock screen window is up the layer is drawn too, so the window never shows over a bare screen`() {
        val routes = screensOfAnOpenApp + screensBeforeUnlock
        for (locked in listOf(false, true)) {
            for (onboarding in listOf(false, true, null)) {
                for (route in routes) {
                    if (shouldShowLockScreen(locked, onboarding, route)) {
                        assertTrue(shouldCoverContent(locked, onboarding, route), "$locked $onboarding $route")
                    }
                }
            }
        }
    }
}
