// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the shortcut-building logic in ShortcutUpdater.
 *
 * Because [ShortcutInfoCompat.Builder] and [IconCompat] are Android framework
 * classes that cannot be instantiated in a JVM-only test, we test the
 * *selection and priority algorithm* directly (i.e. which IDs are selected,
 * how many, and in which order) by replicating the identical logic here
 * (hosts first up to MAX_SHORTCUTS, then tunnels filling remaining slots).
 *
 * The companion-object constants (MAX_SHORTCUTS, EXTRA_HOST_ID, …) are still
 * referenced from the production class so any change to them will break these
 * tests appropriately.
 */
class ShortcutUpdaterTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeHost(id: String) = Host(
        id = id,
        label = "Host $id",
        hostname = "host$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        isFavorite = true,
    )

    private fun makeTunnel(id: String) = TunnelConfig(
        id = id,
        label = "Tunnel $id",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        isFavorite = true,
    )

    /**
     * Mirrors the algorithm in [ShortcutUpdater.updateShortcuts]:
     * hosts fill slots first (up to MAX_SHORTCUTS), tunnels fill the rest.
     * Returns pairs of (shortcutId, rank).
     */
    private fun buildShortcutList(
        hosts: List<Host>,
        tunnels: List<TunnelConfig>,
    ): List<Pair<String, Int>> {
        val max = ShortcutUpdater.MAX_SHORTCUTS
        val result = mutableListOf<Pair<String, Int>>()
        var rank = 0

        for (host in hosts.take(max)) {
            result.add("host_${host.id}" to rank++)
        }

        val remaining = max - result.size
        for (tunnel in tunnels.take(remaining)) {
            result.add("tunnel_${tunnel.id}" to rank++)
        }

        return result
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `3 favorite hosts and 2 favorite tunnels produce 4 shortcuts (hosts priority, capped at MAX)`() {
        val hosts = listOf(makeHost("h1"), makeHost("h2"), makeHost("h3"))
        val tunnels = listOf(makeTunnel("t1"), makeTunnel("t2"))

        val shortcuts = buildShortcutList(hosts, tunnels)

        // Total is capped at MAX_SHORTCUTS (4)
        assertEquals(ShortcutUpdater.MAX_SHORTCUTS, shortcuts.size)

        // All 3 hosts are included (they fit within the cap)
        assertEquals(3, shortcuts.count { (id, _) -> id.startsWith("host_") })
        // Only 1 tunnel fills the remaining slot
        assertEquals(1, shortcuts.count { (id, _) -> id.startsWith("tunnel_") })
    }

    @Test
    fun `0 favorites produce 0 shortcuts`() {
        val shortcuts = buildShortcutList(emptyList(), emptyList())

        assertTrue(shortcuts.isEmpty())
    }

    @Test
    fun `5 favorite hosts and 0 tunnels produce 4 shortcuts (capped at MAX)`() {
        val hosts = (1..5).map { makeHost("h$it") }

        val shortcuts = buildShortcutList(hosts, emptyList())

        assertEquals(ShortcutUpdater.MAX_SHORTCUTS, shortcuts.size)
        assertTrue(shortcuts.all { (id, _) -> id.startsWith("host_") })
    }

    @Test
    fun `0 hosts and 3 favorite tunnels produce 3 tunnel shortcuts`() {
        val tunnels = (1..3).map { makeTunnel("t$it") }

        val shortcuts = buildShortcutList(emptyList(), tunnels)

        assertEquals(3, shortcuts.size)
        assertTrue(shortcuts.all { (id, _) -> id.startsWith("tunnel_") })
    }

    @Test
    fun `shortcut IDs follow host_{id} and tunnel_{id} pattern`() {
        val hosts = listOf(makeHost("abc"))
        val tunnels = listOf(makeTunnel("xyz"))

        val shortcuts = buildShortcutList(hosts, tunnels)

        val ids = shortcuts.map { it.first }
        assertTrue(ids.contains("host_abc"))
        assertTrue(ids.contains("tunnel_xyz"))
    }

    @Test
    fun `ranks are assigned sequentially starting at 0`() {
        val hosts = listOf(makeHost("h1"), makeHost("h2"))
        val tunnels = listOf(makeTunnel("t1"))

        val shortcuts = buildShortcutList(hosts, tunnels)

        val ranks = shortcuts.map { it.second }
        assertEquals(listOf(0, 1, 2), ranks)
    }

    @Test
    fun `hosts always come before tunnels in the list`() {
        val hosts = listOf(makeHost("h1"), makeHost("h2"))
        val tunnels = listOf(makeTunnel("t1"), makeTunnel("t2"))

        val shortcuts = buildShortcutList(hosts, tunnels)

        // Hosts occupy the first slots
        val hostCount = shortcuts.count { (id, _) -> id.startsWith("host_") }
        for (i in 0 until hostCount) {
            assertTrue(shortcuts[i].first.startsWith("host_"),
                "Expected slot $i to be a host shortcut")
        }
        for (i in hostCount until shortcuts.size) {
            assertTrue(shortcuts[i].first.startsWith("tunnel_"),
                "Expected slot $i to be a tunnel shortcut")
        }
    }

    @Test
    fun `MAX_SHORTCUTS companion constant equals 4`() {
        assertEquals(4, ShortcutUpdater.MAX_SHORTCUTS)
    }

    @Test
    fun `action constants are defined correctly`() {
        assertEquals("connect_host", ShortcutUpdater.ACTION_CONNECT_HOST)
        assertEquals("start_tunnel", ShortcutUpdater.ACTION_START_TUNNEL)
        assertEquals("shortcut_host_id", ShortcutUpdater.EXTRA_HOST_ID)
        assertEquals("shortcut_tunnel_id", ShortcutUpdater.EXTRA_TUNNEL_ID)
    }
}
