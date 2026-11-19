// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import fr.techtical.nextsh.core.shortcut.ShortcutRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * What the wipe of the vault changes in the decisions of the navigation: a launcher shortcut tapped while
 * the app is closed connects and starts nothing (the host or the tunnel it names is being erased).
 */
class NavGraphWipeTest {

    private val host = ShortcutRequest.ConnectHost("h1")
    private val tunnel = ShortcutRequest.StartTunnel("t1")
    private val terminal = ShortcutRequest.OpenTerminal

    private val screensOfAnOpenApp = listOf(
        Routes.HostList.route,
        Routes.Sessions.route,
        Routes.Settings.route,
        Routes.TunnelList.route,
        Routes.TunnelList.createRoute("t1"),
    )

    @Test
    fun `a shortcut waits while the wipe has the app closed, whatever screen is under the modal`() {
        for (route in screensOfAnOpenApp) {
            for (request in listOf(host, tunnel, terminal)) {
                val step = decideShortcut(request, resumePending = false, route = route, locked = false, wipeClosed = true)

                assertEquals(ShortcutStep.Wait, step, "$route $request")
                assertFalse(step.consumesRequest, "it neither acts nor is spent")
            }
        }
    }

    @Test
    fun `a pending restart of tunnels does not change that the shortcut waits during the wipe`() {
        for (route in screensOfAnOpenApp) {
            assertEquals(
                ShortcutStep.Wait,
                decideShortcut(host, resumePending = true, route = route, locked = false, wipeClosed = true),
                route,
            )
        }
    }

    @Test
    fun `with the app open the wipe changes nothing`() {
        for (route in screensOfAnOpenApp) {
            assertEquals(
                ShortcutStep.Open(host),
                decideShortcut(host, resumePending = false, route = route, locked = false, wipeClosed = false),
                route,
            )
            assertEquals(
                ShortcutStep.Open(host),
                decideShortcut(host, resumePending = false, route = route, locked = false),
                "the default is an app that is not wiped: $route",
            )
        }
    }

    @Test
    fun `no shortcut is still none during the wipe`() {
        assertEquals(ShortcutStep.None, decideShortcut(null, resumePending = false, route = Routes.HostList.route, locked = false, wipeClosed = true))
    }
}
