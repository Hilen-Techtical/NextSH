// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

enum class Platform { ANDROID, DESKTOP }

/** Device metadata only: the shared secret lives in the vault under key `lan_sync_secret_<deviceId>`. */
@Serializable
data class EnrolledDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: Platform,
    val publicKeyFingerprint: String,
    /** SHA-256 hex lowercase 64 chars of the Desktop sync server TLS cert DER encoding.
     *  null when platform=ANDROID (phone does not expose a sync server).
     *  non-null when platform=DESKTOP (stored by Android for future TLS pinning). */
    val tlsCertFingerprint: String?,
    val lastSyncAt: Long?,
    val enrolledAt: Long,
    /** Last known IP or hostname of the peer.
     *  Non-null for DESKTOP peers (populated from QR scan).
     *  Always null for ANDROID peers (phone does not expose a sync server). */
    val lastKnownHost: String? = null,
)
