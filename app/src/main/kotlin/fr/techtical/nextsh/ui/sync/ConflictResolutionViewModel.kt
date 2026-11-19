// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.data.repository.OrphanTunnelRepair
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Identity of a conflicted element, stable across re-detections.
 *
 * [PendingConflict.id] is a random UUID regenerated each time a sync detects the conflict
 * again, so a user choice keyed by it is lost whenever a background sync runs between the
 * tap and the Apply button. The element itself ([entityType], [entityId]) does not change.
 * Contains no secret: an entity type and an opaque id.
 */
data class ConflictKey(
    val entityType: SyncableEntityType,
    val entityId: String,
)

/** The [ConflictKey] under which the user's choice for this conflict is stored. */
fun PendingConflict.resolutionKey(): ConflictKey = ConflictKey(entityType, entityId)

/**
 * Stable String key of this conflict's element for a lazy list: unlike [PendingConflict.id]
 * it survives a re-detection, so the card is not recreated. A String because lazy item keys
 * must be saveable in a Bundle.
 */
fun PendingConflict.listKey(): String = "${entityType.name}/$entityId"

/**
 * SHA-256 (hex) of the two versions the user is shown: [PendingConflict.localJson] and
 * [PendingConflict.remoteJson], separated by a NUL (never present raw in JSON, so two
 * different pairs cannot concatenate to the same input). [PendingConflict.id] and
 * [PendingConflict.detectedAt] are not part of it: a re-detection with identical content
 * has the same fingerprint.
 */
