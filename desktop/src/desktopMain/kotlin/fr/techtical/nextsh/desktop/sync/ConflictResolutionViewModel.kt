// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

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
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ConflictResolutionViewModel"

/**
 * Identity of the element a conflict is about.
 *
 * The [PendingConflict.id] is a random UUID regenerated every time the sync re-detects the conflict
 * (and on Desktop every push from the peer re-detects it), so it cannot hold the user's choice.
 */
data class ConflictKey(val entityType: SyncableEntityType, val entityId: String)

/** Key under which the user's choice for this conflict is stored; stable across re-detections. */
val PendingConflict.choiceKey: ConflictKey get() = ConflictKey(entityType, entityId)

/**
 * A choice made by the user, tied to the two versions that were on screen when it was made.
 *
 * [localJson] and [remoteJson] are the conflict content the user saw. The choice only applies to a
 * conflict of the same element that still carries exactly this content: a re-detection with a new
 * random id but identical content keeps the choice, a re-detection after the local or the remote
 * version changed does not, so the user decides again after seeing the new versions.
 */
data class ConflictChoice(
    /** true = keep the local version, false = take the remote version. */
    val keepLocal: Boolean,
    val localJson: String,
    val remoteJson: String,
) {
    fun appliesTo(conflict: PendingConflict): Boolean =
        conflict.localJson == localJson && conflict.remoteJson == remoteJson
}

/** The choice that applies to [conflict] (true = keep local), or null when none was made for these versions. */
fun Map<ConflictKey, ConflictChoice>.choiceFor(conflict: PendingConflict): Boolean? =
    this[conflict.choiceKey]?.takeIf { it.appliesTo(conflict) }?.keepLocal

