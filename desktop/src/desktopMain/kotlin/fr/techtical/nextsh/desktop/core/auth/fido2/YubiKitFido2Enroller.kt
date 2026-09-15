// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

/**
 * Adapte [YubiKitFidoManager] vers l'interface [Fido2Enroller].
 *
 * Permet à [Fido2EnrollViewModel] de dépendre de l'interface [Fido2Enroller]
 * (testable) plutôt que de la classe concrète [YubiKitFidoManager]
 * (qui requiert PC/SC physique).
 *
 * Injection de production dans [fr.techtical.nextsh.desktop.data.di.DesktopContainer].
 */
class YubiKitFido2Enroller(
    private val manager: YubiKitFidoManager,
) : Fido2Enroller {

    override suspend fun detectDevice(): Fido2Enroller.DeviceDetection =
        manager.detectDeviceForEnrollment()

    override suspend fun makeCredential(
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
        pin: CharArray?,
        timeoutMs: Long,
    ): Fido2Enroller.EnrollResult = manager.enrollCredential(
        rpId = rpId,
        rpName = rpName,
        userName = userName,
        userDisplayName = userDisplayName,
        pin = pin,
        timeoutMs = timeoutMs,
    )
}
