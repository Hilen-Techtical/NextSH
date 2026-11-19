// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.di.IoDispatcher
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.BiometricFailure
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricPromptResponse
import fr.techtical.nextsh.core.ssh.VaultAuthPurpose
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultMigration
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Unlocks NextSH: at launch (the unlock screen, the first destination of the navigation) and over
 * the app when the lock screen covers it (see `AppLockScreen`). The same steps for both.
 *
 * The prompt is the system dialog of the coordinator, never a `BiometricPrompt` of our own: it takes
 * the turn shared with the other prompts and cannot step on one. After a success the vault is opened
 * HERE, in the authentication window of its master key (a migration first, if one is due), and not
 * at its first use, which can be long after that window closed. Only then is the app unlocked.
 *
 * What the screen shows is a resource of ours, never the text of the system dialog nor of an
 * exception. A dialog the user closes says nothing.
 */
@HiltViewModel
class VaultUnlockViewModel @Inject constructor(
    private val biometricHelper: BiometricHelper,
    private val vaultMigration: VaultMigration,
    private val vaultManager: VaultManager,
    private val biometricPrompts: BiometricPromptCoordinator,
    private val appLock: AppLock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class UnlockState(
        val isUnlocked: Boolean = false,
        val biometricAvailable: Boolean = false,
        /** The system dialog is up, or the vault is opening: the button waits. */
        val isAuthenticating: Boolean = false,
        @StringRes val errorRes: Int? = null,
    )

    private val _state = MutableStateFlow(UnlockState())
    val state: StateFlow<UnlockState> = _state.asStateFlow()

    private var authJob: Job? = null

    init {
        _state.value = UnlockState(
            biometricAvailable = biometricHelper.canAuthenticate(),
        )
    }

    /**
     * For the lock screen, which keeps one instance of this view model for the life of the activity,
     * and calls this each time it is resumed: the phone is asked again what it can do (a lock or a
     * fingerprint may have been set up in the settings of the phone since the last time). Nothing
     * else changes: an authentication that is under way stays under way (the button waits for it, a
     * resume while the screen of the phone code was up is no end of it), and the message of the last
     * try stays until the next one or until the lock screen goes away.
     */
    fun refresh() {
        _state.update { it.copy(biometricAvailable = biometricHelper.canAuthenticate()) }
    }

    /** The button: one dialog at a time, a second tap while it is up is ignored. */
    fun authenticate() {
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
            _state.update { it.copy(isAuthenticating = true, errorRes = null) }
            try {
                val response = biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK)
                if (response == BiometricPromptResponse.Authorized) {
                    openVaultAndUnlock()
                } else {
                    _state.update { it.copy(errorRes = vaultPromptFailureRes(response)) }
                }
            } finally {
                _state.update { it.copy(isAuthenticating = false) }
            }
        }
    }

    /**
     * Migration if one is due, then the opening of the vault, on IO (both read files and the
     * Keystore); the app is unlocked once the vault is open and not before.
     */
    private suspend fun openVaultAndUnlock() {
        try {
            withContext(ioDispatcher) {
                if (vaultMigration.needsMigration()) vaultMigration.migrate()
                vaultManager.open()
            }
            appLock.unlock()
            _state.update { it.copy(isUnlocked = true, errorRes = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultAuthExpiredException) {
            _state.update { it.copy(errorRes = R.string.vault_unlock_error_auth_expired) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            _state.update { it.copy(errorRes = R.string.vault_unlock_error_key_invalidated) }
        } catch (e: Exception) {
            // The class only: the message may say what the vault holds or where.
            Timber.w("Vault could not be opened at unlock (${errorClass(e)})")
            _state.update { it.copy(errorRes = R.string.vault_unlock_error_open_failed) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorRes = null) }
    }
}

/**
 * What to tell the user when the vault dialog ended without authorizing: nothing for a dialog the user
 * closed (or the system, the app having left), and for a failure a sentence of ours that says why, as
 * far as the user can act on it (the sensor locked for a moment, locked until the phone code is
 * typed, no lock set up, the sensor busy or missing), never the text of the system. A dialog nobody
 * answered in time (null) or an unnamed failure reads as a generic "did not complete". Null for a
 * success. [BiometricPromptResponse.NoScreen] is never the answer of the vault request, it reads like
 * any other failure.
 */
@StringRes
internal fun vaultPromptFailureRes(response: BiometricPromptResponse?): Int? = when (response) {
    BiometricPromptResponse.Authorized, BiometricPromptResponse.Cancelled -> null
    is BiometricPromptResponse.Failed -> when (response.reason) {
        BiometricFailure.LOCKOUT -> R.string.vault_unlock_error_lockout
        BiometricFailure.LOCKOUT_PERMANENT -> R.string.vault_unlock_error_lockout_permanent
        BiometricFailure.NO_BIOMETRICS -> R.string.vault_unlock_error_none_enrolled
        BiometricFailure.HW_UNAVAILABLE -> R.string.vault_unlock_error_sensor_unavailable
        BiometricFailure.HW_NOT_PRESENT -> R.string.vault_unlock_error_no_sensor
        BiometricFailure.OTHER -> R.string.vault_unlock_error_prompt_failed
    }
    BiometricPromptResponse.NoScreen, null -> R.string.vault_unlock_error_prompt_failed
}
