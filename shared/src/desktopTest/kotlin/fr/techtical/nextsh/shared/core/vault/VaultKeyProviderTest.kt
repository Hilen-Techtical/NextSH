// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.vault

import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Roundtrip and behavioural tests for the Desktop [VaultKeyProvider].
 *
 * On Windows these exercise the real Credential Manager under the current
 * [DeviceIdentity] target; on Linux/macOS they exercise the file backend.
 * We scope the file backend to a temp directory via `nextsh.config.dir` so a
 * test run never pollutes `~/.config/nextsh/`. On Windows a test credential
 * is left behind under the real deviceId: app startup already expects this
 * key to exist (and re-creates it idempotently), so it's safe.
 */
class VaultKeyProviderTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-keyprovider-test").toFile()
        System.setProperty("nextsh.config.dir", tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("nextsh.config.dir")
        tempDir.deleteRecursively()
    }

    @Test
    fun `encrypt decrypt roundtrip`() = runTest {
        val provider = VaultKeyProvider()
        val plaintext = "hello nextsh".toByteArray(Charsets.UTF_8)
        val encrypted = provider.encrypt(plaintext)
        val decrypted = provider.decrypt(encrypted)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `same plaintext produces different ciphertexts (random IV)`() = runTest {
        val provider = VaultKeyProvider()
        val plaintext = "same input".toByteArray(Charsets.UTF_8)
        val c1 = provider.encrypt(plaintext)
        val c2 = provider.encrypt(plaintext)
        assertFalse(c1.contentEquals(c2), "Two encryptions of the same plaintext must differ (IV reuse bug)")
    }

    @Test
    fun `tampered ciphertext fails GCM integrity check`() = runTest {
        val provider = VaultKeyProvider()
        val encrypted = provider.encrypt("payload".toByteArray())
        // Flip a bit in the ciphertext body (after the 12-byte IV)
        val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertFailsWith<AEADBadTagException> { provider.decrypt(tampered) }
    }

    @Test
    fun `ciphertext starts with 12 byte IV and is at least 16 bytes longer than plaintext (tag)`() = runTest {
        val provider = VaultKeyProvider()
        val plaintext = ByteArray(64).also { it.indices.forEach { i -> it[i] = i.toByte() } }
        val encrypted = provider.encrypt(plaintext)
        // AES-GCM output = 12-byte IV + plaintext.size + 16-byte tag = plaintext.size + 28
        assertTrue(encrypted.size == plaintext.size + 28, "unexpected ciphertext length: ${encrypted.size}")
    }

    // -------------------------------------------------------------------------
    // LinuxKeyStrategy: unit tests using null lib (forces FILE fallback)
    // -------------------------------------------------------------------------

    @Test
    fun `LinuxKeyStrategy falls back to FILE when libsecret unavailable`() {
        // Pass null libSecret: simulates lib not found on any platform (including CI Windows)
        val strategy = LinuxKeyStrategy(libSecret = null, fileFallback = FileKeyStrategy())
        strategy.loadOrCreate()
        assertEquals(Backend.FILE, strategy.currentBackend)
    }

    @Test
    fun `LinuxKeyStrategy FILE fallback returns 32-byte key`() {
        val strategy = LinuxKeyStrategy(libSecret = null, fileFallback = FileKeyStrategy())
        val key = strategy.loadOrCreate()
        assertEquals(32, key.size)
    }

    @Test
    fun `LinuxKeyStrategy FILE fallback is idempotent across calls`() {
        val fallback = FileKeyStrategy()
        val strategy = LinuxKeyStrategy(libSecret = null, fileFallback = fallback)
        val k1 = strategy.loadOrCreate()
        val k2 = strategy.loadOrCreate()
        assertContentEquals(k1, k2)
    }

    @Test
    fun `LinuxKeyStrategy wipe removes file when on FILE backend`() {
        val fallback = FileKeyStrategy()
        val strategy = LinuxKeyStrategy(libSecret = null, fileFallback = fallback)
        strategy.loadOrCreate()
        assertTrue(fallback.keyFile.exists(), "key file should exist after first load")
        strategy.wipe()
        assertFalse(fallback.keyFile.exists(), "key file should be deleted after wipe")
    }

    @Test
    fun `LinuxKeyStrategy migrates existing file to libsecret when keyring available`() {
        // Create a fake libsecret that always succeeds (in-memory map)
        val store = mutableMapOf<String, String>()
        val fakeLib = FakeLibSecret(store)
        val fallback = FileKeyStrategy()

        // Pre-create a legacy master.key in the temp dir
        val legacyKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fallback.keyFile.writeBytes(legacyKey)
        assertTrue(fallback.keyFile.exists())

        val strategy = LinuxKeyStrategy(libSecret = fakeLib, fileFallback = fallback)
        val loaded = strategy.loadOrCreate()

        // Key content preserved
        assertContentEquals(legacyKey, loaded)
        // File deleted after migration
        assertFalse(fallback.keyFile.exists(), "legacy file should be removed after migration")
        // Backend upgraded
        assertEquals(Backend.LIBSECRET, strategy.currentBackend)
    }

    @Test
    fun `LinuxKeyStrategy generates fresh key in libsecret when no legacy file`() {
        val store = mutableMapOf<String, String>()
        val fakeLib = FakeLibSecret(store)
        val fallback = FileKeyStrategy()
        assertFalse(fallback.keyFile.exists())

        val strategy = LinuxKeyStrategy(libSecret = fakeLib, fileFallback = fallback)
        val key = strategy.loadOrCreate()

        assertEquals(32, key.size)
        assertEquals(Backend.LIBSECRET, strategy.currentBackend)
        assertFalse(fallback.keyFile.exists(), "no file should have been created")
    }

    @Test
    fun `LinuxKeyStrategy second load returns same key via keyring`() {
        val store = mutableMapOf<String, String>()
        val fakeLib = FakeLibSecret(store)
        val fallback = FileKeyStrategy()

        val strategy = LinuxKeyStrategy(libSecret = fakeLib, fileFallback = fallback)
        val k1 = strategy.loadOrCreate()
        val k2 = strategy.loadOrCreate()
        assertContentEquals(k1, k2)
    }

    @Test
    fun `LinuxKeyStrategy wipe clears keyring entry AND a stale file fallback`() {
        val store = mutableMapOf<String, String>()
        val fakeLib = FakeLibSecret(store)
        val fallback = FileKeyStrategy()
        val strategy = LinuxKeyStrategy(libSecret = fakeLib, fileFallback = fallback)

        // Keyring backend active, key stored there.
        strategy.loadOrCreate()
        assertEquals(Backend.LIBSECRET, strategy.currentBackend)
        assertTrue(store.isNotEmpty(), "keyring should hold the key")

        // Simulate a stale fallback file left from an earlier run when the
        // keyring was unavailable. A complete wipe must remove BOTH.
        fallback.keyFile.writeBytes(ByteArray(32) { 7 })
        assertTrue(fallback.keyFile.exists())

        strategy.wipe()

        assertTrue(store.isEmpty(), "keyring entry must be cleared by wipe")
        assertFalse(fallback.keyFile.exists(), "stale fallback file must also be deleted by wipe")
    }

    // -------------------------------------------------------------------------
    // MacOSKeyStrategy: unit tests using null lib (forces FILE fallback)
    // -------------------------------------------------------------------------

    @Test
    fun `MacOSKeyStrategy falls back to FILE when Security unavailable`() {
        val strategy = MacOSKeyStrategy(security = null, fileFallback = FileKeyStrategy())
        strategy.loadOrCreate()
        assertEquals(Backend.FILE, strategy.currentBackend)
    }

    @Test
    fun `MacOSKeyStrategy FILE fallback returns 32-byte key`() {
        val strategy = MacOSKeyStrategy(security = null, fileFallback = FileKeyStrategy())
        val key = strategy.loadOrCreate()
        assertEquals(32, key.size)
    }

    @Test
    fun `MacOSKeyStrategy FILE fallback is idempotent across calls`() {
        val fallback = FileKeyStrategy()
        val strategy = MacOSKeyStrategy(security = null, fileFallback = fallback)
        val k1 = strategy.loadOrCreate()
        val k2 = strategy.loadOrCreate()
        assertContentEquals(k1, k2)
    }

    @Test
    fun `MacOSKeyStrategy wipe removes file when on FILE backend`() {
        val fallback = FileKeyStrategy()
        val strategy = MacOSKeyStrategy(security = null, fileFallback = fallback)
        strategy.loadOrCreate()
        assertTrue(fallback.keyFile.exists())
        strategy.wipe()
        assertFalse(fallback.keyFile.exists())
    }

    @Test
    fun `MacOSKeyStrategy migrates existing file to Keychain when Security available`() {
        val keychainStore = mutableMapOf<String, ByteArray>()
        val fakeSecurity = FakeSecurity(keychainStore)
        val fallback = FileKeyStrategy()

        val legacyKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fallback.keyFile.writeBytes(legacyKey)
        assertTrue(fallback.keyFile.exists())

        val strategy = MacOSKeyStrategy(security = fakeSecurity, fileFallback = fallback)
        val loaded = strategy.loadOrCreate()

        assertContentEquals(legacyKey, loaded)
        assertFalse(fallback.keyFile.exists(), "legacy file should be removed after migration")
        assertEquals(Backend.KEYCHAIN, strategy.currentBackend)
    }

    @Test
    fun `MacOSKeyStrategy generates fresh key in Keychain when no legacy file`() {
        val keychainStore = mutableMapOf<String, ByteArray>()
        val fakeSecurity = FakeSecurity(keychainStore)
        val fallback = FileKeyStrategy()

        val strategy = MacOSKeyStrategy(security = fakeSecurity, fileFallback = fallback)
        val key = strategy.loadOrCreate()

        assertEquals(32, key.size)
        assertEquals(Backend.KEYCHAIN, strategy.currentBackend)
        assertFalse(fallback.keyFile.exists())
    }

    @Test
    fun `MacOSKeyStrategy second load returns same key via Keychain`() {
        val keychainStore = mutableMapOf<String, ByteArray>()
        val fakeSecurity = FakeSecurity(keychainStore)
        val fallback = FileKeyStrategy()

        val strategy = MacOSKeyStrategy(security = fakeSecurity, fileFallback = fallback)
        val k1 = strategy.loadOrCreate()
        val k2 = strategy.loadOrCreate()
        assertContentEquals(k1, k2)
    }

    @Test
    fun `MacOSKeyStrategy wipe also deletes a stale file fallback on Keychain backend`() {
        val keychainStore = mutableMapOf<String, ByteArray>()
        val fakeSecurity = FakeSecurity(keychainStore)
        val fallback = FileKeyStrategy()
        val strategy = MacOSKeyStrategy(security = fakeSecurity, fileFallback = fallback)

        // Keychain backend active.
        strategy.loadOrCreate()
        assertEquals(Backend.KEYCHAIN, strategy.currentBackend)

        // Simulate a stale fallback file from an earlier Keychain-unavailable run.
        // (FakeSecurity does not model item deletion, so we assert the new
        // behaviour we added: wipe() must also clear the file fallback.)
        fallback.keyFile.writeBytes(ByteArray(32) { 7 })
        assertTrue(fallback.keyFile.exists())

        strategy.wipe()

        assertFalse(fallback.keyFile.exists(), "stale fallback file must be deleted even on Keychain backend")
    }

    // -------------------------------------------------------------------------
    // Backend reporting on current platform
    // -------------------------------------------------------------------------

    @Test
    fun `VaultKeyProvider currentBackend is non-null after first operation`() = runTest {
        val provider = VaultKeyProvider()
        provider.encrypt("probe".toByteArray()) // trigger lazy init
        assertNotNull(provider.currentBackend)
    }
}

