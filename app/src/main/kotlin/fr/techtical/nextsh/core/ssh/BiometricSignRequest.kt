// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import java.security.Signature

/**
 * What the SSH layer asks the user to authorize with a fingerprint: the signature of a biometric
 * (Android Keystore) key.
 *
 * [signature] is an already initialized `SHA256withECDSA` [Signature] of the Keystore key: an opaque
 * handle, no key material, which the `BiometricPrompt` unlocks through a `CryptoObject`. The data to
 * sign is NOT here: the prompt only authorizes the handle, and the SSH side updates it and signs
 * once the prompt has returned (see [BiometricAuthMethod]), so the prompt never holds anything but
 * the handle.
 *
 * [hostLabel] and [keyLabel] are user-owned text for the prompt to display, nothing else: they are
 * never logged, and [toString] hides them so that a log line or a debugger view of a state cannot
 * carry them. Identity equality on purpose: the coordinator tells which request is on screen by
 * reference.
 */
class BiometricSignRequest(
    val signature: Signature,
    val hostLabel: String,
    val keyLabel: String,
) : BiometricRequest {
    override fun toString(): String = "BiometricSignRequest"
}

/** How a fingerprint prompt ended, when it ended with an answer. A prompt nobody answered in time has none (null). */
sealed class BiometricPromptResponse {
    /** The fingerprint was recognized: the [BiometricSignRequest.signature] may sign now. */
    data object Authorized : BiometricPromptResponse()

    /** The user, or the system on the user's behalf (the app was left), gave up. */
    data object Cancelled : BiometricPromptResponse()

    /**
     * The fingerprint cannot be asked for, for [reason]: neither a cancellation the user made nor a
     * refusal by the server, and nothing a new attempt right away would fix. A finger that is not
     * recognized is not this: the system dialog lets the user try again, it is no end.
     */
    data class Failed(val reason: BiometricFailure) : BiometricPromptResponse()

    /**
     * The app was not on screen when the prompt was asked for, so no dialog was started and the
     * turn was not taken: nobody could have presented a finger (see [BiometricPromptCoordinator.prompt]).
     */
    data object NoScreen : BiometricPromptResponse()
}

/** Why the fingerprint could not be asked for, as much as the user can act on. */
enum class BiometricFailure {
    /** Too many wrong fingers: the sensor is locked for a short while. */
    LOCKOUT,

    /** The sensor stays locked until the phone is unlocked with its code. */
    LOCKOUT_PERMANENT,

    /** No fingerprint is enrolled on the phone. */
    NO_BIOMETRICS,

    /** The sensor is busy or broken for now (`ERROR_HW_UNAVAILABLE`): a new attempt later may work. */
    HW_UNAVAILABLE,

    /** The phone has no sensor this app can use (`ERROR_HW_NOT_PRESENT`): permanent, nothing to retry. */
    HW_NOT_PRESENT,

    /** Anything else the system reports. */
    OTHER,
}
