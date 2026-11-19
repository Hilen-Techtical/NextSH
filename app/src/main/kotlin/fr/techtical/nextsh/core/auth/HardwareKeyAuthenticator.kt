// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

/**
 * Interface for FIDO2 hardware security key communication (USB/NFC).
 * Produces raw CTAP2 assertions for SSH sk-* authentication.
 */
interface HardwareKeyAuthenticator {

    /**
     * Request a FIDO2 assertion from a hardware key, waiting for one to be plugged in or tapped.
     *
     * There is no timeout of its own: the caller ends the wait by cancelling the coroutine (the
     * FIDO2 prompt does it when its deadline passes), which also stops a pending touch on the key.
     * The cancellation is never turned into a result, it is rethrown.
     *
     * @param rpId Relying party ID (typically "ssh:" for SSH keys)
     * @param clientDataHash SHA-256 of the data to sign (SSH signing data). Zeroed when this returns.
     * @param allowedCredentials List of credential IDs the server will accept
     * @param requireUserVerification Whether to require built-in user verification on the key
     * @param onStatus Told where the exchange stands (waiting for the key, touch it, ...), so the UI
     *   can say what the user has to do. Called from whichever thread the work runs on.
     * @param pinProvider Called when the key requires PIN authentication (CTAP2_ERR_PIN_REQUIRED).
     *   Must return the PIN as a CharArray (wiped by the authenticator after use), or null to cancel.
     * @return Assertion result with signature, flags, and counter
     */
    suspend fun getAssertion(
        rpId: String,
        clientDataHash: ByteArray,
        allowedCredentials: List<ByteArray>,
        requireUserVerification: Boolean = false,
        onStatus: (HwKeyStatus) -> Unit = {},
        pinProvider: (suspend (PinPrompt) -> CharArray?)? = null,
    ): HwKeyResult
}

/**
 * Carries context about a PIN prompt triggered by the key refusing the assertion without PIN auth.
 *
 * @param triesRemaining Retries left before lockout; null if the key has not reported the count.
 * @param incorrect The previous PIN was refused: the prompt is a retry.
 * @param secondContact Over NFC the connection is closed while the PIN is typed: after it, the key
 *   has to be taken away and brought close again. The prompt says so.
 */
data class PinPrompt(
    val triesRemaining: Int?,
    val incorrect: Boolean = false,
    val secondContact: Boolean = false,
)

/** Where a [HardwareKeyAuthenticator.getAssertion] exchange stands, for the UI to tell the user. */
enum class HwKeyStatus {
    /** No key yet: plug it in or bring it close to the phone. */
    WAITING_FOR_KEY,

    /**
     * No key yet, and one is plugged in that Android has not (yet) let NextSH use. That is the normal
     * state while the system prompt "Open NextSH?" is on screen, so the sentence tells to accept it;
     * only when it never appears (it was refused or missed earlier) does unplugging the key and
     * plugging it back bring it again. It must not read as a refusal.
     */
    USB_ACCESS_DENIED,

    /** No key yet, and NFC is switched off: turn it on in the settings, or use the USB port. */
    NFC_DISABLED,

    /** A key is there and being talked to: over NFC it must stay in place. */
    TALKING_TO_KEY,

    /** Over USB, the key waits for a touch on its button or sensor. */
    TOUCH_KEY,

    /**
     * Over NFC, as the PIN is asked: the connection is already closed, so the key has to be taken
     * away, the PIN typed, and the key brought close again. Said before the typing: "keep it in
     * place" would still be on screen otherwise, when it no longer helps.
     */
    PIN_THEN_REAPPROACH,

    /** Over NFC, after the PIN: take the key away, then bring it close again. */
    REMOVE_AND_REAPPROACH,
}

/**
 * Why a hardware key exchange failed. A reason, not a sentence: the layer that knows the language
 * of the user turns it into text.
 */
sealed interface HwKeyError {
    /** The key does not hold the credential of this SSH key (CTAP2_ERR_NO_CREDENTIALS). */
    data object NoMatchingCredential : HwKeyError

    /** The PIN was refused, [triesRemaining] is null when the key did not say. */
    data class PinIncorrect(val triesRemaining: Int?) : HwKeyError

    /** The PIN is locked for good: only a reset of the key unlocks it (CTAP2_ERR_PIN_BLOCKED). */
    data object PinBlocked : HwKeyError

    /** Too many wrong PINs in a row: the key must be unplugged and plugged back (CTAP2_ERR_PIN_AUTH_BLOCKED). */
    data object PinAuthBlocked : HwKeyError

    /** The key went away or the link broke (USB unplugged, NFC tag lost). */
    data object KeyRemoved : HwKeyError

    /** Not a key this can sign with (no FIDO2 application, unsupported option or algorithm). */
    data object UnsupportedKey : HwKeyError

    /** The key gave up waiting for the touch on its own. */
    data object TouchTimeout : HwKeyError

    /** The key refused the operation (CTAP2_ERR_OPERATION_DENIED, CTAP2_ERR_UP_REQUIRED). */
    data object Refused : HwKeyError

    /** The key wants a PIN and nothing can ask the user for it. */
    data object PinUiUnavailable : HwKeyError

    /**
     * The key requires user verification but has no PIN yet (CTAP2_ERR_PIN_NOT_SET): there is
     * nothing to type, a PIN has to be set on the key first.
     */
    data object PinNotSet : HwKeyError

    /** Anything else, including a malformed answer from the key. */
    data object Unexpected : HwKeyError
}

sealed class HwKeyResult {
    data class Success(
        val flags: Byte,
        val counter: UInt,
        val signature: ByteArray,
        val credentialId: ByteArray,
    ) : HwKeyResult() {
        /** Wipe all crypto material. */
        fun wipe() {
            signature.fill(0)
            credentialId.fill(0)
        }
    }

    data object UserCancelled : HwKeyResult()

    /** The key failed, for [reason]. The connection fails as a key error, not as a cancellation. */
    data class Error(val reason: HwKeyError) : HwKeyResult()
}
