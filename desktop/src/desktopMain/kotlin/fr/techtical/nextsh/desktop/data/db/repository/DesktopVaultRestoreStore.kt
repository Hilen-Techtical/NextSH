// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.ssh.StoredKnownHost
import fr.techtical.nextsh.desktop.core.vault.VaultRestoreResult
import fr.techtical.nextsh.desktop.core.vault.VaultRestoreStore
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.backup.BackupTrustRules
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a whole restore in ONE SQL transaction: if any row fails (a constraint, a disk error) nothing is
 * kept, so a restore never leaves a half-merged database behind.
 *
 * It calls the repositories' non-suspend `saveInTransaction` bodies on a single thread, because a SQLDelight
 * transaction is bound to the thread that opened it: going through the suspend `save` functions (each one
 * switches to `Dispatchers.IO`) would silently run the writes outside the transaction.
 *
 * Themes and snippets go through their own restore path (clock ticked from the stored one, tombstones
 * included), and the known hosts are inserted only, never replaced: see [VaultRestoreStore].
 *
 * The availability of a tunnel's host is checked INSIDE the transaction, after the hosts of the backup were
 * written: a host is available when it is live. So a host of the backup counts (it is revived or inserted
 * live by the write above), and so does a host that is live on this device.
 */
class DesktopVaultRestoreStore(private val db: NextShDatabase) : VaultRestoreStore {

    private val hostRepository = DesktopHostRepository(db)
    private val sshKeyRepository = DesktopSshKeyRepository(db)
    private val tunnelRepository = DesktopTunnelRepository(db)
    private val snippetRepository = DesktopSnippetRepository(db)
    private val themeRepository = DesktopCustomTerminalThemeRepository(db)
    private val knownHostsStore = DesktopKnownHostsStore(db)

    override suspend fun liveSshKeys(ids: Collection<String>): Map<String, SshKey> = withContext(Dispatchers.IO) {
        buildMap { ids.forEach { id -> sshKeyRepository.getById(id)?.let { put(id, it) } } }
    }

    override suspend fun restore(
        hosts: List<Host>,
        sshKeys: List<SshKey>,
        tunnels: List<TunnelConfig>,
        snippets: List<Snippet>,
        customThemes: List<CustomTerminalTheme>,
        knownHosts: List<StoredKnownHost>,
    ): VaultRestoreResult = withContext(Dispatchers.IO) {
        db.transactionWithResult {
            // BEFORE the hosts are written: the local hosts that share an id with the backup (live or
            // tombstoned): for those hosts the backup never brings the FIRST fingerprint of their address.
            val localHosts = hosts.mapNotNull { hostRepository.getByIdIncludingDeletedInTransaction(it.id) }
            val held = BackupTrustRules.heldDestinations(localHosts, hosts)

            // Themes first: a host references its theme by id.
            customThemes.forEach { themeRepository.restoreInTransaction(it) }
            snippets.forEach { snippetRepository.restoreInTransaction(it) }
            hosts.forEach { hostRepository.saveInTransaction(it) }
            sshKeys.forEach { sshKeyRepository.saveInTransaction(it) }
            var skipped = 0
            tunnels.forEach { tunnel ->
                if (hostRepository.existsLiveInTransaction(tunnel.hostId)) {
                    tunnelRepository.saveInTransaction(tunnel)
                } else {
                    skipped++
                }
            }
            // Insertion only, in the same transaction: a failure above or here leaves the trust store untouched.
            // For a host already on this device (same id) the file never brings the first fingerprint of its
            // address: otherwise a crafted file could approve a server for a host the user trusts elsewhere,
            // and a second import of the same file would approve what the first one moved.
            val knownHostsResult = knownHostsStore.importMissingInTransaction(knownHosts, held)
            VaultRestoreResult(
                restoredTunnels = tunnels.size - skipped,
                skippedTunnels = skipped,
                knownHostsAdded = knownHostsResult.added,
                knownHostsAlreadyKnown = knownHostsResult.alreadyKnown,
                knownHostConflicts = knownHostsResult.conflicts,
                knownHostsHeld = knownHostsResult.held,
            )
        }
    }
}
