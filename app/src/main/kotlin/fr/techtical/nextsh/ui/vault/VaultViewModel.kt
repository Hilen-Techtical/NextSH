// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ExportVaultUseCase
import fr.techtical.nextsh.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportVaultUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val sshKeyRepository: SshKeyRepository,
    private val vaultManager: VaultManager,
    private val sshKeyManager: SshKeyManager,
    private val keystoreManager: KeystoreManager,
    private val importSshKey: ImportSshKeyUseCase,
    private val generateSshKey: GenerateSshKeyUseCase,
    private val exportVaultUseCase: ExportVaultUseCase,
    private val importVaultUseCase: ImportVaultUseCase,
) : ViewModel() {

    data class VaultUiState(
        val sshKeys: List<SshKey> = emptyList(),
        val storedCredentialCount: Int = 0,
        val isLoading: Boolean = true,
        val isGenerating: Boolean = false,
        val isExporting: Boolean = false,
        val isImporting: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
        /**
         * Non-null when a passphrase is required to import the pending key.
         * Carries the label + raw PEM so the UI can re-invoke `importKey` once
         * the user supplies the passphrase via a dialog.
         */
        val pendingEncryptedImport: PendingImport? = null,
    )

    data class PendingImport(
        val label: String,
        val pem: String,
    )

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        // Observe SSH keys from repository
        viewModelScope.launch {
            sshKeyRepository.observeAll().collect { keys ->
                _uiState.update { state ->
                    state.copy(
                        sshKeys = keys,
                        isLoading = false,
                    )
                }
            }
        }

        // Count stored credentials from vault
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = vaultManager.listStoredCredentialIds().size
                _uiState.update { it.copy(storedCredentialCount = count, isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Erreur acces vault")
                _uiState.update { it.copy(storedCredentialCount = 0, isLoading = false) }
            }
        }
    }

    fun generateKey(label: String, type: SshKeyType = SshKeyType.ED25519) {
        if (label.isBlank()) {
            _uiState.update { it.copy(error = "Le nom de la clé ne peut pas être vide") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            val result = generateSshKey(label.trim(), type)
            result.fold(
                onSuccess = { key ->
                    Timber.i("Key generated: ${key.label}")
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            successMessage = "Clé ${key.keyType.name} « ${key.label} » générée avec succès",
                        )
                    }
                },
                onFailure = { e ->
                    Timber.e(e, "Key generation failed")
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            error = "Échec de la génération : ${e.localizedMessage}",
                        )
                    }
                },
            )
        }
    }

    fun importKey(label: String, privateKeyPem: String, passphrase: CharArray? = null) {
        if (label.isBlank()) {
            _uiState.update { it.copy(error = "Le nom de la clé ne peut pas être vide") }
            passphrase?.fill('\u0000')
            return
        }
        if (privateKeyPem.isBlank()) {
            _uiState.update { it.copy(error = "La clé privée PEM ne peut pas être vide") }
            passphrase?.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            val trimmedLabel = label.trim()
            val trimmedPem = privateKeyPem.trim()
            val result = importSshKey(trimmedLabel, trimmedPem, passphrase)
            passphrase?.fill('\u0000')
            result.fold(
                onSuccess = { key ->
                    Timber.i("Key imported: ${key.label}")
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            pendingEncryptedImport = null,
                            successMessage = "Clé « ${key.label} » importée avec succès",
                        )
                    }
                },
                onFailure = { e ->
                    if (e is fr.techtical.nextsh.domain.usecase.PassphraseRequiredException) {
                        // Signal the UI to prompt for a passphrase; hold the PEM in state
                        // so the user doesn't have to re-pick the file.
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                pendingEncryptedImport = PendingImport(trimmedLabel, trimmedPem),
                            )
                        }
                    } else {
                        Timber.e(e, "Key import failed")
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                error = "Échec de l'import : ${e.localizedMessage}",
                            )
                        }
                    }
                },
            )
        }
    }

    /** Cancel a pending encrypted-key import, discards the held PEM and resets the prompt state. */
    fun cancelEncryptedImport() {
        _uiState.update { it.copy(pendingEncryptedImport = null) }
    }

    fun deleteKey(keyId: String) {
        viewModelScope.launch {
            try {
                // Vérifier si c'est une clé biométrique pour supprimer du Keystore
                val key = sshKeyRepository.getById(keyId)
                val keystoreAlias = key?.keystoreAlias
                if (key?.isBiometric == true && keystoreAlias != null) {
                    keystoreManager.deleteBiometricKey(keystoreAlias)
                } else {
                    vaultManager.deletePrivateKey(keyId)
                }
                sshKeyRepository.delete(keyId)
                _uiState.update { it.copy(successMessage = "Clé supprimée") }
            } catch (e: Exception) {
                Timber.e(e, "Key deletion failed")
                _uiState.update { it.copy(error = "Erreur lors de la suppression : ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Génère une clé SSH biométrique dans le Keystore Android.
     * La clé privée ne quitte jamais le hardware (TEE/StrongBox).
     */
    fun generateBiometricKey(label: String) {
        if (label.isBlank()) {
            _uiState.update { it.copy(error = "Le nom de la clé ne peut pas être vide") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }
            try {
                val keyId = java.util.UUID.randomUUID().toString()
                val alias = "nextsh_bio_$keyId"

                val publicKey = keystoreManager.generateBiometricSshKey(alias)
                val publicKeyOpenSsh = sshKeyManager.formatKeystorePublicKeyOpenSsh(publicKey)

                val sshKey = fr.techtical.nextsh.domain.model.SshKey(
                    id = keyId,
                    label = label.trim(),
                    keyType = fr.techtical.nextsh.domain.model.SshKeyType.ECDSA_256,
                    publicKey = publicKeyOpenSsh,
                    isBiometric = true,
                    keystoreAlias = alias,
                )
                sshKeyRepository.save(sshKey)

                Timber.i("Biometric key generated: ${sshKey.label}")
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        successMessage = "Clé biométrique « ${sshKey.label} » générée (ECDSA P-256)",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Biometric key generation failed")
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        error = "Échec de la génération biométrique : ${e.localizedMessage}",
                    )
                }
            }
        }
    }

    fun exportVault(passphrase: CharArray, outputUri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExporting = true, error = null) }
            try {
                val result = exportVaultUseCase(passphrase)
                result.fold(
                    onSuccess = { data ->
                        context.contentResolver.openOutputStream(outputUri)?.use { it.write(data) }
                        _uiState.update {
                            it.copy(isExporting = false, successMessage = "Backup exporté avec succès")
                        }
                    },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isExporting = false, error = "Échec de l'export : ${e.localizedMessage}")
                        }
                    },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExporting = false, error = "Échec de l'export : ${e.localizedMessage}")
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun importVault(passphrase: CharArray, inputUri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isImporting = true, error = null) }
            try {
                val data = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                if (data == null || data.size > 50 * 1024 * 1024) {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            error = if (data == null) "Fichier illisible" else "Fichier trop volumineux (max 50 Mo)",
                        )
                    }
                    return@launch
                }
                val result = importVaultUseCase(data, passphrase)
                result.fold(
                    onSuccess = { count ->
                        _uiState.update {
                            it.copy(isImporting = false, successMessage = "$count éléments importés avec succès")
                        }
                    },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isImporting = false, error = "Échec de l'import : ${e.localizedMessage}")
                        }
                    },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isImporting = false, error = "Échec de l'import : ${e.localizedMessage}")
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
