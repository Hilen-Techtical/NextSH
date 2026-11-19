// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the restart of the tunnels that waited for the user sends the user to the tunnel list: never
 * before the unlock screen is passed (the unlock itself sends them), never when they are already
 * there, and from any other screen of an open app.
 */
class NavGraphResumeTest {

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
    )

    @Test
    fun `nothing to resume, nowhere to go`() {
        for (route in screensOfAnOpenApp + listOf(Routes.VaultUnlock.route, Routes.TunnelList.route, null)) {
            assertFalse(shouldOpenTunnelListForResume(resumePending = false, route = route, locked = false), "$route")
        }
    }

    @Test
    fun `before the unlock screen is passed the resume waits, the unlock sends the user to the list`() {
        assertFalse(shouldOpenTunnelListForResume(true, Routes.VaultUnlock.route, locked = false))
        assertFalse(shouldOpenTunnelListForResume(true, Routes.Onboarding.route, locked = false))
        assertFalse(shouldOpenTunnelListForResume(true, null, locked = false), "no destination yet")
    }

    @Test
    fun `on an open app the user is taken to the list from any other screen`() {
        for (route in screensOfAnOpenApp) {
            assertTrue(shouldOpenTunnelListForResume(true, route, locked = false), "$route")
        }
    }

    @Test
    fun `already on the tunnel list there is nowhere to go, in either form of its route`() {
        assertFalse(shouldOpenTunnelListForResume(true, Routes.TunnelList.route, locked = false), "the pattern of the destination")
        assertFalse(shouldOpenTunnelListForResume(true, Routes.TunnelList.createRoute(), locked = false))
        assertFalse(shouldOpenTunnelListForResume(true, Routes.TunnelList.createRoute("t1"), locked = false))
    }
}
