// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** How the FIDO2 prompt of one connection attempt ended, when it did not end with a signature. */
enum class Fido2PromptEnd {
    /** No prompt ended without a signature: an authentication failure is the server's. */
    NONE,

    /** The user cancelled the sheet. */
    CANCELLED,

    /** Nobody answered before the deadline. */
    TIMED_OUT,

    /**
     * The security key failed: communication error, key removed, PIN blocked, refused by the key.
     * Neither a cancellation nor a refusal by the server, and nothing a retry would fix. The reason
     * comes with it, see [requestFido2Signature]. A key that never shows up is not this: the
     * authenticator has no clock, so the wait ends with the deadline of the prompt, as [TIMED_OUT].
     */
    KEY_ERROR,

    /**
     * The app was not on screen when the prompt was asked for (a reconnection driven by a service):
     * the request ended at once, without a sheet. Like [TIMED_OUT] for the code, so that nothing
     * retries by itself; the message says to open the app.
     */
    NO_SCREEN,
}

/**
 * Asks [callback] for the signature of [request] and tells [onEnd] when the prompt ended without
 * one: how it ended, and for a [Fido2PromptEnd.KEY_ERROR] why (null otherwise). Returns the
 * signature, or null when there is none (cancelled, timed out).
 *
 * The distinction matters to the user, who must read "connection cancelled" after pressing
 * Cancel, not "authentication failed", and to the automatic reconnection, which must not ask
 * again for a key the user just refused.
 *
 * [caller] is the Job of the coroutine that is connecting. `SkAuthMethod` (in `:shared`) calls its
 * handler from `runBlocking` on the SSH transport thread, a coroutine of its own that knows
 * nothing of the connection. Without [caller], leaving the screen or cancelling the connection
 * would leave its prompt on screen, or queued behind another one, until the 60 s are over. With
 * it the prompt runs as a child of the connecting coroutine: cancelling the connection cancels
 * the prompt, which removes itself and zeroes the data to sign (see [Fido2PromptCoordinator.prompt]).
 * Null runs the callback in the context it is given, for callers without a connection.
 */
internal suspend fun requestFido2Signature(
    callback: suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?,
    request: Fido2ChallengeRequest,
    caller: Job?,
    onEnd: (end: Fido2PromptEnd, keyError: HwKeyError?) -> Unit,
): SkSshSignature? {
    val response = if (caller != null) {
        var obtained: Fido2ChallengeResponse? = null
        try {
            withContext(caller) { callback(request).also { obtained = it } }
        } catch (e: CancellationException) {
            // The connection was cancelled as the answer came in: withContext drops the answer
            // and throws. A signature nobody will use is wiped, not left in memory.
            (obtained as? Fido2ChallengeResponse.Signed)?.signature?.close()
            throw e
        }
    } else {
        callback(request)
    }
    return when (response) {
        is Fido2ChallengeResponse.Signed -> response.signature
        is Fido2ChallengeResponse.Cancelled -> {
            onEnd(Fido2PromptEnd.CANCELLED, null)
            null
        }
        is Fido2ChallengeResponse.KeyError -> {
            onEnd(Fido2PromptEnd.KEY_ERROR, response.reason)
            null
        }
        is Fido2ChallengeResponse.NoScreen -> {
            onEnd(Fido2PromptEnd.NO_SCREEN, null)
            null
        }
        null -> {
            onEnd(Fido2PromptEnd.TIMED_OUT, null)
            null
        }
    }
}

/**
 * The error code of a FIDO2 connection that failed in the authentication, by how its prompt ended.
 * A key error is `AUTH_FAILED` with its own message: it is an authentication failure that a retry
 * does not fix (and must not be shown as a cancellation the user did not make).
 */
