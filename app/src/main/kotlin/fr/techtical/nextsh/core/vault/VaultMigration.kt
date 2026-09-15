// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

private const val LEGACY_VAULT_FILE_NAME = "nextsh_vault"
private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
private const val MIGRATION_DONE_FLAG = "nextsh_vault_v2_migrated"

/**
 * Migre le vault v1 (MasterKey sans auth) vers le vault v2 (MasterKey auth-required).
 *
 * La migration lit le vault v1 (EncryptedSharedPreferences sans contrainte d'auth)
 * et réécrit toutes les entrées dans le vault v2 (MasterKey avec setUserAuthenticationRequired).
 *
 * Pré-condition : doit être appelée APRÈS une authentification biométrique/PIN réussie,
 * dans la fenêtre de validité de [AUTH_VALIDITY_SECONDS] secondes du nouveau MasterKey.
 *
 * Appels typiques depuis VaultUnlockViewModel, après BiometricPrompt success.
 */
@Singleton
class VaultMigration @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Vérifie si une migration est nécessaire.
     *
     * Retourne true si le fichier SharedPreferences du vault v1 existe ET
     * que le flag de migration réussie n'a pas encore été posé.
     */
    fun needsMigration(): Boolean {
        if (migrationDoneFile().exists()) return false
        return legacyVaultFile().exists()
    }

    /**
     * Effectue la migration du vault v1 vers le vault v2.
     *
     * @throws VaultAuthExpiredException si l'écriture dans le vault v2 échoue
     *   car la fenêtre d'authentification a expiré.
     */
    fun migrate() {
        val legacyFile = legacyVaultFile()
        if (!legacyFile.exists()) {
            Timber.i("VaultMigration: legacy vault file not found, nothing to migrate")
            return
        }

        try {
            // Step 1: Open legacy vault (no auth required)
            // Jetpack Security 1.1.0 deprecie MasterKey et
            // EncryptedSharedPreferences. Les sortir serait une reecriture du
            // format de stockage du vault, chantier a part entiere : la
            // suppression est posee declaration par declaration, jamais sur la
            // fonction ni sur le fichier.
            @Suppress("DEPRECATION")
            val legacyMasterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            @Suppress("DEPRECATION")
            val legacyPrefs = EncryptedSharedPreferences.create(
                context,
                LEGACY_VAULT_FILE_NAME,
                legacyMasterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            // Step 2: Read all entries
            @Suppress("UNCHECKED_CAST")
            val allEntries = legacyPrefs.all as Map<String, String?>

            if (allEntries.isEmpty()) {
                Timber.i("VaultMigration: legacy vault is empty, cleaning up")
                markMigrationDone()
                cleanupLegacyVault()
                return
            }

            Timber.i("VaultMigration: found ${allEntries.size} entries to migrate")

            // Step 3: Open v2 vault (auth-required, caller must have authenticated within 300s)
            @Suppress("DEPRECATION")
            val authMasterKey = MasterKey.Builder(context, AUTH_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(true, AUTH_VALIDITY_SECONDS)
                .setRequestStrongBoxBacked(true)
                .build()

            @Suppress("DEPRECATION")
            val v2Prefs = EncryptedSharedPreferences.create(
                context,
                VAULT_FILE_NAME_V2,
                authMasterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            // Step 4: Write all entries to v2
            val editor = v2Prefs.edit()
            for ((key, value) in allEntries) {
                if (value != null) {
                    editor.putString(key, value)
                }
            }
            editor.apply()

            Timber.i("VaultMigration: successfully wrote ${allEntries.size} entries to v2 vault")

            // Step 5: Mark migration as done, then cleanup legacy
            markMigrationDone()
            cleanupLegacyVault()

            Timber.i("VaultMigration: migration complete")

        } catch (e: VaultAuthExpiredException) {
            throw e
        } catch (e: UserNotAuthenticatedException) {
            Timber.e(e, "VaultMigration: auth window expired during migration")
            throw VaultAuthExpiredException(e)
        } catch (e: Exception) {
            Timber.e(e, "VaultMigration: unexpected error during migration")
            // Do not delete legacy vault on unexpected error: data safety first
        }
    }

    /**
     * Supprime le fichier du vault v1 et l'alias legacy du Keystore Android.
     * Safe to call even if file/alias don't exist.
     */
    fun cleanupLegacyVault() {
        val legacyFile = legacyVaultFile()
        if (legacyFile.exists()) {
            val deleted = legacyFile.delete()
            if (deleted) {
                Timber.i("VaultMigration: legacy vault file deleted")
            } else {
                Timber.w("VaultMigration: failed to delete legacy vault file")
            }
        }

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
                Timber.i("VaultMigration: legacy Keystore alias deleted")
            }
        } catch (e: Exception) {
            Timber.w(e, "VaultMigration: failed to delete legacy Keystore alias")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun sharedPrefsFile(name: String): File =
        File(context.filesDir.parent, "shared_prefs/$name.xml")

    private fun legacyVaultFile(): File = sharedPrefsFile(LEGACY_VAULT_FILE_NAME)

    private fun migrationDoneFile(): File = File(context.filesDir, MIGRATION_DONE_FLAG)

    private fun markMigrationDone() {
        try {
            migrationDoneFile().createNewFile()
        } catch (e: Exception) {
            Timber.w(e, "VaultMigration: failed to write migration flag")
        }
    }
}
