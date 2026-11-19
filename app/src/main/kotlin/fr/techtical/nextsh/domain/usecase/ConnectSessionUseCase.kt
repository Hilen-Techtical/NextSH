// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.core.crypto.BiometricKeyAlias
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.crypto.MISSING_BIOMETRIC_KEY_MESSAGE
import fr.techtical.nextsh.core.crypto.NOT_A_BIOMETRIC_ALIAS_MESSAGE
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.keyProblemLogDescription
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.domain.model.fido2ModeOrDefault
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * UseCase : établir une connexion SSH vers un hôte.
 * Orchestre la récupération des credentials depuis le vault
 * et la connexion via le SshSessionManager.
 *
 * Une connexion réussie rend la session avec UNE référence, celle de l'appelant (voir la note de
 * [SshSessionManager]) : il la remet à qui l'utilise, ou la rend avec
 * [SshSessionManager.release]. Jusqu'à ce que le résultat lui soit remis, la référence est à ce
 * use case, qui la rend si quelque chose l'empêche de livrer (voir [recordingConnection]) : une
 * annulation, ou une erreur, après la connexion ne laisse pas de session que personne ne possède.
 */
class ConnectSessionUseCase @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val hostRepository: HostRepository,
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val keystoreManager: KeystoreManager,
    @ApplicationContext private val context: Context,
    private val appLock: AppLock,
) {
    /**
     * For BIOMETRIC_KEY the caller has nothing to prepare: the fingerprint prompt is shown by
     * `BiometricPromptHost` when the SshSessionManager asks for it, from any screen (host list,
     * terminal, SFTP, tunnels).
     */
    suspend operator fun invoke(host: Host): SshResult<SshSession> {
        return try {
            when (host.authType) {
                AuthType.PASSWORD -> {
                    try {
                        val password = vaultManager.getPassword(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                context.getString(R.string.connect_error_password_missing, host.label)
                            )
                        val result = sessionManager.connectWithPassword(host, password)
                        // password wiped inside connectWithPassword

                        recordingConnection(host, result)
                    } catch (e: VaultAuthExpiredException) {
                        return lockAndReportVaultExpired()
                    }
                }

                AuthType.SSH_KEY -> {
                    try {
                        val privateKey = vaultManager.getPrivateKey(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                context.getString(R.string.connect_error_key_missing, host.label)
                            )
                        val result = withKeyPassphrase(host.credentialId) { passphrase ->
                            sessionManager.connectWithKey(host, privateKey, passphrase)
                        }

                        recordingConnection(host, result)
                    } catch (e: VaultAuthExpiredException) {
                        return lockAndReportVaultExpired()
                    }
                }

                AuthType.CERTIFICATE -> {
                    try {
                        val privateKey = vaultManager.getPrivateKey(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                context.getString(R.string.connect_error_key_missing, host.label)
                            )
                        val certificate = vaultManager.getCertificate(host.credentialId)
                            ?: return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                context.getString(R.string.connect_error_certificate_missing, host.label)
                            )
                        val result = withKeyPassphrase(host.credentialId) { passphrase ->
                            sessionManager.connectWithCertificate(host, privateKey, certificate, passphrase)
                        }

                        recordingConnection(host, result)
                    } catch (e: VaultAuthExpiredException) {
                        return lockAndReportVaultExpired()
                    }
                }

                AuthType.FIDO2 -> {
                    // A host that came from the Desktop has no mode (it has no such notion): it
                    // is a security key host, as HostViewModel reads it when it loads the host.
                    val fido2Mode = host.fido2ModeOrDefault()

                    when (fido2Mode) {
                        Fido2Mode.HARDWARE_KEY -> {
                            // Clé matérielle : auth sk-* via SkAuthMethod + hardware key
                            val sshKey = sshKeyRepository.getById(host.credentialId)
                                ?: return SshResult.Error(
                                    SshErrorCode.FIDO2_NO_KEY,
                                    context.getString(R.string.connect_error_fido2_key_missing, host.label)
                                )
                            val skPublicKey = SkSshPublicKey.fromAuthorizedKeysLine(sshKey.publicKey)
                                ?: return SshResult.Error(
                                    SshErrorCode.AUTH_FAILED,
                                    context.getString(R.string.connect_error_fido2_key_invalid, sshKey.label)
                                )
                            // A non-resident key only answers when it is given its credential id.
                            val credentialIds = listOfNotNull(decodeCredentialId(sshKey.fido2CredentialId))
                            val result = skPublicKey.use {
                                sessionManager.connectWithSkKey(host, it, credentialIds)
                            }

                            recordingConnection(host, result)
                        }

                        Fido2Mode.PASSKEY -> {
                            // Passkey (hôtes existants, plus proposé aux nouveaux) : connexion par la clé
                            // SSH choisie, sans vérification par clé de sécurité ni par passkey
                            val sshKey = sshKeyRepository.getById(host.credentialId)
                                ?: return SshResult.Error(
                                    SshErrorCode.FIDO2_NO_KEY,
                                    context.getString(R.string.connect_error_passkey_key_missing, host.label)
                                )
                            try {
                                val privateKey = vaultManager.getPrivateKey(sshKey.id)
                                    ?: return SshResult.Error(
                                        SshErrorCode.AUTH_FAILED,
                                        context.getString(R.string.connect_error_key_missing, sshKey.label)
                                    )
                                // La phrase de passe est celle de la clé choisie, rangée sous son id comme le PEM.
                                val result = withKeyPassphrase(sshKey.id) { passphrase ->
                                    sessionManager.connectWithKey(host, privateKey, passphrase)
                                }

                                recordingConnection(host, result)
                            } catch (e: VaultAuthExpiredException) {
                                return lockAndReportVaultExpired()
                            }
                        }
                    }
                }

                AuthType.BIOMETRIC_KEY -> {
                    // Récupérer la clé SSH pour obtenir le keystoreAlias
                    val sshKey = sshKeyRepository.getById(host.credentialId)
                        ?: return SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            context.getString(R.string.connect_error_biometric_key_missing, host.label)
                        )
                    val alias = sshKey.keystoreAlias
                        ?: run {
                            // Texte fixe : rien de la clé ne va dans le journal.
                            Timber.w("BIOMETRIC_KEY connect refused: the key has no Keystore alias")
                            // Une clé sans alias du Keystore ne peut plus signer : comme une clé disparue.
                            return SshResult.Error(
                                SshErrorCode.AUTH_FAILED,
                                context.getString(R.string.auth_error_biometric_key_invalid)
                            )
                        }
                    // L'alias vient de la base, donc potentiellement d'un backup ou d'une sync :
                    // il ne sert à signer que s'il est exactement celui que l'app génère pour
                    // cette clé. Sinon il pourrait désigner une autre entrée du Keystore.
                    if (!BiometricKeyAlias.matches(alias, sshKey.id)) {
                        // Texte fixe : l'alias est une entrée non fiable, il ne va pas dans le log.
                        Timber.w("BIOMETRIC_KEY connect refused: the Keystore alias does not match the key")
                        return SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            context.getString(R.string.auth_error_biometric_key_invalid)
                        )
                    }

                    // The public key is read without any authentication, which is what lets the server
                    // be asked whether it knows the key before the user is disturbed. One finger per
                    // connection: the fingerprint is asked by the SshSessionManager itself, once the
                    // key is accepted, on the Signature it opens then (openSignature), never here nor
                    // by the caller. Only an entry missing from the Keystore, or an alias that is not a
                    // biometric key alias, is a key to regenerate: any other Keystore failure proves
                    // nothing, and regenerating a healthy key would cut the access to every server it
                    // is authorized on.
                    val publicKey = try {
                        keystoreManager.getBiometricPublicKey(alias)
                    } catch (e: java.security.KeyStoreException) {
                        val keyIsGone = e.message == MISSING_BIOMETRIC_KEY_MESSAGE ||
                            e.message == NOT_A_BIOMETRIC_ALIAS_MESSAGE
                        // Ni le message (il peut nommer l'alias), ni l'alias : le drapeau, et la classe de
                        // ce qui a vraiment échoué (voir keyProblemLogDescription).
                        Timber.w(
                            "BIOMETRIC_KEY connect refused: Keystore public key unavailable " +
                                "keyIsGone=$keyIsGone (${keyProblemLogDescription(e)})"
                        )
                        return SshResult.Error(
                            SshErrorCode.AUTH_FAILED,
                            context.getString(
                                if (keyIsGone) {
                                    R.string.auth_error_biometric_key_invalid
                                } else {
                                    R.string.auth_error_biometric_key_unusable
                                },
                            )
                        )
                    }
                    val result = sessionManager.connectWithBiometricKey(host, publicKey, sshKey.label) {
                        keystoreManager.getSignatureForBiometricKey(alias)
                    }

                    recordingConnection(host, result)
                }
            }
        } catch (e: CancellationException) {
            // L'écran quitté ou le ViewModel détruit annule la connexion : ce n'est pas un échec à
            // afficher (UNKNOWN), la coroutine doit finir annulée.
            throw e
        } catch (e: Exception) {
            // Le message de l'exception ne va pas à l'écran (technique, en anglais, il peut citer un
            // chemin ou un identifiant) : le journal garde la classe de l'exception, rien d'autre.
            Timber.w("Connect failed hostId=${LogId.of(host.id)} (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, context.getString(R.string.connect_error_unexpected))
        }
    }

    /**
     * La fenêtre d'authentification du Vault (300 s) est close : l'écran de verrou recouvre l'écran
     * courant, l'utilisateur déverrouille NextSH (ce qui rouvre le vault), puis recommence. Le message
     * reste affiché dessous.
     */
    private fun lockAndReportVaultExpired(): SshResult.Error {
        appLock.lock()
        return SshResult.Error(SshErrorCode.AUTH_EXPIRED, context.getString(R.string.connect_error_vault_auth_expired))
    }

    /**
     * Enregistre la dernière connexion de [host] quand [result] est un succès, puis rend [result].
     * L'enregistrement suspend : annulé là (écran quitté, ViewModel détruit), ou en échec, il
     * laisserait la session connectée dans le registre avec sa référence et personne pour la
     * rendre, puisque l'appelant ne reçoit alors aucun résultat. La référence est rendue avant que
     * l'exception ne continue (une annulation continue telle quelle, voir [invoke]).
     */
    private suspend fun recordingConnection(host: Host, result: SshResult<SshSession>): SshResult<SshSession> {
        if (result is SshResult.Success) {
            try {
                hostRepository.updateLastConnected(host.id)
            } catch (e: Exception) {
                sessionManager.release(result.data.id)
                throw e
            }
        }
        return result
    }

    /**
     * Lit la phrase de passe de la clé [keyId] dans le vault et la donne à [block], qui ouvre la
     * connexion ; null quand la clé n'est pas chiffrée (rien n'est stocké), et la connexion se fait
     * alors comme avant. Le tableau est effacé dès que [block] rend la main, qu'il ait réussi,
     * échoué ou levé une exception (annulation comprise). La phrase de passe est lue en dernier,
     * après les autres secrets du vault, pour qu'aucun retour anticipé ne la laisse en mémoire.
     */
    private inline fun <T> withKeyPassphrase(keyId: String, block: (CharArray?) -> T): T {
        val passphrase = vaultManager.getKeyPassphrase(keyId)
        try {
            return block(passphrase)
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    /**
     * The credential id of an enrolled security key, as it is stored (standard Base64, written by
     * the enrolment on both platforms), or null when there is none to give.
     *
     * Missing, blank or not Base64 is no reason to refuse the connection: a resident key (made
     * with `ssh-keygen -O resident`, say) finds its credential without a list, and a key that
     * cannot is reported by the prompt. The stored value comes from the database, so from a
     * backup or a sync: neither it nor its decoded bytes ever reach the log.
     */
    private fun decodeCredentialId(stored: String?): ByteArray? {
        if (stored.isNullOrBlank()) return null
        return try {
            java.util.Base64.getDecoder().decode(stored.trim()).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            // Fixed text: the stored value is untrusted and identifies a credential.
            Timber.w("FIDO2 credential id is not valid Base64, connecting without an allow list")
            null
        }
    }
}
