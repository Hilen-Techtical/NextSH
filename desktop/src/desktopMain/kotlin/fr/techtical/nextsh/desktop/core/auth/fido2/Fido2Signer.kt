// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

/**
 * Interface de signature FIDO2 : permet l'injection d'un test double dans
 * [fr.techtical.nextsh.desktop.core.ssh.fido2.SkFido2Signature] et
 * [fr.techtical.nextsh.desktop.core.ssh.fido2.SkFido2PrivateKey] sans
 * dépendre de [YubiKitFidoManager] concret (qui requiert PC/SC physique).
 *
 * Implementation de production : [YubiKitFidoManager] (adapté via
 * [YubiKitFido2Signer] dans le DesktopContainer).
 * Implementation de test : fake in-memory avec assertion déterministe.
 */
interface Fido2Signer {

    /**
     * Résultat d'une assertion FIDO2.
     * @param authData authData brut (≥ 37 bytes : rpIdHash + flags + counter + ...)
     * @param signature Signature brute ed25519 (64 bytes) ou ECDSA DER
     */
    data class Assertion(val authData: ByteArray, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Assertion) return false
            return authData.contentEquals(other.authData) && signature.contentEquals(other.signature)
        }
        override fun hashCode(): Int = 31 * authData.contentHashCode() + signature.contentHashCode()
    }

    /**
     * Effectue un GetAssertion FIDO2.
     *
     * @param rpId         RP-ID (ex. "ssh:")
     * @param clientDataHash SHA-256 du challenge SSH (32 bytes)
     * @param credentialId Credential id de la clé SK-*
     * @return [Assertion] avec authData et signature brute
     * @throws [YubiKitFidoManager.FidoError] si l'opération échoue
     */
    suspend fun signChallenge(
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
    ): Assertion
}
