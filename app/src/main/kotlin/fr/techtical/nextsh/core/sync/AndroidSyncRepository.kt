// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

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
) : SyncRepository {

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

        val localHosts = hostRepo.getAllSyncEntries().associateBy { it.id }
        for (remote in bundle.hosts) {
            when (val merged = CrdtEngine.merge(localHosts[remote.id], remote)) {
                is MergeResult.Clean -> {
                    hostRepo.upsertSyncEntry(merged.entry)
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

        val localTunnels = tunnelRepo.getAllSyncEntries().associateBy { it.id }
        for (remote in bundle.tunnels) {
            when (val merged = CrdtEngine.merge(localTunnels[remote.id], remote)) {
                is MergeResult.Clean -> {
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

        return ApplyResult(clean, conflicts)
    }

    override fun deviceId(): String = DeviceIdentity.deviceId()

    private suspend fun mergeAndPersist(
        entityType: SyncableEntityType,
        entityId: String,
        conflict: MergeResult.Conflict<*>,
    ) {
        pendingConflictRepository.deleteByEntity(entityType, entityId)
        val record = PendingConflict(
            id = randomUuid(),
            entityType = entityType,
            entityId = entityId,
            localJson = ConflictSerializer.encode(entityType, conflict.local),
            remoteJson = ConflictSerializer.encode(entityType, conflict.remote),
            detectedAt = System.currentTimeMillis(),
        )
        pendingConflictRepository.save(record)
    }
}
