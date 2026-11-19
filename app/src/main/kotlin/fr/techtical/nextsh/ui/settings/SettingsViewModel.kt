// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.lock.AutoLockDelay
import fr.techtical.nextsh.core.vault.AndroidVaultWipe
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.sync.SyncInterval
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val androidVaultWipe: AndroidVaultWipe,
    private val syncScheduler: SyncScheduler,
    private val appLock: AppLock,
) : ViewModel() {

    val state = settingsDataStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsDataStore.Settings(),
    )

    /** Live sync status forwarded from [SyncScheduler.syncState]. */
    val syncState: StateFlow<SyncState> = syncScheduler.syncState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncState(SyncStatus.IDLE, null),
    )

    fun updateFontSize(size: Int) {
        viewModelScope.launch { settingsDataStore.updateTerminalFontSize(size) }
    }

    fun updateScrollback(lines: Int) {
        viewModelScope.launch { settingsDataStore.updateScrollbackLines(lines) }
    }

    fun toggleAutoReconnect() {
        viewModelScope.launch { settingsDataStore.updateAutoReconnect(!state.value.autoReconnect) }
    }

    fun updateConnectionTimeout(seconds: Int) {
        viewModelScope.launch { settingsDataStore.updateConnectionTimeout(seconds) }
    }

    fun updateClipboardClearTimeout(seconds: Int) {
        viewModelScope.launch { settingsDataStore.updateClipboardClearTimeout(seconds) }
    }

    fun updateAutoLockDelay(delay: AutoLockDelay) {
        viewModelScope.launch { settingsDataStore.updateAutoLockDelay(delay) }
    }

    /** "Lock now": the lock screen covers the app at once. */
    fun lockNow() {
        appLock.lock()
    }

    fun toggleSyncEnabled() {
        val enabled = !state.value.syncEnabled
        viewModelScope.launch {
            settingsDataStore.updateSyncEnabled(enabled)
            if (enabled) {
                syncScheduler.start(state.value.syncInterval.intervalMs)
            } else {
                syncScheduler.stop()
            }
        }
    }

    fun updateSyncInterval(interval: SyncInterval) {
        viewModelScope.launch {
            settingsDataStore.updateSyncInterval(interval)
            if (state.value.syncEnabled) {
                // Restart scheduler with new interval
                syncScheduler.stop()
                syncScheduler.start(interval.intervalMs)
            }
        }
    }

    fun forceSync() {
        viewModelScope.launch {
            try {
                syncScheduler.forceSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // viewModelScope has no handler: an escaping exception would kill the process.
                // The scheduler already reports its own failures through syncState.
                Timber.w("forceSync failed (${errorClass(e)})")
            }
        }
    }

    /**
     * Starts the wipe of the vault, after the two steps of confirmation of the screen. The wipe belongs to
     * the application ([AndroidVaultWipe], its own scope): leaving the settings, a rotation or the
     * destruction of the activity does not cut it, and this view model does not even wait for it. Its
     * progress and its failure are shown by the modal of `MainActivity`, from the state of the app.
     *
     * No authentication is asked: the wipe works on the raw preferences of the vault and does not need it
     * open (it stays possible after the authentication window closed, or the master key was invalidated).
     */
    fun wipeVault() {
        androidVaultWipe.start()
    }
}
