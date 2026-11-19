// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.shared.util.LogId
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

        /**
         * La clé dont [open] et [isAuthRequired] demandent l'existence. Elle n'est jamais écrite : seule
         * compte l'ouverture du jeu de clés que le test déclenche. Ce n'est pas une clé réservée
         * d'EncryptedSharedPreferences (qui lèverait une SecurityException) et aucun préfixe de secret
         * (`pwd_`, `key_`, `keypass_`, `cert_`) ne la désigne.
         */
        internal const val OPEN_PROBE_KEY = "nextsh_open_probe"

        /**
         * Préfixe des deux entrées réservées d'EncryptedSharedPreferences (les jeux de clés Tink des noms
         * et des valeurs, chiffrés par la clé maître). Elles ne sont jamais effacées : voir [wipeAllEntries].
         */
        internal const val RESERVED_KEY_PREFIX = "__androidx_security_crypto_encrypted_prefs_"
    }

    /**
     * Toute opération du vault passe par ici : la sonde de la clé maître précède donc TOUJOURS la
     * première création d'EncryptedSharedPreferences. Un `lazy` ne garde pas un initialiseur qui a
     * échoué : après une authentification réussie, l'accès suivant relance toute la séquence.
     */
    private val prefsLazy = lazy {
        prefsOpener.ensureMasterKey()
        probeMasterKey()
        openPrefs()
    }

    private val encryptedPrefs: SharedPreferences by prefsLazy

    /**
     * True once the vault has been opened in this process. Touches neither the Keystore, a file nor
     * any secret: it only reads the state of the lazy opener, which stays uninitialized when the
     * opening failed. Background work uses it to avoid opening the vault, which is only allowed in
     * the foreground (an expired authentication window would otherwise surface as an exception).
     */
    fun isOpen(): Boolean = prefsLazy.isInitialized()

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
     * Force l'ouverture du vault maintenant, sans rien lire ni écrire d'autre. Sert au
     * déverrouillage de l'app : le vault s'ouvre dans la fenêtre de [AUTH_VALIDITY_SECONDS] s qui suit
     * l'authentification, plutôt qu'au premier usage, parfois bien plus tard et hors fenêtre.
     * Sans effet si le vault est déjà ouvert dans le processus.
     *
     * Le test d'une clé ([OPEN_PROBE_KEY]) suffit à forcer l'ouverture (la création des
     * EncryptedSharedPreferences, donc le jeu de clés) sans déchiffrer une seule valeur : aucun
     * secret n'est lu en clair, contrairement à un `all`, qui les déchiffrerait tous en String.
     *
     * @throws VaultAuthExpiredException si la fenêtre d'authentification est close : rien n'est ouvert,
     * l'appel suivant recommence toute la séquence. Une clé invalidée lève
     * `KeyPermanentlyInvalidatedException`, telle quelle.
     */
    fun open() {
        withVaultAuth { encryptedPrefs.contains(OPEN_PROBE_KEY) }
    }

    /**
     * Retourne true si une (ré-)authentification est nécessaire avant d'accéder au vault.
     * Une fois le vault ouvert dans le processus, retourne false : il ne se reverrouille pas.
     * Même sonde légère que [open] : aucun secret n'est déchiffré pour répondre.
     */
    fun isAuthRequired(): Boolean {
        return try {
            encryptedPrefs.contains(OPEN_PROBE_KEY)  // tente un accès léger, sans rien déchiffrer
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
        Timber.d("Password stored for credential ${LogId.of(credentialId)}")
    }

    /** Récupère un mot de passe. L'appelant DOIT wipe le CharArray après usage. */
    fun getPassword(credentialId: String): CharArray? = withVaultAuth {
        val value = encryptedPrefs.getString("pwd_$credentialId", null)
        value?.toCharArray()
    }

    /**
     * Dit si un mot de passe est stocké pour [credentialId], sans le lire : `contains` ne chiffre
     * que le NOM de l'entrée et ne déchiffre aucune valeur, contrairement à `all` ou à `getString`
     * qui sortent le secret en String. C'est ce qui sert à compter les mots de passe.
     */
    fun hasPassword(credentialId: String): Boolean = withVaultAuth {
        encryptedPrefs.contains("pwd_$credentialId")
    }

    fun deletePassword(credentialId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("pwd_$credentialId").apply()
        Timber.d("Password deleted for credential ${LogId.of(credentialId)}")
    }

    // ── Clés privées SSH ─────────────────────────────────────────────────────────

    /** Stocke une clé privée PEM chiffrée dans le vault */
    fun storePrivateKey(keyId: String, privateKeyPem: String) = withVaultAuth {
        encryptedPrefs.edit()
            .putString("key_$keyId", privateKeyPem)
            .apply()
        Timber.d("Private key stored for key ${LogId.of(keyId)}")
    }

    /**
     * Dit si une clé privée est stockée pour [keyId], sans la lire : `contains` ne déchiffre aucune valeur
     * (même principe que [hasPassword]).
     */
    fun hasPrivateKey(keyId: String): Boolean = withVaultAuth {
        encryptedPrefs.contains("key_$keyId")
    }

    /** Récupère une clé privée PEM */
    fun getPrivateKey(keyId: String): String? = withVaultAuth {
        encryptedPrefs.getString("key_$keyId", null)
    }

    fun deletePrivateKey(keyId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("key_$keyId").apply()
        Timber.d("Private key deleted for key ${LogId.of(keyId)}")
    }

    // ── Passphrases de clés SSH chiffrées ────────────────────────────────────────

    /** Stocke la passphrase d'une clé privée chiffrée (prefix "keypass_"). */
    fun storeKeyPassphrase(keyId: String, passphrase: CharArray) = withVaultAuth {
        encryptedPrefs.edit()
            .putString("keypass_$keyId", String(passphrase))
            .apply()
        passphrase.fill('\u0000')
        Timber.d("Key passphrase stored for key ${LogId.of(keyId)}")
    }

    /** Récupère la passphrase. L'appelant DOIT wipe le CharArray après usage. */
    fun getKeyPassphrase(keyId: String): CharArray? = withVaultAuth {
        val value = encryptedPrefs.getString("keypass_$keyId", null)
        value?.toCharArray()
    }

    fun deleteKeyPassphrase(keyId: String) = withVaultAuth {
        encryptedPrefs.edit().remove("keypass_$keyId").apply()
        Timber.d("Key passphrase deleted for key ${LogId.of(keyId)}")
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

    /**
     * Efface tout le vault : DESTRUCTIF, confirmation requise en amont. Sans ouvrir le vault (voir
     * [wipeAllEntries]) : aucune authentification n'est exigée, et rien n'est déchiffré.
     *
     * @throws java.io.IOException si une entrée reste après l'effacement
     */
    fun wipeVault() {
        if (!wipeAllEntries()) throw java.io.IOException("Vault could not be erased")
    }

    /**
     * Efface toutes les entrées du vault en travaillant sur les PRÉFÉRENCES BRUTES du fichier, la même
     * instance que celle qu'enveloppe EncryptedSharedPreferences : rien n'est déchiffré. Un `clear()` de
     * l'enveloppe parcourt `getAll()`, qui déchiffre chaque nom ET chaque valeur en `String` : tous les
     * mots de passe et toutes les clés PEM sortiraient en clair, exactement ce que l'invariant du
     * vault interdit. Ne jamais lire `encryptedPrefs.all` ni appeler `clear()` dessus.
     *
     * Ni la clé maître du Keystore, ni la sonde, ni l'ouverture du vault ne sont sollicitées : l'effacement
     * reste possible fenêtre d'authentification close, ou clé maître invalidée. Les deux entrées réservées
     * d'EncryptedSharedPreferences (les jeux de clés Tink, chiffrés par la clé maître) restent : elles ne
     * contiennent aucun secret de l'utilisateur et l'instance ouverte en a besoin. L'ancien vault v1 est
     * supprimé aussi s'il existe.
     *
     * L'instance ouverte reste cohérente (même `SharedPreferencesImpl`) : elle voit un vault vide.
     *
     * @return vrai si l'écriture est confirmée (`commit`), qu'aucune entrée non réservée ne reste et
     * que l'ancien vault est parti
     */
    fun wipeAllEntries(): Boolean {
        val raw = prefsOpener.rawPrefs()
        val editor = raw.edit()
        for (key in raw.all.keys) {
            if (!key.startsWith(RESERVED_KEY_PREFIX)) editor.remove(key)
        }
        val committed = editor.commit()
        val legacyGone = prefsOpener.deleteLegacyVault()
        val clean = holdsNoEntry(raw)
        Timber.w("Vault wiped entirely")
        return committed && legacyGone && clean
    }

    /**
     * Dit si le vault est vraiment vide : plus aucune entrée non réservée dans son fichier, plus d'ancien
     * vault v1. Même lecture brute que [wipeAllEntries], aucun secret déchiffré.
     */
    fun isWiped(): Boolean = holdsNoEntry(prefsOpener.rawPrefs()) && !prefsOpener.legacyVaultPresent()

    private fun holdsNoEntry(raw: SharedPreferences): Boolean =
        raw.all.keys.none { !it.startsWith(RESERVED_KEY_PREFIX) }

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
