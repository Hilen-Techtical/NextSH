// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "EnrolledDevicesViewModel"

class EnrolledDevicesViewModel(
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val secretStore: EnrolledDeviceSecretStore,
    private val appScope: AppScope,
) {
    val devices: StateFlow<List<EnrolledDevice>> = enrolledDeviceRepository.observeAll()
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, emptyList())

    sealed class Event {
        data class Revoked(val deviceName: String) : Event()
        data class Error(val message: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun revoke(device: EnrolledDevice) {
        appScope.coroutineScope.launch {
            runCatching {
                secretStore.delete(device.deviceId)
                enrolledDeviceRepository.delete(device.deviceId)
            }.fold(
                onSuccess = { _events.tryEmit(Event.Revoked(device.deviceName)) },
                onFailure = { e ->
                    Logger.w(TAG, "Failed to revoke device ${device.deviceId}: ${e.message}")
                    _events.tryEmit(Event.Error("Echec de la révocation"))
                },
            )
        }
    }
}
