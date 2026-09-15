// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

/**
 * A persisted record of a CRDT conflict detected during sync merge.
 * [localJson] and [remoteJson] hold the serialized SyncEntry<T> for the conflicting versions.
 * Resolution (Wave 4.2) will read these back and let the user pick a winner.
 */
@Serializable
data class PendingConflict(
    val id: String,
    val entityType: SyncableEntityType,
    val entityId: String,
    val localJson: String,
    val remoteJson: String,
    val detectedAt: Long,
)

@Serializable
enum class SyncableEntityType {
    HOST,
    TUNNEL,
    SSH_KEY,
    SNIPPET,
    CREDENTIAL,
    CUSTOM_TERMINAL_THEME,
}
