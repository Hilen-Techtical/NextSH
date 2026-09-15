// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import fr.techtical.nextsh.shared.domain.model.Host

/**
 * Folder de regroupement d'hôtes pour la sidebar Desktop. La source de
 * vérité est `host.group` (chaîne libre éditable côté HostDetail). Il
 * n'y a pas (encore) de table dédiée. Les éventuels enrichissements
 * (icône / tag / couleur custom par folder) seront ajoutés en mode
 * Équipe via une table séparée optionnelle qui s'attachera à `group`.
 */
data class HostFolder(
    val name: String,
)

/**
 * DTO de jointure folder ↔ hosts pour la sidebar refondue. `hosts` est
 * trié par label. `folder == null` représente le bucket « Sans groupe »
 * pour les hôtes dont `host.group` est null ou vide.
 *
 * `lastConnectedByHostId` mappe `host.id` → timestamp ms de dernière
 * connexion (depuis `Hosts.lastConnectedAt`). Les hôtes sans connexion
 * antérieure sont absents de la map. Exposé en parallèle de `hosts`
 * pour ne pas casser les consommateurs existants qui itèrent sur
 * `List<Host>` (sidebar, etc.).
 */
data class HostFolderWithHosts(
    val folder: HostFolder?,
    val hosts: List<Host>,
    val lastConnectedByHostId: Map<String, Long> = emptyMap(),
)
