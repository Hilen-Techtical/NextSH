// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig

private const val HOST_PREFIX = "host_"
private const val TUNNEL_PREFIX = "tunnel_"

/** The id of the shortcut of a host: what the launcher knows it by, and what a pinned copy keeps. */
internal fun hostShortcutId(hostId: String): String = HOST_PREFIX + hostId

/** The id of the shortcut of a tunnel, see [hostShortcutId]. */
internal fun tunnelShortcutId(tunnelId: String): String = TUNNEL_PREFIX + tunnelId

/**
 * The favourites that [ShortcutUpdater] publishes as dynamic shortcuts: [hosts] first, then
 * [tunnels]. The rank of a shortcut is its place in [shortcutIds].
 */
internal data class DynamicSelection(val hosts: List<Host>, val tunnels: List<TunnelConfig>) {
    val shortcutIds: List<String>
        get() = hosts.map { hostShortcutId(it.id) } + tunnels.map { tunnelShortcutId(it.id) }
}

/**
 * Which favourites become dynamic shortcuts: the hosts fill the slots first, up to [max], and the
 * tunnels take what is left. The others are not published as dynamic shortcuts; a copy of one of
 * them that the user pinned is reached by [pinnedToRefresh].
 */
internal fun selectDynamicShortcuts(
    hosts: List<Host>,
    tunnels: List<TunnelConfig>,
    max: Int = ShortcutUpdater.MAX_SHORTCUTS,
): DynamicSelection {
    val selectedHosts = hosts.take(max)
    val selectedTunnels = tunnels.take((max - selectedHosts.size).coerceAtLeast(0))
    return DynamicSelection(selectedHosts, selectedTunnels)
}

/** The host or the tunnel that a pinned shortcut was published for, read from its id. */
internal sealed interface PinnedTarget {
    val shortcutId: String

    data class PinnedHost(val hostId: String) : PinnedTarget {
        override val shortcutId: String get() = hostShortcutId(hostId)
    }

    data class PinnedTunnel(val tunnelId: String) : PinnedTarget {
        override val shortcutId: String get() = tunnelShortcutId(tunnelId)
    }
}

/** The target of the shortcut [shortcutId], or null if it is not one of ours. */
internal fun parseShortcutId(shortcutId: String): PinnedTarget? = when {
    shortcutId.startsWith(HOST_PREFIX) && shortcutId.length > HOST_PREFIX.length ->
        PinnedTarget.PinnedHost(shortcutId.removePrefix(HOST_PREFIX))
    shortcutId.startsWith(TUNNEL_PREFIX) && shortcutId.length > TUNNEL_PREFIX.length ->
        PinnedTarget.PinnedTunnel(shortcutId.removePrefix(TUNNEL_PREFIX))
    else -> null
}

/**
 * The pinned shortcuts (home screen) that publishing the dynamic ones does not reach: `setDynamicShortcuts`
 * only updates the pinned copies of the ids it publishes. Those left are a favourite beyond the
 * dynamic slots, or a host or tunnel that is no longer a favourite: their intent has no token, so
 * they would do nothing until they are rebuilt, or disabled if the host or tunnel is gone.
 *
 * [pinnedIds] are the ids of the shortcuts the user pinned, [dynamicIds] the ones just published.
 * An id that is not one of ours is left alone.
 */
internal fun pinnedToRefresh(pinnedIds: Collection<String>, dynamicIds: Set<String>): List<PinnedTarget> =
    pinnedIds.asSequence()
        .filter { it !in dynamicIds }
        .mapNotNull(::parseShortcutId)
        .distinct()
        .toList()