// =============================================================================
// Test doubles
// =============================================================================

// Mirror of private constants from VaultKeyProvider.desktop.kt
private const val ERR_SEC_SUCCESS: Int = 0
private const val ERR_SEC_ITEM_NOT_FOUND: Int = -25300

/**
 * In-memory fake for [LibSecret]. Stores secrets in a plain map (service+target → base64).
 * Simulates the libsecret simple-password API contract used by [LinuxKeyStrategy].
 */
private class FakeLibSecret(private val store: MutableMap<String, String>) : LibSecret {

    // Extracts "service" and "target" from the vararg attribute list.
    private fun attrs(attributes: Array<out Any?>): Pair<String?, String?> {
        var service: String? = null
        var target: String? = null
        var i = 0
        while (i + 1 < attributes.size) {
            when (attributes[i]) {
                "service" -> service = attributes[i + 1] as? String
                "target" -> target = attributes[i + 1] as? String
            }
            i += 2
        }
        return service to target
    }

    private fun key(attributes: Array<out Any?>): String {
        val (s, t) = attrs(attributes)
        return "$s/$t"
    }

    override fun secret_password_store_sync(
        schema: com.sun.jna.Pointer?,
        collection: String?,
        label: String,
        password: String,
        cancellable: com.sun.jna.Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean {
        store[key(attributes)] = password
        return true
    }

    override fun secret_password_lookup_sync(
        schema: com.sun.jna.Pointer?,
        cancellable: com.sun.jna.Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): String? = store[key(attributes)]

    override fun secret_password_clear_sync(
        schema: com.sun.jna.Pointer?,
        cancellable: com.sun.jna.Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean {
        store.remove(key(attributes))
        return true
    }

    override fun secret_password_free(password: String?) { /* no-op: JVM String, no native allocation */ }
}

/**
 * In-memory fake for [Security] (macOS Keychain).
 * Stores secrets in a plain map (service/account → raw bytes).
 */
private class FakeSecurity(private val store: MutableMap<String, ByteArray>) : Security {

    private fun key(serviceName: ByteArray, accountName: ByteArray) =
        "${String(serviceName, Charsets.UTF_8)}/${String(accountName, Charsets.UTF_8)}"

    override fun SecKeychainAddGenericPassword(
        keychain: com.sun.jna.Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: Int,
        passwordData: ByteArray,
        itemRef: PointerByReference?,
    ): Int {
        store[key(serviceName, accountName)] = passwordData.copyOf(passwordLength)
        return ERR_SEC_SUCCESS
    }

    override fun SecKeychainFindGenericPassword(
        keychainOrArray: com.sun.jna.Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: IntByReference?,
        passwordData: PointerByReference?,
        itemRef: PointerByReference?,
    ): Int {
        val k = key(serviceName, accountName)
        val bytes = store[k] ?: return ERR_SEC_ITEM_NOT_FOUND

        passwordLength?.setValue(bytes.size)
        if (passwordData != null) {
            // Allocate native memory for the bytes so pointer arithmetic works
            val m = com.sun.jna.Memory(bytes.size.toLong())
            m.write(0, bytes, 0, bytes.size)
            passwordData.value = m
        }
        return ERR_SEC_SUCCESS
    }

    override fun SecKeychainItemFreeContent(
        attrList: com.sun.jna.Pointer?,
        data: com.sun.jna.Pointer?,
    ): Int = ERR_SEC_SUCCESS // memory managed by JNA GC in tests

    override fun SecKeychainItemDelete(itemRef: com.sun.jna.Pointer?): Int = ERR_SEC_SUCCESS
}
