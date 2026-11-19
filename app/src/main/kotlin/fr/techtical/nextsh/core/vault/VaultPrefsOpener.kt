// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Les deux étapes Android de l'ouverture du vault, séparées pour que [VaultManager] puisse
 * intercaler la [MasterKeyProbe] entre elles, et pour tester cet enchaînement sur la JVM.
 */
internal interface VaultPrefsOpener {

    /**
     * Crée la clé maître dans le Keystore si elle manque. N'exige aucune authentification. Elle
     * passe AVANT la sonde, qui traite une clé absente comme anormale (échec fermé) : sans cette
     * étape, Tink créerait lui-même la clé sous cet alias, sans authentification utilisateur.
     */
    fun ensureMasterKey()

    /**
     * Crée EncryptedSharedPreferences. C'est ici que Tink déchiffre ses jeux de clés avec la clé
     * maître : la fenêtre d'authentification doit être ouverte (voir [MasterKeyProbe]).
     */
    fun open(): SharedPreferences

    /**
     * Les préférences BRUTES du fichier du vault : la même instance que celle qu'enveloppe
     * EncryptedSharedPreferences, sans la clé maître ni aucun déchiffrement. Ne sert qu'à l'effacement
     * (voir [VaultManager.wipeAllEntries]) : les noms et les valeurs y sont chiffrés.
     */
    fun rawPrefs(): SharedPreferences = throw UnsupportedOperationException("No raw preferences")

    /** Supprime l'ancien fichier du vault v1. Vrai s'il n'en reste rien. */
    fun deleteLegacyVault(): Boolean = true

    /** Vrai si l'ancien fichier du vault v1 existe encore. */
    fun legacyVaultPresent(): Boolean = false
}

internal class AndroidVaultPrefsOpener(
    private val context: Context,
) : VaultPrefsOpener {

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

    // Lire la propriété suffit : le lazy construit le MasterKey, qui crée la clé si besoin. Les
    // alternatives changeraient le comportement (isKeyStoreBacked ajoute un accès au Keystore, et
    // un check dessus lèverait une exception de plus) : mieux vaut taire l'avertissement.
    @Suppress("UNUSED_EXPRESSION")
    override fun ensureMasterKey() {
        masterKey
    }

    override fun rawPrefs(): SharedPreferences =
        context.getSharedPreferences(VAULT_FILE_NAME_V2, Context.MODE_PRIVATE)

    override fun deleteLegacyVault(): Boolean =
        context.deleteSharedPreferences(VaultManager.LEGACY_VAULT_FILE)

    override fun legacyVaultPresent(): Boolean =
        java.io.File(context.applicationInfo.dataDir, "shared_prefs/${VaultManager.LEGACY_VAULT_FILE}.xml").exists()

    @Suppress("DEPRECATION")
    override fun open(): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            VAULT_FILE_NAME_V2,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
