// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
private const val PBKDF2_ITERATIONS = 200_000
private const val KEY_BITS = 256
private const val DEK_BYTES = 32
private const val SALT_BYTES = 16
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

private const val META_VERSION_ENVELOPE = 2

/** Legacy v1 verify token: only used when migrating an old direct-PIN vault. */
private const val LEGACY_VERIFY_PLAINTEXT = "nextsh-vault-v1"

private val json = Json { ignoreUnknownKeys = true }

/**
 * v2 envelope meta: the real master key (DEK) is random and wrapped under one
 * or more KEKs derived from a secret (PIN / recovery phrase). A wrong secret
 * fails the AES-GCM tag check on unwrap: no separate verify token needed.
 */
@Serializable
private data class VaultMeta(
    val version: Int,                          // 2 = envelope
    val pinSaltB64: String,
    val pinWrappedDekB64: String,              // iv||GCM(DEK) under KEK_pin
    val recoverySaltB64: String? = null,
    val recoveryWrappedDekB64: String? = null, // iv||GCM(DEK) under KEK_recovery; null until set
)

/**
 * Probe used to distinguish legacy v1 meta from v2. v1 has neither [version]
 * nor [pinWrappedDekB64]; it carries [saltBase64] + [verifyCiphertextBase64].
 */
@Serializable
private data class VaultMetaProbe(
    val version: Int? = null,
    val pinWrappedDekB64: String? = null,
    // Legacy v1 fields:
    val saltBase64: String? = null,
    val verifyCiphertextBase64: String? = null,
)

/**
 * Manages the Desktop vault master key (the DEK).
 *
 * Crypto model (v2 envelope):
 *  - The DEK is 32 random bytes and is what actually encrypts `vault.dat`.
 *  - The DEK is wrapped (AES-256-GCM) under a KEK derived by PBKDF2 from the
 *    PIN, and optionally under a second KEK derived from a BIP39 recovery
 *    phrase. Either secret can therefore unwrap the same DEK.
 *  - The DEK lives in memory only while unlocked; wiped on [lock] / [wipe].
 *
 * Legacy v1 vaults derived the key directly from the PIN. On the first
 * [unlock] of such a vault we transparently migrate to v2 by adopting the
 * old derived key AS the DEK (so `vault.dat` keeps decrypting unchanged) and
 * re-wrapping it under a fresh PIN KEK. No recovery phrase is added during
 * migration; the user can add one later via [setupRecoveryPhrase].
 */
class VaultPinManager {

    @Volatile
    private var masterKeyBytes: ByteArray? = null

    fun isSetup(): Boolean = VaultPaths.metaFile.exists()

    val isUnlocked: Boolean get() = masterKeyBytes != null

