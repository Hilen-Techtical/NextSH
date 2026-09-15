// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.MainActivity
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startObserving() {
        scope.launch {
            combine(
                hostRepository.observeFavorites(),
                tunnelRepository.observeFavorites(),
            ) { hosts, tunnels -> hosts to tunnels }
                .distinctUntilChanged()
                .collectLatest { (hosts, tunnels) ->
                    updateShortcuts(hosts, tunnels)
                }
        }
    }

    private fun updateShortcuts(hosts: List<Host>, tunnels: List<TunnelConfig>) {
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        var rank = 0

        for (host in hosts.take(MAX_SHORTCUTS)) {
            shortcuts.add(buildHostShortcut(host, rank++))
        }

        val remaining = MAX_SHORTCUTS - shortcuts.size
        for (tunnel in tunnels.take(remaining)) {
            shortcuts.add(buildTunnelShortcut(tunnel, rank++))
        }

        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            Timber.d("Dynamic shortcuts updated: %d hosts, %d tunnels",
                hosts.take(MAX_SHORTCUTS).size,
                tunnels.take(remaining.coerceAtLeast(0)).size)
        } catch (e: Exception) {
            Timber.w(e, "Failed to update dynamic shortcuts")
        }
    }

    private fun buildHostShortcut(host: Host, rank: Int): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SHORTCUT_ACTION, ACTION_CONNECT_HOST)
            putExtra(EXTRA_HOST_ID, host.id)
        }

        return ShortcutInfoCompat.Builder(context, "host_${host.id}")
            .setShortLabel(host.label)
            .setLongLabel(context.getString(R.string.shortcut_connect_host, host.label))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_host))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private fun buildTunnelShortcut(tunnel: TunnelConfig, rank: Int): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SHORTCUT_ACTION, ACTION_START_TUNNEL)
            putExtra(EXTRA_TUNNEL_ID, tunnel.id)
        }

        return ShortcutInfoCompat.Builder(context, "tunnel_${tunnel.id}")
            .setShortLabel(tunnel.label)
            .setLongLabel(context.getString(R.string.shortcut_start_tunnel, tunnel.label))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_tunnel))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    companion object {
        const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
        const val EXTRA_HOST_ID = "shortcut_host_id"
        const val EXTRA_TUNNEL_ID = "shortcut_tunnel_id"
        const val ACTION_CONNECT_HOST = "connect_host"
        const val ACTION_START_TUNNEL = "start_tunnel"
        const val MAX_SHORTCUTS = 4
    }
}
