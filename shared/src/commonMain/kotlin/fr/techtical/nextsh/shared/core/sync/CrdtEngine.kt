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
}
