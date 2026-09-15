// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

private const val PREFIX_PASSWORD = "pwd:"
private const val PREFIX_PRIVATE_KEY = "key:"
private const val PREFIX_CERTIFICATE = "cert:"
private const val PREFIX_KEY_PASSPHRASE = "keypass:"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

@Serializable
private data class VaultBlob(val ivB64: String, val dataB64: String)

@Serializable
private data class VaultStore(val entries: Map<String, VaultBlob> = emptyMap())

/**
 * File-based vault for Desktop. All blobs encrypted with the AES-256-GCM master key
 * held by [VaultPinManager]; the master key is never written to disk and lives
 * in memory only while the vault is unlocked.
 */
class DesktopVaultManager(
    private val pinManager: VaultPinManager,
) : VaultManager {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun storePassword(credentialId: String, password: CharArray) {
        val bytes = charsToBytes(password)
        try {
            putEntry(PREFIX_PASSWORD + credentialId, bytes)
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    override suspend fun getPassword(credentialId: String): CharArray? {
        val bytes = getEntry(PREFIX_PASSWORD + credentialId) ?: return null
        return try {
            bytesToChars(bytes)
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) {
        putEntry(PREFIX_PRIVATE_KEY + keyId, privateKeyPem.toByteArray(Charsets.UTF_8))
    }

    override suspend fun getPrivateKey(keyId: String): String? {
        val bytes = getEntry(PREFIX_PRIVATE_KEY + keyId) ?: return null
        val s = bytes.toString(Charsets.UTF_8)
        Arrays.fill(bytes, 0)
        return s
    }

    override suspend fun storeCertificate(certId: String, certPem: String) {
        putEntry(PREFIX_CERTIFICATE + certId, certPem.toByteArray(Charsets.UTF_8))
    }

    override suspend fun getCertificate(certId: String): String? {
        val bytes = getEntry(PREFIX_CERTIFICATE + certId) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    override suspend fun deleteCertificate(certId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = load()
            val newEntries = store.entries - (PREFIX_CERTIFICATE + certId)
            if (newEntries.size != store.entries.size) save(VaultStore(newEntries))
        }
    }

    override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) {
        val bytes = charsToBytes(passphrase)
        try {
            putEntry(PREFIX_KEY_PASSPHRASE + keyId, bytes)
        } finally {
            java.util.Arrays.fill(bytes, 0)
        }
    }

    override suspend fun getKeyPassphrase(keyId: String): CharArray? {
        val bytes = getEntry(PREFIX_KEY_PASSPHRASE + keyId) ?: return null
        return try {
            bytesToChars(bytes)
        } finally {
            java.util.Arrays.fill(bytes, 0)
        }
    }

    override suspend fun deleteKeyPassphrase(keyId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = load()
            val newEntries = store.entries - (PREFIX_KEY_PASSPHRASE + keyId)
            if (newEntries.size != store.entries.size) save(VaultStore(newEntries))
        }
    }

    override suspend fun listStoredKeyPassphraseIds(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load().entries.keys
                .filter { it.startsWith(PREFIX_KEY_PASSPHRASE) }
                .map { it.removePrefix(PREFIX_KEY_PASSPHRASE) }
        }
    }

    override suspend fun deleteCredential(credentialId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = load()
            val newEntries = store.entries.filterKeys { key ->
                key != PREFIX_PASSWORD + credentialId &&
                    key != PREFIX_PRIVATE_KEY + credentialId &&
                    key != PREFIX_CERTIFICATE + credentialId &&
                    key != PREFIX_KEY_PASSPHRASE + credentialId
            }
            save(VaultStore(newEntries))
        }
    }

    override suspend fun wipeVault() = withContext(Dispatchers.IO) {
        mutex.withLock {
            pinManager.wipe()
        }
    }

    override suspend fun isInitialized(): Boolean = pinManager.isSetup()

    override suspend fun listStoredCredentialIds(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load().entries.keys
                .filter { it.startsWith(PREFIX_PASSWORD) }
                .map { it.removePrefix(PREFIX_PASSWORD) }
        }
    }

    override suspend fun listStoredKeyIds(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load().entries.keys
                .filter { it.startsWith(PREFIX_PRIVATE_KEY) }
                .map { it.removePrefix(PREFIX_PRIVATE_KEY) }
        }
    }

    override suspend fun listStoredCertificateIds(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            load().entries.keys
                .filter { it.startsWith(PREFIX_CERTIFICATE) }
                .map { it.removePrefix(PREFIX_CERTIFICATE) }
        }
    }

    private suspend fun putEntry(fullKey: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = load()
            val blob = encrypt(plaintext)
            save(VaultStore(store.entries + (fullKey to blob)))
        }
    }

    private suspend fun getEntry(fullKey: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val blob = load().entries[fullKey] ?: return@withLock null
            decrypt(blob)
        }
    }

    private fun load(): VaultStore {
        val file = VaultPaths.dataFile
        if (!file.exists() || file.length() == 0L) return VaultStore()
        return json.decodeFromString(VaultStore.serializer(), file.readText())
    }

    private fun save(store: VaultStore) {
        // Atomic + durable: vault.dat holds every credential, so a torn write
        // (truncate-in-place + crash) would lose them all. See
        // [VaultPaths.atomicWrite].
        VaultPaths.atomicWrite(
            VaultPaths.dataFile,
            json.encodeToString(VaultStore.serializer(), store).toByteArray(Charsets.UTF_8),
        )
    }

    private fun encrypt(plaintext: ByteArray): VaultBlob {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pinManager.requireMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val body = cipher.doFinal(plaintext)
        return VaultBlob(
            ivB64 = Base64.getEncoder().encodeToString(iv),
            dataB64 = Base64.getEncoder().encodeToString(body),
        )
    }

    private fun decrypt(blob: VaultBlob): ByteArray {
        val iv = Base64.getDecoder().decode(blob.ivB64)
        val body = Base64.getDecoder().decode(blob.dataB64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, pinManager.requireMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun charsToBytes(chars: CharArray): ByteArray {
        val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(chars))
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    private fun bytesToChars(bytes: ByteArray): CharArray {
        val buf = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
        val out = CharArray(buf.remaining())
        buf.get(out)
        return out
    }
}
