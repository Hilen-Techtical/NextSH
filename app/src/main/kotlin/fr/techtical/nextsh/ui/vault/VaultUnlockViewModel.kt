// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class VaultUnlockViewModel @Inject constructor(
    private val biometricHelper: BiometricHelper,
    private val vaultMigration: VaultMigration,
    private val vaultManager: VaultManager,
) : ViewModel() {

    data class UnlockState(
        val isUnlocked: Boolean = false,
        val biometricAvailable: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(UnlockState())
    val state: StateFlow<UnlockState> = _state.asStateFlow()

    init {
        _state.value = UnlockState(
            biometricAvailable = biometricHelper.canAuthenticate(),
        )
    }

    fun authenticate(activity: FragmentActivity) {
        biometricHelper.authenticate(
            activity = activity,
            onSuccess = {
                // Migration et vérification vault sur IO pour éviter ANR
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (vaultMigration.needsMigration()) vaultMigration.migrate()
                        }
                        _state.value = _state.value.copy(isUnlocked = true, errorMessage = null)
                    } catch (e: VaultAuthExpiredException) {
                        _state.value = _state.value.copy(
                            errorMessage = "Session d'authentification expirée. Veuillez réessayer."
                        )
                    } catch (e: KeyPermanentlyInvalidatedException) {
                        _state.value = _state.value.copy(
                            errorMessage = "Clé vault invalidée : les données biométriques ont changé. " +
                                "Le vault doit être recréé. Restaurez vos données depuis un backup .nextsh."
                        )
                    }
                }
            },
            onError = { errorCode, errString ->
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    _state.value = _state.value.copy(errorMessage = errString)
                }
            },
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
