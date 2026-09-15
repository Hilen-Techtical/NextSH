// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ConflictResolutionViewModel"

class ConflictResolutionViewModel(
    private val pendingConflictRepository: PendingConflictRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val snippetRepository: SnippetRepository,
    private val customThemeRepository: CustomTerminalThemeRepository,
    private val syncScheduler: SyncScheduler,
    private val appScope: AppScope,
    private val credentialSyncRepository: CredentialSyncRepository,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val secretStore: EnrolledDeviceSecretStore,
) {
    val conflicts: StateFlow<List<PendingConflict>> = pendingConflictRepository.observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    /**
     * Stores the choice made for each conflict by conflict.id.
     * true = keep local version, false = take remote version.
     * Absent key means no decision has been made yet.
     */
    private val _resolution = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val resolution: StateFlow<Map<String, Boolean>> = _resolution

    fun keepLocal(conflict: PendingConflict) {
        _resolution.update { it + (conflict.id to true) }
    }

    fun keepRemote(conflict: PendingConflict) {
        _resolution.update { it + (conflict.id to false) }
    }

    /**
     * Ignores (deletes) a corrupt conflict without applying any version.
     */
    fun ignoreConflict(conflict: PendingConflict) {
        appScope.coroutineScope.launch {
            pendingConflictRepository.deleteById(conflict.id)
            Logger.d(TAG, "ignoreConflict: discarded corrupt conflict ${conflict.id}")
        }
    }

    /**
     * Applies all decisions in [_resolution] to the corresponding repositories.
     *
     * - keepLocal (true): just deletes the pending conflict, the local version is already
     *   in the DB. The next sync will propagate it to the peer.
     * - keepRemote (false): decodes the remote SyncEntry JSON and upserts it into the
     *   appropriate repository, then deletes the pending conflict.
     * - No decision: conflict is left pending.
     *
     * Returns the number of conflicts effectively resolved.
     */
    suspend fun applyAll(): Int {
        val currentConflicts = pendingConflictRepository.getAll()
        val currentResolutions = _resolution.value
        var resolved = 0

        for (conflict in currentConflicts) {
            val keepLocal = currentResolutions[conflict.id] ?: continue // no decision: skip

            if (keepLocal) {
                // Local version is already in DB: just remove the pending marker.
                pendingConflictRepository.deleteById(conflict.id)
                Logger.d(TAG, "applyAll: kept local for ${conflict.entityType}/${conflict.entityId}")
            } else {
                // Decode remote entry and upsert it.
                val applied = applyRemote(conflict)
                if (applied) {
                    pendingConflictRepository.deleteById(conflict.id)
                    Logger.d(TAG, "applyAll: applied remote for ${conflict.entityType}/${conflict.entityId}")
                } else {
                    Logger.w(TAG, "applyAll: failed to decode remote for ${conflict.entityType}/${conflict.entityId}, leaving pending")
                    continue
                }
            }
            resolved++
        }

        Logger.d(TAG, "applyAll: resolved=$resolved total=${currentConflicts.size}")
        return resolved
    }

    /**
     * Decodes the remote JSON from [conflict] and upserts the entry into the appropriate
     * repository. Returns true on success, false if JSON decoding fails.
     */
    private suspend fun applyRemote(conflict: PendingConflict): Boolean {
        return try {
            when (conflict.entityType) {
                SyncableEntityType.HOST -> {
                    val entry = ConflictSerializer.decodeHost(conflict.remoteJson)
                    hostRepository.upsertSyncEntry(entry)
                    true
                }
                SyncableEntityType.TUNNEL -> {
                    val entry = ConflictSerializer.decodeTunnel(conflict.remoteJson)
                    tunnelRepository.upsertSyncEntry(entry)
                    true
                }
                SyncableEntityType.SSH_KEY -> {
                    val entry = ConflictSerializer.decodeSshKey(conflict.remoteJson)
                    sshKeyRepository.upsertSyncEntry(entry)
                    true
                }
                SyncableEntityType.SNIPPET -> {
                    val entry = ConflictSerializer.decodeSnippet(conflict.remoteJson)
                    snippetRepository.upsertSyncEntry(entry)
                    true
                }
                SyncableEntityType.CUSTOM_TERMINAL_THEME -> {
                    val entry = ConflictSerializer.decodeCustomTerminalTheme(conflict.remoteJson)
                    customThemeRepository.upsertSyncEntry(entry)
                    true
                }
                SyncableEntityType.CREDENTIAL -> applyRemoteCredential(conflict)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "applyRemote: decode failed for ${conflict.entityType}/${conflict.entityId}: ${e.message}")
            false
        }
    }

    /** See Android counterpart: Solo LAN picks the sole enrolled peer. Phase 3 Team needs a sourceDeviceId in PendingConflict. */
    private suspend fun applyRemoteCredential(conflict: PendingConflict): Boolean {
        val entry = ConflictSerializer.decodeCredential(conflict.remoteJson)
        val device = enrolledDeviceRepository.getAll().firstOrNull()
        if (device == null) {
            Logger.w(TAG, "applyRemoteCredential: no enrolled device, cannot decrypt ${conflict.entityId}")
            return false
        }
        val secret = secretStore.retrieve(device.deviceId)
        if (secret == null) {
            Logger.w(TAG, "applyRemoteCredential: no secret stored for ${device.deviceId}")
            return false
        }
        return try {
            credentialSyncRepository.applyRemoteCredentialForced(entry, secret)
        } finally {
            secret.fill(0)
        }
    }

    /** Triggers a force sync to propagate resolved versions to the peer. */
    fun forceSyncAfterResolve() {
        appScope.coroutineScope.launch { syncScheduler.forceSync() }
    }
}
