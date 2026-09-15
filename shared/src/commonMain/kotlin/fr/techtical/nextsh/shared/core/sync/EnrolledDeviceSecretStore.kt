// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Vault credential-id prefix under which per-device LAN sync shared secrets are stored.
 *
 * IMPORTANT: these entries live in the same `pwd_` namespace as host passwords, so any
 * code that enumerates vault credentials MUST exclude this prefix. The shared secret is
 * a device-local authentication key and must never leave the device: neither exported
 * to / reconciled by a peer (the credential-sync CRDT engine) nor written into a vault
 * backup (the platform `VaultExporter`s). Public so `:app` and `:desktop` can filter on it.
 */
const val LAN_SYNC_SECRET_KEY_PREFIX = "lan_sync_secret_"

/**
 * Stores and retrieves the ECDH-derived shared secret for each enrolled device via the vault.
 *
 * The secret is Base64-encoded as a CharArray before being handed to [VaultManager.storePassword].
 * Callers are responsible for wiping the ByteArray returned by [retrieve] after use.
 */
@OptIn(ExperimentalEncodingApi::class)
class EnrolledDeviceSecretStore(private val vaultManager: VaultManager) {

    suspend fun store(deviceId: String, secret: ByteArray) {
        val encoded = Base64.encode(secret).toCharArray()
        try {
            vaultManager.storePassword("$LAN_SYNC_SECRET_KEY_PREFIX$deviceId", encoded)
        } finally {
            encoded.fill('\u0000')
        }
    }

    /** Returns the decoded secret, or null if not found. Caller must wipe the result after use. */
    suspend fun retrieve(deviceId: String): ByteArray? {
        val encoded = vaultManager.getPassword("$LAN_SYNC_SECRET_KEY_PREFIX$deviceId") ?: return null
        return try {
            Base64.decode(String(encoded))
        } finally {
            encoded.fill('\u0000')
        }
    }

    suspend fun delete(deviceId: String) {
        vaultManager.deleteCredential("$LAN_SYNC_SECRET_KEY_PREFIX$deviceId")
    }
}
