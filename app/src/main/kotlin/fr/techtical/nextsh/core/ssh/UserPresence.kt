// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.fido2ModeOrDefault

/**
 * True when connecting to this host needs the user in front of the phone: a security key to touch
 * (FIDO2 in hardware key mode) or a fingerprint to present (biometric key). Those prompts are
 * rendered by the activity, so nobody can answer them while the app is not on screen.
 *
 * The FIDO2 passkey mode is not one of them: it is a plain login with an SSH key, no prompt. A
 * FIDO2 host without a mode is a security key host, as `ConnectSessionUseCase` reads it (see
 * [fido2ModeOrDefault], the one place of that default).
 *
 * Both `when` are exhaustive on purpose: a new [AuthType] or [Fido2Mode] does not compile until
 * someone decides whether it needs the user.
 */
fun Host.needsUserPresence(): Boolean = when (authType) {
    AuthType.BIOMETRIC_KEY -> true
    AuthType.FIDO2 -> when (fido2ModeOrDefault()) {
        Fido2Mode.HARDWARE_KEY -> true
        Fido2Mode.PASSKEY -> false
    }
    AuthType.PASSWORD,
    AuthType.SSH_KEY,
    AuthType.CERTIFICATE,
    -> false
}