internal fun fido2AuthFailureCode(end: Fido2PromptEnd): SshErrorCode = when (end) {
    Fido2PromptEnd.NONE -> SshErrorCode.AUTH_FAILED
    Fido2PromptEnd.CANCELLED -> SshErrorCode.FIDO2_USER_CANCELLED
    Fido2PromptEnd.TIMED_OUT -> SshErrorCode.FIDO2_TIMEOUT
    Fido2PromptEnd.KEY_ERROR -> SshErrorCode.AUTH_FAILED
    // The timeout code on purpose: non-retryable, so the reconnection holds the tunnel instead of
    // trying again into the same empty screen.
    Fido2PromptEnd.NO_SCREEN -> SshErrorCode.FIDO2_TIMEOUT
}

/**
 * The message the user reads for [fido2AuthFailureCode]. For a key error it is the one that tells
 * why ([keyError], see [hwKeyErrorMessageRes]): the sheet showed it for two seconds only, and a
 * connection that fails must not read the same for a PIN that is blocked, a key that was pulled
 * out, or a key that holds no such credential. Without a reason, the general key message.
 */
@StringRes
internal fun fido2AuthFailureMessageRes(end: Fido2PromptEnd, keyError: HwKeyError? = null): Int = when (end) {
    Fido2PromptEnd.NONE -> R.string.auth_error_failed_fido2
    Fido2PromptEnd.CANCELLED -> R.string.auth_error_fido2_cancelled
    Fido2PromptEnd.TIMED_OUT -> R.string.auth_error_fido2_timeout
    Fido2PromptEnd.NO_SCREEN -> R.string.auth_error_no_screen
    Fido2PromptEnd.KEY_ERROR ->
        if (keyError == null || keyError == HwKeyError.Unexpected) {
            R.string.auth_error_fido2_key_error
        } else {
            hwKeyErrorMessageRes(keyError)
        }
}

/** The text of [fido2AuthFailureMessageRes], with the numbers some sentences carry. */
internal fun fido2AuthFailureMessage(context: Context, end: Fido2PromptEnd, keyError: HwKeyError?): String {
    val res = fido2AuthFailureMessageRes(end, keyError)
    val args = if (end == Fido2PromptEnd.KEY_ERROR && keyError != null) hwKeyErrorMessageArgs(keyError) else emptyArray()
    return if (args.isEmpty()) context.getString(res) else context.getString(res, *args)
}

/**
 * The sentence a non-technician reads when the security key failed, for the banner of the FIDO2
 * sheet and for the message of the failed connection.
 */
@StringRes
internal fun hwKeyErrorMessageRes(reason: HwKeyError): Int = when (reason) {
    HwKeyError.NoMatchingCredential -> R.string.fido2_error_no_matching_credential
    is HwKeyError.PinIncorrect ->
        if (reason.triesRemaining != null) R.string.fido2_error_pin_incorrect_tries else R.string.fido2_error_pin_incorrect
    HwKeyError.PinBlocked -> R.string.fido2_pin_blocked
    HwKeyError.PinAuthBlocked -> R.string.fido2_error_pin_auth_blocked
    HwKeyError.PinNotSet -> R.string.fido2_error_pin_not_set
    HwKeyError.KeyRemoved -> R.string.fido2_error_key_removed
    HwKeyError.UnsupportedKey -> R.string.fido2_error_unsupported_key
    HwKeyError.TouchTimeout -> R.string.fido2_error_touch_timeout
    HwKeyError.Refused -> R.string.fido2_error_refused
    HwKeyError.PinUiUnavailable -> R.string.fido2_pin_required_no_ui
    HwKeyError.Unexpected -> R.string.fido2_error_unexpected
}

/** The format arguments of [hwKeyErrorMessageRes]: the tries left, for a PIN refused with a known count. */
internal fun hwKeyErrorMessageArgs(reason: HwKeyError): Array<Any> =
    if (reason is HwKeyError.PinIncorrect && reason.triesRemaining != null) {
        arrayOf<Any>(reason.triesRemaining)
    } else {
        emptyArray()
    }
