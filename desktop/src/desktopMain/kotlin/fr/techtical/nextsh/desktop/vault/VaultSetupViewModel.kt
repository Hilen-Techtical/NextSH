// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.vault.VaultPinManager
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

data class VaultSetupState(
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /**
     * One-time recovery mnemonic produced by [VaultPinManager.setupNewVault].
     * Populated on success so the UI pass can display it for the user to write
     * down. Null until setup succeeds.
     */
    val recoveryWords: List<String>? = null,
)

class VaultSetupViewModel(
    private val appScope: AppScope = DesktopContainer.appScope,
    private val pinManager: VaultPinManager = DesktopContainer.vaultPinManager,
) {
    private val _state = MutableStateFlow(VaultSetupState())
    val state: StateFlow<VaultSetupState> = _state.asStateFlow()

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    /**
     * Drops the one-time recovery mnemonic from state once the user has
     * acknowledged saving it, so the plaintext words are not retained for the
     * rest of the onboarding session.
     */
    fun clearRecoveryWords() {
        if (_state.value.recoveryWords != null) _state.value = _state.value.copy(recoveryWords = null)
    }

    fun submit(pin: CharArray, confirm: CharArray, onSuccess: () -> Unit) {
        try {
            if (pin.size < 4) {
                _state.value = _state.value.copy(error = "La Passphrase doit contenir au moins 4 caractères")
                return
            }
            if (!pin.contentEquals(confirm)) {
                _state.value = _state.value.copy(error = "Les deux Passphrases ne correspondent pas")
                return
            }
            _state.value = _state.value.copy(isSubmitting = true, error = null)
            val pinCopy = pin.copyOf()
            appScope.coroutineScope.launch {
                val result = withContext(Dispatchers.IO) { pinManager.setupNewVault(pinCopy) }
                if (result.isSuccess) {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        recoveryWords = result.getOrNull(),
                    )
                    onSuccess()
                } else {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        error = result.exceptionOrNull()?.message ?: "Échec de l'initialisation",
                    )
                }
            }
        } finally {
            Arrays.fill(pin, '\u0000')
            Arrays.fill(confirm, '\u0000')
        }
    }
}
