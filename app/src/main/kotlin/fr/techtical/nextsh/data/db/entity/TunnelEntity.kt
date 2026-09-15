// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType

@Entity(
    tableName = "tunnels",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("hostId")]
)
data class TunnelEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val hostId: String,
    val type: String,               // TunnelType.name
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val autoStart: Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val vectorClock: String = "{}",
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    fun toDomain(): TunnelConfig = TunnelConfig(
        id = id,
        label = label,
        hostId = hostId,
        type = TunnelType.valueOf(type),
        localPort = localPort,
        remoteHost = remoteHost,
        remotePort = remotePort,
        autoStart = autoStart,
        openBrowserOnConnect = openBrowserOnConnect,
        keepAliveAfterBrowserClose = keepAliveAfterBrowserClose,
        isFavorite = isFavorite,
    )

    companion object {
        fun fromDomain(config: TunnelConfig): TunnelEntity = TunnelEntity(
            id = config.id,
            label = config.label,
            hostId = config.hostId,
            type = config.type.name,
            localPort = config.localPort,
            remoteHost = config.remoteHost,
            remotePort = config.remotePort,
            autoStart = config.autoStart,
            openBrowserOnConnect = config.openBrowserOnConnect,
            keepAliveAfterBrowserClose = config.keepAliveAfterBrowserClose,
            isFavorite = config.isFavorite,
        )
    }
}
