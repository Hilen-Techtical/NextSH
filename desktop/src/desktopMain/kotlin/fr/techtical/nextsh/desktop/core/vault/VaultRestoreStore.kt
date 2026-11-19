// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.ssh.StoredKnownHost
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig

/**
 * What a restore did: [skippedTunnels] tunnels were left out because their host is not available (see
 * [VaultRestoreStore]), the others were written. The known-host counters come from the insertion-only
 * import: [knownHostsAdded] new host:port, [knownHostsAlreadyKnown] same key already stored, and
 * [knownHostConflicts] the host:port whose stored key differs (the stored key was kept).
 */
data class VaultRestoreResult(
    val restoredTunnels: Int,
    val skippedTunnels: Int,
    val knownHostsAdded: Int = 0,
    val knownHostsAlreadyKnown: Int = 0,
    val knownHostConflicts: List<String> = emptyList(),
    /** host:port of the fingerprints held back: first fingerprint of the address of a host already on this device. */
    val knownHostsHeld: List<String> = emptyList(),
)

/**
 * The database half of a backup restore: writes custom themes, snippets, hosts, SSH keys and tunnels, then
 * the known hosts. Themes come before hosts (a host references its theme by id, so the state is consistent
 * at every moment), and hosts before tunnels (a tunnel references its host).
 *
 * Merge semantics, backup wins: an item that already exists (live or tombstoned) is updated in place with
 * the backup content, revived and given a ticked clock; a live item that already holds exactly the backup
 * content is left untouched; a new item is inserted; local items that are not in the backup are left alone.
 * Snippets and themes follow the same rule through a dedicated restore path (the clock is ticked from the
 * one stored for the id, tombstones included), never through their `save`.
 *
 * Known hosts are the exception: INSERTION ONLY, a different local key is kept (a known host is a trust
 * decision, see `DesktopKnownHostsStore.importMissing`).
 *
 * A tunnel is only written when its host is available once the hosts of the backup are written, that is when
 * the host is live (in the backup, or live on this device). Otherwise the tunnel is skipped and counted in
 * [VaultRestoreResult.skippedTunnels]. The check belongs to the store, and to its transaction, so that a host
 * deleted at the wrong moment cannot turn into a foreign key error half way through a restore.
 *
 * The application wires `DesktopVaultRestoreStore`, which does all of it in ONE SQL transaction.
 */
interface VaultRestoreStore {
    /**
     * The LIVE local rows (not deleted) among the SSH key [ids], by id. The importer reads them BEFORE
     * [restore] to apply the "add only" rules of the keys (`BackupKeyRules`): [restore] then receives the
     * rows already decided. The default (no local key) suits a store that holds no key of its own.
     */
    suspend fun liveSshKeys(ids: Collection<String>): Map<String, SshKey> = emptyMap()

    suspend fun restore(
        hosts: List<Host>,
        sshKeys: List<SshKey>,
        tunnels: List<TunnelConfig>,
        snippets: List<Snippet> = emptyList(),
        customThemes: List<CustomTerminalTheme> = emptyList(),
        knownHosts: List<StoredKnownHost> = emptyList(),
    ): VaultRestoreResult
}
