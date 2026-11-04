// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal const val AUTH_MASTER_KEY_ALIAS = "nextsh_vault_auth_key"

/**
 * Durée, en secondes, pendant laquelle une authentification (code, schéma, mot de passe ou
 * biométrie forte) autorise l'usage de la clé maître du vault.
 *
 * Cette fenêtre ne compte qu'à la PREMIÈRE ouverture du vault dans le processus : voir [VaultManager].
 */
internal const val AUTH_VALIDITY_SECONDS = 300
internal const val VAULT_FILE_NAME_V2 = "nextsh_vault_v2"

/**
 * Levée quand la fenêtre d'authentification du vault a expiré à son ouverture.
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
 *
 * Fenêtre d'authentification : la clé maître exige une authentification de moins de
 * [AUTH_VALIDITY_SECONDS] s, mais seulement à la PREMIÈRE ouverture du vault dans le processus.
 * EncryptedSharedPreferences déchiffre alors ses jeux de clés et ne sollicite plus jamais le
 * Keystore ensuite : une fois ouvert, le vault reste ouvert jusqu'à la mort du processus, il ne se
 * reverrouille pas au bout de 300 s. Déverrouiller l'écran du téléphone rafraîchit aussi la
 * fenêtre, mais seulement par un code PIN, un schéma ou un mot de passe, ou par une biométrie forte
 * (classe 3), dans la fenêtre : le visage de classe 2 et Smart Lock ne comptent pas. Si elle a
 * expiré à la première ouverture, la [MasterKeyProbe] le détecte et l'appelant reçoit
 * [VaultAuthExpiredException] ; l'ouverture est alors réessayée à l'accès suivant.
 */
@Singleton
class VaultManager internal constructor(
    private val masterKeyProbe: MasterKeyProbe,
    private val prefsOpener: VaultPrefsOpener,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(KeystoreMasterKeyProbe(), AndroidVaultPrefsOpener(context))

    companion object {
        internal const val LEGACY_VAULT_FILE = "nextsh_vault"
    }

    /**
     * Toute opération du vault passe par ici : la sonde de la clé maître précède donc TOUJOURS la
     * première création d'EncryptedSharedPreferences. Un `lazy` ne garde pas un initialiseur qui a
     * échoué : après une authentification réussie, l'accès suivant relance toute la séquence.
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        prefsOpener.ensureMasterKey()
        probeMasterKey()
        openPrefs()
    }

    /**
     * Crée EncryptedSharedPreferences. La fenêtre peut expirer entre la sonde et la création : si
     * l'ouverture échoue, la clé maître est sondée une seconde fois. Une fenêtre alors expirée est
     * la vraie cause, son exception prime et l'échec d'origine lui est joint en supprimé ; sinon
     * l'échec d'origine remonte tel quel. Les `Error` ne sont pas interceptées.
     */
    private fun openPrefs(): SharedPreferences {
        try {
            return prefsOpener.open()
        } catch (e: Exception) {
            try {
                probeMasterKey()
            } catch (probeFailure: Exception) {
                if (probeFailure !== e) probeFailure.addSuppressed(e)
                throw probeFailure
            }
            throw e
        }
    }

    /** Voir [MasterKeyProbe] : empêche Tink d'atteindre son repli sur un jeu de clés en clair. */
    private fun probeMasterKey() {
        try {
            masterKeyProbe.probe()
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            Timber.d("Vault master key probe: authentication window expired, vault not opened")
            throw e
        }
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
     * Une fois le vault ouvert dans le processus, retourne false : il ne se reverrouille pas.
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
