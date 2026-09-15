// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "EnrollmentViewModel"

class EnrollmentViewModel(
    private val appScope: AppScope,
) {
    private val server = EnrollmentServer(appScope, onEnrolled = ::onEnrolled)

    val state: StateFlow<EnrollmentServer.State> = server.state

    init {
        server.start()
    }

    fun cancel() {
        server.cancel()
    }

    private suspend fun onEnrolled(device: EnrolledDevice) {
        DesktopContainer.enrolledDeviceRepository.save(device)
        Logger.d(TAG, "Appareil enrôlé et persisté : ${device.deviceName} (fp=${device.publicKeyFingerprint})")
    }
}
