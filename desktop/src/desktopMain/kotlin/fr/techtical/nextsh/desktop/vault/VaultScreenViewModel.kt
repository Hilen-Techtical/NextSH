// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyLoader
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyManager
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultExporter
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.VaultImportError
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import kotlinx.coroutines.flow.combine
import fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Arrays

data class VaultOperationState(
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class VaultScreenViewModel(
    private val appScope: AppScope = DesktopContainer.appScope,
    private val keyRepository: SshKeyRepository = DesktopContainer.sshKeyRepository,
    private val hostRepository: HostRepository = DesktopContainer.hostRepository,
    private val vaultManager: VaultManager = DesktopContainer.vaultManager,
    private val generate: GenerateSshKeyUseCase = DesktopContainer.generateSshKeyUseCase,
    private val keyManager: DesktopSshKeyManager = DesktopSshKeyManager(),
    private val exporter: DesktopVaultExporter = DesktopContainer.vaultExporter,
    private val importer: DesktopVaultImporter = DesktopContainer.vaultImporter,
) {
    val keys: StateFlow<List<SshKey>> = keyRepository
        .observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    /**
     * Map de keyId → nb d'hôtes utilisant cette clé (auth `SSH_KEY` ou
     * `CERTIFICATE` avec `host.credentialId == key.id`). Recalculé à chaque
     * changement de la liste des hôtes : Eagerly pour que la UI reçoive la
     * valeur au premier rendu.
     */
    val hostsUsingKey: StateFlow<Map<String, Int>> = hostRepository
        .observeAll()
        .combine(keys) { hosts, allKeys ->
            allKeys.associate { key ->
                key.id to hosts.count { h ->
                    (h.authType == AuthType.SSH_KEY || h.authType == AuthType.CERTIFICATE) &&
                        h.credentialId == key.id
                }
            }
        }
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyMap())

    private val _status = MutableStateFlow(VaultOperationState())
    val status: StateFlow<VaultOperationState> = _status.asStateFlow()

    fun clearStatus() {
        if (_status.value.message != null) _status.value = VaultOperationState()
    }

    /**
     * Notifie l'UI qu'une clé FIDO2 sk-ssh-ed25519 vient d'être enrôlée.
     * La clé est déjà sauvegardée dans le repository par [Fido2EnrollViewModel] :
     * on se contente d'afficher le message de confirmation.
     */
    fun notifyFido2KeyEnrolled(key: SshKey) {
        _status.value = VaultOperationState(message = "Clé FIDO2 enrôlée : ${key.label}")
    }

    fun generateKey(label: String, keyType: SshKeyType) {
        if (label.isBlank()) {
            _status.value = VaultOperationState(message = "Le label est requis", isError = true)
            return
        }
        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { generate(label.trim(), keyType) }
            _status.value = if (result.isSuccess) {
                VaultOperationState(message = "Clé générée : ${result.getOrNull()?.label}")
            } else {
                VaultOperationState(
                    message = "Échec : ${result.exceptionOrNull()?.message ?: "erreur inconnue"}",
                    isError = true,
                )
            }
        }
    }

    fun importKey(file: File, label: String, passphrase: CharArray?) {
        if (label.isBlank()) {
            _status.value = VaultOperationState(message = "Le label est requis", isError = true)
            passphrase?.let { java.util.Arrays.fill(it, '\u0000') }
            return
        }
        val effectivePassphrase = passphrase?.takeIf { it.isNotEmpty() }  // empty = no passphrase at all
        // SSHJ's `PasswordUtils.createOneOff` wipes the char[] it's given after the first
        // `reqPassword` call (defensive security). If we pass [effectivePassphrase]
        // straight to SSHJ and THEN try to persist it in the vault, we persist a wiped
        // array: at connect time the stored "passphrase" is all zeros and decrypt fails.
        // Snapshot it up front so we have an untouched copy to store.
        val passphraseSnapshot = effectivePassphrase?.copyOf()

        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            try {
                val pem = withContext(Dispatchers.IO) { file.readText() }

                // Use the shared loader so we can distinguish passphrase errors from format
                // errors: the legacy [keyManager.extractPublicKey] returns null on either.
                val keyProvider = try {
                    withContext(Dispatchers.IO) {
                        DesktopSshKeyLoader.loadKeyProviderFromString(pem, effectivePassphrase)
                    }
                } catch (e: Exception) {
                    val msg = if (DesktopSshKeyLoader.isLikelyPassphraseError(e)) {
                        if (effectivePassphrase == null) "Cette clé est chiffrée : saisissez la passphrase"
                        else "Passphrase incorrecte"
                    } else {
                        "Impossible de lire la clé : ${e.message ?: "format inconnu"}"
                    }
                    _status.value = VaultOperationState(message = msg, isError = true)
                    return@launch
                }

                val publicKeyOpenSsh = run {
                    val pub = keyProvider.public
                    val keyType = net.schmizz.sshj.common.KeyType.fromKey(pub)
                    val encoded = java.util.Base64.getEncoder().encodeToString(
                        net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(pub).compactData
                    )
                    "$keyType $encoded nextsh-imported"
                }
                val id = randomUuid()
                vaultManager.storePrivateKey(id, pem)
                if (passphraseSnapshot != null) {
                    // storeKeyPassphrase copies the array internally.
                    vaultManager.storeKeyPassphrase(id, passphraseSnapshot.copyOf())
                }
                keyRepository.save(
                    SshKey(
                        id = id,
                        label = label.trim(),
                        keyType = inferType(publicKeyOpenSsh),
                        publicKey = publicKeyOpenSsh,
                        isBiometric = false,
                        keystoreAlias = null,
                    ),
                )
                _status.value = VaultOperationState(message = "Clé importée : ${label.trim()}")
            } catch (e: Exception) {
                _status.value = VaultOperationState(
                    message = "Échec de l'import : ${e.message ?: "erreur inconnue"}",
                    isError = true,
                )
            } finally {
                passphrase?.let { java.util.Arrays.fill(it, '\u0000') }
                passphraseSnapshot?.let { java.util.Arrays.fill(it, '\u0000') }
            }
        }
    }

    fun deleteKey(key: SshKey) {
        appScope.coroutineScope.launch {
            vaultManager.deleteCredential(key.id)
            keyRepository.delete(key.id)
        }
    }

    /**
     * Export the vault to [destination]. [passphrase] is copied and wiped by the
     * exporter: the caller's array is not reused after this call.
     */
    fun exportBackup(destination: File, passphrase: CharArray) {
        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            var bytes: ByteArray? = null
            try {
                val produced = withContext(Dispatchers.IO) { exporter.export(passphrase) }
                bytes = produced
                val counts = exporter.entryCountSummary()
                withContext(Dispatchers.IO) {
                    destination.parentFile?.takeIf { !it.exists() }?.mkdirs()
                    destination.writeBytes(produced)
                }
                _status.value = VaultOperationState(
                    message = "Backup exporté : ${destination.absolutePath} (${counts.total} entrées)",
                )
            } catch (e: Exception) {
                _status.value = VaultOperationState(
                    message = "Échec de l'export : ${e.message ?: "erreur inconnue"}",
                    isError = true,
                )
            } finally {
                bytes?.fill(0)
            }
        }
    }

    /**
     * Import a `.nextsh` backup from [source]. Clear errors are surfaced via [status];
     * [passphrase] is copied and wiped by the importer.
     */
    fun importBackup(source: File, passphrase: CharArray) {
        _status.value = VaultOperationState(isBusy = true)
        appScope.coroutineScope.launch {
            try {
                if (source.length() > DesktopVaultImporter.MAX_BACKUP_SIZE) {
                    _status.value = VaultOperationState(
                        message = "Fichier trop volumineux (> 50 Mo).",
                        isError = true,
                    )
                    Arrays.fill(passphrase, '\u0000')
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) { source.readBytes() }
                val result = importer.import(bytes, passphrase)
                result.fold(
                    onSuccess = { summary ->
                        _status.value = VaultOperationState(
                            message = "Backup importé : ${summary.total} entrées restaurées",
                        )
                    },
                    onFailure = { err ->
                        val msg = when (err) {
                            is VaultImportError.FileTooLarge -> "Fichier trop volumineux (> 50 Mo)."
                            is VaultImportError.WrongPassphrase -> "Passphrase incorrecte."
                            is VaultImportError.Malformed -> "Fichier corrompu ou format invalide."
                            is VaultImportError.UnsupportedVersion ->
                                "Version de backup non supportée : ${err.version}."
                            else -> "Échec de l'import : ${err.message ?: "erreur inconnue"}"
                        }
                        _status.value = VaultOperationState(message = msg, isError = true)
                    },
                )
            } catch (e: Exception) {
                Arrays.fill(passphrase, '\u0000')
                _status.value = VaultOperationState(
                    message = "Échec de l'import : ${e.message ?: "erreur inconnue"}",
                    isError = true,
                )
            }
        }
    }

    private fun inferType(publicKeyOpenSsh: String): SshKeyType {
        val prefix = publicKeyOpenSsh.substringBefore(' ')
        return when (prefix) {
            "ssh-ed25519" -> SshKeyType.ED25519
            "ssh-rsa" -> SshKeyType.RSA_4096
            "ecdsa-sha2-nistp256" -> SshKeyType.ECDSA_256
            "ecdsa-sha2-nistp384" -> SshKeyType.ECDSA_384
            "ecdsa-sha2-nistp521" -> SshKeyType.ECDSA_521
            "sk-ssh-ed25519@openssh.com" -> SshKeyType.SK_ED25519
            "sk-ecdsa-sha2-nistp256@openssh.com" -> SshKeyType.SK_ECDSA_256
            else -> SshKeyType.ED25519
        }
    }
}
