// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.scope.ScreenViewModel
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "HostViewModel"

class HostViewModel(
    folderRepository: DesktopHostFolderRepository = DesktopContainer.hostFolderRepository,
    sessionManager: DesktopSessionManager = DesktopContainer.sessionManager,
    private val hostRepository: HostRepository = DesktopContainer.hostRepository,
    appScope: AppScope = DesktopContainer.appScope,
    private val tunnelService: DesktopTunnelService = DesktopContainer.tunnelService,
) : ScreenViewModel(appScope) {
    /** Hôtes regroupés par folder, triés alphabétiquement. */
    val foldersWithHosts: StateFlow<List<HostFolderWithHosts>> = folderRepository
        .observeAllWithHosts()
        .stateIn(screenScope, SharingStarted.Eagerly, emptyList())

    /**
     * Ensemble des hostIds avec au moins une session active (SSH ou SFTP).
     * Utilisé pour dériver l'online dot sur chaque HostCard.
     */
    val activeHostIds: StateFlow<Set<String>> = sessionManager.tabs
        .map { tabs -> tabs.map { it.host.id }.toSet() }
        .stateIn(screenScope, SharingStarted.Eagerly, emptySet())

    /**
     * Supprime un hôte et ses tunnels (soft-delete CRDT, propagé par la sync). Les tunnels de l'hôte qui
     * tournent sont arrêtés d'abord : la suppression ne touche que la base et les laisserait actifs, sans
     * ligne dans la liste.
     */
    fun deleteHost(id: String) {
        appScope.coroutineScope.launch {
            try {
                tunnelService.stopTunnelsOfHost(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stopping is best effort: the delete goes on. Class name only in the log.
                Logger.e(TAG, "deleteHost: stopping the host tunnels failed (${errorClass(e)})")
            }
            hostRepository.delete(id)
        }
    }
}
