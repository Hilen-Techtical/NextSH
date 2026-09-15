// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class EnrolledDevicesViewModel @Inject constructor(
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val secretStore: EnrolledDeviceSecretStore,
    private val syncProtocol: SyncProtocol,
) : ViewModel() {

    val devices: StateFlow<List<EnrolledDevice>> = enrolledDeviceRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Buffer must absorb back-to-back tryEmit calls within one dispatch
    // (SearchStarted immediately followed by SearchFound/NotFound): with a
    // 1-slot buffer the second event is silently dropped before the
    // collector gets resumed.
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** deviceId currently being searched for on the network, or null when idle. One search at a time. */
    private val _searchingDeviceId = MutableStateFlow<String?>(null)
    val searchingDeviceId: StateFlow<String?> = _searchingDeviceId.asStateFlow()

    sealed class Event {
        data class Revoked(val deviceName: String) : Event()

        /**
         * [message] is a diagnostic, not UI copy: the screen renders the localized
         * `enrolled_status_error` string. Kept in English for that reason.
         */
        data class Error(val message: String) : Event()
        data class SearchStarted(val deviceName: String) : Event()
        data class SearchFound(val deviceName: String) : Event()
        data class SearchNotFound(val deviceName: String) : Event()
    }

    fun revoke(device: EnrolledDevice) {
        viewModelScope.launch {
            try {
                secretStore.delete(device.deviceId)
                enrolledDeviceRepository.delete(device.deviceId)
                _events.tryEmit(Event.Revoked(device.deviceName))
            } catch (e: Exception) {
                Timber.w(e, "Failed to revoke device ${device.deviceId}")
                _events.tryEmit(Event.Error("Revocation failed"))
            }
        }
    }

    /**
     * Manual "search on network" action: runs the same host-resolution chain as the
     * background scheduler ([SyncProtocol.fullSync] resolves a missing/unreachable host
     * via a hostname probe then LAN discovery before syncing: see [fr.techtical.nextsh.core.sync.LanSyncClient]).
     * A [SyncResult.Success] means a matching Desktop answered AND the sync that followed
     * succeeded; anything else is reported as not found, regardless of the precise reason,
     * to keep the button's feedback simple.
     *
     * Single-flight: the slot is claimed with an atomic compare-and-set on the caller's thread,
     * before any coroutine is launched. Reading the flow and then setting it inside the launched
     * coroutine left a window where two taps landing in the same main-thread dispatch both saw
     * null and both started a full sync, including two LAN discovery bursts.
     */
    fun searchOnNetwork(device: EnrolledDevice) {
        if (!_searchingDeviceId.compareAndSet(expect = null, update = device.deviceId)) return
        viewModelScope.launch {
            _events.tryEmit(Event.SearchStarted(device.deviceName))
            try {
                val result = syncProtocol.fullSync(device)
                if (result is SyncResult.Success) {
                    _events.tryEmit(Event.SearchFound(device.deviceName))
                } else {
                    Timber.d("searchOnNetwork: ${device.deviceId} not found: $result")
                    _events.tryEmit(Event.SearchNotFound(device.deviceName))
                }
            } catch (e: Exception) {
                Timber.w(e, "searchOnNetwork failed for ${device.deviceId}")
                _events.tryEmit(Event.SearchNotFound(device.deviceName))
            } finally {
                _searchingDeviceId.value = null
            }
        }
    }
}
