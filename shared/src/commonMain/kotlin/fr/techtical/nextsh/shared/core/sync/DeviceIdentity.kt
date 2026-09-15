// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

/** Provides a stable device identifier and a human-readable device name. */
expect object DeviceIdentity {
    /** Stable UUID persisted on first call. Thread-safe. */
    fun deviceId(): String

    /** Human-readable name (hostname or model). */
    fun deviceName(): String
}
