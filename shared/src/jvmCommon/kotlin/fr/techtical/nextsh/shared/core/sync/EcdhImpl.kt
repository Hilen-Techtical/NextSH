// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

internal object EcdhImpl {

    fun generateEphemeralKeypair(): EphemeralKeypair {
        val gen = X25519KeyPairGenerator()
        gen.init(KeyGenerationParameters(SecureRandom(), 255))
        val pair = gen.generateKeyPair()
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        return EphemeralKeypair(pub, priv)
    }

    fun deriveSharedSecret(
        localPrivateKey: ByteArray,
        remotePublicKey: ByteArray,
        salt: ByteArray,
        info: String,
    ): ByteArray {
        val priv = X25519PrivateKeyParameters(localPrivateKey, 0)
        val pub = X25519PublicKeyParameters(remotePublicKey, 0)

        val agreement = X25519Agreement()
        agreement.init(priv)
        val rawSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pub, rawSecret, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(rawSecret, salt, info.toByteArray(Charsets.UTF_8)))
        val derived = ByteArray(32)
        hkdf.generateBytes(derived, 0, 32)

        rawSecret.fill(0)
        return derived
    }

    fun fingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
