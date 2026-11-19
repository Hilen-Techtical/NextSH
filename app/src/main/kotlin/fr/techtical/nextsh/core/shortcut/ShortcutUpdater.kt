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
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the favourite hosts and tunnels as launcher shortcuts.
 *
 * Every shortcut intent carries the token of this install ([ShortcutTokenStore]): the activity is
 * exported, so it acts on a shortcut only when the token matches (see [readShortcutRequest]).
 * The shortcuts are republished at every start, which gives a shortcut published before the token
 * existed its new intent. `setDynamicShortcuts` updates the pinned copies (home screen) that have
 * the same id as a dynamic one. The pinned shortcuts it does not reach (a favourite beyond
 * [MAX_SHORTCUTS], or a host or tunnel that is no longer a favourite) are rebuilt by id from the
 * repositories by [refreshPinned], or disabled if the host or tunnel is gone: with the old intent
 * they would silently do nothing.
 *
 * `setDynamicShortcuts` and `updateShortcuts` answer false when the system rate limits the app, which
 * happens in a background start. The refusal is remembered and [republishIfRefused] publishes again
 * when the app comes on screen, where the limit is reset.
 */
@Singleton
class ShortcutUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val tokens: ShortcutTokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One publication at a time: the favourites flow and a republish can overlap. */
    private val publishLock = Mutex()

    @Volatile private var latestFavourites: Pair<List<Host>, List<TunnelConfig>>? = null

    /** True when the last publication was refused by the rate limit. */
    @Volatile private var refused = false

    fun startObserving() {
        scope.launch {
            combine(
                hostRepository.observeFavorites(),
                tunnelRepository.observeFavorites(),
            ) { hosts, tunnels -> hosts to tunnels }
                .distinctUntilChanged()
                .collectLatest { (hosts, tunnels) ->
                    latestFavourites = hosts to tunnels
                    publish(hosts, tunnels)
                }
        }
    }

    /**
     * Publishes again the favourites of the last publication if the system refused it (rate
     * limit). `MainActivity` calls it each time it comes on screen; it does nothing otherwise.
     */
    fun republishIfRefused() {
        if (!refused) return
        val (hosts, tunnels) = latestFavourites ?: return
        scope.launch { publish(hosts, tunnels) }
    }

    private suspend fun publish(hosts: List<Host>, tunnels: List<TunnelConfig>) {
        publishLock.withLock {
            try {
                val token = tokens.token()
                val selection = selectDynamicShortcuts(hosts, tunnels)
                var rank = 0
                val dynamic = selection.hosts.map { buildHostShortcut(it, rank++, token) } +
                    selection.tunnels.map { buildTunnelShortcut(it, rank++, token) }

                val published = ShortcutManagerCompat.setDynamicShortcuts(context, dynamic)
                val refreshed = refreshPinned(selection.shortcutIds.toSet(), rank, token)
                refused = !published || !refreshed
                if (refused) {
                    Timber.w("Shortcuts not published (rate limited): retried when the app is on screen")
                } else {
                    Timber.d("Dynamic shortcuts updated: %d hosts, %d tunnels",
                        selection.hosts.size,
                        selection.tunnels.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Failed to update dynamic shortcuts (${errorClass(e)})")
            }
        }
    }

    /**
     * Gives the pinned shortcuts that [publish] did not reach (see [pinnedToRefresh]) the intent
     * that carries the token. A host or tunnel that still exists, a favourite or not, is rebuilt by
     * its id. One that is gone gets its pinned shortcut disabled, with a short message: it would
     * otherwise stay on the home screen and do nothing. Returns false if the system refused the
     * update.
     */
    private suspend fun refreshPinned(dynamicIds: Set<String>, firstRank: Int, token: String): Boolean {
        val pinnedIds = ShortcutManagerCompat
            .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .filter { it.isEnabled }
            .map { it.id }
        val targets = pinnedToRefresh(pinnedIds, dynamicIds)
        if (targets.isEmpty()) return true

        var rank = firstRank
        val rebuilt = mutableListOf<ShortcutInfoCompat>()
        val gone = mutableListOf<String>()
        for (target in targets) {
            val shortcut = when (target) {
                is PinnedTarget.PinnedHost ->
                    hostRepository.getById(target.hostId)?.let { buildHostShortcut(it, rank++, token) }
                is PinnedTarget.PinnedTunnel ->
                    tunnelRepository.getById(target.tunnelId)?.let { buildTunnelShortcut(it, rank++, token) }
            }
            if (shortcut != null) rebuilt += shortcut else gone += target.shortcutId
        }
        val updated = rebuilt.isEmpty() || ShortcutManagerCompat.updateShortcuts(context, rebuilt)
        if (gone.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_disabled_message))
        }
        return updated
    }

    private fun buildHostShortcut(host: Host, rank: Int, token: String): ShortcutInfoCompat {
        val intent = hostIntent(Intent(context, MainActivity::class.java), host.id, token)

        return ShortcutInfoCompat.Builder(context, hostShortcutId(host.id))
            .setShortLabel(host.label)
            .setLongLabel(context.getString(R.string.shortcut_connect_host, host.label))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_host))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private fun buildTunnelShortcut(tunnel: TunnelConfig, rank: Int, token: String): ShortcutInfoCompat {
        val intent = tunnelIntent(Intent(context, MainActivity::class.java), tunnel.id, token)

        return ShortcutInfoCompat.Builder(context, tunnelShortcutId(tunnel.id))
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

        /** The token of this install, see [ShortcutTokenStore]. */
        const val EXTRA_SHORTCUT_TOKEN = "shortcut_token"
        const val ACTION_CONNECT_HOST = "connect_host"
        const val ACTION_START_TUNNEL = "start_tunnel"
        const val ACTION_OPEN_TERMINAL = "open_terminal"
        const val MAX_SHORTCUTS = 4

        /** Fills [base] as the intent of the shortcut of a host: what to do, for which host, and the token. */
        internal fun hostIntent(base: Intent, hostId: String, token: String): Intent = base.apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SHORTCUT_ACTION, ACTION_CONNECT_HOST)
            putExtra(EXTRA_HOST_ID, hostId)
            putExtra(EXTRA_SHORTCUT_TOKEN, token)
        }

        /** Fills [base] as the intent of the shortcut of a tunnel: what to do, for which tunnel, and the token. */
        internal fun tunnelIntent(base: Intent, tunnelId: String, token: String): Intent = base.apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SHORTCUT_ACTION, ACTION_START_TUNNEL)
            putExtra(EXTRA_TUNNEL_ID, tunnelId)
            putExtra(EXTRA_SHORTCUT_TOKEN, token)
        }

        /**
         * Fills [base] as the intent that opens the terminal on its tabs: what to do and the token, no
         * target. Not published as a launcher shortcut: it is the content intent of the notification
         * of the terminal service, which reaches the exported activity by the same checked path.
         */
        internal fun openTerminalIntent(base: Intent, token: String): Intent = base.apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SHORTCUT_ACTION, ACTION_OPEN_TERMINAL)
            putExtra(EXTRA_SHORTCUT_TOKEN, token)
        }
    }
}
