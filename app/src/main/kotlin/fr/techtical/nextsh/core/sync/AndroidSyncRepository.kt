// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.repository.OrphanTunnelRepair
import fr.techtical.nextsh.shared.core.sync.ApplyResult
import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.randomUuid
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSyncRepository @Inject constructor(
    private val hostRepo: HostRepository,
    private val tunnelRepo: TunnelRepository,
    private val sshKeyRepo: SshKeyRepository,
    private val snippetRepo: SnippetRepository,
    private val customThemeRepo: CustomTerminalThemeRepository,
    private val pendingConflictRepository: PendingConflictRepository,
    private val orphanTunnelRepair: OrphanTunnelRepair,
    private val transactionRunner: TransactionRunner,
    /** The wipe of the vault: a pull that is still in flight writes nothing once the app is closed. */
    private val wipeGate: VaultWipeGate,
) : SyncRepository {

    /** Without the wipe gate of the app (tests). Not an injection point. */
    constructor(
        hostRepo: HostRepository,
        tunnelRepo: TunnelRepository,
        sshKeyRepo: SshKeyRepository,
        snippetRepo: SnippetRepository,
        customThemeRepo: CustomTerminalThemeRepository,
        pendingConflictRepository: PendingConflictRepository,
        orphanTunnelRepair: OrphanTunnelRepair,
        transactionRunner: TransactionRunner,
    ) : this(
        hostRepo, tunnelRepo, sshKeyRepo, snippetRepo, customThemeRepo, pendingConflictRepository,
        orphanTunnelRepair, transactionRunner, VaultWipeGate(),
    )

    override suspend fun getAllLocalAsBundle(): SyncBundle = SyncBundle(
        hosts = hostRepo.getAllSyncEntries(),
        tunnels = tunnelRepo.getAllSyncEntries(),
        sshKeys = sshKeyRepo.getAllSyncEntries(),
        snippets = snippetRepo.getAllSyncEntries(),
        customThemes = customThemeRepo.getAllSyncEntries(),
    )

    override suspend fun applyRemoteBundle(bundle: SyncBundle): ApplyResult {
        val conflicts = mutableListOf<MergeResult.Conflict<*>>()
        var clean = 0
        if (wipeGate.isClosed()) return stoppedByWipe(clean, conflicts)

        val localHosts = hostRepo.getAllSyncEntries().associateBy { it.id }
        // Ids that have a row in hosts once the hosts of this bundle are applied, tombstones
        // included (a tombstoned row satisfies the foreign key of tunnels.hostId). A remote
        // tombstone for an unknown host writes no row, so it does not add to this set.
        val knownHostIds = HashSet<String>(localHosts.keys)
        for (remote in bundle.hosts) {
            when (val merged = CrdtEngine.merge(localHosts[remote.id], remote)) {
                is MergeResult.Clean -> {
                    hostRepo.upsertSyncEntry(merged.entry)
                    if (merged.entry.payload != null) knownHostIds.add(merged.entry.id)
                    pendingConflictRepository.deleteByEntity(SyncableEntityType.HOST, remote.id)
                    clean++
                }
                is MergeResult.Conflict -> {
                    conflicts.add(merged)
                    mergeAndPersist(SyncableEntityType.HOST, remote.id, merged)
                }
                null -> Unit
            }
        }

        if (wipeGate.isClosed()) return stoppedByWipe(clean, conflicts)
        val localTunnels = tunnelRepo.getAllSyncEntries().associateBy { it.id }
        var skippedOrphanTunnels = 0
        for (remote in bundle.tunnels) {
            when (val merged = CrdtEngine.merge(localTunnels[remote.id], remote)) {
                is MergeResult.Clean -> {
                    // A live tunnel whose host has no row would fail the foreign key and abort
                    // the whole sync, e.g. a device paired after the host was deleted elsewhere.
                    // Skip it (a tombstone has no payload and is never skipped).
                    val hostId = merged.entry.payload?.hostId
                    if (hostId != null && hostId !in knownHostIds) {
                        skippedOrphanTunnels++
                        continue
                    }
                    tunnelRepo.upsertSyncEntry(merged.entry)
                    pendingConflictRepository.deleteByEntity(SyncableEntityType.TUNNEL, remote.id)
                    clean++
                }
                is MergeResult.Conflict -> {
                    conflicts.add(merged)
                    mergeAndPersist(SyncableEntityType.TUNNEL, remote.id, merged)
                }
                null -> Unit
            }
        }

        if (skippedOrphanTunnels > 0) {
            // Count only: no id, label or host, nothing from the decrypted bundle.
            Timber.w("applyRemoteBundle: skipped $skippedOrphanTunnels tunnel(s) whose host is unknown")
        }

        if (wipeGate.isClosed()) return stoppedByWipe(clean, conflicts)
        val localKeys = sshKeyRepo.getAllSyncEntries().associateBy { it.id }
        for (remote in bundle.sshKeys) {
            when (val merged = CrdtEngine.merge(localKeys[remote.id], remote)) {
                is MergeResult.Clean -> {
                    sshKeyRepo.upsertSyncEntry(merged.entry)
                    pendingConflictRepository.deleteByEntity(SyncableEntityType.SSH_KEY, remote.id)
                    clean++
                }
                is MergeResult.Conflict -> {
                    conflicts.add(merged)
                    mergeAndPersist(SyncableEntityType.SSH_KEY, remote.id, merged)
                }
                null -> Unit
            }
        }

        if (wipeGate.isClosed()) return stoppedByWipe(clean, conflicts)
        val localSnippets = snippetRepo.getAllSyncEntries().associateBy { it.id }
        for (remote in bundle.snippets) {
            when (val merged = CrdtEngine.merge(localSnippets[remote.id], remote)) {
                is MergeResult.Clean -> {
                    snippetRepo.upsertSyncEntry(merged.entry)
                    pendingConflictRepository.deleteByEntity(SyncableEntityType.SNIPPET, remote.id)
                    clean++
                }
                is MergeResult.Conflict -> {
                    conflicts.add(merged)
                    mergeAndPersist(SyncableEntityType.SNIPPET, remote.id, merged)
                }
                null -> Unit
            }
        }

        if (wipeGate.isClosed()) return stoppedByWipe(clean, conflicts)
        val localThemes = customThemeRepo.getAllSyncEntries().associateBy { it.id }
        for (remote in bundle.customThemes) {
            when (val merged = CrdtEngine.merge(localThemes[remote.id], remote)) {
                is MergeResult.Clean -> {
                    customThemeRepo.upsertSyncEntry(merged.entry)
                    pendingConflictRepository.deleteByEntity(SyncableEntityType.CUSTOM_TERMINAL_THEME, remote.id)
                    clean++
                }
                is MergeResult.Conflict -> {
                    conflicts.add(merged)
                    mergeAndPersist(SyncableEntityType.CUSTOM_TERMINAL_THEME, remote.id, merged)
                }
                null -> Unit
            }
        }

        // The bundle may have left live tunnels under a host this device holds as a tombstone
        // (the foreign key accepts it, see knownHostIds). Delete them now: the push that follows
        // in the same sync cycle carries the tombstones to the peers. A failure here must not
        // turn a pull that succeeded into an error.
        orphanTunnelRepair.runSafely()

        return ApplyResult(clean, conflicts)
    }

    override fun deviceId(): String = DeviceIdentity.deviceId()

    /** The pull stops where it is: the app is being wiped, nothing more is written to the database. */
    private fun stoppedByWipe(clean: Int, conflicts: List<MergeResult.Conflict<*>>): ApplyResult {
        Timber.w("applyRemoteBundle: stopped, the app is being wiped")
        return ApplyResult(clean, conflicts)
    }

    private suspend fun mergeAndPersist(
        entityType: SyncableEntityType,
        entityId: String,
        conflict: MergeResult.Conflict<*>,
    ) {
        // Encode first: a failure here must not happen between the delete and the save.
        val record = PendingConflict(
            id = randomUuid(),
            entityType = entityType,
            entityId = entityId,
            localJson = ConflictSerializer.encode(entityType, conflict.local),
            remoteJson = ConflictSerializer.encode(entityType, conflict.remote),
            detectedAt = System.currentTimeMillis(),
        )
        // Delete then save as one transaction: a conflict is re-detected by replacing its
        // marker, and without the transaction a reader could land between the two writes and
        // see no marker. OrphanTunnelRepair spares the tunnels of a host only while its HOST
        // marker exists, so that gap would let it delete them for a host that is still in
        // conflict.
        transactionRunner.run {
            pendingConflictRepository.deleteByEntity(entityType, entityId)
            pendingConflictRepository.save(record)
        }
    }
}
