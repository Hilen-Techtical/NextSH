// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import javax.inject.Inject

/**
 * UseCase : établir une connexion SSH vers un hôte.
 * Orchestre la récupération des credentials depuis le vault
 * et la connexion via le SshSessionManager.
 */
class ConnectSessionUseCase @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val hostRepository: HostRepository,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val keystoreManager: KeystoreManager,
    private val sshKeyManager: SshKeyManager,
) {
    /**
     * Pour BIOMETRIC_KEY, l'appelant DOIT d'abord obtenir un Signature authentifié
     * via BiometricPrompt.CryptoObject, puis le passer ici.
     */
    suspend operator fun invoke(
        host: Host,
        authenticatedSignature: java.security.Signature? = null,
    ): SshResult<SshSession> {
        return try {
            when (host.authType) {
                AuthType.PASSWORD -> {
                    try {
                        val password = vaultManager.getPassword(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                "Mot de passe introuvable dans le vault pour ${host.label}"
                            )
                        val result = sessionManager.connectWithPassword(host, password)
                        // password wiped inside connectWithPassword

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: VaultAuthExpiredException) {
                        return SshResult.Error(
                            SshErrorCode.AUTH_EXPIRED,
                            "Authentification vault expirée : veuillez vous ré-authentifier"
                        )
                    }
                }

                AuthType.SSH_KEY -> {
                    try {
                        val privateKey = vaultManager.getPrivateKey(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                "Clé SSH introuvable dans le vault pour ${host.label}"
                            )
                        val result = sessionManager.connectWithKey(host, privateKey)

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: VaultAuthExpiredException) {
                        return SshResult.Error(
                            SshErrorCode.AUTH_EXPIRED,
                            "Authentification vault expirée : veuillez vous ré-authentifier"
                        )
                    }
                }

                AuthType.CERTIFICATE -> {
                    try {
                        val privateKey = vaultManager.getPrivateKey(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                "Clé SSH introuvable dans le vault pour ${host.label}"
                            )
                        val certificate = vaultManager.getCertificate(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                "Certificat introuvable dans le vault pour ${host.label}"
                            )
                        val result = sessionManager.connectWithCertificate(host, privateKey, certificate)

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: VaultAuthExpiredException) {
                        return SshResult.Error(
                            SshErrorCode.AUTH_EXPIRED,
                            "Authentification vault expirée : veuillez vous ré-authentifier"
                        )
                    }
                }

                AuthType.FIDO2 -> {
                    val fido2Mode = host.fido2Mode
                        ?: return SshResult.Error(
                            SshErrorCode.FIDO2_NO_KEY,
                            "Mode FIDO2 non configuré pour ${host.label}"
                        )

                    when (fido2Mode) {
                        Fido2Mode.HARDWARE_KEY -> {
                            // Clé matérielle : auth sk-* via SkAuthMethod + hardware key
                            val sshKey = sshKeyRepository.getById(host.credentialId)
                                ?: return SshResult.Error(
                                    SshErrorCode.FIDO2_NO_KEY,
                                    "Clé FIDO2 introuvable pour ${host.label}"
                                )
                            val skPublicKey = SkSshPublicKey.fromAuthorizedKeysLine(sshKey.publicKey)
                                ?: return SshResult.Error(
                                    SshErrorCode.AUTH_FAILED,
                                    "Format de clé FIDO2 invalide pour ${sshKey.label}"
                                )
                            val result = skPublicKey.use {
                                sessionManager.connectWithSkKey(host, it)
                            }

                            if (result is SshResult.Success) {
                                hostRepository.updateLastConnected(host.id)
                            }
                            result
                        }

                        Fido2Mode.PASSKEY -> {
                            // Passkey : Credential Manager vérifie l'identité, puis auth SSH standard
                            // Le ViewModel a déjà déclenché Fido2AuthManager.getCredential() avant invoke()
                            val sshKey = sshKeyRepository.getById(host.credentialId)
                                ?: return SshResult.Error(
                                    SshErrorCode.FIDO2_NO_KEY,
                                    "Clé SSH associée au passkey introuvable pour ${host.label}"
                                )
                            val privateKey = vaultManager.getPrivateKey(sshKey.id)
                                ?: return SshResult.Error(
                                    SshErrorCode.AUTH_FAILED,
                                    "Clé privée introuvable dans le vault pour ${sshKey.label}"
                                )
                            val result = sessionManager.connectWithKey(host, privateKey)

                            if (result is SshResult.Success) {
                                hostRepository.updateLastConnected(host.id)
                            }
                            result
                        }
                    }
                }

                AuthType.BIOMETRIC_KEY -> {
                    // Récupérer la clé SSH pour obtenir le keystoreAlias
                    val sshKey = sshKeyRepository.getById(host.credentialId)
                        ?: return SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            "Clé SSH biométrique introuvable pour ${host.label}"
                        )
                    val alias = sshKey.keystoreAlias
                        ?: return SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            "Alias Keystore manquant pour la clé biométrique ${sshKey.label}"
                        )

                    // L'appelant (ViewModel) doit avoir déclenché BiometricPrompt
                    // avec CryptoObject(keystoreManager.getSignatureForBiometricKey(alias))
                    // et passer le Signature authentifié ici.
                    if (authenticatedSignature == null) {
                        return SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            "Authentification biométrique requise : déclenchez le BiometricPrompt d'abord"
                        )
                    }

                    try {
                        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                            .apply { load(null) }
                        val publicKey = keyStore.getCertificate(alias).publicKey
                        // La clé privée dans le Keystore est accessible via le Signature authentifié.
                        // On récupère le handle privé (opaque, ne quitte pas le hardware).
                        val privateKey = (keyStore.getEntry(alias, null) as java.security.KeyStore.PrivateKeyEntry).privateKey

                        val keyProvider = sshKeyManager.getKeystoreKeyProvider(alias, publicKey, privateKey)
                        val result = sessionManager.connectWithKeyProvider(host, keyProvider)

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                        SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            "Clé biométrique invalidée (empreinte modifiée) : régénérez-la"
                        )
                    } catch (e: android.security.keystore.UserNotAuthenticatedException) {
                        SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            "Authentification biométrique expirée : réessayez"
                        )
                    } catch (e: java.security.KeyStoreException) {
                        SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            "Clé biométrique invalide ou supprimée : régénérez-la"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
        }
    }
}
