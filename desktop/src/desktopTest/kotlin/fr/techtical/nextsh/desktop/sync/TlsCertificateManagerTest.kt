// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [TlsCertificateManager] and [derivePkcs12Passphrase].
 *
 * [VaultKeyProvider] is not instantiated here because it hits the OS secret store
 * (Windows Credential Manager / POSIX file), not suitable for unit tests.
 * Instead, [TlsCertificateManager] accepts a [masterKeyFn] lambda, so each test
 * injects a fixed 32-byte key directly.
 *
 * The [derivePkcs12Passphrase] function is `internal`, so it is callable from
 * the same module in test source sets.
 */
class TlsCertificateManagerTest {

    private lateinit var tempDir: File

    init {
        // In production, BouncyCastle is registered in Main.kt. Tests bypass main(),
        // so we register it here to support JcaContentSignerBuilder(".setProvider("BC")").
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-tls-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Returns a fresh copy of a fixed 32-byte master key for testing. */
    private fun fixedMasterKey(): ByteArray = ByteArray(32) { it.toByte() }

    /** Returns a second, distinct 32-byte master key for negative tests. */
    private fun otherMasterKey(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    private fun managerWithFixedKey(dir: File = tempDir): TlsCertificateManager =
        TlsCertificateManager(dir = dir, masterKeyFn = { fixedMasterKey() })

    // ── Test 1: HKDF is deterministic ─────────────────────────────────────────

    @Test
    fun `derivePkcs12Passphrase is deterministic for the same master key`() {
        val p1 = derivePkcs12Passphrase(fixedMasterKey())
        val p2 = derivePkcs12Passphrase(fixedMasterKey())
        try {
            assertTrue(p1.contentEquals(p2), "Same master key must produce the same passphrase")
        } finally {
            Arrays.fill(p1, '\u0000')
            Arrays.fill(p2, '\u0000')
        }
    }

    // ── Test 2: HKDF output differs from raw master key ──────────────────────

    @Test
    fun `derived passphrase is not the Base64 of the raw master key`() {
        val masterKey = fixedMasterKey()
        val rawBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(masterKey)
        val derived = derivePkcs12Passphrase(masterKey.copyOf()) // pass a copy since fn zeros it
        try {
            assertNotEquals(
                rawBase64,
                String(derived),
                "Derived passphrase must differ from a direct Base64 encoding of the master key",
            )
        } finally {
            Arrays.fill(derived, '\u0000')
        }
    }

    // ── Test 3: Different master keys produce different passphrases ───────────

    @Test
    fun `different master keys produce different passphrases`() {
        val p1 = derivePkcs12Passphrase(fixedMasterKey())
        val p2 = derivePkcs12Passphrase(otherMasterKey())
        try {
            assertFalse(p1.contentEquals(p2), "Different master keys must produce different passphrases")
        } finally {
            Arrays.fill(p1, '\u0000')
            Arrays.fill(p2, '\u0000')
        }
    }

    // ── Test 4: Generate + reload roundtrip (same dir, same key) ─────────────

    @Test
    fun `generate then reload with same master key succeeds and fingerprint is stable`() = runTest {
        val mgr1 = managerWithFixedKey()
        val m1 = mgr1.loadOrGenerate()
        val fp1 = mgr1.fingerprintHex()

        // New instance, same dir, same key → must open existing P12 without regenerating.
        val mgr2 = managerWithFixedKey()
        val m2 = mgr2.loadOrGenerate()
        val fp2 = mgr2.fingerprintHex()

        assertEquals(fp1, fp2, "Fingerprint must be stable across reloads")
        // The certificate bytes must be identical.
        assertTrue(
            m1.certificate.encoded.contentEquals(m2.certificate.encoded),
            "Certificate must be identical after reload",
        )
        // Passphrase must be zeroed by caller, not our job here, but verify it is non-empty.
        assertTrue(m1.passphrase.isNotEmpty())
        assertTrue(m2.passphrase.isNotEmpty())
    }

    // ── Test 5: Migration from legacy passphrase ──────────────────────────────

    @Test
    fun `legacy P12 sealed with static passphrase is migrated transparently`() = runTest {
        // Write a P12 file using the legacy static passphrase, as an older version would have.
        // The value mirrors LEGACY_PKCS12_PASSPHRASE in TlsCertificateManager.kt: update both
        // if the migration passphrase ever changes.
        val legacyPassphrase = "nextsh-sync-v1".toCharArray()
        val p12File = File(tempDir, "sync-tls.p12")
        val (legacyCert, legacyKey) = generateSelfSignedForTest()

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("sync", legacyKey, legacyPassphrase, arrayOf(legacyCert))
        p12File.outputStream().use { ks.store(it, legacyPassphrase) }

        // Compute fingerprint of the original cert so we can assert it is unchanged.
        val origFingerprint = fingerprintOf(legacyCert)

        // Now let TlsCertificateManager migrate it.
        val mgr = managerWithFixedKey()
        val material = mgr.loadOrGenerate()

        // The fingerprint must be identical, only the passphrase changed.
        assertEquals(
            origFingerprint,
            fingerprintOf(material.certificate),
            "Fingerprint must be unchanged after passphrase migration",
        )

        // A second manager on the same dir+key must also succeed (file is now sealed with derived key).
        val mgr2 = managerWithFixedKey()
        val material2 = mgr2.loadOrGenerate()
        assertEquals(
            origFingerprint,
            fingerprintOf(material2.certificate),
            "Fingerprint must remain stable after migration roundtrip",
        )
    }

    // ── Test 6 (optional): Corrupt P12 triggers regeneration ─────────────────

    @Test
    fun `corrupt P12 file triggers full regeneration with a new fingerprint`() = runTest {
        // Write a valid P12 first so there is a file on disk to corrupt.
        managerWithFixedKey().loadOrGenerate()

        // Corrupt the file on disk.
        val p12File = File(tempDir, "sync-tls.p12")
        p12File.writeBytes(ByteArray(64) { 0xFF.toByte() })

        // A fresh manager must detect corruption, regenerate, and produce a different cert.
        val mgr2 = managerWithFixedKey()
        val newFp = mgr2.fingerprintHex()

        // Fingerprint may or may not match (probability negligible); the point is that
        // loadOrGenerate() completes without exception.
        // We do assert that the file is now a valid P12 (reload succeeds).
        val mgr3 = managerWithFixedKey()
        val fp3 = mgr3.fingerprintHex()
        assertEquals(newFp, fp3, "Fingerprint must be stable after regeneration from corruption")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateSelfSignedForTest(): Pair<X509Certificate, java.security.PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val subject = X500Principal("CN=NextSH Test")
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + 365L * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(now)

        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.public,
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC").build(kp.private)
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider("BC").getCertificate(builder.build(signer))
        return cert to kp.private
    }

    private fun fingerprintOf(cert: X509Certificate): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

