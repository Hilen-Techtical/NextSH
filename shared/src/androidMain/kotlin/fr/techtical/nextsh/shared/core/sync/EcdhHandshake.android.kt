// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

actual object EcdhHandshake {
    actual fun generateEphemeralKeypair(): EphemeralKeypair = EcdhImpl.generateEphemeralKeypair()
    actual fun deriveSharedSecret(
        localPrivateKey: ByteArray,
        remotePublicKey: ByteArray,
        salt: ByteArray,
        info: String,
    ): ByteArray = EcdhImpl.deriveSharedSecret(localPrivateKey, remotePublicKey, salt, info)
    actual fun fingerprint(publicKey: ByteArray): String = EcdhImpl.fingerprint(publicKey)
}
