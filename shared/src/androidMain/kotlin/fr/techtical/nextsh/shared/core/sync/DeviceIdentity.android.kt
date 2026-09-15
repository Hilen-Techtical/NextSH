// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.util.randomUuid
import java.io.File

/**
 * File-based persistence: java.util.prefs.Preferences is known to be unreliable
 * on Android (userRoot silently fails to write when user.home is read-only).
 * [App.onCreate] seeds the JVM system property "user.home" to the application's
 * private filesDir before any [deviceId] call, so the storage file ends up in
 * app-private storage that survives restarts but is wiped on uninstall.
 */
private const val FILE_NAME = ".nextsh_device_id"

actual object DeviceIdentity {

    @Volatile private var cachedId: String? = null
    private val lock = Any()

    actual fun deviceId(): String {
        cachedId?.let { return it }
        return synchronized(lock) {
            cachedId ?: run {
                val id = loadOrGenerate()
                cachedId = id
                id
            }
        }
    }

    actual fun deviceName(): String = "Android"

    private fun loadOrGenerate(): String {
        val home = System.getProperty("user.home") ?: "."
        val file = File(home, FILE_NAME)
        val stored = runCatching {
            if (file.exists() && file.length() > 0) file.readText().trim() else null
        }.getOrNull()
        if (!stored.isNullOrBlank()) return stored

        val newId = randomUuid()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(newId)
        }
        return newId
    }
}
