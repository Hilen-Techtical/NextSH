// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

object CrdtEngine {

    /**
     * Merges two entries. Returns null only when both inputs are null.
     * Soft-delete entries propagate via normal clock rules: no special casing.
     */
    fun <T> merge(local: SyncEntry<T>?, remote: SyncEntry<T>?): MergeResult<T>? {
        if (local == null && remote == null) return null
        if (local == null) return MergeResult.Clean(remote!!)
        if (remote == null) return MergeResult.Clean(local)

        return when {
            local.clock == remote.clock -> MergeResult.Clean(local)
            local.clock.dominates(remote.clock) -> MergeResult.Clean(local)
            remote.clock.dominates(local.clock) -> MergeResult.Clean(remote)
            // Clocks concurrent: if both sides resolved to the same payload+tombstone,
            // there is no real conflict: collapse by merging the two clocks.
            local.payload == remote.payload && local.deleted == remote.deleted ->
                MergeResult.Clean(local.copy(clock = local.clock.mergedWith(remote.clock)))
            else -> MergeResult.Conflict(local, remote)
        }
    }

    /**
     * Resolves a conflict in favor of [local]: returns [local] stamped with a clock that
     * strictly dominates both its own clock and [remoteClock] (see [VectorClock.supersede]
     * for why the device tick is mandatory).
     *
     * Deleting the pending-conflict marker alone resolves nothing: the two clocks stay
     * concurrent, so the very next sync detects the same conflict again on both devices.
     * The returned entry must be written back to the local store the way a synced entry
     * is, so the next sync sends it as a clean win.
     *
     * Payload, [SyncEntry.deleted] and [SyncEntry.deletedAt] are kept: keeping a local
     * deletion keeps the tombstone. Only [SyncEntry.clock] and [SyncEntry.updatedAt]
     * change. Pure function: no I/O, no clock access.
     *
     * @param local the CURRENT stored entry of this device, never the snapshot in
     *   `PendingConflict.localJson`: that snapshot predates any edit made since the
     *   conflict was detected, and writing it back would overwrite those edits on
     *   both devices.
     * @param remoteClock clock of the remote version being discarded, decoded from
     *   `PendingConflict.remoteJson`
     * @param deviceId id of this device (the one whose user made the choice)
     * @param nowMs wall-clock time of the choice, in milliseconds
     */
    fun <T> keepLocal(
        local: SyncEntry<T>,
        remoteClock: VectorClock,
        deviceId: String,
        nowMs: Long,
    ): SyncEntry<T> = local.copy(
        clock = local.clock.supersede(remoteClock, deviceId, nowMs),
        updatedAt = nowMs,
    )
}
