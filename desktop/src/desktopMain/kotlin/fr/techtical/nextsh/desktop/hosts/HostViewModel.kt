// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostViewModel(
    folderRepository: DesktopHostFolderRepository = DesktopContainer.hostFolderRepository,
    sessionManager: DesktopSessionManager = DesktopContainer.sessionManager,
    private val hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val appScope: AppScope = DesktopContainer.appScope,
) {
    /** Hôtes regroupés par folder, triés alphabétiquement. */
    val foldersWithHosts: StateFlow<List<HostFolderWithHosts>> = folderRepository
        .observeAllWithHosts()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    /**
     * Ensemble des hostIds avec au moins une session active (SSH ou SFTP).
     * Utilisé pour dériver l'online dot sur chaque HostCard.
     */
    val activeHostIds: StateFlow<Set<String>> = sessionManager.tabs
        .map { tabs -> tabs.map { it.host.id }.toSet() }
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptySet())

    /** Supprime un hôte (soft-delete CRDT, reversible via sync). */
    fun deleteHost(id: String) {
        appScope.coroutineScope.launch {
            hostRepository.delete(id)
        }
    }
}
