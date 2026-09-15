// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.desktop.core.vault.VaultPinManager
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VaultUnlockState(
    val isUnlocking: Boolean = false,
    val error: String? = null,
    /**
     * Error specific to the recovery-phrase sub-flow. Kept separate from
     * [error] (the PIN field error) so the two views never cross-contaminate
     * each other's messages.
     */
    val recoveryError: String? = null,
)

class VaultViewModel(
    private val appScope: AppScope = DesktopContainer.appScope,
    private val pinManager: VaultPinManager = DesktopContainer.vaultPinManager,
) {

    private val _state = MutableStateFlow(VaultUnlockState())
    val state: StateFlow<VaultUnlockState> = _state.asStateFlow()

    /** Whether a recovery wrap exists: gates the "use recovery phrase" link. */
    fun hasRecoveryPhrase(): Boolean = pinManager.hasRecoveryPhrase()

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    fun clearRecoveryError() {
        if (_state.value.recoveryError != null) _state.value = _state.value.copy(recoveryError = null)
    }

    // PIN is CharArray so pinManager.unlock can wipe it after derivation.
    fun attemptUnlock(pin: CharArray, onSuccess: () -> Unit) {
        if (pin.size < 4) {
            _state.value = _state.value.copy(error = "Passphrase incorrecte")
            java.util.Arrays.fill(pin, ' ')
            return
        }
        _state.value = _state.value.copy(isUnlocking = true, error = null)
        StartupTrace.mark("PIN submitted: unlock dispatched")
        appScope.coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { pinManager.unlock(pin) }
            if (result.isSuccess) {
                StartupTrace.mark("vault unlocked")
                _state.value = _state.value.copy(isUnlocking = false)
                onSuccess()
            } else {
                // Message générique : on ne leak pas le détail crypto pour
                // éviter de distinguer "PIN trop court" / "PIN faux" /
                // "vault corrompu" : toute erreur d'unlock affiche la même
                // phrase côté UI.
                _state.value = _state.value.copy(
                    isUnlocking = false,
                    error = "Passphrase incorrecte",
                )
            }
        }
    }

    /**
     * Attempts to unlock the vault with a 12-word recovery phrase. The words
     * list is consumed by [VaultPinManager.unlockWithRecovery], which derives
     * (and wipes) a seed CharArray internally: this VM does not retain the
     * words after dispatch. On failure a single generic message is shown so we
     * never distinguish "invalid words" from "wrong phrase".
     */
    fun attemptRecoveryUnlock(words: List<String>, onSuccess: () -> Unit) {
        _state.value = _state.value.copy(isUnlocking = true, recoveryError = null)
        appScope.coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { pinManager.unlockWithRecovery(words) }
            if (result.isSuccess) {
                _state.value = _state.value.copy(isUnlocking = false)
                onSuccess()
            } else {
                _state.value = _state.value.copy(
                    isUnlocking = false,
                    recoveryError = "Phrase de récupération invalide",
                )
            }
        }
    }

    /**
     * Re-wraps the DEK of an already-unlocked vault under a new PIN. Used right
     * after a successful recovery unlock when the user has forgotten the old
     * PIN. The new PIN [CharArray] is wiped by [VaultPinManager.changePin].
     */
    fun setNewPin(newPin: CharArray, onSuccess: () -> Unit) {
        if (newPin.size < 4) {
            _state.value = _state.value.copy(recoveryError = "La Passphrase doit contenir au moins 4 caractères")
            java.util.Arrays.fill(newPin, ' ')
            return
        }
        _state.value = _state.value.copy(isUnlocking = true, recoveryError = null)
        appScope.coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { pinManager.changePin(newPin) }
            if (result.isSuccess) {
                _state.value = _state.value.copy(isUnlocking = false)
                onSuccess()
            } else {
                _state.value = _state.value.copy(
                    isUnlocking = false,
                    recoveryError = "Échec du changement de Passphrase",
                )
            }
        }
    }
}
