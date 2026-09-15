// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestionnaire FIDO2/Passkey via Android Credential Manager.
 *
 * EXPERIMENTAL : l'authentification SSH via FIDO2 nécessite un serveur SSH
 * configuré pour accepter les clés FIDO2 (sk-ecdsa-sha2-nistp256@openssh.com
 * ou sk-ssh-ed25519@openssh.com). SSHJ ne supporte pas nativement ce flux ;
 * cette classe prépare les credentials côté client.
 *
 * Flux prévu :
 * 1. Le serveur SSH envoie un challenge lors du handshake
 * 2. Fido2AuthManager déclenche le CredentialManager pour signer le challenge
 * 3. La réponse signée est renvoyée au serveur via un AuthMethod custom SSHJ
 */
@Singleton
class Fido2AuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }

    /**
     * Vérifie si le Credential Manager est disponible sur le device.
     * Retourne false si l'API n'est pas accessible (Android < 9, ou service absent).
     */
    fun isAvailable(): Boolean {
        return try {
            // Credential Manager est disponible si on peut l'instancier sans exception
            credentialManager
            true
        } catch (e: Exception) {
            Timber.w("Credential Manager non disponible: ${e.message}")
            false
        }
    }

    /**
     * Récupère un credential FIDO2/Passkey pour l'authentification SSH.
     *
     * @param activity L'Activity Android (nécessaire pour le prompt biométrique)
     * @param rpId Le Relying Party ID (typiquement le hostname du serveur SSH)
     * @param challenge Le challenge envoyé par le serveur SSH (en base64url)
     * @return [Fido2Result] contenant la réponse signée ou une erreur
     */
    suspend fun getCredential(
        activity: android.app.Activity,
        rpId: String,
        challenge: String,
    ): Fido2Result {
        // Construction de la requête WebAuthn/FIDO2
        // Le JSON suit la spécification WebAuthn pour publicKey.get()
        val requestJson = """
            {
                "challenge": "$challenge",
                "rpId": "$rpId",
                "userVerification": "required",
                "timeout": 60000
            }
        """.trimIndent()

        val publicKeyOption = GetPublicKeyCredentialOption(requestJson)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(publicKeyOption)
            .build()

        return try {
            val response = credentialManager.getCredential(activity, request)
            val credential = response.credential

            if (credential is PublicKeyCredential) {
                Timber.i("FIDO2 credential obtenu pour rpId=$rpId")
                Fido2Result.Success(
                    responseJson = credential.authenticationResponseJson,
                )
            } else {
                Timber.w("Type de credential inattendu: ${credential.type}")
                Fido2Result.Error("Type de credential non supporté: ${credential.type}")
            }
        } catch (e: NoCredentialException) {
            Timber.i("Aucun credential FIDO2 disponible pour rpId=$rpId")
            Fido2Result.Error("Aucun passkey disponible pour ce serveur")
        } catch (e: GetCredentialCancellationException) {
            Timber.i("Authentification FIDO2 annulée par l'utilisateur")
            Fido2Result.Cancelled
        } catch (e: GetCredentialException) {
            Timber.e(e, "Erreur Credential Manager")
            Fido2Result.Error("Erreur d'authentification FIDO2 : vérifiez votre passkey")
        } catch (e: Exception) {
            Timber.e(e, "Erreur inattendue FIDO2")
            Fido2Result.Error("Erreur inattendue lors de l'authentification FIDO2")
        }
    }
}

/** Résultat d'une opération FIDO2 */
sealed class Fido2Result {
    /**
     * Credential FIDO2 obtenu avec succès.
     * @param responseJson La réponse JSON WebAuthn (contient authenticatorData, signature, etc.)
     */
    data class Success(val responseJson: String) : Fido2Result()

    /** Opération annulée par l'utilisateur */
    data object Cancelled : Fido2Result()

    /** Erreur lors de l'opération */
    data class Error(val message: String) : Fido2Result()
}
