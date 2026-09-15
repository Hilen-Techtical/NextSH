// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal

private const val TAG = "TlsCertificateManager"

private const val ALIAS = "sync"

/**
 * Legacy passphrase used before master-key derivation was introduced.
 * Kept exclusively for the one-shot migration in [TlsCertificateManager.loadOrGenerate]:
 * if the on-disk P12 was sealed with this value, we re-seal it with the derived passphrase
 * so the private key is protected by the OS secret store going forward.
 * Can be removed once ≥ N+1 (all users have been migrated).
 */
private const val LEGACY_PKCS12_PASSPHRASE = "nextsh-sync-v1"

private const val FILENAME = "sync-tls.p12"
private const val KEY_SIZE = 2048
private val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

/**
 * HKDF parameters for PKCS12 passphrase derivation.
 * Salt and info are distinct from other derivations in the project
 * (e.g. "nextsh-cred-v1" / "credential-sync") to guarantee domain separation.
 */
private const val HKDF_SALT = "nextsh-tls-pkcs12-v1"
private const val HKDF_INFO = "tls-pkcs12-passphrase"
private const val HKDF_OUT_BYTES = 32

data class TlsMaterial(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
    val keystore: KeyStore,
    /**
     * The PKCS12 passphrase used to seal [keystore]. Provided so that callers
     * (e.g. the Ktor SSL connector) can unlock the private key entry without
     * re-deriving it. The caller is responsible for zeroing this array after use.
     */
    val passphrase: CharArray,
)

/**
 * Derives a 32-byte PKCS12 passphrase from [masterKey] via HKDF-SHA256,
 * then encodes it as Base64 URL-safe without padding to obtain a [CharArray].
 *
 * Using HKDF here (rather than the raw master key) achieves two goals:
 * 1. Domain separation: a dedicated salt+info pair prevents cross-use of key material
 *    between unrelated features (TLS vs credential sync).
 * 2. Stability: the derived passphrase is deterministic: as long as the master key
 *    remains in the OS secret store, the P12 can always be unlocked on the same machine.
 *
 * The returned [CharArray] must be zeroed by the caller after use.
 * [masterKey] is zeroed by this function before returning.
 */
internal fun derivePkcs12Passphrase(masterKey: ByteArray): CharArray {
    require(masterKey.size == 32) { "masterKey must be 32 bytes" }
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(
        HKDFParameters(
            masterKey,
            HKDF_SALT.toByteArray(Charsets.UTF_8),
            HKDF_INFO.toByteArray(Charsets.UTF_8),
        ),
    )
    val derived = ByteArray(HKDF_OUT_BYTES)
    try {
        hkdf.generateBytes(derived, 0, HKDF_OUT_BYTES)
        // Base64 URL-safe without padding → only alphanumeric + '-' + '_':
        // avoids characters that some PKCS12 implementations mishandle.
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(derived)
        return encoded.toCharArray()
    } finally {
        Arrays.fill(derived, 0)
        Arrays.fill(masterKey, 0)
    }
}

/**
 * Manages the self-signed TLS certificate used by the LAN sync Ktor server.
 *
 * @param dir          Directory where `sync-tls.p12` is stored (typically `~/.nextsh`).
 * @param masterKeyFn  Suspending function that returns a fresh copy of the 32-byte
 *                     Desktop master key. The returned [ByteArray] is zeroed by this class.
 *                     Backed by [VaultKeyProvider.getOrCreateMasterKey] in production and
 *                     by a fixed test key in unit tests.
 */
