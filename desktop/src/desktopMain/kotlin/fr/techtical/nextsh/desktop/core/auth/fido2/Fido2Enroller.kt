// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType

/**
 * Interface d'enrôlement FIDO2 (MakeCredential).
 *
 * Permet l'injection d'un test double dans [Fido2EnrollViewModel] sans
 * dépendre de [YubiKitFidoManager] concret (qui requiert PC/SC physique).
 *
 * Architecture deux phases (Option A de la spec) :
 * - Phase 1 [detectDevice] : détecte la YubiKey et indique si un PIN est configuré.
 * - Phase 2 [makeCredential] : exécute le MakeCredential avec les paramètres finalisés.
 *
 * Implémentation de production : [YubiKitFidoManager] (via [YubiKitFido2Enroller]).
 */
interface Fido2Enroller {

    /**
     * Résultat de la détection d'un device FIDO2.
     *
     * @param deviceLabel Nom du YubiKey détecté (ex. "YubiKey 5 NFC").
     * @param pinConfigured true si la YubiKey a un PIN configuré → l'UI doit demander le PIN.
     */
    data class DeviceDetection(
        val deviceLabel: String,
        val pinConfigured: Boolean,
    )

    /**
     * Résultat d'un enrôlement réussi.
     *
     * @param credentialId Raw bytes du credential ID retourné par l'authenticator.
     * @param application  L'application / RP-ID utilisé (ex. "ssh:").
     * @param publicKey    Raw bytes de la clé publique extrait du COSE key :
     *                     - Ed25519 : 32 bytes (label -2 COSE OKP)
     *                     - ECDSA P-256 : 65 bytes SEC1 uncompressed (04 || x32 || y32)
     * @param deviceLabel  Nom du YubiKey ayant réalisé l'enrôlement (pour log/UI).
     * @param keyType      Type de clé effectivement enrôlé (SK_ED25519 ou SK_ECDSA_256).
     *                     Peut différer de l'algorithme demandé si Windows a fait un fallback.
     */
    data class EnrollResult(
        val credentialId: ByteArray,
        val application: String,
        val publicKey: ByteArray,
        val deviceLabel: String,
        val keyType: SkKeyType = SkKeyType.SK_ED25519,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EnrollResult) return false
            return credentialId.contentEquals(other.credentialId) &&
                application == other.application &&
                publicKey.contentEquals(other.publicKey) &&
                deviceLabel == other.deviceLabel &&
                keyType == other.keyType
        }

        override fun hashCode(): Int {
            var result = credentialId.contentHashCode()
            result = 31 * result + application.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            result = 31 * result + deviceLabel.hashCode()
            result = 31 * result + keyType.hashCode()
            return result
        }
    }

    /**
     * Phase 1 : détecte le premier YubiKey disponible via PC/SC.
     *
     * @return [DeviceDetection] avec le label du device et l'état du PIN.
     * @throws [YubiKitFidoManager.FidoError.NoDeviceFound] si aucun device détecté.
     * @throws [YubiKitFidoManager.FidoError.TransportError] sur erreur de transport.
     */
    suspend fun detectDevice(): DeviceDetection

    /**
     * Phase 2 : exécute le MakeCredential sur le device détecté.
     *
     * @param rpId           RP-ID (ex. "ssh:"). Doit être identique à celui utilisé lors de [detectDevice].
     * @param rpName         Nom lisible du RP (ex. "NextSH").
     * @param userName       Nom de l'utilisateur / label de la clé.
     * @param userDisplayName Nom d'affichage de l'utilisateur.
     * @param pin            PIN si [DeviceDetection.pinConfigured] était true, null sinon.
     *                       Wipé après usage.
     * @param timeoutMs      Timeout en ms (touch + éventuel PIN flow).
     * @return [EnrollResult] avec les données de la nouvelle credential.
     * @throws [YubiKitFidoManager.FidoError] si l'opération échoue.
     */
    suspend fun makeCredential(
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
        pin: CharArray?,
        timeoutMs: Long,
    ): EnrollResult
}
