// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.EnrolledDeviceDao
import fr.techtical.nextsh.data.db.entity.EnrolledDeviceEntity
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnrolledDeviceRepositoryImpl @Inject constructor(
    private val dao: EnrolledDeviceDao,
) : EnrolledDeviceRepository {

    override suspend fun getAll(): List<EnrolledDevice> =
        dao.getAll().map(::toModel)

    override fun observeAll(): Flow<List<EnrolledDevice>> =
        dao.observeAll().map { entities -> entities.map(::toModel) }

    override suspend fun getByDeviceId(deviceId: String): EnrolledDevice? =
        dao.getByDeviceId(deviceId)?.let(::toModel)

    override suspend fun save(device: EnrolledDevice) {
        dao.insert(toEntity(device))
    }

    override suspend fun delete(deviceId: String) {
        dao.deleteByDeviceId(deviceId)
    }

    override suspend fun updateLastSyncAt(deviceId: String, timestamp: Long) {
        dao.updateLastSyncAt(deviceId, timestamp)
    }

    override suspend fun updateLastKnownHost(deviceId: String, host: String) {
        dao.updateLastKnownHost(deviceId, host)
    }

    private fun toModel(entity: EnrolledDeviceEntity): EnrolledDevice = EnrolledDevice(
        deviceId = entity.deviceId,
        deviceName = entity.deviceName,
        platform = Platform.valueOf(entity.platform),
        publicKeyFingerprint = entity.publicKeyFingerprint,
        tlsCertFingerprint = entity.tlsCertFingerprint,
        lastSyncAt = entity.lastSyncAt,
        enrolledAt = entity.enrolledAt,
        lastKnownHost = entity.lastKnownHost,
    )

    private fun toEntity(device: EnrolledDevice): EnrolledDeviceEntity = EnrolledDeviceEntity(
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
