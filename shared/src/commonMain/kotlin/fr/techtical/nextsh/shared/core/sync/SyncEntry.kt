// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

/**
 * A versioned entry in the CRDT log.
 * [payload] is null only when [deleted] is true (soft delete).
 */
@Serializable
data class SyncEntry<T>(
    val id: String,
    val payload: T?,
    val clock: VectorClock,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long,
)
