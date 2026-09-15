// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.coroutines.flow.Flow

interface EnrolledDeviceRepository {
    suspend fun getAll(): List<EnrolledDevice>
    fun observeAll(): Flow<List<EnrolledDevice>>
    suspend fun getByDeviceId(deviceId: String): EnrolledDevice?
    suspend fun save(device: EnrolledDevice)
    suspend fun delete(deviceId: String)
    suspend fun updateLastSyncAt(deviceId: String, timestamp: Long)

    /**
     * Persists a newly re-discovered LAN address for [deviceId] (C4 discovery).
     * Callers MUST only invoke this after a full authenticated sync round-trip
     * succeeds against [host], never from an unauthenticated signal alone
     * (e.g. a UDP discovery ACK), since that would let an on-LAN attacker
     * redirect future sync attempts by spoofing a cheaper-to-forge response.
     */
    suspend fun updateLastKnownHost(deviceId: String, host: String)
}
