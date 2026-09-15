// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

expect object EcdhHandshake {
    fun generateEphemeralKeypair(): EphemeralKeypair
    fun deriveSharedSecret(
        localPrivateKey: ByteArray,
        remotePublicKey: ByteArray,
        salt: ByteArray,
        info: String,
    ): ByteArray
    fun fingerprint(publicKey: ByteArray): String
}
