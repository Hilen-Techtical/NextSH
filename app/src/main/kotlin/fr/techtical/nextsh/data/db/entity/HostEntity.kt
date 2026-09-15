// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: String,           // AuthType.name
    val credentialId: String,
    val group: String? = null,
    val keepAliveSeconds: Int = 30,
    val autoReconnect: Boolean = true,
    val terminalTheme: String = "TECHTICAL_DARK",
    val fido2Mode: String? = null,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    val vectorClock: String = "{}",
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    fun toDomain(): Host = Host(
        id = id,
        label = label,
        hostname = hostname,
        port = port,
        username = username,
        authType = AuthType.valueOf(authType),
        credentialId = credentialId,
        group = group,
        keepAliveSeconds = keepAliveSeconds,
        autoReconnect = autoReconnect,
        terminalTheme = terminalTheme,
        fido2Mode = fido2Mode?.let { fr.techtical.nextsh.domain.model.Fido2Mode.valueOf(it) },
        isFavorite = isFavorite,
    )

    companion object {
        fun fromDomain(host: Host): HostEntity = HostEntity(
            id = host.id,
            label = host.label,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            authType = host.authType.name,
            credentialId = host.credentialId,
            group = host.group,
            keepAliveSeconds = host.keepAliveSeconds,
            autoReconnect = host.autoReconnect,
            terminalTheme = host.terminalTheme,
            fido2Mode = host.fido2Mode?.name,
            isFavorite = host.isFavorite,
        )
    }
}
