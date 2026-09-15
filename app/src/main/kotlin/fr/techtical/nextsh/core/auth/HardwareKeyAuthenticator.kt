// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for FIDO2 hardware security key communication (USB/NFC).
 * Produces raw CTAP2 assertions for SSH sk-* authentication.
 */
interface HardwareKeyAuthenticator {

    /** Current connection state of hardware key devices. */
    val connectionState: StateFlow<HwKeyConnectionState>

    /**
     * Request a FIDO2 assertion from a connected hardware key.
     *
     * @param rpId Relying party ID (typically "ssh:" for SSH keys)
     * @param clientDataHash SHA-256 of the data to sign (SSH signing data)
     * @param allowedCredentials List of credential IDs the server will accept
     * @param requireUserVerification Whether to require PIN/biometric on the key
     * @param pinProvider Called when the key requires PIN authentication (CTAP2_ERR_PIN_REQUIRED).
     *   Must return the PIN as a CharArray (wiped by the authenticator after use), or null to cancel.
     * @return Assertion result with signature, flags, and counter
     */
    suspend fun getAssertion(
        rpId: String,
        clientDataHash: ByteArray,
        allowedCredentials: List<ByteArray>,
        requireUserVerification: Boolean = false,
        pinProvider: (suspend (PinPrompt) -> CharArray?)? = null,
    ): HwKeyResult
}

/**
 * Carries context about a PIN prompt triggered by the key refusing the assertion without PIN auth.
 *
 * @param triesRemaining Retries left before lockout; null if the key has not yet reported the count.
 */
data class PinPrompt(val triesRemaining: Int?)

enum class HwKeyConnectionState {
    DISCONNECTED,
    USB_CONNECTED,
    NFC_READY,
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
    data class Error(val message: String) : HwKeyResult()

    /** The key is permanently locked after too many wrong PIN attempts. A reset is required. */
    data object PinBlocked : HwKeyResult()
}
