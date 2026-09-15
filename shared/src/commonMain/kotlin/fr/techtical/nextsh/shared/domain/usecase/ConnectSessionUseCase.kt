// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.usecase

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.ssh.SshSessionManager
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException
import fr.techtical.nextsh.shared.domain.vault.VaultManager

/**
 * UseCase : établir une connexion SSH vers un hôte.
 * Orchestre la récupération des credentials depuis le vault
 * et la connexion via le SshSessionManager.
 *
 * Note: Les branches BIOMETRIC_KEY et FIDO2 sont Android-only et ne sont
 * pas disponibles dans cette couche partagée. Elles retournent une erreur
 * explicite. Les implémentations plateforme-spécifiques doivent étendre
 * ce use case ou fournir leur propre orchestration.
 */
class ConnectSessionUseCase(
    private val sessionManager: SshSessionManager,
    private val hostRepository: HostRepository,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
) {
    suspend operator fun invoke(host: Host): SshResult<SshSession> {
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

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: VaultAuthExpiredException) {
                        SshResult.Error(
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
                        val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                        val result = try {
                            sessionManager.connectWithKey(host, privateKey, passphrase)
                        } finally {
                            passphrase?.fill('\u0000')
                        }

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: VaultAuthExpiredException) {
                        SshResult.Error(
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
                        val passphrase = vaultManager.getKeyPassphrase(host.credentialId)
                        val result = try {
                            sessionManager.connectWithCertificate(host, privateKey, certificate, passphrase)
                        } finally {
                            passphrase?.fill('\u0000')
                        }

                        if (result is SshResult.Success) {
                            hostRepository.updateLastConnected(host.id)
                        }
                        result
                    } catch (e: VaultAuthExpiredException) {
                        SshResult.Error(
                            SshErrorCode.AUTH_EXPIRED,
                            "Authentification vault expirée : veuillez vous ré-authentifier"
                        )
                    }
                }

                AuthType.FIDO2 -> {
                    // FIDO2 auth is Android-only (YubiKit/CTAP2 + Credential Manager).
                    // Platform layer must handle this before calling shared use case,
                    // or provide a platform-specific override.
                    SshResult.Error(
                        SshErrorCode.UNKNOWN,
                        "FIDO2 auth not available in shared layer yet"
                    )
                }

                AuthType.BIOMETRIC_KEY -> {
                    // Biometric key auth requires Android Keystore + BiometricPrompt.
                    // Platform layer must handle this before calling shared use case,
                    // or provide a platform-specific override.
                    SshResult.Error(
                        SshErrorCode.UNKNOWN,
                        "Biometric key auth not available in shared layer yet"
                    )
                }
            }
        } catch (e: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
        }
    }
}
