// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val keyType: String,            // SshKeyType.name
    val publicKey: String,          // format OpenSSH
    val isBiometric: Boolean = false,
    val keystoreAlias: String? = null,
    val fido2CredentialId: String? = null,
    val fido2RpId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val vectorClock: String = "{}",
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    fun toDomain(): SshKey = SshKey(
        id = id,
        label = label,
        keyType = SshKeyType.valueOf(keyType),
        publicKey = publicKey,
        isBiometric = isBiometric,
        keystoreAlias = keystoreAlias,
        fido2CredentialId = fido2CredentialId,
        fido2RpId = fido2RpId,
    )

    companion object {
        fun fromDomain(key: SshKey): SshKeyEntity = SshKeyEntity(
            id = key.id,
            label = key.label,
            keyType = key.keyType.name,
            publicKey = key.publicKey,
            isBiometric = key.isBiometric,
            keystoreAlias = key.keystoreAlias,
            fido2CredentialId = key.fido2CredentialId,
            fido2RpId = key.fido2RpId,
        )
    }
}
