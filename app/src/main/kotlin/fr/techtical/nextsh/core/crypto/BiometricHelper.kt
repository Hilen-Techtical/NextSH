// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager as AndroidBiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moyens d'authentification acceptés pour ouvrir le vault selon la version d'Android : ceux du
 * dialogue du vault (`BiometricPromptHost`) et ceux que [BiometricHelper.checkCapability] vérifie.
 *
 * À partir de l'API 30 : biométrie forte ou code de l'appareil. Avant, androidx.biometric refuse
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (et `DEVICE_CREDENTIAL` seul) : `canAuthenticate` renvoie
 * `BIOMETRIC_ERROR_UNSUPPORTED` et `setAllowedAuthenticators` échoue, si bien qu'un téléphone sous
 * Android 10 n'offrait aucun moyen de déverrouiller le vault. `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`
 * est accepté sur toutes les API.
 *
 * Sur l'API 29, le Keystore n'ouvre la clé maîtresse que sur un code ou une biométrie forte : une
 * biométrie qu'il refuserait peut se terminer par le message existant d'authentification expirée,
 * et l'utilisateur passe alors par son code (non constaté sur un vrai téléphone Android 10 à
 * biométrie faible).
 */
internal fun vaultAuthenticators(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG or
            AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        AndroidBiometricManager.Authenticators.BIOMETRIC_WEAK or
            AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

/**
 * Ce que l'appareil sait faire pour authentifier le propriétaire (biométrie ou code), et rien de
 * plus : aucun dialogue n'est lancé d'ici. Tous les dialogues système du coffre passent par
 * `BiometricPromptCoordinator` (voir `authenticateVault`), qui prend le tour partagé avec les autres
 * invites et l'unique emplacement de rappel d'androidx.
 *
 * Le constructeur interne rend la version d'Android et l'appel à `BiometricManager.canAuthenticate`
 * injectables pour les tests unitaires.
 */
@Singleton
class BiometricHelper internal constructor(
    private val sdkInt: Int,
    private val canAuthenticateWith: (authenticators: Int) -> Int,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        sdkInt = Build.VERSION.SDK_INT,
        canAuthenticateWith = { authenticators ->
            AndroidBiometricManager.from(context).canAuthenticate(authenticators)
        },
    )

    enum class BiometricCapability {
        AVAILABLE,              // Biométrie + device credential prêts
        DEVICE_CREDENTIAL_ONLY, // Pas de biométrie, mais PIN/pattern/password disponible
        NO_HARDWARE,            // Aucun moyen d'authentification disponible
        NOT_ENROLLED,           // Capteur présent mais aucune empreinte/PIN configuré
        UNAVAILABLE,            // Temporairement indisponible
    }

    /**
     * Vérifie la capacité d'authentification de l'appareil (biométrie ou PIN/pattern).
     *
     * Avant l'API 30, la vérification combinée de [vaultAuthenticators] suffit : elle réussit aussi
     * sur un téléphone qui n'a qu'un code, et `DEVICE_CREDENTIAL` seul n'y est pas pris en charge,
     * donc le repli n'est pas tenté.
     */
    fun checkCapability(): BiometricCapability {
        // Check biométrie + device credential ensemble
        val combined = canAuthenticateWith(vaultAuthenticators(sdkInt))
        if (combined == AndroidBiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricCapability.AVAILABLE
        }

        // Fallback : vérifier device credential seul (PIN/pattern/password)
        // Nécessaire sur certains devices sans capteur biométrique (API 30 et plus seulement)
        if (sdkInt >= Build.VERSION_CODES.R) {
            val deviceCredential = canAuthenticateWith(
                AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            if (deviceCredential == AndroidBiometricManager.BIOMETRIC_SUCCESS) {
                return BiometricCapability.DEVICE_CREDENTIAL_ONLY
            }
        }

        return when (combined) {
            AndroidBiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                -> BiometricCapability.NO_HARDWARE
            AndroidBiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                -> BiometricCapability.NOT_ENROLLED
            else
                -> BiometricCapability.UNAVAILABLE
        }
    }

    /** Retourne true si l'appareil peut authentifier l'utilisateur (biométrie ou PIN) */
    fun canAuthenticate(): Boolean {
        val cap = checkCapability()
        return cap == BiometricCapability.AVAILABLE || cap == BiometricCapability.DEVICE_CREDENTIAL_ONLY
    }

    /**
     * Whether the phone can check a STRONG biometric (BIOMETRIC_STRONG) right now. Unlike
     * [checkCapability] the device PIN does not count: a key of the Keystore generated for a
     * biometric SSH key never accepts it, and the Keystore refuses to create such a key when no
     * strong biometric is enrolled.
     */
    fun checkStrongBiometric(): StrongBiometricStatus =
        strongBiometricStatusFor(
            canAuthenticateWith(AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG),
        )
}