fun PendingConflict.fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(localJson.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(remoteJson.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * A decision for one conflicted element, tied to the content the user saw when deciding.
 *
 * @property keepLocal true = keep the local version, false = take the remote version.
 * @property fingerprint [PendingConflict.fingerprint] of the conflict at decision time.
 *   Holds a hash, no content.
 */
data class ConflictChoice(
    val keepLocal: Boolean,
    val fingerprint: String,
)

/**
 * The decision for [conflict], or null when none applies: no choice for its element, or a
 * choice made on different versions (the conflict was re-detected with new content, so the
 * user must choose again after seeing them). A re-detection with identical content, under
 * a new random id, keeps the choice.
 */
fun Map<ConflictKey, ConflictChoice>.choiceFor(conflict: PendingConflict): Boolean? {
    val choice = this[conflict.resolutionKey()] ?: return null
    return if (choice.fingerprint == conflict.fingerprint()) choice.keepLocal else null
}

/**
 * Result of [ConflictResolutionViewModel.applyAll].
 *
 * @property resolved conflicts whose marker was deleted.
 * @property failed decided conflicts that could not be applied (undecodable JSON, write
 *   error). They stay pending, with the user's choice kept, so the user can retry or ignore.
 *
 * Conflicts without a decision count in neither. A call rejected because another one is
 * already running returns 0 and 0.
 */
data class ApplyOutcome(
    val resolved: Int,
    val failed: Int,
)

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
    private val orphanTunnelRepair: OrphanTunnelRepair,
) : ViewModel() {

    val conflicts: StateFlow<List<PendingConflict>> = pendingConflictRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Stores the choice made for each conflicted element, by [ConflictKey] (entity type and
     * entity id), never by [PendingConflict.id]: that id changes each time the conflict is
     * re-detected, which would silently drop a choice made but not yet applied.
     *
     * Each [ConflictChoice] also carries the fingerprint of the versions the user saw. Read
     * it with [choiceFor]: a choice applies, and is shown as selected, only while the current
     * conflict of that element has the same fingerprint. A re-detection with identical
     * content keeps it; one with different content does not, and the user chooses again.
     * Absent key, or a stale fingerprint, means no decision has been made yet.
     *
     * Entries are removed once applied (or ignored), never pruned reactively from
     * [conflicts]: a sync re-detects a conflict by deleting its marker then saving a new
     * one, so the flow can briefly lack an element that is still in conflict.
     *
     * In memory only: nothing here is written to saved state.
     */
    private val _resolution = MutableStateFlow<Map<ConflictKey, ConflictChoice>>(emptyMap())
    val resolution: StateFlow<Map<ConflictKey, ConflictChoice>> = _resolution

    fun keepLocal(conflict: PendingConflict) = choose(conflict, keepLocal = true)

    fun keepRemote(conflict: PendingConflict) = choose(conflict, keepLocal = false)

    private fun choose(conflict: PendingConflict, keepLocal: Boolean) {
        val choice = ConflictChoice(keepLocal = keepLocal, fingerprint = conflict.fingerprint())
        _resolution.update { it + (conflict.resolutionKey() to choice) }
    }

    /**
     * True while [applyAll] runs. The screen disables the Apply button on it, and
     * [applyAll] itself rejects a concurrent call, so a double tap cannot apply twice.
     */
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying

    /**
     * Applies all decisions in [_resolution] to the corresponding repositories.
     *
     * - keepLocal (true): re-stamps the CURRENT local entry with a vector clock that
     *   dominates both versions ([CrdtEngine.keepLocal]) and writes it back, then deletes
     *   the pending conflict. Deleting the marker alone would resolve nothing: the clocks
     *   would stay concurrent and the forced sync that follows would detect the same
     *   conflict again. For a credential the same is done on the vault metadata by
     *   [CredentialSyncRepository.reassertLocalCredential]. If the local row no longer
     *   exists, nothing is written and only the marker is deleted: the next pull restores
     *   the remote version through a clean merge.
     * - keepRemote (false): decodes the remote SyncEntry JSON and upserts it into the
     *   appropriate repository, then deletes the pending conflict.
     * - No decision: conflict is left pending.
     * - A decision whose application fails (undecodable JSON, write error) leaves the
     *   conflict pending, the choice is kept, and the conflict counts in
     *   [ApplyOutcome.failed].
     *
     * Reads the current list directly from the repository so it is always fresh,
     * regardless of whether the [conflicts] StateFlow has an active collector.
     *
     * A call made while another one is running does nothing and returns 0 and 0.
     */
    suspend fun applyAll(): ApplyOutcome {
        if (!_applying.compareAndSet(expect = false, update = true)) {
            Timber.d("applyAll: already running, call ignored")
            return ApplyOutcome(resolved = 0, failed = 0)
        }
        try {
            return applyDecisions()
        } finally {
            _applying.value = false
        }
    }

    private suspend fun applyDecisions(): ApplyOutcome {
        val currentConflicts = pendingConflictRepository.getAll()
        val currentResolutions = _resolution.value
        var resolved = 0
        var failed = 0
        var hostResolved = false

        for (conflict in currentConflicts) {
            val key = conflict.resolutionKey()
            // No decision, or one made on other versions than these: skip.
            val keepLocal = currentResolutions.choiceFor(conflict) ?: continue

            val applied = if (keepLocal) applyLocal(conflict) else applyRemote(conflict)
            if (!applied) {
                Timber.w("applyAll: could not apply ${conflict.entityType}, leaving it pending")
                failed++
                continue
            }

            pendingConflictRepository.deleteById(conflict.id)
            _resolution.update { it - key }
            Timber.d("applyAll: ${if (keepLocal) "kept local" else "applied remote"} for ${conflict.entityType}")
            resolved++
            if (conflict.entityType == SyncableEntityType.HOST) hostResolved = true
        }

        // A host decided as deleted (kept local, or the remote tombstone taken) leaves its live
        // tunnels orphaned, and their protection ended with the HOST marker deleted above.
        // Delete them now, before the forced sync that follows pushes the resolved versions.
        if (hostResolved) orphanTunnelRepair.runSafely()

        Timber.d("applyAll: resolved=$resolved failed=$failed total=${currentConflicts.size}")
        return ApplyOutcome(resolved = resolved, failed = failed)
    }

    /**
     * Resolves [conflict] in favor of the local version. Returns true when the local
     * version was re-asserted (the marker can be deleted), false on any decode or write
     * failure (the conflict stays pending).
     */
    private suspend fun applyLocal(conflict: PendingConflict): Boolean {
        return try {
            when (conflict.entityType) {
                SyncableEntityType.HOST -> reassertLocal(
                    conflict,
                    decode = ConflictSerializer::decodeHost,
                    readAll = { hostRepository.getAllSyncEntries() },
                    write = { hostRepository.upsertSyncEntry(it) },
                )
                SyncableEntityType.TUNNEL -> reassertLocal(
                    conflict,
                    decode = ConflictSerializer::decodeTunnel,
                    readAll = { tunnelRepository.getAllSyncEntries() },
                    write = { tunnelRepository.upsertSyncEntry(it) },
                )
                SyncableEntityType.SSH_KEY -> reassertLocal(
                    conflict,
                    decode = ConflictSerializer::decodeSshKey,
                    readAll = { sshKeyRepository.getAllSyncEntries() },
                    write = { sshKeyRepository.upsertSyncEntry(it) },
                )
                SyncableEntityType.SNIPPET -> reassertLocal(
                    conflict,
                    decode = ConflictSerializer::decodeSnippet,
                    readAll = { snippetRepository.getAllSyncEntries() },
                    write = { snippetRepository.upsertSyncEntry(it) },
                )
                SyncableEntityType.CUSTOM_TERMINAL_THEME -> reassertLocal(
                    conflict,
                    decode = ConflictSerializer::decodeCustomTerminalTheme,
                    readAll = { customThemeRepository.getAllSyncEntries() },
                    write = { customThemeRepository.upsertSyncEntry(it) },
                )
                SyncableEntityType.CREDENTIAL -> reassertLocalCredential(conflict)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("applyLocal: failed for ${conflict.entityType} (${errorClass(e)})")
            false
        }
    }

    /**
     * Writes back the current local entry of [conflict] stamped by [CrdtEngine.keepLocal].
     *
     * The base is the CURRENT stored entry, read from [readAll] (tombstones included by
     * every repository), never the snapshot in [PendingConflict.localJson]: that snapshot
     * predates any edit made since the conflict was detected, and writing it back would
     * overwrite those edits (or resurrect a row, or hit a foreign key). When the row is
     * gone there is nothing to re-assert: nothing is written, the caller deletes the
     * marker, and the next pull brings the remote version back as a clean merge.
     * The remote clock comes from [PendingConflict.remoteJson].
     */
    private suspend fun <T> reassertLocal(
        conflict: PendingConflict,
        decode: (String) -> SyncEntry<T>,
        readAll: suspend () -> List<SyncEntry<T>>,
        write: suspend (SyncEntry<T>) -> Unit,
    ) {
        val current = readAll().firstOrNull { it.id == conflict.entityId }
        if (current == null) {
            Timber.d("applyLocal: no local row for a ${conflict.entityType} conflict, marker dropped only")
            return
        }
        val remote = decode(conflict.remoteJson)
        write(
            CrdtEngine.keepLocal(
                local = current,
                remoteClock = remote.clock,
                deviceId = DeviceIdentity.deviceId(),
                nowMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Credential counterpart of [reassertLocal]: the vault metadata is re-stamped by
     * [CredentialSyncRepository.reassertLocalCredential]. No secret is read or needed.
     * A false result means there was nothing to re-assert (no local metadata, or a
     * device-local secret that never syncs): the marker is still deleted by the caller.
     */
    private suspend fun reassertLocalCredential(conflict: PendingConflict) {
        val remote = ConflictSerializer.decodeCredential(conflict.remoteJson)
        if (!credentialSyncRepository.reassertLocalCredential(remote)) {
            Timber.d("applyLocal: nothing to re-assert for a credential conflict")
        }
    }

    /**
     * Decodes the remote JSON from [conflict] and upserts the entry into the appropriate
     * repository. Returns true on success, false on any decode or write failure.
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("applyRemote: failed for ${conflict.entityType} (${errorClass(e)})")
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
            Timber.w("applyRemoteCredential: no enrolled device, cannot decrypt the remote credential")
            return false
        }
        val secret = secretStore.retrieve(device.deviceId)
        if (secret == null) {
            Timber.w("applyRemoteCredential: no secret stored for the enrolled device")
            return false
        }
        return try {
            credentialSyncRepository.applyRemoteCredentialForced(entry, secret)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Triggers a force sync to propagate resolved versions to the peer.
     *
     * The screen is popped right after this call, which clears this ViewModel and cancels
     * [viewModelScope]. Only the wait is cancelled: the scheduler runs the sync in its own
     * scope, so the pull then push of the new clock still completes.
     */
    fun forceSyncAfterResolve() {
        viewModelScope.launch {
            try {
                syncScheduler.forceSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // viewModelScope has no handler: an escaping exception would kill the process.
                Timber.w("forceSyncAfterResolve failed (${errorClass(e)})")
            }
        }
    }

    /** Ignores (deletes) a corrupt conflict without applying any version. */
    fun ignoreConflict(conflict: PendingConflict) {
        viewModelScope.launch {
            pendingConflictRepository.deleteById(conflict.id)
            _resolution.update { it - conflict.resolutionKey() }
            Timber.d("ignoreConflict: discarded corrupt conflict id=${LogId.of(conflict.id)}")
        }
    }
}
