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

    /** L'enrôlement s'est arrêté pour [reason] ; la phrase est choisie par l'écran, voir [enrollmentErrorMessageRes]. */
    data class Error(val reason: EnrollmentError) : ScannedEnrollment()
}

/**
 * Pourquoi l'enrôlement s'est arrêté : une cause, jamais une phrase ni le message d'une exception
 * (technique, en anglais, il peut citer une adresse). Seul l'hôte visé par le QR accompagne
 * [Unreachable], pour que l'utilisateur voie quelle adresse a été essayée.
 */
sealed interface EnrollmentError {
    /** Le QR n'est pas un QR d'enrôlement NextSH. */
    data object InvalidQr : EnrollmentError

    /** Le Desktop a répondu autre chose que ce qu'attend le protocole (clé malformée, statut ou corps inattendu). */
    data object InvalidResponse : EnrollmentError

    /** Le Desktop n'a pas répondu à temps à la première requête. */
    data object ConnectTimeout : EnrollmentError

    /** Le Desktop de [host]:[port] n'a pas pu être joint. */
    data class Unreachable(val host: String, val port: Int) : EnrollmentError

    /** Le Desktop a refusé la requête (famille 4xx : QR déjà utilisé, serveur arrêté). */
    data object Refused : EnrollmentError

    /** Le Desktop a répondu par une erreur interne (famille 5xx). */
    data object ServerFailure : EnrollmentError

    /** Le Desktop n'a pas répondu à temps à l'envoi de l'enrôlement. */
    data object SubmitTimeout : EnrollmentError

    /** La liaison avec le Desktop s'est coupée pendant l'envoi. */
    data object ConnectionLost : EnrollmentError

    /** Toute autre erreur réseau. */
    data object Network : EnrollmentError

    /** Le Vault est verrouillé (fenêtre d'authentification close) : le secret n'a pas pu y être rangé. */
    data object VaultLocked : EnrollmentError

    /** L'appareil est enrôlé côté Desktop mais n'a pas pu être enregistré ici. */
    data object NotSaved : EnrollmentError
}
