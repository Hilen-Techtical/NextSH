// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "ConflictResolutionViewModel"

@HiltViewModel
class ConflictResolutionViewModel @Inject constructor(
    private val pendingConflictRepository: PendingConflictRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val snippetRepository: SnippetRepository,
    private val customThemeRepository: CustomTerminalThemeRepository,
    private val syncScheduler: SyncScheduler,
    private val credentialSyncRepository: CredentialSyncRepository,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val secretStore: EnrolledDeviceSecretStore,
) : ViewModel() {

    val conflicts: StateFlow<List<PendingConflict>> = pendingConflictRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
     * Applies all decisions in [_resolution] to the corresponding repositories.
     *
     * - keepLocal (true): just deletes the pending conflict, the local version is already
     *   in the DB. The next sync will propagate it to the peer.
     * - keepRemote (false): decodes the remote SyncEntry JSON and upserts it into the
     *   appropriate repository, then deletes the pending conflict.
     * - No decision: conflict is left pending.
     *
     * Reads the current list directly from the repository so it is always fresh,
     * regardless of whether the [conflicts] StateFlow has an active collector.
     *
     * Returns the number of conflicts effectively resolved.
     */
    suspend fun applyAll(): Int {
        val currentConflicts = pendingConflictRepository.getAll()
        val currentResolutions = _resolution.value
        var resolved = 0

        for (conflict in currentConflicts) {
            val keepLocal = currentResolutions[conflict.id] ?: continue  // no decision: skip

            if (keepLocal) {
                // Local version is already in DB, just remove the pending marker.
                pendingConflictRepository.deleteById(conflict.id)
                Timber.d(TAG, "applyAll: kept local for ${conflict.entityType}/${conflict.entityId}")
            } else {
                // Decode remote entry and upsert it.
                val applied = applyRemote(conflict)
                if (applied) {
                    pendingConflictRepository.deleteById(conflict.id)
                    Timber.d(TAG, "applyAll: applied remote for ${conflict.entityType}/${conflict.entityId}")
                } else {
                    Timber.w(TAG, "applyAll: failed to decode remote for ${conflict.entityType}/${conflict.entityId}, leaving pending")
                    continue
                }
            }
            resolved++
        }

        Timber.d(TAG, "applyAll: resolved=$resolved total=${currentConflicts.size}")
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
            Timber.e(TAG, "applyRemote: decode failed for ${conflict.entityType}/${conflict.entityId}: ${e.message}")
            false
        }
    }

    /**
     * Accepts a remote credential conflict by decrypting its payload with the enrolled
     * peer's shared secret and forcing it into the vault.
     *
     * Solo LAN sync has a single enrolled peer, so we pick the first device in the
     * registry. Phase 3 Team will need [PendingConflict] to carry the source device id
     * so we can pick the correct secret among multiple peers.
     */
    private suspend fun applyRemoteCredential(conflict: PendingConflict): Boolean {
        val entry = ConflictSerializer.decodeCredential(conflict.remoteJson)
        val device = enrolledDeviceRepository.getAll().firstOrNull()
        if (device == null) {
            Timber.w(TAG, "applyRemoteCredential: no enrolled device, cannot decrypt ${conflict.entityId}")
            return false
        }
        val secret = secretStore.retrieve(device.deviceId)
        if (secret == null) {
            Timber.w(TAG, "applyRemoteCredential: no secret stored for ${device.deviceId}")
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
        viewModelScope.launch { syncScheduler.forceSync() }
    }

    /** Ignores (deletes) a corrupt conflict without applying any version. */
    fun ignoreConflict(conflict: PendingConflict) {
        viewModelScope.launch {
            pendingConflictRepository.deleteById(conflict.id)
            Timber.d(TAG, "ignoreConflict: discarded corrupt conflict ${conflict.id}")
        }
    }
}
