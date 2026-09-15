// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

/**
 * Wire format for an encrypted sync message sent between peers.
 *
 * [iv] and [ciphertext] are Base64 URL-safe without padding (RFC 4648 §5).
 * [ciphertext] contains the AES-256-GCM ciphertext with the 16-byte authentication tag appended.
 * [timestamp] is used for anti-replay: the receiver rejects payloads outside ±5 minutes.
 */
@Serializable
data class SyncPayload(
    val senderDeviceId: String,
    val timestamp: Long,
    val iv: String,
    val ciphertext: String,
    val protocolVersion: Int = 1,
)
