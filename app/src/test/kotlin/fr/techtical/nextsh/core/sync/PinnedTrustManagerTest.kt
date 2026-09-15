// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

class PinnedTrustManagerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Generates a self-signed RSA-2048 X.509 certificate for test purposes. */
    private fun generateSelfSignedCert(): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=test")
        val notBefore = Date(System.currentTimeMillis() - 1_000L)
        val notAfter = Date(System.currentTimeMillis() + 86_400_000L)
        val serial = BigInteger.valueOf(System.nanoTime())

        val holder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))

        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `checkServerTrusted accepts cert matching pinned fingerprint`() {
        val cert = generateSelfSignedCert()
        val fp = fingerprintOf(cert)

        val trustManager = PinnedTrustManager(fp)

        // Must not throw
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun `checkServerTrusted rejects cert with different fingerprint`() {
        val certA = generateSelfSignedCert()
        val certB = generateSelfSignedCert()
        val fpA = fingerprintOf(certA)

        val trustManager = PinnedTrustManager(fpA)

        // certB does not match the pinned fpA
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(certB), "RSA")
        }
    }

    @Test
    fun `checkServerTrusted rejects empty certificate chain`() {
        val trustManager = PinnedTrustManager("a".repeat(64))

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(emptyArray(), "RSA")
        }
    }
}
