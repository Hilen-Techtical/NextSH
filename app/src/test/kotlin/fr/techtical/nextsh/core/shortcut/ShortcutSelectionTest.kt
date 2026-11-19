// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which favourites are published as dynamic shortcuts, and which pinned shortcuts the dynamic
 * publication does not reach, so that [ShortcutUpdater] rebuilds them with the token.
 */
class ShortcutSelectionTest {

    private fun host(id: String) = Host(
        id = id,
        label = "Host $id",
        hostname = "host$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        isFavorite = true,
    )

    private fun tunnel(id: String) = TunnelConfig(
        id = id,
        label = "Tunnel $id",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        isFavorite = true,
    )

    // ── The dynamic ones ──────────────────────────────────────────────────────

    @Test
    fun `hosts take the slots first, tunnels the ones that are left`() {
        val selection = selectDynamicShortcuts(
            hosts = listOf(host("h1"), host("h2")),
            tunnels = listOf(tunnel("t1"), tunnel("t2"), tunnel("t3")),
        )

        assertEquals(listOf("host_h1", "host_h2", "tunnel_t1", "tunnel_t2"), selection.shortcutIds)
        assertEquals(listOf("h1", "h2"), selection.hosts.map { it.id })
        assertEquals(listOf("t1", "t2"), selection.tunnels.map { it.id })
    }

    @Test
    fun `four hosts leave no slot for a tunnel`() {
        val selection = selectDynamicShortcuts((1..6).map { host("h$it") }, listOf(tunnel("t1")))

        assertEquals(4, selection.hosts.size)
        assertTrue(selection.tunnels.isEmpty())
    }

    @Test
    fun `the maximum is a parameter that defaults to the one of the updater`() {
        val hosts = (1..6).map { host("h$it") }

        assertEquals(ShortcutUpdater.MAX_SHORTCUTS, selectDynamicShortcuts(hosts, emptyList()).hosts.size)
        assertEquals(2, selectDynamicShortcuts(hosts, emptyList(), max = 2).hosts.size)
        assertTrue(selectDynamicShortcuts(hosts, emptyList(), max = 0).shortcutIds.isEmpty())
    }

    // ── Ids ───────────────────────────────────────────────────────────────────

    @Test
    fun `an id is read back to the host or the tunnel it was made for`() {
        assertEquals(PinnedTarget.PinnedHost("h-1"), parseShortcutId(hostShortcutId("h-1")))
        assertEquals(PinnedTarget.PinnedTunnel("t_2"), parseShortcutId(tunnelShortcutId("t_2")))
        assertEquals("host_h-1", PinnedTarget.PinnedHost("h-1").shortcutId)
        assertEquals("tunnel_t_2", PinnedTarget.PinnedTunnel("t_2").shortcutId)
    }

    @Test
    fun `an id that is not one of ours, or has nothing after the prefix, is not read`() {
        assertNull(parseShortcutId(""))
        assertNull(parseShortcutId("host_"))
        assertNull(parseShortcutId("tunnel_"))
        assertNull(parseShortcutId("snippet_s1"))
        assertNull(parseShortcutId("Host_h1"))
    }

    // ── The pinned ones the dynamic publication does not reach ───────────────

    @Test
    fun `a pinned copy of a dynamic shortcut is already updated, the others are to refresh`() {
        val targets = pinnedToRefresh(
            pinnedIds = listOf("host_h1", "host_h9", "tunnel_t1", "tunnel_t9"),
            dynamicIds = setOf("host_h1", "tunnel_t1"),
        )

        assertEquals(listOf(PinnedTarget.PinnedHost("h9"), PinnedTarget.PinnedTunnel("t9")), targets)
    }

    @Test
    fun `a favourite beyond the dynamic slots that is pinned is to refresh`() {
        val hosts = (1..6).map { host("h$it") }
        val selection = selectDynamicShortcuts(hosts, emptyList())

        val targets = pinnedToRefresh(listOf("host_h5"), selection.shortcutIds.toSet())

        assertEquals(listOf(PinnedTarget.PinnedHost("h5")), targets)
    }

    @Test
    fun `nothing pinned, or only dynamic ones pinned, nothing to refresh`() {
        assertTrue(pinnedToRefresh(emptyList(), setOf("host_h1")).isEmpty())
        assertTrue(pinnedToRefresh(listOf("host_h1"), setOf("host_h1")).isEmpty())
    }

    @Test
    fun `a pinned shortcut that is not one of ours is left alone, and an id is listed once`() {
        val targets = pinnedToRefresh(
            pinnedIds = listOf("other_x", "host_h2", "host_h2", ""),
            dynamicIds = emptySet(),
        )

        assertEquals(listOf(PinnedTarget.PinnedHost("h2")), targets)
    }
}
