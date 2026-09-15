// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.sync.SyncInterval
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val vaultManager: VaultManager,
    private val syncScheduler: SyncScheduler,
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

    fun updateKeepAlive(seconds: Int) {
        viewModelScope.launch { settingsDataStore.updateKeepAliveInterval(seconds) }
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
        viewModelScope.launch { syncScheduler.forceSync() }
    }

    private val _authRequiredEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authRequiredEvent: SharedFlow<Unit> = _authRequiredEvent.asSharedFlow()

    fun wipeVault() {
        try {
            vaultManager.wipeVault()
        } catch (e: VaultAuthExpiredException) {
            _authRequiredEvent.tryEmit(Unit)
        }
    }
}
