// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

interface SyncRepository {
    /** Snapshot complet local (inclut les soft-deletes pour la propagation). */
    suspend fun getAllLocalAsBundle(): SyncBundle

    /** Applique un SyncBundle distant, merge CRDT, persiste les entrées clean, remonte les conflits. */
    suspend fun applyRemoteBundle(bundle: SyncBundle): ApplyResult

    /** deviceId de CE device. */
    fun deviceId(): String
}

data class ApplyResult(
    val cleanApplied: Int,
    val conflicts: List<MergeResult.Conflict<*>>,
)
