// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import java.security.Signature

/**
 * What [BiometricPromptCoordinator] asks the system dialog for: the signature of a biometric SSH key
 * ([BiometricSignRequest]), or the proof that the person holding the phone is its owner, for the
 * vault ([VaultAuthRequest]).
 *
 * Both take the same turn, the same deadline and the same single dialog of the activity, which is
 * what ends the collision between the vault prompts and the signature prompts (they used to share
 * the one callback slot of androidx without knowing of each other). Requests are told apart by
 * reference, never by value.
 */
sealed interface BiometricRequest

/**
 * Why the vault asks for the owner. The purpose picks the title and the subtitle of the system
 * dialog, so that the dialog says what it is for.
 */
enum class VaultAuthPurpose(@StringRes val titleRes: Int, @StringRes val subtitleRes: Int) {
    /** Unlocking NextSH: at launch, and when the lock screen covers the app. */
    UNLOCK(R.string.vault_auth_unlock_title, R.string.vault_auth_unlock_subtitle),

    /** Saving a host whose password the vault refused to take, its authentication window having closed. */
    CONFIRM_SAVE(R.string.host_save_confirm_title, R.string.host_save_confirm_subtitle),
}

/**
 * The owner of the phone, for the vault: a biometric or the code of the phone (strong biometric from
 * API 30, see `vaultAuthenticators`), with no `CryptoObject`. A success refreshes the
 * authentication window of the master key of the vault, that is all it is for.
 *
 * Identity equality on purpose, like [BiometricSignRequest]: the coordinator tells which request is
 * on screen by reference.
 */
class VaultAuthRequest(val purpose: VaultAuthPurpose) : BiometricRequest {
    override fun toString(): String = "VaultAuthRequest($purpose)"
}

/**
 * Whether the success of the dialog authorizes this request. A signature request needs the very
 * [Signature] it handed over to come back (defense in depth: what the system authorized must be what
 * we are going to sign with). The vault request handed none over, so the only success that is its own
 * is one that comes back with none: a `CryptoObject` on a dialog that had none says the callback is
 * not the one of this dialog, and nothing is authorized by it.
 */
internal fun BiometricRequest.isAuthorizedBy(authorizedSignature: Signature?): Boolean = when (this) {
    is BiometricSignRequest -> authorizedSignature != null && authorizedSignature === signature
    is VaultAuthRequest -> authorizedSignature == null
}
