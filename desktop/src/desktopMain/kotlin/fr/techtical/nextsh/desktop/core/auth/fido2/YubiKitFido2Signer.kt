// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import java.security.MessageDigest

/**
 * Adapte [YubiKitFidoManager] vers l'interface [Fido2Signer].
 *
 * Permet à [fr.techtical.nextsh.desktop.core.ssh.fido2.SkFido2PrivateKey] et
 * [fr.techtical.nextsh.desktop.core.ssh.fido2.SkFido2Signature] de dépendre
 * de l'interface [Fido2Signer] (testable) plutôt que de la classe concrète
 * [YubiKitFidoManager] (qui requiert PC/SC physique).
 *
 * Dérive le clientDataHash `SHA-256(challenge)` : voir [Fido2Signer.signChallenge].
 */
class YubiKitFido2Signer(
    private val manager: YubiKitFidoManager,
) : Fido2Signer {

    override suspend fun signChallenge(
        rpId: String,
        challenge: ByteArray,
        credentialId: ByteArray,
    ): Fido2Signer.Assertion {
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(challenge)
        val skAssertion = manager.signChallenge(
            rpId = rpId,
            clientDataHash = clientDataHash,
            credentialId = credentialId,
        )
        return Fido2Signer.Assertion(
            authData = skAssertion.authData,
            signature = skAssertion.signature,
        )
    }
}
