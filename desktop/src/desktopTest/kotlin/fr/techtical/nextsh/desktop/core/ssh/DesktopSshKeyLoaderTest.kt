// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlinx.coroutines.test.runTest
import java.io.IOException
import javax.crypto.BadPaddingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSshKeyLoaderTest {

    private val keyManager = DesktopSshKeyManager()

    init {
        // Register the i2p EdDSA provider: in production this happens in Main.kt,
        // but test JVMs start with only BC + JDK. Without this the Ed25519
        // generate→SSHJ-parse cycle fails with a ClassCastException.
        if (java.security.Security.getProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
        }
    }

    @Test
    fun `generates Ed25519 key via i2p provider that SSHJ can re-parse`() = runTest {
        val (pem, publicKey) = keyManager.generateKeyPair(SshKeyType.ED25519, "ed25519-roundtrip")
        val provider = DesktopSshKeyLoader.loadKeyProviderFromString(pem, passphrase = null)
        assertNotNull(provider.public)
        assertTrue(publicKey.startsWith("ssh-ed25519 "), "expected OpenSSH ed25519 header")
    }

    @Test
    fun `Ed25519 public key encodes into SSHJ buffer without classcast error`() = runTest {
        // This is the exact path that failed pre-fix: SSHJ casts the key to
        // `net.i2p.crypto.eddsa.EdDSAPublicKey` inside `putPublicKey`. With the
        // i2p provider registered and used for generation, the cast succeeds.
        val (pem, _) = keyManager.generateKeyPair(SshKeyType.ED25519, "ed25519-buffer")
        val provider = DesktopSshKeyLoader.loadKeyProviderFromString(pem, passphrase = null)
        val buffer = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(provider.public)
        assertTrue(buffer.compactData.isNotEmpty())
    }

    @Test
    fun `loads unencrypted RSA 4096 key`() = runTest {
        val (pem, publicKey) = keyManager.generateKeyPair(SshKeyType.RSA_4096, "rsa-test")
        val provider = DesktopSshKeyLoader.loadKeyProviderFromString(pem, passphrase = null)
        assertNotNull(provider.public)
        assertTrue(publicKey.startsWith("ssh-rsa "))
    }

    @Test
    fun `loads unencrypted ECDSA 256 key`() = runTest {
        val (pem, _) = keyManager.generateKeyPair(SshKeyType.ECDSA_256, "ec256")
        val provider = DesktopSshKeyLoader.loadKeyProviderFromString(pem, passphrase = null)
        assertNotNull(provider.public)
    }

    @Test
    fun `unknown format throws IllegalArgumentException`() = runTest {
        val junk = "this is not a PEM file at all"
        assertFailsWith<IllegalArgumentException> {
            DesktopSshKeyLoader.loadKeyProviderFromString(junk, passphrase = null)
        }
    }

    @Test
    fun `isLikelyPassphraseError identifies BadPaddingException`() {
        val e = BadPaddingException("GCM tag mismatch")
        assertTrue(DesktopSshKeyLoader.isLikelyPassphraseError(e))
    }

    @Test
    fun `isLikelyPassphraseError identifies nested passphrase messages`() {
        val inner = IOException("wrong passphrase supplied")
        val outer = RuntimeException("key load failed", inner)
        assertTrue(DesktopSshKeyLoader.isLikelyPassphraseError(outer))
    }

    @Test
    fun `isLikelyPassphraseError rejects unrelated IO errors`() {
        val e = IOException("Connection refused")
        assertFalse(DesktopSshKeyLoader.isLikelyPassphraseError(e))
    }

    @Test
    fun `isLikelyPassphraseError rejects null-chained network errors`() {
        val e = java.net.SocketTimeoutException("read timed out")
        assertFalse(DesktopSshKeyLoader.isLikelyPassphraseError(e))
    }

    @Test
    fun `cert parser rejects empty certificate line`() = runTest {
        // Using ECDSA_256 because Ed25519 round-trip is blocked by the JDK 21
        // native vs SSHJ/i2p EdDSA clash (see note above).
        val (pem, _) = keyManager.generateKeyPair(SshKeyType.ECDSA_256, "owner")
        assertFailsWith<IllegalArgumentException> {
            DesktopSshKeyLoader.loadKeyProviderWithCertificate(pem, certificatePem = "", passphrase = null)
        }
    }

    @Test
    fun `cert parser rejects malformed certificate line`() = runTest {
        val (pem, _) = keyManager.generateKeyPair(SshKeyType.ECDSA_256, "owner")
        // Single-token line: SSHJ cert format requires at least algo + base64 data
        assertFailsWith<IllegalArgumentException> {
            DesktopSshKeyLoader.loadKeyProviderWithCertificate(pem, certificatePem = "ssh-ed25519", passphrase = null)
        }
    }
}
