// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.onboarding

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.di.IoDispatcher
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricPromptResponse
import fr.techtical.nextsh.core.ssh.VaultAuthPurpose
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.core.vault.VaultMigration
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.ui.vault.vaultPromptFailureRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * OnboardingViewModel: drives the Android first-launch onboarding stepper.
 *
 * Unlike Desktop (which derives a key from a user-chosen PIN), Android backs
 * the vault with the hardware Keystore (TEE/StrongBox). There is therefore no
 * PIN and no recovery phrase: the "create vault" step is a hardware-backed
 * unlock that asks the system dialog through the [BiometricPromptCoordinator],
 * then opens the vault, exactly the same path as
 * [fr.techtical.nextsh.ui.vault.VaultUnlockViewModel], so no crypto is
 * duplicated here.
 *
 * Completion is persisted via [SettingsDataStore.setOnboardingCompleted] so the
 * stepper never reappears on later launches. It also unlocks the app: the
 * owner has just proved who they are, and the host list must not open under the
 * lock screen of a process that has not been unlocked yet.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val biometricHelper: BiometricHelper,
    private val vaultMigration: VaultMigration,
    private val vaultManager: VaultManager,
    private val settingsDataStore: SettingsDataStore,
    private val biometricPrompts: BiometricPromptCoordinator,
    private val appLock: AppLock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class OnboardingState(
        /** True once the hardware-backed vault step has been satisfied. */
        val vaultReady: Boolean = false,
        /** Whether the device can authenticate (biometric or device credential). */
        val biometricAvailable: Boolean = false,
        /** In-flight biometric prompt / migration. */
        val isAuthenticating: Boolean = false,
        /**
         * Localizable error: a failed dialog, or a known vault exception. Resolved with
         * `stringResource(errorRes)` at the composable layer so an English-locale
         * user never sees a hardcoded French literal, nor the text of the system dialog.
         */
        @StringRes val errorRes: Int? = null,
    )

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * Resolved first-launch decision used by the NavGraph to pick its start
     * destination. `null` while the persisted flag is still loading (the UI
     * waits before building the NavHost to avoid a flash of the wrong screen),
     * then `true` if onboarding was already completed (→ go straight to unlock).
     */
    val onboardingCompleted: StateFlow<Boolean?> =
        settingsDataStore.onboardingCompletedFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    init {
        _state.value = _state.value.copy(
            biometricAvailable = biometricHelper.canAuthenticate(),
        )
    }

    /**
     * Asks the system dialog (biometric or device credential) that initializes the
     * hardware-backed vault key. On success, runs any pending v1→v2 migration and
     * opens the vault on IO, then marks the vault step as ready. A dialog the user
     * closes says nothing; any other end is a sentence of ours, never the text of
     * the system.
     */
    fun createVault() {
        if (_state.value.isAuthenticating) return
        _state.value = _state.value.copy(isAuthenticating = true, errorRes = null)
        viewModelScope.launch {
            try {
                val response = biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK)
                if (response == BiometricPromptResponse.Authorized) {
                    openVault()
                } else {
                    _state.value = _state.value.copy(
                        isAuthenticating = false,
                        errorRes = vaultPromptFailureRes(response),
                    )
                }
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isAuthenticating = false)
                throw e
            }
        }
    }

    private suspend fun openVault() {
        try {
            withContext(ioDispatcher) {
                if (vaultMigration.needsMigration()) vaultMigration.migrate()
                vaultManager.open()
            }
            _state.value = _state.value.copy(vaultReady = true, isAuthenticating = false, errorRes = null)
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(isAuthenticating = false)
            throw e
        } catch (e: VaultAuthExpiredException) {
            _state.value = _state.value.copy(
                isAuthenticating = false,
                errorRes = R.string.firstlaunch_error_auth_expired,
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            _state.value = _state.value.copy(
                isAuthenticating = false,
                errorRes = R.string.firstlaunch_error_key_invalidated,
            )
        } catch (e: Exception) {
            // The class only: the message may say what the vault holds or where.
            Timber.w("Vault could not be opened at onboarding (${errorClass(e)})")
            _state.value = _state.value.copy(
                isAuthenticating = false,
                errorRes = R.string.vault_unlock_error_open_failed,
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorRes = null)
    }

    /**
     * Persists the onboarding-completed flag, then invokes [onPersisted] so the
     * caller can navigate away. Guarantees the flag is written before the host
     * list is shown, so the stepper cannot reappear on a fast relaunch.
     *
     * The app is unlocked first when the vault step was done: the host list opens
     * right after, and the lock screen must not cover it for a process in which the
     * owner has just proved who they are.
     */
    fun completeOnboarding(onPersisted: () -> Unit) {
        viewModelScope.launch {
            settingsDataStore.setOnboardingCompleted(true)
            if (_state.value.vaultReady) appLock.unlock()
            onPersisted()
        }
    }
}
