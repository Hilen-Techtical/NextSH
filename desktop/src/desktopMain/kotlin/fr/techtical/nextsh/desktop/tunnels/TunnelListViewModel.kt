// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.ui.browser.DesktopBrowserSessionHolder
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ViewModel de la liste des tunnels Desktop.
 *
 * Source de vérité pour l'état runtime : [DesktopTunnelService.tunnelStates].
 * Ce VM expose aussi la liste persistée (`tunnels` repo) + hôtes pour afficher
 * les infos de rattachement.
 *
 * Les actions utilisateur (start/stop/startAll/stopAll) sont déléguées au
 * service : le VM ne fait que dispatcher dans le scope de l'app.
 */
class TunnelListViewModel(
    tunnelRepository: TunnelRepository = DesktopContainer.tunnelRepository,
    hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val tunnelService: DesktopTunnelService = DesktopContainer.tunnelService,
    private val browserSessionHolder: DesktopBrowserSessionHolder = DesktopContainer.browserSessionHolder,
    private val appScope: AppScope = DesktopContainer.appScope,
) {
    val tunnels: StateFlow<List<TunnelConfig>> = tunnelRepository
        .observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    val hosts: StateFlow<List<Host>> = hostRepository
        .observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    /** Map tunnelId → TunnelState. ACTIVE / STARTING / RECONNECTING / ERROR / STOPPED. */
    val tunnelStates: StateFlow<Map<String, TunnelState>> = tunnelService.tunnelStates

    /** Map tunnelId → RTT en ms, mesuré par le watchdog keepalive. Null = pas encore mesuré / tunnel perdu. */
    val latenciesByTunnel: StateFlow<Map<String, Long?>> = DesktopContainer.tunnelManager.latenciesByTunnel

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // Tracks the last known status per tunnel to detect STOPPED/STARTING/ERROR → ACTIVE
    // transitions without re-triggering on subsequent ACTIVE emissions.
    private val lastKnownStatus = ConcurrentHashMap<String, TunnelStatus>()

    init {
        // Watch for STOPPED/STARTING/ERROR → ACTIVE transitions on LOCAL_FORWARD tunnels
        // that have openBrowserOnConnect=true, and open the in-app browser automatically.
        //
        // Guards:
        // - `hasSession(id)` avoids double-show when the overlay is already up.
        // - `isUserDismissed(id)` avoids re-popping the browser on every
        //   RECONNECTING → ACTIVE cycle after the user explicitly closed it with
        //   keepAliveAfterBrowserClose=true. STOPPED clears that flag so a fresh
        //   start can still auto-open.
        appScope.coroutineScope.launch {
            tunnelStates.collect { states ->
                states.forEach { (id, state) ->
                    val prev = lastKnownStatus[id]
                    val isNowActive = state.status == TunnelStatus.ACTIVE
                    val wasNotActive = prev != TunnelStatus.ACTIVE
                    if (state.status == TunnelStatus.STOPPED) {
                        browserSessionHolder.clearUserDismissed(id)
                    }
                    if (isNowActive && wasNotActive
                        && state.config.type == TunnelType.LOCAL_FORWARD
                        && state.config.openBrowserOnConnect
                        && !browserSessionHolder.hasSession(id)
                        && !browserSessionHolder.isUserDismissed(id)
                    ) {
                        browserSessionHolder.show(id)
                    }
                    lastKnownStatus[id] = state.status
                }
                // Drop entries for removed tunnels so a future restart re-triggers.
                lastKnownStatus.keys.retainAll(states.keys)
            }
        }
    }

    fun toggle(config: TunnelConfig) {
        val state = tunnelStates.value[config.id]?.status
        val running = state == TunnelStatus.ACTIVE
            || state == TunnelStatus.STARTING
            || state == TunnelStatus.RECONNECTING
        appScope.coroutineScope.launch {
            val result = if (running) tunnelService.stopTunnel(config.id)
            else tunnelService.startTunnel(config)
            if (result is SshResult.Error) _errors.tryEmit(result.message)
        }
    }

    fun startAll() {
        appScope.coroutineScope.launch {
            val results = tunnelService.startAllTunnels()
            results.forEach { (_, result) ->
                if (result is SshResult.Error) _errors.tryEmit(result.message)
            }
        }
    }

    fun stopAll() {
        appScope.coroutineScope.launch { tunnelService.stopAllTunnels() }
    }
}
