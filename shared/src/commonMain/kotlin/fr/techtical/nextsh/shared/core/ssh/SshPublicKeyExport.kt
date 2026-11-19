// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.core.backup.SshKeyRules
import fr.techtical.nextsh.shared.core.backup.SshPublicKeyLine
import fr.techtical.nextsh.shared.domain.model.SshKey

/** What "Copy the public key" and "Download .pub" put out for one key ([SshPublicKeyExportRules.resolve]). */
sealed interface SshPublicKeyExport {

    /**
     * The stored line is the key of the private key: [line] is the proven key with the stored comment, on one
     * line. No notice. Every [line] of this type is cleaned by [SshPublicKeyLine.normalize] (one line).
     */
    class Verified(val line: String) : SshPublicKeyExport {
        override fun toString(): String = "Verified"
    }

    /**
     * The stored line was empty or did not match the private key. [line] is the proven derived one. The
     * platform saves [correctedKey] with an update (the sync spreads it) and, when [hadStoredLine], shows the
     * notice "the saved public key did not match the private key, NextSH copied the right one".
     */
    class Corrected(val line: String, val correctedKey: SshKey, val hadStoredLine: Boolean) : SshPublicKeyExport {
        override fun toString(): String = "Corrected"
    }

    /**
     * A software key whose private key could not be read (absent from the vault, encrypted without a stored
     * passphrase, [SshPublicKeyDerivation.Unsupported], [SshPublicKeyDerivation.UnsupportedEncryption],
     * too costly or unreadable): the stored [line] cleaned to one line, with the notice "copied without a check".
     */
    class Unverified(val line: String) : SshPublicKeyExport {
        override fun toString(): String = "Unverified"
    }

    /** A FIDO2 key, or a biometric key on a device that does not hold it: the stored [line] cleaned to one line, no notice. */
    class Hardware(val line: String) : SshPublicKeyExport {
        override fun toString(): String = "Hardware"
    }

    /** Nothing can be copied: the stored line is empty and no proven key is available. */
    data object Unavailable : SshPublicKeyExport
}

/** Same decision on both platforms for the public key a user copies to a server. Pure, never logs. */
object SshPublicKeyExportRules {

    /**
     * The export of [key]. [derivation] is the deriver's result on the private key of [key] read from the
     * vault (with its stored passphrase: one key derivation function is fine here, bounded by the deriver),
     * or null when the vault has no private key for it.
     *
     * A biometric key on Android is NOT resolved here: the platform reads its public key from the Keystore
     * (and copies nothing when the Keystore entry is gone). Every other hardware key gives [SshPublicKeyExport.Hardware].
     */
    fun resolve(key: SshKey, derivation: SshPublicKeyDerivation?): SshPublicKeyExport {
        // Never put out a stored line as it is: on two lines, it would authorize a second key on the server.
        val stored = SshPublicKeyLine.normalize(key.publicKey).orEmpty()
        if (SshKeyRules.isHardwareKey(key)) {
            return if (stored.isNotEmpty()) SshPublicKeyExport.Hardware(stored) else SshPublicKeyExport.Unavailable
        }
        val proven = derivation?.provenKey()
        if (proven == null) {
            return if (stored.isNotEmpty()) SshPublicKeyExport.Unverified(stored) else SshPublicKeyExport.Unavailable
        }
        if (stored.isNotEmpty() && SshPublicKeyLine.blobKey(stored) == proven.blobKey) {
            return SshPublicKeyExport.Verified(proven.line(SshPublicKeyLine.comment(stored)))
        }
        val line = proven.line(SshPublicKeyLine.comment(stored))
        return SshPublicKeyExport.Corrected(
            line = line,
            correctedKey = key.copy(keyType = proven.keyType, publicKey = line),
            hadStoredLine = stored.isNotEmpty(),
        )
    }
}
