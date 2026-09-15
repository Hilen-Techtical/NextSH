// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

/** État du flow d'enrôlement QR côté Android. */
sealed class ScannedEnrollment {
    object Idle : ScannedEnrollment()
    object RequestingPermission : ScannedEnrollment()
    object PermissionDenied : ScannedEnrollment()
    object Scanning : ScannedEnrollment()

    /** QR parsé, fingerprint affiché, en attente de confirmation utilisateur. */
    data class FingerprintConfirm(
        val parsed: EnrollmentQrParser.Parsed,
        val serverDeviceName: String,
    ) : ScannedEnrollment()

    /** Le fingerprint calculé ne correspond pas à celui du QR, probable MITM. */
    data class FingerprintMismatch(
        val expected: String,
        val actual: String,
    ) : ScannedEnrollment()

    object Submitting : ScannedEnrollment()

    data class Success(val desktopDeviceName: String) : ScannedEnrollment()

    data class Error(val message: String) : ScannedEnrollment()
}
