// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.scope.ScreenViewModel
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.service.StoppedTunnel
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
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

/**
 * ViewModel de la liste des tunnels Desktop.
 *
 * Source de vérité pour l'état runtime : [DesktopTunnelService.tunnelStates].
 * Ce VM expose aussi la liste persistée (`tunnels` repo) + hôtes pour afficher
 * les infos de rattachement.
 *
 * Les actions utilisateur (start/stop/startAll/stopAll) sont déléguées au
 * service : le VM ne fait que dispatcher dans le scope de l'app, pour qu'elles
 * aillent au bout même si l'écran est quitté juste après. Les observateurs
 * de la base (`tunnels`, `hosts`) vivent dans le scope de l'écran.
 *
 * L'ouverture automatique du navigateur n'est pas ici : elle est dans
 * [DesktopTunnelService], quel que soit l'écran affiché.
 */
class TunnelListViewModel(
    tunnelRepository: TunnelRepository = DesktopContainer.tunnelRepository,
    hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val tunnelService: DesktopTunnelService = DesktopContainer.tunnelService,
    /** Map tunnelId → RTT en ms, mesuré par le watchdog keepalive. Null = pas encore mesuré / tunnel perdu. */
    val latenciesByTunnel: StateFlow<Map<String, Long?>> = DesktopContainer.tunnelManager.latenciesByTunnel,
    appScope: AppScope = DesktopContainer.appScope,
) : ScreenViewModel(appScope) {
    val tunnels: StateFlow<List<TunnelConfig>> = tunnelRepository
        .observeAll()
        .stateIn(screenScope, SharingStarted.Eagerly, emptyList())

    val hosts: StateFlow<List<Host>> = hostRepository
        .observeAll()
        .stateIn(screenScope, SharingStarted.Eagerly, emptyList())

    /** Map tunnelId → TunnelState. ACTIVE / STARTING / RECONNECTING / ERROR / STOPPED. */
    val tunnelStates: StateFlow<Map<String, TunnelState>> = tunnelService.tunnelStates

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Tunnels that were stopped because they, or their host, were deleted on another device, until the
     * user dismisses the banner. Held by the service: it must still be there when the screen opens after
     * the stop.
     */
    val deletionNotice: StateFlow<List<StoppedTunnel>> = tunnelService.deletionNotice

    /** Closes the banner for the tunnels it was [showing]: one stopped since then keeps its notice. */
    fun dismissDeletionNotice(showing: List<StoppedTunnel>) =
        tunnelService.dismissDeletionNotice(showing.map { it.id })

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