/** Outcome of [ConflictResolutionViewModel.applyAll]: conflicts resolved, and conflicts left pending after a failure. */
data class ApplyOutcome(val resolved: Int, val failed: Int)

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
    private val deviceIdProvider: () -> String = { DeviceIdentity.deviceId() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Called after a HOST or TUNNEL conflict was resolved (a choice applied, or the conflict ignored) and
     * its marker removed. The container wires the orphan tunnel repair here:
     * - a host with a pending conflict is protected from it, so the tunnels of a host that the choice
     *   leaves deleted are only tombstoned once the choice is made;
     * - a TUNNEL choice (keep the remote version) can leave a live tunnel under a deleted host.
     * The repair is idempotent and cheap. No-op by default.
     */
    private val afterConflictResolved: suspend () -> Unit = {},
) {
    val conflicts: StateFlow<List<PendingConflict>> = pendingConflictRepository.observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    /**
     * Stores the choice made for each element, keyed by (entityType, entityId) and not by
     * conflict.id: the id changes whenever the conflict is re-detected, which would silently drop
     * a choice made but not yet applied. Each choice also remembers the versions it was made on
     * (see [ConflictChoice]); read it through [choiceFor].
     * Absent key means no decision has been made yet.
     */
    private val _resolution = MutableStateFlow<Map<ConflictKey, ConflictChoice>>(emptyMap())
    val resolution: StateFlow<Map<ConflictKey, ConflictChoice>> = _resolution

    private val applyMutex = Mutex()
    private val _applying = MutableStateFlow(false)

    /** True while [applyAll] runs: the screen disables its Apply button. */
    val applying: StateFlow<Boolean> = _applying

    fun keepLocal(conflict: PendingConflict) {
        _resolution.update { it + (conflict.choiceKey to ConflictChoice(true, conflict.localJson, conflict.remoteJson)) }
    }

    fun keepRemote(conflict: PendingConflict) {
        _resolution.update { it + (conflict.choiceKey to ConflictChoice(false, conflict.localJson, conflict.remoteJson)) }
    }

    /**
     * Ignores (deletes) a corrupt conflict without applying any version.
     */
    fun ignoreConflict(conflict: PendingConflict) {
        appScope.coroutineScope.launch {
            pendingConflictRepository.deleteById(conflict.id)
            Logger.d(TAG, "ignoreConflict: discarded corrupt conflict ${LogId.of(conflict.id)}")
            // The marker no longer protects a deleted host from the orphan repair.
            if (conflict.entityType.concernsTunnelOrphans()) runAfterConflictResolved()
        }
    }

    /**
     * Applies all decisions in [_resolution] to the corresponding repositories.
     *
     * - keepLocal: writes the CURRENT local entry back with a vector clock that dominates
     *   both versions and a fresh updatedAt (see [CrdtEngine.keepLocal]), then deletes the pending
     *   conflict. Deleting the marker alone would leave the two clocks concurrent and the next sync
     *   would recreate the conflict on both devices. The peer receives the local version as a clean
     *   win at its next sync. Credentials go through
     *   [CredentialSyncRepository.reassertLocalCredential]. When the local row no longer exists,
     *   only the marker is deleted: the next pull restores the remote version.
     * - keepRemote: decodes the remote SyncEntry JSON and upserts it into the
     *   appropriate repository, then deletes the pending conflict.
     * - No decision, or a decision made on other versions than the ones now pending: conflict is
     *   left pending.
     *
     * A choice that cannot be applied (decode or write failure) leaves its conflict pending and
     * its choice in place, and is counted in [ApplyOutcome.failed]. Applied choices are forgotten.
     *
     * Calls are serialized: a second call waits for the first and then only sees what is left.
     */
    suspend fun applyAll(): ApplyOutcome = applyMutex.withLock {
        _applying.value = true
        try {
            applyPending()
        } finally {
            _applying.value = false
        }
    }

    private suspend fun applyPending(): ApplyOutcome {
        val currentConflicts = pendingConflictRepository.getAll()
        val currentChoices = _resolution.value
        val applied = mutableMapOf<ConflictKey, ConflictChoice>()
        var resolved = 0
        var failed = 0
        var orphansMayAppear = false

        for (conflict in currentConflicts) {
            val choice = currentChoices[conflict.choiceKey]?.takeIf { it.appliesTo(conflict) } ?: continue // no decision: skip

            if (resolveConflict(conflict, choice.keepLocal)) {
                applied[conflict.choiceKey] = choice
                if (conflict.entityType.concernsTunnelOrphans()) orphansMayAppear = true
                Logger.d(
                    TAG,
                    "applyAll: kept ${if (choice.keepLocal) "local" else "remote"} for ${conflict.entityType}/${LogId.of(conflict.entityId)}",
                )
                resolved++
            } else {
                Logger.w(TAG, "applyAll: could not apply choice for ${conflict.entityType}/${LogId.of(conflict.entityId)}, leaving pending")
                failed++
            }
        }

        if (orphansMayAppear) runAfterConflictResolved()

        // Forget what was applied. A choice the user changed while this ran is not the one that was
        // applied and is left alone.
        if (applied.isNotEmpty()) _resolution.update { current -> current.filter { (key, choice) -> applied[key] != choice } }

        Logger.d(TAG, "applyAll: resolved=$resolved failed=$failed total=${currentConflicts.size}")
        return ApplyOutcome(resolved, failed)
    }

    /** Only hosts and tunnels can leave a live tunnel under a deleted host. */
    private fun SyncableEntityType.concernsTunnelOrphans(): Boolean =
        this == SyncableEntityType.HOST || this == SyncableEntityType.TUNNEL

    /** A failing cleanup must not turn an applied choice into a failure: log the class name and carry on. */
    private suspend fun runAfterConflictResolved() {
        try {
            afterConflictResolved()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "applyAll: post-resolve step failed (${errorClass(e)})")
        }
    }

    /**
     * Applies the user's choice for [conflict], then deletes its marker. Returns true on success,
     * false if it must stay pending (the failure is logged).
     */
    private suspend fun resolveConflict(conflict: PendingConflict, keepLocal: Boolean): Boolean {
        return try {
            val done = if (keepLocal) {
                reassertLocal(conflict)
                true
            } else {
                applyRemote(conflict)
            }
            if (done) pendingConflictRepository.deleteById(conflict.id)
            done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: a kotlinx.serialization message embeds a JSON excerpt of the payload,
            // which can hold a tunnel browserPath and its query. A write can fail too (for example a
            // tunnel whose host is unknown here fails the foreign key), so this is not only a decode error.
            Logger.e(
                TAG,
                "resolveConflict: ${if (keepLocal) "keep local" else "keep remote"} failed for " +
                    "${conflict.entityType}/${LogId.of(conflict.entityId)}: ${errorClass(e)}",
            )
            false
        }
    }

    /**
     * Resolves [conflict] in favor of the local version. The current local entry is read from its
     * repository (never the localJson snapshot, which predates any edit made since the detection)
     * and written back stamped by [CrdtEngine.keepLocal]. A local tombstone stays a tombstone.
     * When the entry no longer exists locally nothing is written: the snapshot must not bring back
     * a row that was removed, and the caller deletes the marker so the next pull restores the
     * remote version through a clean merge.
     * Throws on decode or write failure: the caller leaves the conflict pending.
     */
    private suspend fun reassertLocal(conflict: PendingConflict) {
        when (conflict.entityType) {
            SyncableEntityType.HOST -> reassertLocalEntry(
                conflict,
                decode = { ConflictSerializer.decodeHost(it) },
                readAll = { hostRepository.getAllSyncEntries() },
                upsert = { hostRepository.upsertSyncEntry(it) },
            )
            SyncableEntityType.TUNNEL -> reassertLocalEntry(
                conflict,
                decode = { ConflictSerializer.decodeTunnel(it) },
                readAll = { tunnelRepository.getAllSyncEntries() },
                upsert = { tunnelRepository.upsertSyncEntry(it) },
            )
            SyncableEntityType.SSH_KEY -> reassertLocalEntry(
                conflict,
                decode = { ConflictSerializer.decodeSshKey(it) },
                readAll = { sshKeyRepository.getAllSyncEntries() },
                upsert = { sshKeyRepository.upsertSyncEntry(it) },
            )
            SyncableEntityType.SNIPPET -> reassertLocalEntry(
                conflict,
                decode = { ConflictSerializer.decodeSnippet(it) },
                readAll = { snippetRepository.getAllSyncEntries() },
                upsert = { snippetRepository.upsertSyncEntry(it) },
            )
            SyncableEntityType.CUSTOM_TERMINAL_THEME -> reassertLocalEntry(
                conflict,
                decode = { ConflictSerializer.decodeCustomTerminalTheme(it) },
                readAll = { customThemeRepository.getAllSyncEntries() },
                upsert = { customThemeRepository.upsertSyncEntry(it) },
            )
            SyncableEntityType.CREDENTIAL -> {
                val remote = ConflictSerializer.decodeCredential(conflict.remoteJson)
                // False means there is nothing to reassert (no local metadata, or a device-local
                // secret that never syncs): the marker is still deleted by the caller.
                if (!credentialSyncRepository.reassertLocalCredential(remote)) {
                    Logger.d(TAG, "reassertLocal: nothing to reassert for ${conflict.entityType}")
                }
            }
        }
    }

    private suspend fun <T> reassertLocalEntry(
        conflict: PendingConflict,
        decode: (String) -> SyncEntry<T>,
        readAll: suspend () -> List<SyncEntry<T>>,
        upsert: suspend (SyncEntry<T>) -> Unit,
    ) {
        val remote = decode(conflict.remoteJson)
        val current = readAll().firstOrNull { it.id == conflict.entityId }
        if (current == null) {
            Logger.d(TAG, "reassertLocal: no local row for a ${conflict.entityType}, only the conflict marker is removed")
            return
        }
        upsert(CrdtEngine.keepLocal(current, remote.clock, deviceIdProvider(), clock()))
    }

    /**
     * Decodes the remote JSON from [conflict] and upserts the entry into the appropriate
     * repository. Returns true on success, false if the credential cannot be applied. Throws
     * on decode or write failure: the caller leaves the conflict pending.
     */
    private suspend fun applyRemote(conflict: PendingConflict): Boolean {
        return when (conflict.entityType) {
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
    }

    /** See Android counterpart: Solo LAN picks the sole enrolled peer. Phase 3 Team needs a sourceDeviceId in PendingConflict. */
    private suspend fun applyRemoteCredential(conflict: PendingConflict): Boolean {
        val entry = ConflictSerializer.decodeCredential(conflict.remoteJson)
        val device = enrolledDeviceRepository.getAll().firstOrNull()
        if (device == null) {
            Logger.w(TAG, "applyRemoteCredential: no enrolled device, cannot decrypt ${LogId.of(conflict.entityId)}")
            return false
        }
        val secret = secretStore.retrieve(device.deviceId)
        if (secret == null) {
            Logger.w(TAG, "applyRemoteCredential: no secret stored for device=${LogId.of(device.deviceId)}")
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
