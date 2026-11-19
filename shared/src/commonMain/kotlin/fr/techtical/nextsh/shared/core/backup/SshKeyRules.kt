// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType

/**
 * One rule for an SSH key row that comes from outside the device (a backup restore, a sync peer, a
 * conflict resolution): what is cleaned, what is refused. Pure, never logs.
 */
object SshKeyRules {

    /**
     * [key] the way it is stored, or null when it must not be written at all.
     *
     * Cleaned, never refused: the label (control chars become a space, cut to 4 096 chars), the public key
     * line ([SshPublicKeyLine.normalize]: trimmed, one line) and `fido2RpId` (control chars become a space;
     * the field is informative, the connection reads the application from the public key).
     *
     * Refused (null): an invalid id (empty, over 128 chars, control char), a public key line over 64 KiB
     * of UTF-8, a `keystoreAlias` over 256 chars or with a control char, a `fido2CredentialId` over 4 096
     * chars or with a control char (no legitimate value has one: it is base64 since FIDO2 exists), a
     * `fido2RpId` over 4 096 chars.
     */
    fun normalizeForStorage(key: SshKey): SshKey? {
        if (!BackupValidator.isValidId(key.id)) return null
        val publicKey = SshPublicKeyLine.normalize(key.publicKey) ?: return null
        if (key.keystoreAlias != null && !BackupValidator.isValidShortText(key.keystoreAlias)) return null
        if (key.fido2CredentialId != null && !BackupValidator.isValidFreeText(key.fido2CredentialId)) return null
        val rpId = key.fido2RpId
        if (rpId != null && rpId.length > BackupLimits.MAX_FREE_TEXT_LENGTH) return null
        return key.copy(
            label = BackupValidator.cleanLabel(key.label),
            publicKey = publicKey,
            fido2RpId = rpId?.let { BackupValidator.replaceControlChars(it) },
        )
    }

    /**
     * True for a key whose private half lives in hardware (Android Keystore biometric key, FIDO2 `sk-*`
     * key): no private key of the vault can ever be its private key, and its public key cannot be derived.
     */
    fun isHardwareKey(key: SshKey): Boolean =
        key.isBiometric || key.keyType == SshKeyType.SK_ED25519 || key.keyType == SshKeyType.SK_ECDSA_256
}
