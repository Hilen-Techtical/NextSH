// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

sealed class SyncResult {
    data class Success(
        val pushedCount: Int,
        val pulledCount: Int,
        val conflicts: List<MergeResult.Conflict<*>>,
        val syncedAt: Long,
    ) : SyncResult()

    data class Error(val reason: SyncError, val message: String? = null) : SyncResult()

    data object DeviceUnreachable : SyncResult()

    data object AuthFailed : SyncResult()
}

enum class SyncError {
    DECRYPT_FAILED,
    HMAC_INVALID,
    TIMESTAMP_DRIFT,
    VERSION_MISMATCH,
    NETWORK_TIMEOUT,

    /**
     * The peer's address could not be reached at all: connection refused, no route to host,
     * or the stored host name no longer resolves. Distinct from [NETWORK_TIMEOUT], which means
     * a live server accepted the connection but did not answer in time.
     *
     * This is the machine-readable signal that the stored address is stale, so a client may
     * re-resolve it (hostname probe, then LAN discovery) instead of surfacing a dead end.
     */
    HOST_UNREACHABLE,
    SERVER_ERROR,
    UNKNOWN,
}
