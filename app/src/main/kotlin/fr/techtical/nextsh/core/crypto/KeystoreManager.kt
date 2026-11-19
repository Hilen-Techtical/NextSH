// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber
import java.security.KeyStore
import java.security.KeyStoreException
import javax.inject.Inject
import javax.inject.Singleton

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** Fixed text of the refusal: the alias is untrusted input and is never quoted. */
internal const val NOT_A_BIOMETRIC_ALIAS_MESSAGE = "Not a biometric key alias"

/** Fixed text of the failure for an entry that is not (any more) in the Keystore: the alias is never quoted. */
internal const val MISSING_BIOMETRIC_KEY_MESSAGE = "Biometric key not found"

/** Fixed text of the failure of [KeystoreManager.deleteAllBiometricKeys]: an alias is never quoted. */
internal const val BIOMETRIC_KEYS_NOT_DELETED_MESSAGE = "Biometric keys could not be deleted"

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
                Timber.i("Biometric SSH key generated in StrongBox")
            }
        } catch (e: Exception) {
            // Repli TEE si StrongBox est absent du materiel.
            Timber.w("StrongBox unavailable for biometric key, falling back to TEE (${errorClass(e)})")
            kpg.initialize(biometricKeySpec(alias, strongBox = false))
            kpg.generateKeyPair().public.also {
                Timber.i("Biometric SSH key generated in TEE")
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
     * Comme [deleteBiometricKey], elle refuse un alias qui n'a pas le format des alias que l'app
     * génère ([BiometricKeyAlias]) : l'alias vient de la base, donc potentiellement d'un backup ou
     * d'une sync, et ne doit jamais donner accès à une autre entrée du Keystore.
     *
     * @throws java.security.KeyStoreException si l'alias n'existe pas, ou n'est pas un alias de clé biométrique
     * @throws android.security.keystore.UserNotAuthenticatedException si la biométrie n'est pas passée
     */
    fun getBiometricPrivateKey(alias: String): java.security.PrivateKey {
        requireBiometricAlias(alias)
        // A missing entry is null here: a KeyStoreException with a fixed text, like the other
        // refusals, not a cast failure that no caller would think to expect.
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw KeyStoreException(MISSING_BIOMETRIC_KEY_MESSAGE)
        return entry.privateKey
    }

    /**
     * The public key of a biometric key, to announce it to the SSH server before the fingerprint is
     * asked for (reading it needs no authentication, only signing does).
     *
     * Same alias guard as [getBiometricPrivateKey]: the alias comes from the database and must never
     * give access to another Keystore entry.
     *
     * @throws java.security.KeyStoreException if the alias is not a biometric key alias, or if the
     * entry is gone (key deleted)
     */
    fun getBiometricPublicKey(alias: String): java.security.PublicKey {
        requireBiometricAlias(alias)
        val certificate = keyStore.getCertificate(alias)
            ?: throw KeyStoreException(MISSING_BIOMETRIC_KEY_MESSAGE)
        return certificate.publicKey
    }

    /** Refuse (KeyStoreException, texte fixe sans l'alias) tout alias qui n'est pas un alias de clé biométrique. */
    private fun requireBiometricAlias(alias: String) {
        if (!BiometricKeyAlias.isValid(alias)) {
            throw KeyStoreException(NOT_A_BIOMETRIC_ALIAS_MESSAGE)
        }
    }

    /**
     * Supprime une clé biométrique du Keystore.
     *
     * L'alias vient de la base, donc potentiellement d'un backup ou d'un payload de sync : il ne
     * supprime rien s'il n'a pas exactement le format des alias que l'app génère
     * ([BiometricKeyAlias]). Sinon un alias forgé pourrait faire supprimer la clé maîtresse du
     * vault (nextsh_vault_auth_key) ou toute autre entrée du Keystore de l'app.
     */
    fun deleteBiometricKey(alias: String) {
        if (!BiometricKeyAlias.isValid(alias)) {
            // Fixed text on purpose: the alias is untrusted input, it does not go to the log.
            Timber.w("deleteBiometricKey refused: the alias is not a biometric key alias")
            return
        }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            Timber.d("Biometric key deleted")
        }
    }

    /**
     * Deletes every biometric key of the app from the Keystore (the "Wipe the vault" action): without
     * it the `ssh_keys` rows are gone but their keys stay usable. Only the aliases that have exactly the
     * format of the ones the app generates are deleted, see [BiometricKeyAlias.biometricAliasesOf]: the
     * master key of the vault stays (an erase that kept it is the decision of 1.1.0).
     *
     * Every deletion is tried before the failure is raised.
     *
     * @throws KeyStoreException if a biometric key is still there after the deletions
     */
    fun deleteAllBiometricKeys() {
        val aliases = BiometricKeyAlias.biometricAliasesOf(java.util.Collections.list(keyStore.aliases()))
        for (alias in aliases) {
            try {
                keyStore.deleteEntry(alias)
            } catch (e: Exception) {
                Timber.w("A biometric key could not be deleted (${errorClass(e)})")
            }
        }
        val left = BiometricKeyAlias.biometricAliasesOf(java.util.Collections.list(keyStore.aliases()))
        if (left.isNotEmpty()) throw KeyStoreException(BIOMETRIC_KEYS_NOT_DELETED_MESSAGE)
        val count = aliases.size
        Timber.i("Biometric keys deleted ($count)")
    }

    /**
     * Obtient un Signature initialisé pour signer avec la clé biométrique.
     * Le Signature résultant doit être passé dans un BiometricPrompt.CryptoObject
     * pour que l'utilisateur autorise la signature.
     */
    fun getSignatureForBiometricKey(alias: String): java.security.Signature {
        requireBiometricAlias(alias)
        val privateKey = getBiometricPrivateKey(alias)
        return java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
        }
    }
}
