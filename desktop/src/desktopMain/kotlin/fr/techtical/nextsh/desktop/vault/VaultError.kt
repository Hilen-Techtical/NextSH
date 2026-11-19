// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import androidx.compose.runtime.Composable
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.vault_error_passphrase_mismatch
import fr.techtical.nextsh.desktop.generated.resources.vault_error_passphrase_too_short
import fr.techtical.nextsh.desktop.generated.resources.vault_error_recovery_change_failed
import fr.techtical.nextsh.desktop.generated.resources.vault_error_recovery_generation_failed
import fr.techtical.nextsh.desktop.generated.resources.vault_error_recovery_phrase_invalid
import fr.techtical.nextsh.desktop.generated.resources.vault_error_setup_failed
import fr.techtical.nextsh.desktop.generated.resources.vault_error_wrong_passphrase
import org.jetbrains.compose.resources.stringResource

/**
 * Why a vault screen refuses an action. Typed so that the screen translates it: the view models
 * never carry a text (nor the message of an exception). The screens use the value itself as the
 * shake trigger, the error going back to null between two attempts.
 */
enum class VaultError {
    WRONG_PASSPHRASE,
    INVALID_RECOVERY_PHRASE,
    PASSPHRASE_TOO_SHORT,
    PASSPHRASE_MISMATCH,
    RECOVERY_CHANGE_FAILED,
    SETUP_FAILED,
    RECOVERY_GENERATION_FAILED,
}

@Composable
fun vaultErrorText(error: VaultError): String = stringResource(
    when (error) {
        VaultError.WRONG_PASSPHRASE -> Res.string.vault_error_wrong_passphrase
        VaultError.INVALID_RECOVERY_PHRASE -> Res.string.vault_error_recovery_phrase_invalid
        VaultError.PASSPHRASE_TOO_SHORT -> Res.string.vault_error_passphrase_too_short
        VaultError.PASSPHRASE_MISMATCH -> Res.string.vault_error_passphrase_mismatch
        VaultError.RECOVERY_CHANGE_FAILED -> Res.string.vault_error_recovery_change_failed
        VaultError.SETUP_FAILED -> Res.string.vault_error_setup_failed
        VaultError.RECOVERY_GENERATION_FAILED -> Res.string.vault_error_recovery_generation_failed
    },
)
