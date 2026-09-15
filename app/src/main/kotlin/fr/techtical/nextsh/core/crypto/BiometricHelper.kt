// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.content.Context
import androidx.biometric.BiometricManager as AndroidBiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper autour de BiometricPrompt API.
 * Gère l'authentification biométrique pour le déverrouillage du vault
 * et la signature de clés liées au Keystore.
 */
@Singleton
class BiometricHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class BiometricCapability {
        AVAILABLE,              // Biométrie + device credential prêts
        DEVICE_CREDENTIAL_ONLY, // Pas de biométrie, mais PIN/pattern/password disponible
        NO_HARDWARE,            // Aucun moyen d'authentification disponible
        NOT_ENROLLED,           // Capteur présent mais aucune empreinte/PIN configuré
        UNAVAILABLE,            // Temporairement indisponible
    }

    /** Vérifie la capacité d'authentification de l'appareil (biométrie ou PIN/pattern) */
    fun checkCapability(): BiometricCapability {
        val manager = AndroidBiometricManager.from(context)

        // Check biométrie + device credential ensemble
        val combined = manager.canAuthenticate(
            AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG
                    or AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (combined == AndroidBiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricCapability.AVAILABLE
        }

        // Fallback : vérifier device credential seul (PIN/pattern/password)
        // Nécessaire sur certains devices sans capteur biométrique ou API 29
        val deviceCredential = manager.canAuthenticate(
            AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (deviceCredential == AndroidBiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricCapability.DEVICE_CREDENTIAL_ONLY
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
     * Lance le prompt biométrique avec un Cipher pour déclencher
     * le déchiffrement du vault via le Keystore.
     *
     * @param activity FragmentActivity requise par BiometricPrompt
     * @param cipher Cipher initialisé avec la vault key (nécessite auth)
     * @param onSuccess Callback avec le CryptoObject authentifié
     * @param onError Callback en cas d'échec
     */
    fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String = "Déverrouiller NextSH",
        subtitle: String = "Authentification requise pour accéder au vault",
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (Int, String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.d("Biometric auth succeeded")
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
            .setAllowedAuthenticators(
                AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG
                        or AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(
            promptInfo,
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    /**
     * Lance le prompt biométrique SANS CryptoObject.
     * Utilisé pour l'authentification basée sur le temps (time-based auth)
     * où une validation biométrique réussie suffit à satisfaire l'exigence
     * d'authentification de la MasterKey, sans déchiffrement per-use.
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
            .setAllowedAuthenticators(
                AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG
                        or AndroidBiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }
}
