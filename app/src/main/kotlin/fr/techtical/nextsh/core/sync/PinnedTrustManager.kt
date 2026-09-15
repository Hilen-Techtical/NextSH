// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * TrustManager that pins a single TLS certificate by its SHA-256 fingerprint.
 *
 * Accepts the server-presented leaf certificate only if its SHA-256 digest
 * (hex lowercase, 64 chars) matches [expectedFingerprintHex].
 * Uses constant-time comparison to prevent timing side-channels.
 *
 * Client certificate validation is intentionally left empty: Android clients
 * do not present mTLS certificates in Wave 3.x.
 */
class PinnedTrustManager(private val expectedFingerprintHex: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
        val leaf = chain[0]
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expectedFingerprintHex.toByteArray(Charsets.UTF_8))) {
            throw CertificateException("TLS fingerprint mismatch: pinning rejected")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        // Client authentication not used in Wave 3.x: HMAC covers request integrity.
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
