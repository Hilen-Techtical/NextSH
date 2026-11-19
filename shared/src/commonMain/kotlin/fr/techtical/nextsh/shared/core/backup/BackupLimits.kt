// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

/** Size limits of the backup format, one source for both platforms. */
object BackupLimits {

    /** Maximum size of a `.nextsh` file, in bytes (check it BEFORE reading the whole file). */
    const val MAX_FILE_BYTES: Long = 50L * 1024 * 1024

    /** Accepted length of the salt in the binary frame. */
    const val MIN_SALT_BYTES: Int = 1
    const val MAX_SALT_BYTES: Int = 1024

    const val MAX_SNIPPETS: Int = 10_000
    const val MAX_CUSTOM_THEMES: Int = 500
    const val MAX_KNOWN_HOSTS: Int = 10_000
    const val MAX_CERTIFICATES: Int = 1_000

    /** Maximum size of one certificate value, in UTF-8 bytes (64 KiB). */
    const val MAX_CERTIFICATE_BYTES: Int = 64 * 1024

    /** Maximum number of entries of each section of the v1 core (same cap as snippets and known hosts). */
    const val MAX_HOSTS: Int = 10_000
    const val MAX_SSH_KEYS: Int = 10_000
    const val MAX_TUNNELS: Int = 10_000
    const val MAX_CREDENTIALS: Int = 10_000
    const val MAX_PRIVATE_KEYS: Int = 10_000
    const val MAX_KEY_PASSPHRASES: Int = 10_000

    /**
     * Maximum size of one secret value (password, private key PEM, key passphrase), in UTF-8 bytes (64 KiB).
     * The largest usable SSH private key (RSA 16384, the OpenSSH ceiling) is about 13 KiB as PEM, and no
     * password or passphrase comes near 1 KiB: 64 KiB keeps a wide margin and one constant for all secrets.
     */
    const val MAX_SECRET_VALUE_BYTES: Int = 64 * 1024

    /**
     * Maximum total of all secret values of a file (private keys first, then key passphrases,
     * passwords and certificates, in that order), in UTF-8 bytes (16 MiB). It bounds what a file leaves in the vault for good.
     */
    const val MAX_SECRETS_TOTAL_BYTES: Int = 16 * 1024 * 1024

    /** Maximum size of the public key line of an SSH key, in UTF-8 bytes (64 KiB). */
    const val MAX_SSH_PUBLIC_KEY_BYTES: Int = 64 * 1024

    /** Maximum length of a free text of the v1 core (labels, user names, groups), in characters. */
    const val MAX_FREE_TEXT_LENGTH: Int = 4_096

    /** Maximum length of an id, in characters. */
    const val MAX_ID_LENGTH: Int = 128

    /** Maximum length of a label, a name or a category, in characters. */
    const val MAX_TEXT_LENGTH: Int = 256

    /** Maximum size of a snippet command, in UTF-8 bytes (64 KiB). */
    const val MAX_COMMAND_BYTES: Int = 64 * 1024

    /** Maximum length of the host part of a `host:port` known-host key, in characters. */
    const val MAX_HOST_LENGTH: Int = 255

    /** Maximum size of a decoded SSH public key blob, in bytes (8 KiB). */
    const val MAX_PUBLIC_KEY_BLOB_BYTES: Int = 8 * 1024

    /** Maximum length of the `appVersion` of the producer, in characters. */
    const val MAX_APP_VERSION_LENGTH: Int = 64

    /** True when a file of [sizeBytes] bytes may be read at all. */
    fun fitsFile(sizeBytes: Long): Boolean = sizeBytes in 0..MAX_FILE_BYTES
}
