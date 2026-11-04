// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager as AndroidBiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moyens d'authentification acceptés pour ouvrir le vault selon la version d'Android.
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
 * Wrapper autour de BiometricPrompt API.
 * Gère l'authentification biométrique pour le déverrouillage du vault
 * et la signature de clés liées au Keystore.
 *
 * Le constructeur interne rend la version d'Android et l'appel à `BiometricManager.canAuthenticate`
 * injectables pour les tests unitaires.
 */
@Singleton
class BiometricHelper internal constructor(
    private val context: Context,
    private val sdkInt: Int,
    private val canAuthenticateWith: (authenticators: Int) -> Int,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
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
     * Lance le prompt biométrique SANS CryptoObject.
     * Utilisé pour l'authentification basée sur le temps (time-based auth)
     * où une validation biométrique réussie suffit à satisfaire l'exigence
     * d'authentification de la MasterKey, sans déchiffrement per-use. Les moyens acceptés sont ceux
     * de [vaultAuthenticators], qui dépendent de l'API.
     *
     * @param activity FragmentActivity requise par BiometricPrompt
     * @param title Titre affiché dans le prompt
     * @param subtitle Sous-titre affiché dans le prompt
     * @param onSuccess Callback avec le résultat d'authentification
     * @param onError Callback en cas d'échec
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Déverrouiller NextSH",
        subtitle: String = "Authentification requise pour accéder au vault",
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.d("Biometric auth succeeded (no CryptoObject)")
                onSuccess(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Timber.w("Biometric auth error $errorCode: $errString")
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                Timber.d("Biometric auth failed (retry possible)")
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(vaultAuthenticators(sdkInt))
            .build()

        prompt.authenticate(promptInfo)
    }
}
