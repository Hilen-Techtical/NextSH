// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.CustomTerminalThemeDao
import fr.techtical.nextsh.data.db.dao.SnippetDao
import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.data.db.entity.CustomTerminalThemeEntity
import fr.techtical.nextsh.data.db.entity.SnippetEntity
import fr.techtical.nextsh.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.shared.core.backup.BackupCustomTheme
import fr.techtical.nextsh.shared.core.backup.BackupSnippet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restores the snippets and the custom terminal themes of a backup.
 *
 * Dedicated path, NOT the `save()` of the repositories: those restart the vector clock from "{}"
 * (and the theme one even reads the row without its tombstone), so a restore over an existing id
 * would carry a clock that does not dominate the peers' one and raise a false conflict at the next
 * sync. Same rule as `HostRepositoryImpl.save`:
 *  - the row is read tombstone included;
 *  - a live row that already holds exactly this content is left alone (no tick, nothing to sync);
 *  - otherwise the row is written revived, its clock ticked from the stored one, so the restored
 *    version wins on the peers.
 *
 * The callers run these inside the transaction of the import. Entries are already validated
 * (`BackupValidator`); nothing here logs.
 */
@Singleton
class BackupRestoreStore @Inject constructor(
    private val snippetDao: SnippetDao,
    private val themeDao: CustomTerminalThemeDao,
    private val hostDao: HostDao,
) {

    /**
     * Les hôtes locaux, tombstones compris (id, hostname et port suffisent à la règle de redirection
     * des empreintes). À lire AVANT l'écriture des hôtes du backup.
     */
    suspend fun localHostsIncludingDeleted(): List<Host> =
        hostDao.getAllIncludingDeleted().map { it.toDomain() }

    suspend fun restoreSnippets(snippets: List<BackupSnippet>) {
        for (item in snippets) {
            val domain = Snippet(
                id = item.id,
                label = item.label,
                command = item.command,
                category = item.category,
                hostId = item.hostId,
                createdAt = item.createdAt,
            )
            val existing = snippetDao.getByIdIncludingDeleted(domain.id)
            if (existing != null && !existing.deleted && existing.toDomain() == domain) continue
            val now = System.currentTimeMillis()
            snippetDao.upsert(
                SnippetEntity.fromDomain(domain).copy(
                    vectorClock = tickedClockJson(existing?.vectorClock ?: "{}", now),
                    updatedAt = now,
                    deleted = false,
                    deletedAt = null,
                ),
            )
        }
    }

    suspend fun restoreThemes(themes: List<BackupCustomTheme>) {
        for (item in themes) {
            val domain = CustomTerminalTheme(
                id = item.id,
                name = item.name,
                background = item.background,
                foreground = item.foreground,
                cursor = item.cursor,
                selectionBg = item.selectionBg,
                ansi = item.ansi,
                createdAt = item.createdAt,
            )
            val existing = themeDao.getByIdIncludingDeleted(domain.id)
            if (existing != null && !existing.deleted && existing.toDomain() == domain) continue
            val now = System.currentTimeMillis()
            themeDao.upsert(
                CustomTerminalThemeEntity.fromDomain(domain).copy(
                    vectorClock = tickedClockJson(existing?.vectorClock ?: "{}", now),
                    updatedAt = now,
                    deleted = false,
                    deletedAt = null,
                ),
            )
        }
    }
}
