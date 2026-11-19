// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import androidx.biometric.BiometricManager

/** Whether a strong biometric can be used right now, see [BiometricHelper.checkStrongBiometric]. */
enum class StrongBiometricStatus {
    AVAILABLE,

    /** No biometric sensor, or none that is rated strong. */
    NO_HARDWARE,

    /** A sensor, but no fingerprint (or other strong biometric) enrolled. */
    NOT_ENROLLED,

    /** Temporarily unavailable (sensor busy or locked out) or a security update is pending. */
    UNAVAILABLE,
}

/** The [StrongBiometricStatus] for a [BiometricManager.canAuthenticate] result asked for BIOMETRIC_STRONG. */
internal fun strongBiometricStatusFor(canAuthenticateResult: Int): StrongBiometricStatus = when (canAuthenticateResult) {
    BiometricManager.BIOMETRIC_SUCCESS -> StrongBiometricStatus.AVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> StrongBiometricStatus.NO_HARDWARE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> StrongBiometricStatus.NOT_ENROLLED
    // HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, UNSUPPORTED, STATUS_UNKNOWN: nothing the app can
    // fix, the sentence for this status only says to try again.
    else -> StrongBiometricStatus.UNAVAILABLE
}
