// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import fr.techtical.nextsh.desktop.db.Enrolled_devices
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class DesktopEnrolledDeviceRepository(private val db: NextShDatabase) : EnrolledDeviceRepository {

    private val q = db.enrolledDeviceQueries

    override suspend fun getAll(): List<EnrolledDevice> = withContext(Dispatchers.IO) {
        q.selectAll().executeAsList().map(::toModel)
    }

    override fun observeAll(): Flow<List<EnrolledDevice>> =
        q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map(::toModel) }

    override suspend fun getByDeviceId(deviceId: String): EnrolledDevice? = withContext(Dispatchers.IO) {
        q.selectByDeviceId(deviceId).executeAsOneOrNull()?.let(::toModel)
    }

    override suspend fun save(device: EnrolledDevice) = withContext(Dispatchers.IO) {
        q.upsert(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            platform = device.platform.name,
            publicKeyFingerprint = device.publicKeyFingerprint,
            tlsCertFingerprint = device.tlsCertFingerprint,
            lastSyncAt = device.lastSyncAt,
            enrolledAt = device.enrolledAt,
            lastKnownHost = device.lastKnownHost,
        )
    }

    override suspend fun delete(deviceId: String) = withContext(Dispatchers.IO) {
        q.delete(deviceId)
    }

    override suspend fun updateLastSyncAt(deviceId: String, timestamp: Long) = withContext(Dispatchers.IO) {
        q.updateLastSyncAt(timestamp, deviceId)
    }

    override suspend fun updateLastKnownHost(deviceId: String, host: String) = withContext(Dispatchers.IO) {
        q.updateLastKnownHost(host, deviceId)
    }

    private fun toModel(row: Enrolled_devices): EnrolledDevice = EnrolledDevice(
        deviceId = row.deviceId,
        deviceName = row.deviceName,
        platform = Platform.valueOf(row.platform),
        publicKeyFingerprint = row.publicKeyFingerprint,
        tlsCertFingerprint = row.tlsCertFingerprint,
        lastSyncAt = row.lastSyncAt,
        enrolledAt = row.enrolledAt,
        lastKnownHost = row.lastKnownHost,
    )
}
