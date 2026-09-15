// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.vault

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.W32APIOptions
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/** Active OS secret store backend for the master key. */
enum class Backend { WINDOWS, LIBSECRET, KEYCHAIN, FILE }

/**
 * Desktop actual of [VaultKeyProvider]. Persists a 32-byte AES-256 master key in the
 * host OS secret store and offers AES-256-GCM encrypt/decrypt on top of it.
 *
 * Dispatched by OS at construction:
 * - Windows → [WindowsKeyStrategy] (Credential Manager via JNA).
 * - Linux   → [LinuxKeyStrategy]   (libsecret via JNA, fallback [FileKeyStrategy]).
 * - macOS   → [MacOSKeyStrategy]   (Security.framework Keychain via JNA, fallback [FileKeyStrategy]).
 *
 * Call [currentBackend] to discover which backend is active (useful for Settings UI).
 */
actual class VaultKeyProvider {

    private val strategy: KeyStrategy = selectStrategy()

    /** The OS secret store actually in use after lazy init. FILE = fallback. */
    val currentBackend: Backend get() = strategy.currentBackend

    actual suspend fun getOrCreateMasterKey(): ByteArray = strategy.loadOrCreate()

    actual fun encrypt(plaintext: ByteArray): ByteArray {
        val key = strategy.loadOrCreate()
        return try {
            val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            iv + cipher.doFinal(plaintext)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    actual fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > GCM_IV_BYTES) { "ciphertext too short" }
        val key = strategy.loadOrCreate()
        return try {
            val iv = ciphertext.copyOfRange(0, GCM_IV_BYTES)
            val body = ciphertext.copyOfRange(GCM_IV_BYTES, ciphertext.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(body)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    /**
     * Deletes the persistent master key from the OS secret store. Callers wanting a
     * fresh key on the next operation should use this; subsequent encrypt/decrypt
     * calls will lazily create a new one.
     *
     * Note: data encrypted with the old key becomes unreadable, so callers must
     * coordinate this with their own data cleanup.
     */
    fun wipe() = strategy.wipe()
}

internal interface KeyStrategy {
    /** Returns a fresh copy of the master key each call: caller owns zeroing. */
    fun loadOrCreate(): ByteArray
    fun wipe()
    val currentBackend: Backend
}

internal fun selectStrategy(): KeyStrategy {
    val os = System.getProperty("os.name", "").lowercase(Locale.US)
    return when {
        os.contains("windows") -> WindowsKeyStrategy()
        os.contains("linux") -> LinuxKeyStrategy()
        os.contains("mac") || os.contains("darwin") -> MacOSKeyStrategy()
        else -> throw UnsupportedOperationException("Unsupported OS for VaultKeyProvider: $os")
    }
}

// -----------------------------------------------------------------------------
// Windows Credential Manager strategy
// -----------------------------------------------------------------------------

private const val CRED_TYPE_GENERIC = 1
private const val CRED_PERSIST_LOCAL_MACHINE = 2

private interface Advapi32Cred : Library {
    fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
    fun CredWriteW(credential: WinCredential, flags: Int): Boolean
    fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean
    fun CredFree(buffer: Pointer)

    companion object {
        val INSTANCE: Advapi32Cred by lazy {
            Native.load("Advapi32", Advapi32Cred::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }
}

@Structure.FieldOrder(
    "Flags", "Type", "TargetName", "Comment", "LastWritten",
    "CredentialBlobSize", "CredentialBlob", "Persist",
    "AttributeCount", "Attributes", "TargetAlias", "UserName",
)
internal open class WinCredential : Structure {
    @JvmField var Flags: Int = 0
    @JvmField var Type: Int = 0
    @JvmField var TargetName: WString? = null
    @JvmField var Comment: WString? = null
    @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()
    @JvmField var CredentialBlobSize: Int = 0
    @JvmField var CredentialBlob: Pointer? = null
    @JvmField var Persist: Int = 0
    @JvmField var AttributeCount: Int = 0
    @JvmField var Attributes: Pointer? = null
    @JvmField var TargetAlias: WString? = null
    @JvmField var UserName: WString? = null

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

private class WindowsKeyStrategy : KeyStrategy {
    private val targetName: String = "NextSH/MasterKey/${DeviceIdentity.deviceId()}"
    private val lock = Any()

    @Volatile private var cached: ByteArray? = null

    override val currentBackend: Backend get() = Backend.WINDOWS

    override fun loadOrCreate(): ByteArray = synchronized(lock) {
        val current = cached ?: (credRead() ?: generateAndStore()).also { cached = it }
        current.copyOf()
    }

    override fun wipe() = synchronized(lock) {
        cached?.let { Arrays.fill(it, 0) }
        cached = null
        Advapi32Cred.INSTANCE.CredDeleteW(WString(targetName), CRED_TYPE_GENERIC, 0)
        Unit
    }

    private fun credRead(): ByteArray? {
        val ref = PointerByReference()
        if (!Advapi32Cred.INSTANCE.CredReadW(WString(targetName), CRED_TYPE_GENERIC, 0, ref)) {
            return null
        }
        try {
            val cred = WinCredential(ref.value)
            val size = cred.CredentialBlobSize
            val blob = cred.CredentialBlob ?: return null
            if (size <= 0) return null
            return blob.getByteArray(0, size)
        } finally {
            Advapi32Cred.INSTANCE.CredFree(ref.value)
        }
    }

    private fun generateAndStore(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blob = Memory(key.size.toLong())
        try {
            blob.write(0, key, 0, key.size)
            val cred = WinCredential().apply {
                Type = CRED_TYPE_GENERIC
                TargetName = WString(targetName)
                UserName = WString("nextsh")
                CredentialBlobSize = key.size
                CredentialBlob = blob
                Persist = CRED_PERSIST_LOCAL_MACHINE
            }
            cred.write()
            if (!Advapi32Cred.INSTANCE.CredWriteW(cred, 0)) {
                val err = Native.getLastError()
                throw IllegalStateException("CredWriteW failed (Win32 error=$err) for $targetName")
            }
            return key
        } finally {
            // Zero the native buffer before it's GC'd
            for (i in 0 until key.size) blob.setByte(i.toLong(), 0)
        }
    }
}

// -----------------------------------------------------------------------------
// File-based strategy (0600 POSIX): fallback for Linux/macOS when OS keystore unavailable
// -----------------------------------------------------------------------------

internal class FileKeyStrategy : KeyStrategy {
    private val keyDir: File by lazy {
        val override = System.getenv("NEXTSH_CONFIG_DIR") ?: System.getProperty("nextsh.config.dir")
        val dir = if (!override.isNullOrBlank()) File(override)
        else File(System.getProperty("user.home"), ".config/nextsh")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create key dir: ${dir.absolutePath}")
        }
        dir
    }
    internal val keyFile: File get() = File(keyDir, "master.key")

    private val lock = Any()
    @Volatile private var cached: ByteArray? = null

    override val currentBackend: Backend get() = Backend.FILE

    override fun loadOrCreate(): ByteArray = synchronized(lock) {
        val current = cached ?: load().also { cached = it }
        current.copyOf()
    }

    override fun wipe() = synchronized(lock) {
        cached?.let { Arrays.fill(it, 0) }
        cached = null
        if (keyFile.exists()) keyFile.delete()
        Unit
    }

    internal fun load(): ByteArray {
        if (keyFile.exists() && keyFile.length() == 32L) {
            return keyFile.readBytes()
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keyFile.writeBytes(fresh)
        tightenPermissions(keyFile)
        return fresh
    }

    private fun tightenPermissions(f: File) {
        try {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // FS without POSIX perms (e.g. NTFS under WSL on a Windows host): best effort.
        }
    }
}

// -----------------------------------------------------------------------------
// Linux libsecret strategy (JNA binding to libsecret-1)
// -----------------------------------------------------------------------------

/** Minimal JNA binding for libsecret simple-password API. */
internal interface LibSecret : Library {
    /**
     * Store a password in the default keyring.
     * Vararg attributes must be null-terminated: (key, value)..., null.
     */
    fun secret_password_store_sync(
        schema: Pointer?,       // SecretSchema*, null uses compat schema
        collection: String?,    // NULL = default keyring
        label: String,
        password: String,
        cancellable: Pointer?,  // GCancellable*, null = no cancel
        error: PointerByReference?,
        vararg attributes: Any?, // (key, value)..., null
    ): Boolean

    /** Lookup a password; returns null if not found. Caller must free with [secret_password_free]. */
    fun secret_password_lookup_sync(
        schema: Pointer?,
        cancellable: Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): String?

    /** Clear (delete) a password from the keyring. */
    fun secret_password_clear_sync(
        schema: Pointer?,
        cancellable: Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean

    /** Free the string returned by [secret_password_lookup_sync]. */
    fun secret_password_free(password: String?)
}

/** Attempts to load libsecret; returns null on UnsatisfiedLinkError. */
internal fun tryLoadLibSecret(): LibSecret? {
    val names = listOf("libsecret-1.so.0", "libsecret-1.so", "libsecret-1")
    for (name in names) {
        try {
            return Native.load(name, LibSecret::class.java)
        } catch (_: UnsatisfiedLinkError) {
            // try next name
        }
    }
    return null
}

/** Attribute pair constants used with libsecret simple-password schema. */
private const val LS_ATTR_SERVICE = "service"
private const val LS_ATTR_TARGET = "target"
private const val LS_ATTR_SERVICE_VAL = "NextSH"

/**
 * Stores the 32-byte master key in the GNOME/freedesktop keyring via libsecret.
 * Falls back to [FileKeyStrategy] if libsecret is unavailable or the keyring is locked.
 *
 * One-shot migration: if the file backend exists and the keyring succeeds on first use,
 * the existing key is migrated and the file is deleted.
 */
internal class LinuxKeyStrategy(
    private val libSecret: LibSecret? = tryLoadLibSecret(),
    private val fileFallback: FileKeyStrategy = FileKeyStrategy(),
) : KeyStrategy {

    private val targetAttr: String = "MasterKey/${DeviceIdentity.deviceId()}"
    private val lock = Any()

    @Volatile private var cached: ByteArray? = null
    @Volatile private var _backend: Backend = if (libSecret != null) Backend.LIBSECRET else Backend.FILE

    override val currentBackend: Backend get() = _backend

    override fun loadOrCreate(): ByteArray = synchronized(lock) {
        if (cached != null) return@synchronized cached!!.copyOf()

        if (libSecret == null) {
            _backend = Backend.FILE
            val v = fileFallback.loadOrCreate()
            cached = v.copyOf()
            return@synchronized v
        }

        val fromKeyring = secretLookup()
        if (fromKeyring != null) {
            _backend = Backend.LIBSECRET
            cached = fromKeyring.copyOf()
            return@synchronized fromKeyring
        }

        // Keyring reachable but no entry yet: check for legacy file to migrate
        val legacy = fileFallback.keyFile
        val key: ByteArray = if (legacy.exists() && legacy.length() == 32L) {
            val raw = legacy.readBytes()
            if (secretStore(raw)) {
                val wiped = raw.copyOf()
                Arrays.fill(raw, 0)
                legacy.delete()
                wiped
            } else {
                // Store failed, stay on file backend
                _backend = Backend.FILE
                val v = fileFallback.loadOrCreate()
                cached = v.copyOf()
                return@synchronized v
            }
        } else {
            // No legacy file: generate fresh and store in keyring
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            if (secretStore(fresh)) {
                fresh
            } else {
                // Keyring write failed, fall back to file
                _backend = Backend.FILE
                val v = fileFallback.loadOrCreate()
                cached = v.copyOf()
                return@synchronized v
            }
        }

        _backend = Backend.LIBSECRET
        cached = key.copyOf()
        key
    }

    override fun wipe() = synchronized(lock) {
        cached?.let { Arrays.fill(it, 0) }
        cached = null
        // Clear the keyring entry when libsecret is available, AND always the
        // file fallback too: a stale ~/.config/nextsh/master.key can linger from
        // a run before the keyring became reachable, so a complete wipe must
        // remove the key from every place it could still persist.
        if (libSecret != null) secretClear()
        fileFallback.wipe()
    }

    private fun secretLookup(): ByteArray? {
        val err = PointerByReference()
        val result = libSecret!!.secret_password_lookup_sync(
            null, null, err,
            LS_ATTR_SERVICE, LS_ATTR_SERVICE_VAL,
            LS_ATTR_TARGET, targetAttr,
            null,
        ) ?: return null
        return try {
            if (err.value != null) null
            else Base64.getDecoder().decode(result)
        } catch (_: Exception) {
            null
        } finally {
            libSecret.secret_password_free(result)
        }
    }

    private fun secretStore(key: ByteArray): Boolean {
        val encoded = Base64.getEncoder().encodeToString(key)
        val err = PointerByReference()
        val ok = libSecret!!.secret_password_store_sync(
            null, null,
            "NextSH master key",
            encoded,
            null, err,
            LS_ATTR_SERVICE, LS_ATTR_SERVICE_VAL,
            LS_ATTR_TARGET, targetAttr,
            null,
        )
        return ok && err.value == null
    }

    private fun secretClear() {
        libSecret!!.secret_password_clear_sync(
            null, null, null,
            LS_ATTR_SERVICE, LS_ATTR_SERVICE_VAL,
            LS_ATTR_TARGET, targetAttr,
            null,
        )
    }
}

// -----------------------------------------------------------------------------
// macOS Security.framework Keychain strategy (JNA binding)
// -----------------------------------------------------------------------------

/** Minimal JNA binding for macOS Keychain generic-password API. */
internal interface Security : Library {
    fun SecKeychainAddGenericPassword(
        keychain: Pointer?,         // NULL = default keychain
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: Int,
        passwordData: ByteArray,
        itemRef: PointerByReference?, // output, may be null if caller doesn't need it
    ): Int

    fun SecKeychainFindGenericPassword(
        keychainOrArray: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        passwordLength: IntByReference?,  // UInt32*, receives byte count of passwordData
        passwordData: PointerByReference?,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainItemFreeContent(
        attrList: Pointer?,
        data: Pointer?,
    ): Int

    fun SecKeychainItemDelete(itemRef: Pointer?): Int
}

private const val ERR_SEC_SUCCESS: Int = 0
private const val ERR_SEC_ITEM_NOT_FOUND: Int = -25300

/** Attempts to load Security.framework; returns null on UnsatisfiedLinkError. */
internal fun tryLoadSecurity(): Security? = try {
    Native.load("Security", Security::class.java)
} catch (_: UnsatisfiedLinkError) {
    null
}

/**
 * Stores the 32-byte master key in the macOS Keychain.
 * Falls back to [FileKeyStrategy] if Security.framework is unavailable or returns an error.
 *
 * Service: "NextSH", account: "MasterKey-<deviceId>".
 * The 32 raw bytes are stored directly (no Base64 encoding needed: Keychain is binary-safe).
 *
 * One-shot migration: if the file backend exists and the Keychain succeeds on first use,
 * the existing key is migrated and the file is deleted.
 */
internal class MacOSKeyStrategy(
    private val security: Security? = tryLoadSecurity(),
    private val fileFallback: FileKeyStrategy = FileKeyStrategy(),
) : KeyStrategy {

    private val serviceName: ByteArray = "NextSH".toByteArray(Charsets.UTF_8)
    private val accountName: ByteArray = "MasterKey-${DeviceIdentity.deviceId()}".toByteArray(Charsets.UTF_8)
    private val lock = Any()

    @Volatile private var cached: ByteArray? = null
    @Volatile private var _backend: Backend = if (security != null) Backend.KEYCHAIN else Backend.FILE

    override val currentBackend: Backend get() = _backend

    override fun loadOrCreate(): ByteArray = synchronized(lock) {
        if (cached != null) return@synchronized cached!!.copyOf()

        if (security == null) {
            _backend = Backend.FILE
            val v = fileFallback.loadOrCreate()
            cached = v.copyOf()
            return@synchronized v
        }

        val fromKeychain = keychainFind()
        if (fromKeychain != null) {
            _backend = Backend.KEYCHAIN
            cached = fromKeychain.copyOf()
            return@synchronized fromKeychain
        }

        // No entry in Keychain yet: check for legacy file to migrate
        val legacy = fileFallback.keyFile
        val key: ByteArray = if (legacy.exists() && legacy.length() == 32L) {
            val raw = legacy.readBytes()
            val status = keychainAdd(raw)
            if (status == ERR_SEC_SUCCESS) {
                val wiped = raw.copyOf()
                Arrays.fill(raw, 0)
                legacy.delete()
                wiped
            } else {
                // Add failed, fall back to file
                _backend = Backend.FILE
                val v = fileFallback.loadOrCreate()
                cached = v.copyOf()
                return@synchronized v
            }
        } else {
            // Generate fresh and store in Keychain
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val status = keychainAdd(fresh)
            if (status == ERR_SEC_SUCCESS) {
                fresh
            } else {
                _backend = Backend.FILE
                val v = fileFallback.loadOrCreate()
                cached = v.copyOf()
                return@synchronized v
            }
        }

        _backend = Backend.KEYCHAIN
        cached = key.copyOf()
        key
    }

    override fun wipe() = synchronized(lock) {
        cached?.let { Arrays.fill(it, 0) }
        cached = null
        // Clear the Keychain entry when available, AND always the file fallback
        // too (a stale fallback file can linger from before the Keychain was
        // reachable): a complete wipe must cover every place the key persists.
        if (security != null) keychainDelete()
        fileFallback.wipe()
    }

    private fun keychainFind(): ByteArray? {
        if (security == null) return null
        val lenRef = IntByReference()
        val dataRef = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            serviceName.size, serviceName,
            accountName.size, accountName,
            lenRef, dataRef, null,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        if (status != ERR_SEC_SUCCESS) return null
        val dataPtr = dataRef.value ?: return null
        val len = lenRef.value
        if (len <= 0) return null
        return try {
            dataPtr.getByteArray(0, len)
        } finally {
            security.SecKeychainItemFreeContent(null, dataPtr)
        }
    }

    private fun keychainAdd(key: ByteArray): Int {
        if (security == null) return -1
        return security.SecKeychainAddGenericPassword(
            null,
            serviceName.size, serviceName,
            accountName.size, accountName,
            key.size, key,
            null,
        )
    }

    private fun keychainDelete() {
        if (security == null) return
        val itemRef = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            serviceName.size, serviceName,
            accountName.size, accountName,
            null, null, itemRef,
        )
        if (status == ERR_SEC_SUCCESS) {
            val ptr = itemRef.value
            if (ptr != null) security.SecKeychainItemDelete(ptr)
        }
    }
}
