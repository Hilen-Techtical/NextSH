// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.ssh

import fr.techtical.nextsh.shared.core.backup.SshPublicKeyLine
import fr.techtical.nextsh.shared.domain.model.SshKeyType

/**
 * Reads the public key of an SSH private key from the private key itself (never from the public copy a
 * key file may carry) and proves it with a signature. The JVM implementation is `SshPublicKeyDeriver`
 * (jvmCommon), used by both platforms; common code and tests take this interface.
 */
fun interface PublicKeyDeriver {
    /**
     * Derives the public key of [privateKeyPem]. [passphrase] is read, never modified nor kept: the caller
     * wipes it. Without a passphrase an encrypted key gives [SshPublicKeyDerivation.NeedsPassphrase] and no
     * key derivation function is run. Never throws, never logs.
     */
    fun derive(privateKeyPem: String, passphrase: CharArray?): SshPublicKeyDerivation
}

/**
 * Outcome of a [PublicKeyDeriver]. Only [Derived] carries data, and only public data: nothing here holds
 * private key material, and the text forms print nothing of the key.
 */
sealed interface SshPublicKeyDerivation {

    /**
     * The public key computed from the private key, PROVEN: a challenge signed with the private key was
     * verified with this public key, decoded from [blobBase64] by an independent reader. Safe to store and
     * to copy into an `authorized_keys` file.
     */
    class Derived(
        val keyType: SshKeyType,
        /** SSH wire name of the type (`ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`...). */
        val sshType: String,
        /** Standard base64 of the SSH public key blob. */
        val blobBase64: String,
    ) : SshPublicKeyDerivation {
        /** `"<sshType> <blobBase64>"`, comparable with [SshPublicKeyLine.blobKey] of a stored line. */
        val blobKey: String get() = "$sshType $blobBase64"

        /** The OpenSSH line of this key with [comment] cleaned (or [SshPublicKeyLine.DEFAULT_COMMENT]). */
        fun line(comment: String?): String = SshPublicKeyLine.build(sshType, blobBase64, comment)

        override fun equals(other: Any?): Boolean =
            other is Derived && sshType == other.sshType && blobBase64 == other.blobBase64

        override fun hashCode(): Int = 31 * sshType.hashCode() + blobBase64.hashCode()

        // Hand written: a public key is no secret, but nothing of a key goes to a log through a template.
        override fun toString(): String = "Derived"
    }

    /**
     * An OpenSSH key file whose public key copy (the clear envelope of the file) is NOT the public key of
     * its private key. [derived] is the real, proven public key. The key import screen refuses such a file;
     * a restore or a copy uses [derived].
     */
    class Inconsistent(val derived: Derived) : SshPublicKeyDerivation {
        override fun toString(): String = "Inconsistent"
    }

    /** The key is encrypted and no passphrase (or an empty one) was given. Nothing was decrypted. */
    data object NeedsPassphrase : SshPublicKeyDerivation

    /** The key is encrypted and the passphrase does not open it. */
    data object WrongPassphrase : SshPublicKeyDerivation

    /** The text is over 64 KiB of UTF-8 (`BackupLimits.MAX_SECRET_VALUE_BYTES`). Nothing was parsed. */
    data object TooLarge : SshPublicKeyDerivation

    /**
     * Opening the key would cost too much: bcrypt rounds over `SshPublicKeyDeriver.MAX_BCRYPT_ROUNDS`, or
     * PBKDF2, PBES1 or PKCS#12 iterations over `SshPublicKeyDeriver.MAX_PBKDF2_ITERATIONS`. Checked before
     * any list of accepted algorithms, so an unknown cipher never hides the cost. The key derivation function
     * was never run. Never to hand to another reader (SSHJ would run it): the platform refuses the key.
     */
    data object TooCostly : SshPublicKeyDerivation

    /**
     * An ENCRYPTED key (PKCS#8 `ENCRYPTED PRIVATE KEY`, OpenSSH v1 with a key derivation function, PEM with
     * `Proc-Type: 4,ENCRYPTED`) whose scheme, PRF, cipher or key derivation function is outside the accepted
     * lists, its cost being under the bounds. Never to hand to another reader (SSHJ): the platform refuses
     * the key. For "Copy the public key" it is like [Unsupported] (the stored line, not verified).
     */
    data object UnsupportedEncryption : SshPublicKeyDerivation

    /**
     * A clear key the deriver does not read: PuTTY, DSA, Ed25519 in PKCS#8 (which SSHJ 0.38 cannot read
     * either), FIDO2 `sk-*` key files, RSA over 16 384 bits, or a large RSA key once the proof budget of a
     * restore is spent (`memoizedWithoutPassphrase`). Not verifiable here: a restore treats it like an
     * encrypted key (not proven).
     */
    data object Unsupported : SshPublicKeyDerivation

    /** Malformed, truncated or self-contradictory key (its own signature does not verify). */
    data object Unreadable : SshPublicKeyDerivation
}

/** The proven public key: [SshPublicKeyDerivation.Derived] itself or the real key behind an [SshPublicKeyDerivation.Inconsistent] file. */
fun SshPublicKeyDerivation.provenKey(): SshPublicKeyDerivation.Derived? = when (this) {
    is SshPublicKeyDerivation.Derived -> this
    is SshPublicKeyDerivation.Inconsistent -> derived
    else -> null
}
