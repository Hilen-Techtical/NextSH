// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.onboarding

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.StringRes
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import fr.techtical.nextsh.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * OnboardingViewModel: drives the Android first-launch onboarding stepper.
 *
 * Unlike Desktop (which derives a key from a user-chosen PIN), Android backs
 * the vault with the hardware Keystore (TEE/StrongBox). There is therefore no
 * PIN and no recovery phrase: the "create vault" step is a hardware-backed
 * unlock that triggers a [BiometricPrompt] via the existing [BiometricHelper],
 * exactly the same path as [fr.techtical.nextsh.ui.vault.VaultUnlockViewModel],
 * so no crypto is duplicated here.
 *
 * Completion is persisted via [SettingsDataStore.setOnboardingCompleted] so the
 * stepper never reappears on later launches.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val biometricHelper: BiometricHelper,
    private val vaultMigration: VaultMigration,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    data class OnboardingState(
        /** True once the hardware-backed vault step has been satisfied. */
        val vaultReady: Boolean = false,
        /** Whether the device can authenticate (biometric or device credential). */
        val biometricAvailable: Boolean = false,
        /** In-flight biometric prompt / migration. */
        val isAuthenticating: Boolean = false,
        /**
         * Runtime error text surfaced by the system BiometricPrompt (already
         * localized by the OS, so it is passed through as-is). Mutually exclusive
         * with [errorRes]: at most one is non-null at a time.
         */
        val errorMessage: String? = null,
        /**
         * Localizable error surfaced for known vault exceptions. Resolved with
         * `stringResource(errorRes)` at the composable layer so an English-locale
         * user never sees a hardcoded French literal.
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
     * Triggers the biometric / device-credential prompt that initializes the
     * hardware-backed vault key. On success, runs any pending v1→v2 migration
     * on IO and marks the vault step as ready.
     */
    fun createVault(activity: FragmentActivity) {
        _state.value = _state.value.copy(
            isAuthenticating = true,
            errorMessage = null,
            errorRes = null,
        )
        biometricHelper.authenticate(
            activity = activity,
            onSuccess = {
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (vaultMigration.needsMigration()) vaultMigration.migrate()
                        }
                        _state.value = _state.value.copy(
                            vaultReady = true,
                            isAuthenticating = false,
                            errorMessage = null,
                            errorRes = null,
                        )
                    } catch (e: VaultAuthExpiredException) {
                        _state.value = _state.value.copy(
                            isAuthenticating = false,
                            errorMessage = null,
                            errorRes = R.string.firstlaunch_error_auth_expired,
                        )
                    } catch (e: KeyPermanentlyInvalidatedException) {
                        _state.value = _state.value.copy(
                            isAuthenticating = false,
                            errorMessage = null,
                            errorRes = R.string.firstlaunch_error_key_invalidated,
                        )
                    }
                }
            },
            onError = { errorCode, errString ->
                _state.value = _state.value.copy(isAuthenticating = false)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    _state.value = _state.value.copy(
                        errorMessage = errString,
                        errorRes = null,
                    )
                }
            },
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null, errorRes = null)
    }

    /**
     * Persists the onboarding-completed flag, then invokes [onPersisted] so the
     * caller can navigate away. Guarantees the flag is written before the host
     * list is shown, so the stepper cannot reappear on a fast relaunch.
     */
    fun completeOnboarding(onPersisted: () -> Unit) {
        viewModelScope.launch {
            settingsDataStore.setOnboardingCompleted(true)
            onPersisted()
        }
    }
}
