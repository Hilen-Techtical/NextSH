// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** How the fingerprint prompt of one connection attempt ended, when it did not end with an authorization. */
internal sealed interface BiometricPromptEnd {
    /** The user cancelled the dialog, or the system did when the app was left. */
    data object Cancelled : BiometricPromptEnd

    /** Nobody presented a finger before the deadline, or the system gave up waiting. */
    data object TimedOut : BiometricPromptEnd

    /**
     * The fingerprint cannot be asked for, for [reason]. Neither a cancellation nor a refusal by
     * the server, and nothing a retry would fix: the sensor is locked, missing or has no finger
     * enrolled.
     */
    data class Failed(val reason: BiometricFailure) : BiometricPromptEnd

    /**
     * The app was not on screen when the prompt was asked for (a reconnection driven by a service):
     * the request ended at once, without a dialog. Like [TimedOut] for the code, so that nothing
     * retries by itself; the message says to open the app.
     */
    data object NoScreen : BiometricPromptEnd
}

/**
 * Asks [callback] to have [request] authorized by a fingerprint, and tells [onEnd] when the prompt
 * ended without it: how it ended. Returns true when the fingerprint was recognized and the
 * signature of [request] may sign.
 *
 * The distinction matters to the user, who must read "connection cancelled" after pressing Cancel,
 * not "authentication failed", and to the automatic reconnection, which must not ask for a finger
 * again that the user just refused (see [biometricAuthFailureCode]).
 *
 * [caller] is the Job of the coroutine that is connecting. `BiometricAuthMethod` calls its signer
 * from `runBlocking` on the SSH transport thread, a coroutine of its own that knows nothing of the
 * connection. Without [caller], leaving the screen or cancelling the connection would leave its
 * dialog on screen, or queued behind another prompt, until its 60 s are over. With it the prompt
 * runs as a child of the connecting coroutine: cancelling the connection cancels the prompt, which
 * removes itself (see [BiometricPromptCoordinator.prompt]). Null runs the callback in the context
 * it is given, for callers without a connection.
 *
 * Unlike the FIDO2 request there is no result to wipe if the connection is cancelled as the answer
 * comes in: an authorization is a state of the Keystore handle, not data.
 */
internal suspend fun requestBiometricAuthorization(
    callback: suspend (BiometricSignRequest) -> BiometricPromptResponse?,
    request: BiometricSignRequest,
    caller: Job?,
    onEnd: (end: BiometricPromptEnd) -> Unit,
): Boolean {
    val response = if (caller != null) {
        withContext(caller) { callback(request) }
    } else {
        callback(request)
    }
    return when (response) {
        BiometricPromptResponse.Authorized -> true
        BiometricPromptResponse.Cancelled -> {
            onEnd(BiometricPromptEnd.Cancelled)
            false
        }
        is BiometricPromptResponse.Failed -> {
            onEnd(BiometricPromptEnd.Failed(response.reason))
            false
        }
        BiometricPromptResponse.NoScreen -> {
            onEnd(BiometricPromptEnd.NoScreen)
            false
        }
        null -> {
            onEnd(BiometricPromptEnd.TimedOut)
            false
        }
    }
}

/**
 * The error code of a biometric connection whose fingerprint prompt ended without an authorization.
 *
 * The `FIDO2_*` codes are reused on purpose: they are the values the shared enum has for "the user
 * cancelled / did not answer an interactive prompt", they are non-retryable, and every caller that
 * matters (the tunnels above all) already treats them so, which is what keeps an automatic
 * reconnection from asking for a finger again. Renaming them to something neutral would change
 * `:shared`, which this change does not touch. A failed sensor is `AUTH_FAILED` with its own
 * message, an authentication failure that a retry does not fix.
 */
internal fun biometricAuthFailureCode(end: BiometricPromptEnd): SshErrorCode = when (end) {
    BiometricPromptEnd.Cancelled -> SshErrorCode.FIDO2_USER_CANCELLED
    BiometricPromptEnd.TimedOut -> SshErrorCode.FIDO2_TIMEOUT
    // Same code as a prompt nobody answered: non-retryable, held, not retried into an empty screen.
    BiometricPromptEnd.NoScreen -> SshErrorCode.FIDO2_TIMEOUT
    is BiometricPromptEnd.Failed -> SshErrorCode.AUTH_FAILED
}

/**
 * The message the user reads for [biometricAuthFailureCode]: one clear sentence per reason, since
 * the system dialog is gone when the connection fails and the user cannot tell, for a sensor that
 * is locked and one that has no finger enrolled, what to do.
 */
@StringRes
internal fun biometricAuthFailureMessageRes(end: BiometricPromptEnd): Int = when (end) {
    BiometricPromptEnd.Cancelled -> R.string.auth_error_fido2_cancelled
    BiometricPromptEnd.TimedOut -> R.string.auth_error_biometric_timeout
    BiometricPromptEnd.NoScreen -> R.string.auth_error_no_screen
    is BiometricPromptEnd.Failed -> when (end.reason) {
        BiometricFailure.LOCKOUT -> R.string.auth_error_biometric_lockout
        BiometricFailure.LOCKOUT_PERMANENT -> R.string.auth_error_biometric_lockout_permanent
        BiometricFailure.NO_BIOMETRICS -> R.string.auth_error_biometric_none_enrolled
        BiometricFailure.HW_UNAVAILABLE -> R.string.auth_error_biometric_sensor_unavailable
        BiometricFailure.HW_NOT_PRESENT -> R.string.auth_error_biometric_no_sensor
        BiometricFailure.OTHER -> R.string.auth_error_biometric_error
    }
}
