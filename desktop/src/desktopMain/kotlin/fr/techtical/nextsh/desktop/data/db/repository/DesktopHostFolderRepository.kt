// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Service de regroupement des hôtes par `host.group` pour la sidebar.
 * Pas de table SQLite dédiée : la source de vérité est `host.group`
 * (chaîne libre éditable depuis `HostDetailScreen`), pour rester
 * cohérent avec la version Android Solo. La table `host_folders` n'est
 * pas (encore) introduite ; un enrichissement futur (icône/tag par
 * folder, mode Équipe) viendra l'attacher.
 */
class DesktopHostFolderRepository(private val db: NextShDatabase) {

    private val hostQ get() = db.hostQueries

    fun observeAllGroups(): Flow<List<String>> =
        hostQ.selectGroups().asFlow().mapToList(Dispatchers.IO).map { groups ->
            groups.mapNotNull { it.takeIf(String::isNotBlank) }
        }

    /**
     * Observe les hôtes regroupés par `host.group`. Le bucket
     * `folder == null` agrège les hôtes sans `group` ; il n'apparaît que
     * s'il a du contenu. Folders triés alphabétiquement par nom de
     * groupe, hosts triés par label.
     */
    fun observeAllWithHosts(): Flow<List<HostFolderWithHosts>> =
        hostQ.selectAll().asFlow().mapToList(Dispatchers.IO).map { hostRows ->
            // `lastConnectedAt` n'est pas exposé par le domain `Host`
            // (champ Desktop-only) : on le lit ici directement depuis
            // les rows SQLDelight et on l'expose via une map parallèle.
            val lastConnected: Map<String, Long> = hostRows
                .mapNotNull { row -> row.lastConnectedAt?.let { row.id to it } }
                .toMap()
            val byGroup = hostRows.groupBy { it.group?.takeIf(String::isNotBlank) }
            val orphan = byGroup[null].orEmpty()
                .map(::toHost)
                .sortedBy { it.label }
                .let { hosts ->
                    if (hosts.isEmpty()) null
                    else HostFolderWithHosts(
                        folder = null,
                        hosts = hosts,
                        lastConnectedByHostId = lastConnected,
                    )
                }
            val foldersWithHosts = byGroup
                .filterKeys { it != null }
                .toSortedMap(compareBy { it ?: "" })
                .map { (groupName, rows) ->
                    HostFolderWithHosts(
                        folder = HostFolder(name = groupName!!),
                        hosts = rows.map(::toHost).sortedBy { it.label },
                        lastConnectedByHostId = lastConnected,
                    )
                }
            listOfNotNull(orphan) + foldersWithHosts
        }

    private fun toHost(row: fr.techtical.nextsh.desktop.db.Hosts): Host = Host(
        id = row.id,
        label = row.label,
        hostname = row.hostname,
        port = row.port.toInt(),
        username = row.username,
        authType = AuthType.valueOf(row.authType),
        credentialId = row.credentialId,
        group = row.group,
        keepAliveSeconds = row.keepAliveSeconds.toInt(),
        autoReconnect = row.autoReconnect == 1L,
        terminalTheme = row.terminalTheme,
        fido2Mode = row.fido2Mode?.let { Fido2Mode.valueOf(it) },
        isFavorite = row.isFavorite == 1L,
    )
}
