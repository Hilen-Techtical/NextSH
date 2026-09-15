// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.util.randomUuid
import java.util.prefs.Preferences

actual object DeviceIdentity {

    private val prefs: Preferences = Preferences.userRoot().node("fr/techtical/nextsh/sync")
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile private var cachedId: String? = null
    private val lock = Any()

    actual fun deviceId(): String {
        cachedId?.let { return it }
        return synchronized(lock) {
            cachedId ?: run {
                val stored = prefs.get(KEY_DEVICE_ID, null)
                val id = if (stored.isNullOrBlank()) {
                    randomUuid().also { prefs.put(KEY_DEVICE_ID, it) }
                } else {
                    stored
                }
                cachedId = id
                id
            }
        }
    }

    actual fun deviceName(): String = runCatching {
        java.net.InetAddress.getLocalHost().hostName
    }.getOrElse {
        System.getProperty("user.name") ?: "desktop"
    }
}
