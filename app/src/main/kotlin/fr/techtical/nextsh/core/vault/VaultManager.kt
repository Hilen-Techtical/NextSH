// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal const val AUTH_MASTER_KEY_ALIAS = "nextsh_vault_auth_key"
internal const val AUTH_VALIDITY_SECONDS = 300
internal const val VAULT_FILE_NAME_V2 = "nextsh_vault_v2"

/**
 * Levée quand la fenêtre d'authentification du vault a expiré.
 * L'UI doit déclencher un BiometricPrompt avant de réessayer.
 */
class VaultAuthExpiredException(cause: Throwable? = null) : Exception(
    "Authentification vault expirée : veuillez vous ré-authentifier", cause
)

/**
 * Gestionnaire du vault local : stocke les credentials chiffrés
 * dans EncryptedSharedPreferences (AES-256 via Android Keystore).
 *
 * Stocke :
 * - Mots de passe SSH (clé = "pwd_{credentialId}")
 * - Clés privées SSH en PEM (clé = "key_{keyId}")
 * - Certificats (clé = "cert_{certId}")
 */
@Singleton
class VaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        internal const val LEGACY_VAULT_FILE = "nextsh_vault"
    }

    // Jetpack Security a deprecie MasterKey et EncryptedSharedPreferences en
    // 1.1.0 au profit d un usage direct du Keystore Android. Le remplacement
    // est une reecriture du format de stockage du vault, donc un chantier a
    // part entiere : la version stable est adoptee ici sans changer le schema.
    // La suppression reste posee sur les seules declarations concernees.
    @Suppress("DEPRECATION")
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, AUTH_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(true, AUTH_VALIDITY_SECONDS)
            .setRequestStrongBoxBacked(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            VAULT_FILE_NAME_V2,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Auth helper ──────────────────────────────────────────────────────────────

    private inline fun <T> withVaultAuth(block: () -> T): T {
        return try {
            block()
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            throw VaultAuthExpiredException(e)
        }
    }

    /**
     * Retourne true si une (ré-)authentification est nécessaire avant d'accéder au vault.
     */
    fun isAuthRequired(): Boolean {
        return try {
            encryptedPrefs.all  // tente un accès léger
            false
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            true
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException ou autre
            true
        }
    }

    // ── Mots de passe ────────────────────────────────────────────────────────────

    /** Stocke un mot de passe chiffré lié à un credentialId */
    fun storePassword(credentialId: String, password: CharArray) = withVaultAuth {
        encryptedPrefs.edit()
            .putString("pwd_$credentialId", String(password))
            .apply()
        password.fill('\u0000') // wipe immédiat
        Timber.d("Password stored for credential $credentialId")
    }

    /** Récupère un mot de passe. L'appelant DOIT wipe le CharArray après usage. */
    fun getPassword(credentialId: String): CharArray? = withVaultAuth {
        val value = encryptedPrefs.getString("pwd_$credentialId", null)
        value?.toCharArray()
    }

    fun deletePassword(credentialId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("pwd_$credentialId").apply()
        Timber.d("Password deleted for credential $credentialId")
    }

    // ── Clés privées SSH ─────────────────────────────────────────────────────────

    /** Stocke une clé privée PEM chiffrée dans le vault */
    fun storePrivateKey(keyId: String, privateKeyPem: String) = withVaultAuth {
        encryptedPrefs.edit()
            .putString("key_$keyId", privateKeyPem)
            .apply()
        Timber.d("Private key stored for key $keyId")
    }

    /** Récupère une clé privée PEM */
    fun getPrivateKey(keyId: String): String? = withVaultAuth {
        encryptedPrefs.getString("key_$keyId", null)
    }

    fun deletePrivateKey(keyId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("key_$keyId").apply()
        Timber.d("Private key deleted for key $keyId")
    }

    // ── Passphrases de clés SSH chiffrées ────────────────────────────────────────

    /** Stocke la passphrase d'une clé privée chiffrée (prefix "keypass_"). */
    fun storeKeyPassphrase(keyId: String, passphrase: CharArray) = withVaultAuth {
        encryptedPrefs.edit()
            .putString("keypass_$keyId", String(passphrase))
            .apply()
        passphrase.fill('\u0000')
        Timber.d("Key passphrase stored for key $keyId")
    }

    /** Récupère la passphrase. L'appelant DOIT wipe le CharArray après usage. */
    fun getKeyPassphrase(keyId: String): CharArray? = withVaultAuth {
        val value = encryptedPrefs.getString("keypass_$keyId", null)
        value?.toCharArray()
    }

    fun deleteKeyPassphrase(keyId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("keypass_$keyId").apply()
        Timber.d("Key passphrase deleted for key $keyId")
    }

    fun listStoredKeyPassphraseIds(): List<String> = withVaultAuth {
        encryptedPrefs.all.keys
            .filter { it.startsWith("keypass_") }
            .map { it.removePrefix("keypass_") }
    }

    // ── Certificats ──────────────────────────────────────────────────────────────

    fun storeCertificate(certId: String, certPem: String) = withVaultAuth {
        encryptedPrefs.edit()
            .putString("cert_$certId", certPem)
            .apply()
    }

    fun getCertificate(certId: String): String? = withVaultAuth {
        encryptedPrefs.getString("cert_$certId", null)
    }

    fun deleteCertificate(certId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("cert_$certId").apply()
    }

    // ── Vault status ─────────────────────────────────────────────────────────────

    /** Vérifie si le vault est initialisé (contient au moins une entrée) */
    fun isInitialized(): Boolean = withVaultAuth {
        encryptedPrefs.all.isNotEmpty()
    }

    /** Efface tout le vault : DESTRUCTIF, confirmation requise en amont */
    fun wipeVault() = withVaultAuth {
        encryptedPrefs.edit().clear().apply()
        Timber.w("Vault wiped entirely")
    }

    /** Liste les credentialIds stockés (pour sync avec la DB) */
    fun listStoredCredentialIds(): List<String> = withVaultAuth {
        encryptedPrefs.all.keys
            .filter { it.startsWith("pwd_") }
            .map { it.removePrefix("pwd_") }
    }

    /** Liste les keyIds stockés */
    fun listStoredKeyIds(): List<String> = withVaultAuth {
        encryptedPrefs.all.keys
            .filter { it.startsWith("key_") }
            .map { it.removePrefix("key_") }
    }

    /** Liste les certificateIds stockés */
    fun listStoredCertificateIds(): List<String> = withVaultAuth {
        encryptedPrefs.all.keys
            .filter { it.startsWith("cert_") }
            .map { it.removePrefix("cert_") }
    }
}
