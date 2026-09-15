// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

interface SyncProtocol {
    /** Pushes locally modified entries since [EnrolledDevice.lastSyncAt] to the peer. */
    suspend fun push(device: EnrolledDevice): SyncResult

    /** Fetches remote changes since [EnrolledDevice.lastSyncAt] and applies them locally. */
    suspend fun pull(device: EnrolledDevice): SyncResult

    /** Executes push + pull in the same session; returns an aggregated [SyncResult.Success]. */
    suspend fun fullSync(device: EnrolledDevice): SyncResult
}
