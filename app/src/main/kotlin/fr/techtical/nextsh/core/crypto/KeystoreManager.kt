// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Génère une paire de clés EC P-256 dans le Keystore Android,
     * protégée par authentification biométrique.
     * La clé privée ne quitte JAMAIS le Keystore (getEncoded() = null).
     *
     * @param alias Alias unique dans le Keystore (ex: "nextsh_bio_<uuid>")
     * @return La clé publique Java (pour export au format OpenSSH)
     */
    fun generateBiometricSshKey(alias: String): java.security.PublicKey {
        val kpg = java.security.KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )

        return try {
            kpg.initialize(biometricKeySpec(alias, strongBox = true))
            kpg.generateKeyPair().public.also {
                Timber.i("Biometric SSH key generated in StrongBox: $alias")
            }
        } catch (e: Exception) {
            // Repli TEE si StrongBox est absent du materiel.
            Timber.w("StrongBox unavailable for biometric key, falling back to TEE: ${e.message}")
            kpg.initialize(biometricKeySpec(alias, strongBox = false))
            kpg.generateKeyPair().public.also {
                Timber.i("Biometric SSH key generated in TEE: $alias")
            }
        }
    }

    /**
     * Specification commune aux deux tentatives, StrongBox puis TEE.
     *
     * setUserAuthenticationParameters n existe qu a partir d Android 11. Le
     * minimum du projet etant Android 10, l appeler sans garde levait un
     * NoSuchMethodError a la generation d une cle SSH biometrique, hors du
     * try de repli donc fatal. L equivalent Android 10 est une duree de
     * validite de -1, qui exige elle aussi une authentification a chaque
     * usage, via le CryptoObject du BiometricPrompt.
     */
    private fun biometricKeySpec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0, // authentification exigee a chaque usage
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

    /**
     * Récupère la clé privée du Keystore pour signature.
     * Nécessite une authentification biométrique préalable (via BiometricPrompt CryptoObject).
     *
     * @throws java.security.KeyStoreException si l'alias n'existe pas
     * @throws android.security.keystore.UserNotAuthenticatedException si la biométrie n'est pas passée
     */
    fun getBiometricPrivateKey(alias: String): java.security.PrivateKey {
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey
    }

    /** Supprime une clé biométrique du Keystore */
    fun deleteBiometricKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            Timber.d("Biometric key deleted: $alias")
        }
    }

    /**
     * Obtient un Signature initialisé pour signer avec la clé biométrique.
     * Le Signature résultant doit être passé dans un BiometricPrompt.CryptoObject
     * pour que l'utilisateur autorise la signature.
     */
    fun getSignatureForBiometricKey(alias: String): java.security.Signature {
        val privateKey = getBiometricPrivateKey(alias)
        return java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
        }
    }
}
