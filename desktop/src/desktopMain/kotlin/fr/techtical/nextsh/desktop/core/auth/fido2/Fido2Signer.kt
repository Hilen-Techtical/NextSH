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
     * ## Contrat du challenge : données BRUTES, jamais pré-hachées
     * OpenSSH vérifie la signature de l'authenticator sur `authData || SHA-256(data)`,
     * où `data` est le tampon de signature SSH (RFC 4252 §7 pour l'authentification
     * publickey). Le clientDataHash CTAP2 doit donc valoir `SHA-256(data)`, et c'est
     * l'implémentation qui le dérive, pas l'appelant :
     * - [YubiKitFido2Signer] (Linux/macOS, et Windows en repli si WebAuthn est indisponible) :
     *   calcule SHA-256(challenge) puis l'envoie tel quel en CTAP2 GetAssertion ;
     * - [fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn.WindowsWebAuthnProvider] :
     *   place le challenge brut dans `pbClientDataJSON` et laisse `webauthn.dll`
     *   le hacher en SHA-256.
     * Un appelant qui passerait déjà SHA-256(data) provoquerait un double hachage et
     * un rejet `incorrect signature` côté serveur.
     *
     * Les implémentations ne doivent pas conserver de référence au tableau [challenge] :
     * l'appelant peut l'effacer dès le retour de l'appel.
     *
     * @param rpId         RP-ID (ex. "ssh:")
     * @param challenge    Données brutes signées par l'authenticator, NON hachées
     *                     (pour SSH : le tampon de signature RFC 4252 reçu de SSHJ)
     * @param credentialId Credential id de la clé SK-*
     * @return [Assertion] avec authData et signature brute
     * @throws [YubiKitFidoManager.FidoError] si l'opération échoue
     */
    suspend fun signChallenge(
        rpId: String,
        challenge: ByteArray,
        credentialId: ByteArray,
    ): Assertion
}