    /**
     * First-launch setup. Generates a random DEK and a fresh recovery mnemonic,
     * wraps the DEK under both a PIN-derived KEK and a recovery-derived KEK,
     * writes v2 meta, and keeps the DEK in memory.
     *
     * @return the 12 mnemonic words for one-time display. The PIN is wiped.
     */
    fun setupNewVault(pin: CharArray): Result<List<String>> {
        var dek: ByteArray? = null
        return try {
            if (isSetup()) return Result.failure(IllegalStateException("Vault already initialised"))

            dek = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }

            val pinSalt = randomSalt()
            val pinWrapped = wrapDek(dek, pin, pinSalt)

            val words = RecoveryMnemonic.generate()
            val recoverySalt = randomSalt()
            val recoverySeed = RecoveryMnemonic.toSeedChars(words)
            val recoveryWrapped = try {
                wrapDek(dek, recoverySeed, recoverySalt)
            } finally {
                Arrays.fill(recoverySeed, ' ')
            }

            val meta = VaultMeta(
                version = META_VERSION_ENVELOPE,
                pinSaltB64 = b64(pinSalt),
                pinWrappedDekB64 = b64(pinWrapped),
                recoverySaltB64 = b64(recoverySalt),
                recoveryWrappedDekB64 = b64(recoveryWrapped),
            )
            writeMeta(meta)

            masterKeyBytes = dek
            dek = null // ownership transferred to masterKeyBytes; don't wipe in finally
            Result.success(words)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            dek?.let { Arrays.fill(it, 0) }
            Arrays.fill(pin, ' ')
        }
    }

    /**
     * Unlock with the PIN. Handles both legacy v1 (migrate in place) and v2.
     * Wrong PIN → failure ("PIN incorrect"); the DEK is not set on failure.
     */
    fun unlock(pin: CharArray): Result<Unit> {
        val unlockT0 = System.nanoTime()
        return try {
            if (!isSetup()) return Result.failure(IllegalStateException("Vault not initialised"))
            val raw = VaultPaths.metaFile.readText()
            val probe = json.decodeFromString(VaultMetaProbe.serializer(), raw)

            val result = if (isLegacyV1(probe)) {
                migrateLegacyAndUnlock(pin, raw)
            } else {
                unlockV2(pin, raw)
            }
            if (result.isSuccess) {
                StartupTrace.async("vault unlock total", (System.nanoTime() - unlockT0) / 1_000_000)
            }
            result
        } catch (e: javax.crypto.AEADBadTagException) {
            Result.failure(IllegalStateException("PIN incorrect"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            Arrays.fill(pin, ' ')
        }
    }

    /**
     * Unlock with the recovery phrase. Requires a v2 vault that has a recovery
     * wrap. A wrong/invalid phrase fails with a generic message; the DEK is not
     * set on failure. The derived seed CharArray is wiped.
     */
    fun unlockWithRecovery(words: List<String>): Result<Unit> {
        var seed: CharArray? = null
        return try {
            if (!isSetup()) return Result.failure(IllegalStateException("Vault not initialised"))
            val meta = readMetaV2OrNull()
                ?: return Result.failure(IllegalStateException("Recovery phrase not available"))
            val recoverySaltB64 = meta.recoverySaltB64
            val recoveryWrappedB64 = meta.recoveryWrappedDekB64
            if (recoverySaltB64 == null || recoveryWrappedB64 == null) {
                return Result.failure(IllegalStateException("Recovery phrase not available"))
            }
            if (!RecoveryMnemonic.isValid(words)) {
                return Result.failure(IllegalStateException("Recovery phrase invalid"))
            }
            seed = RecoveryMnemonic.toSeedChars(words)
            val dek = unwrapDek(decodeB64(recoveryWrappedB64), seed, decodeB64(recoverySaltB64))
            masterKeyBytes = dek
            Result.success(Unit)
        } catch (e: javax.crypto.AEADBadTagException) {
            Result.failure(IllegalStateException("Recovery phrase invalid"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            seed?.let { Arrays.fill(it, ' ') }
        }
    }

    /** True iff the vault is v2 and a recovery wrap is present. */
    fun hasRecoveryPhrase(): Boolean {
        if (!isSetup()) return false
        val meta = readMetaV2OrNull() ?: return false
        return meta.recoveryWrappedDekB64 != null
    }

    /**
     * Generate (or regenerate) the recovery phrase for an unlocked vault.
     * Wraps the current DEK under a fresh recovery KEK and persists it,
     * preserving the existing PIN wrap.
     *
     * @return the 12 mnemonic words for one-time display.
     */
    fun setupRecoveryPhrase(): Result<List<String>> {
        val dek = masterKeyBytes ?: return Result.failure(IllegalStateException("Vault is locked"))
        return try {
            val meta = readMetaV2OrNull()
                ?: return Result.failure(IllegalStateException("Vault not in envelope format"))

            val words = RecoveryMnemonic.generate()
            val recoverySalt = randomSalt()
            val recoverySeed = RecoveryMnemonic.toSeedChars(words)
            val recoveryWrapped = try {
                wrapDek(dek, recoverySeed, recoverySalt)
            } finally {
                Arrays.fill(recoverySeed, ' ')
            }

            writeMeta(
                meta.copy(
                    recoverySaltB64 = b64(recoverySalt),
                    recoveryWrappedDekB64 = b64(recoveryWrapped),
                ),
            )
            Result.success(words)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Re-wrap the current DEK under a new PIN. Requires an unlocked vault.
     * Preserves any existing recovery wrap. The new PIN is wiped.
     */
    fun changePin(newPin: CharArray): Result<Unit> {
        val dek = masterKeyBytes ?: return Result.failure(IllegalStateException("Vault is locked"))
        return try {
            val meta = readMetaV2OrNull()
                ?: return Result.failure(IllegalStateException("Vault not in envelope format"))
            val pinSalt = randomSalt()
            val pinWrapped = wrapDek(dek, newPin, pinSalt)
            writeMeta(
                meta.copy(
                    pinSaltB64 = b64(pinSalt),
                    pinWrappedDekB64 = b64(pinWrapped),
                ),
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            Arrays.fill(newPin, ' ')
        }
    }

    fun lock() {
        masterKeyBytes?.let { Arrays.fill(it, 0) }
        masterKeyBytes = null
    }

    fun requireMasterKey(): SecretKey {
        val bytes = masterKeyBytes ?: throw IllegalStateException("Vault is locked")
        return SecretKeySpec(bytes, "AES")
    }

    fun wipe() {
        lock()
        VaultPaths.metaFile.delete()
        VaultPaths.dataFile.delete()
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun isLegacyV1(probe: VaultMetaProbe): Boolean {
        // v2 always carries a version and a pin wrap. Treat anything that has
        // the legacy verify token (and no v2 markers) as v1.
        if (probe.version == META_VERSION_ENVELOPE || probe.pinWrappedDekB64 != null) return false
        return probe.saltBase64 != null && probe.verifyCiphertextBase64 != null
    }

    /**
     * Migrate a legacy v1 vault to v2: derive the old direct key from the PIN,
     * verify it, ADOPT it as the DEK (so `vault.dat` keeps decrypting), then
     * re-wrap that DEK under a fresh PIN KEK and persist v2 meta (no recovery).
     */
    private fun migrateLegacyAndUnlock(pin: CharArray, raw: String): Result<Unit> {
        val legacy = json.decodeFromString(LegacyVaultMeta.serializer(), raw)
        val oldSalt = decodeB64(legacy.saltBase64)
        val oldKey = derive(pin, oldSalt)
        var verifyOk = false
        try {
            val plaintext = decryptIvCt(oldKey, decodeB64(legacy.verifyCiphertextBase64))
            verifyOk = plaintext.toString(Charsets.UTF_8) == LEGACY_VERIFY_PLAINTEXT
        } catch (_: javax.crypto.AEADBadTagException) {
            verifyOk = false
        }
        if (!verifyOk) {
            Arrays.fill(oldKey, 0)
            return Result.failure(IllegalStateException("PIN incorrect"))
        }

        // The old derived key BECOMES the DEK: do NOT re-encrypt vault.dat.
        // If wrapping/persisting fails before ownership transfers to
        // masterKeyBytes, wipe oldKey so the derived secret never lingers.
        try {
            val pinSalt = randomSalt()
            val pinWrapped = wrapDek(oldKey, pin, pinSalt)
            writeMeta(
                VaultMeta(
                    version = META_VERSION_ENVELOPE,
                    pinSaltB64 = b64(pinSalt),
                    pinWrappedDekB64 = b64(pinWrapped),
                    recoverySaltB64 = null,
                    recoveryWrappedDekB64 = null,
                ),
            )
        } catch (e: Exception) {
            Arrays.fill(oldKey, 0)
            throw e
        }
        masterKeyBytes = oldKey
        return Result.success(Unit)
    }

    private fun unlockV2(pin: CharArray, raw: String): Result<Unit> {
        val meta = json.decodeFromString(VaultMeta.serializer(), raw)
        var dek: ByteArray? = null
        try {
            dek = unwrapDek(decodeB64(meta.pinWrappedDekB64), pin, decodeB64(meta.pinSaltB64))
            masterKeyBytes = dek
            dek = null // ownership transferred to masterKeyBytes
        } finally {
            // Defensive: if anything threw after unwrap but before assignment,
            // wipe the recovered DEK rather than leaving it for the GC.
            dek?.let { Arrays.fill(it, 0) }
        }
        return Result.success(Unit)
    }

    private fun readMetaV2OrNull(): VaultMeta? {
        if (!VaultPaths.metaFile.exists()) return null
        val raw = VaultPaths.metaFile.readText()
        val probe = json.decodeFromString(VaultMetaProbe.serializer(), raw)
        if (isLegacyV1(probe)) return null
        return try {
            json.decodeFromString(VaultMeta.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(meta: VaultMeta) {
        // Atomic + durable: vault.meta holds the only on-disk wrap of the DEK,
        // so a torn write would permanently lock out vault.dat. See
        // [VaultPaths.atomicWrite].
        VaultPaths.atomicWrite(
            VaultPaths.metaFile,
            json.encodeToString(VaultMeta.serializer(), meta).toByteArray(Charsets.UTF_8),
        )
    }

    private fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeB64(s: String): ByteArray = Base64.getDecoder().decode(s)

    /**
     * Derive a KEK from [secret] + [salt] and AES-256-GCM-encrypt the [dek],
     * returning `iv || ciphertext+tag`. The KEK bytes are wiped before return.
     */
    private fun wrapDek(dek: ByteArray, secret: CharArray, salt: ByteArray): ByteArray {
        val kek = derive(secret, salt)
        return try {
            encryptIvCt(kek, dek)
        } finally {
            Arrays.fill(kek, 0)
        }
    }

    /**
     * Derive a KEK from [secret] + [salt] and decrypt the wrapped DEK. A wrong
     * secret throws [javax.crypto.AEADBadTagException]. The KEK is wiped.
     */
    private fun unwrapDek(wrapped: ByteArray, secret: CharArray, salt: ByteArray): ByteArray {
        val kek = derive(secret, salt)
        return try {
            decryptIvCt(kek, wrapped)
        } finally {
            Arrays.fill(kek, 0)
        }
    }

    private fun derive(secret: CharArray, salt: ByteArray): ByteArray {
        val t0 = System.nanoTime()
        val spec = PBEKeySpec(secret, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            StartupTrace.async("PBKDF2-HMAC-SHA256 ${PBKDF2_ITERATIONS}iter", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun encryptIvCt(keyBytes: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    private fun decryptIvCt(keyBytes: ByteArray, ivAndCt: ByteArray): ByteArray {
        val iv = ivAndCt.copyOfRange(0, GCM_IV_BYTES)
        val body = ivAndCt.copyOfRange(GCM_IV_BYTES, ivAndCt.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    /** Legacy v1 meta shape: read-only, used during migration. */
    @Serializable
    private data class LegacyVaultMeta(
        val saltBase64: String,
        val verifyCiphertextBase64: String,
    )
}
