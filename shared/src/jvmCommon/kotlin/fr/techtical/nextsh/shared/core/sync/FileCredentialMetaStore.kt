// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
private data class MetaStoreFileV1(
    val version: Int = 1,
    val entries: Map<String, CredentialMeta> = emptyMap(),
)

/**
 * JSON-file backed [CredentialMetaStore] usable on any JVM platform (`:app`
 * Android and `:desktop` JVM both consume it via their platform DI).
 *
 * Atomicity: writes go to a `.tmp` sibling then `Files.move(..., ATOMIC_MOVE)`.
 * Concurrency: serialized through a single [Mutex], credential writes are
 * rare enough that this is not a bottleneck.
 */
class FileCredentialMetaStore(private val file: File) : CredentialMetaStore {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun get(credentialId: String): CredentialMeta? = withContext(Dispatchers.IO) {
        mutex.withLock { load().entries[credentialId] }
    }

    override suspend fun put(credentialId: String, meta: CredentialMeta) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = load()
            save(current.copy(entries = current.entries + (credentialId to meta)))
        }
    }

    override suspend fun delete(credentialId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = load()
            if (credentialId in current.entries) {
                save(current.copy(entries = current.entries - credentialId))
            }
        }
    }

    override suspend fun listIds(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { load().entries.keys.toSet() }
    }

    override suspend fun getAll(): Map<String, CredentialMeta> = withContext(Dispatchers.IO) {
        mutex.withLock { load().entries.toMap() }
    }

    private fun load(): MetaStoreFileV1 {
        if (!file.exists() || file.length() == 0L) return MetaStoreFileV1()
        return try {
            json.decodeFromString(MetaStoreFileV1.serializer(), file.readText())
        } catch (_: Exception) {
            // Corrupted or unreadable: rename it for post-mortem and start fresh.
            // Losing clocks is preferable to crashing the sync layer on every boot.
            runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt.${System.currentTimeMillis()}")) }
            MetaStoreFileV1()
        }
    }

    private fun save(store: MetaStoreFileV1) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(MetaStoreFileV1.serializer(), store))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Fallback for filesystems that don't support atomic move (rare).
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