class TlsCertificateManager(
    private val dir: File,
    private val masterKeyFn: suspend () -> ByteArray,
) {

    @Volatile private var cached: TlsMaterial? = null
    private val initMutex = Mutex()

    /**
     * Loads the certificate from disk (with soft migration from the legacy passphrase)
     * or generates a fresh one if the file does not exist or is unreadable.
     * The result is cached in memory for the lifetime of this instance.
     * The [initMutex] prevents two concurrent coroutines from generating or re-sealing
     * the file twice, which would leak a passphrase and double-write the disk entry.
     */
    suspend fun loadOrGenerate(): TlsMaterial = initMutex.withLock {
        cached?.let {
            StartupTrace.mark("TLS cert cache hit")
            return@withLock it
        }
        val t0 = System.nanoTime()
        val p12 = File(dir, FILENAME)
        val fromDisk = p12.exists() && p12.length() > 0L
        val material = if (fromDisk) loadWithMigration(p12) else generate(p12)
        cached = material
        StartupTrace.async("TLS loadOrGenerate (${if (fromDisk) "disk" else "generated"})", (System.nanoTime() - t0) / 1_000_000)
        material
    }

    /** Returns the SHA-256 hex fingerprint (lowercase, 64 chars) of the DER-encoded certificate. */
    suspend fun fingerprintHex(): String {
        val cert = loadOrGenerate().certificate
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Attempts to open the existing P12 with the derived passphrase first.
     * On failure (legacy file), falls back to the static passphrase and re-seals the file
     * with the new derived passphrase, the certificate and private key are unchanged,
     * so the SHA-256 fingerprint used for Android pinning is unaffected.
     * If both passphrases fail, the file is considered corrupt and regenerated.
     */
    private suspend fun loadWithMigration(p12: File): TlsMaterial {
        val passphrase = derivePkcs12Passphrase(masterKeyFn())
        return try {
            load(p12, passphrase)
        } catch (_: UnrecoverableKeyException) {
            Logger.w(TAG, "P12 cannot be opened with derived passphrase, attempting legacy migration")
            migrateLegacyOrRegenerate(p12, passphrase)
        } catch (e: Exception) {
            // Covers provider-specific exceptions (e.g. BouncyCastle BadPaddingException)
            // that may be thrown instead of UnrecoverableKeyException depending on the JDK.
            Logger.w(TAG, "P12 load failed with ${e.javaClass.simpleName}, attempting legacy migration")
            migrateLegacyOrRegenerate(p12, passphrase)
        }
    }

    private suspend fun migrateLegacyOrRegenerate(p12: File, newPassphrase: CharArray): TlsMaterial {
        val legacy = LEGACY_PKCS12_PASSPHRASE.toCharArray()
        return try {
            val material = load(p12, legacy)
            Logger.d(TAG, "Legacy P12 detected, re-sealing with derived passphrase (fingerprint preserved)")
            // reseal() takes ownership of legacy (via existing.passphrase) and zeros it.
            reseal(p12, material, newPassphrase)
        } catch (e: Exception) {
            // Both passphrases failed: file is corrupt or written by another keystore.
            // Affected Android peers will need to re-enrol (new fingerprint).
            Logger.e(TAG, "P12 unreadable with both passphrases (${e.javaClass.simpleName}), regenerating, enrolled peers must re-enrol")
            Arrays.fill(newPassphrase, '\u0000')
            generate(p12)
        } finally {
            // Zero legacy regardless of path (idempotent if reseal() already did it).
            Arrays.fill(legacy, '\u0000')
        }
    }

    private fun load(p12: File, passphrase: CharArray): TlsMaterial {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(p12).use { ks.load(it, passphrase) }
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        val key = ks.getKey(ALIAS, passphrase) as PrivateKey
        // passphrase ownership is retained by the caller: do NOT zero here.
        return TlsMaterial(certificate = cert, privateKey = key, keystore = ks, passphrase = passphrase)
    }

    /** Re-writes the P12 file with [newPassphrase], preserving cert and private key. */
    private fun reseal(p12: File, existing: TlsMaterial, newPassphrase: CharArray): TlsMaterial {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, existing.privateKey, newPassphrase, arrayOf(existing.certificate))
        FileOutputStream(p12).use { ks.store(it, newPassphrase) }
        // Zero the old passphrase that came with 'existing' (it was the legacy passphrase).
        Arrays.fill(existing.passphrase, '\u0000')
        return TlsMaterial(
            certificate = existing.certificate,
            privateKey = existing.privateKey,
            keystore = ks,
            passphrase = newPassphrase,
        )
    }

    private suspend fun generate(p12: File): TlsMaterial {
        if (!dir.exists()) dir.mkdirs()

        val keygenT0 = System.nanoTime()
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(KEY_SIZE)
        val kp = kpg.generateKeyPair()
        StartupTrace.async("RSA-$KEY_SIZE keygen", (System.nanoTime() - keygenT0) / 1_000_000)

        val subject = X500Principal("CN=NextSH Sync Server")
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + TEN_YEARS_MS)
        val serial = BigInteger.valueOf(now)

        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)

        val passphrase = derivePkcs12Passphrase(masterKeyFn())
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, passphrase, arrayOf(cert))
        FileOutputStream(p12).use { ks.store(it, passphrase) }

        return TlsMaterial(certificate = cert, privateKey = kp.private, keystore = ks, passphrase = passphrase)
    }
}
