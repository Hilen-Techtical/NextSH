// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

/**
 * Shared `.nextsh` backup format, version 2 (decrypted JSON document, shared by Android and Desktop).
 *
 * ## What lives here
 *
 * - `commonMain` (this package): the document model ([BackupDocument] and its sections), the codec
 *   ([BackupCodec]), the validator ([BackupValidator]), the limits ([BackupLimits]) and the import
 *   summary shared by both UIs ([BackupImportReport]).
 * - `jvmCommon` (same package name): `KnownHostPortable` (portable known-host keys, SSHJ based) and
 *   `BackupEnvelope` (the binary frame around the AES-GCM payload).
 *
 * The KDF (PBKDF2) and AES-GCM are NOT here: they stay in `CryptoUtils` (Android) and
 * `DesktopCryptoUtils` (Desktop). The envelope takes and returns bytes.
 *
 * ## Export flow (platform code)
 *
 * 1. Collect the data from the repositories, the vault, the known hosts and the settings.
 * 2. Build a [BackupDocument] (set [BackupDocument.producer] and the settings of the own platform only).
 *    Known hosts go through `KnownHostPortable.toPortable(algorithm, x509)`; an entry that returns
 *    null is skipped and counted by the caller.
 * 3. `val json = BackupCodec.encodeToBytes(document)`: ALWAYS writes `"version":2`.
 * 4. Encrypt `json` with the platform crypto: `sealed = encryptAesGcm(json, key)` which is
 *    `[IV_LEN(1)][IV(12)][CIPHERTEXT||TAG]`, then `BackupEnvelope.pack(salt, sealed)`.
 * 5. Wipe the JSON bytes and the key.
 *
 * ## Import flow (platform code)
 *
 * 1. Check the file size first ([BackupLimits.fitsFile]), then `BackupEnvelope.unpack(file)`;
 *    null means a malformed file.
 * 2. Derive the key from `salt`, decrypt `sealed` (a GCM failure means a wrong passphrase or an
 *    altered file).
 * 3. `BackupCodec.decode(jsonBytes)` returns [BackupDecodeResult]: `Decoded`, `Malformed` or
 *    `UnsupportedVersion`. None of them carries a message or a cause of the decoder, which would
 *    copy the decrypted JSON (secrets included).
 * 4. `BackupValidator.validate(document, knownHostCheck)` returns [BackupValidationResult]:
 *    `Valid` (accepted entries of the whole document, v1 core and secrets included, and the count of
 *    ignored invalid ones; a section over its maximum keeps its first entries and counts the surplus as
 *    invalid; the tunnels of a dropped host are dropped and counted too). `Malformed` is no longer returned. Pass `{ KnownHostPortable.fromPortable(it.keyType, it.publicKey) != null }` as the
 *    known-host check so that blobs unreadable by SSHJ are counted as invalid.
 *    ALL validation happens before the first write.
 * 5. Restore (platform code): themes before hosts, known hosts by insertion only (a different local
 *    key is kept, counted and named; a key whose `host:port` is in
 *    `BackupTrustRules.heldDestinations(localHosts, incomingHosts)` (address in the file of a host already
 *    on the device, alive or deleted) is never the first fingerprint of that address: absent locally it is
 *    held back and reported with `withHeldKnownHosts`, present with the same key it is "already known",
 *    present with another key it is a conflict), settings of `common` plus the own platform only. Each step after the
 *    database runs in its own try; a failure goes to `withFailedSteps` (partial restore warning).
 *    Editors of snippets and themes use `BackupFieldRules` so they refuse what a restore would refuse.
 * 6. Fill a [BackupImportReport] and show it. `Decoded.hasUnknownSections` becomes
 *    [BackupImportReport.newerDataSkipped].
 *
 * ## Compatibility rules (do not break)
 *
 * - The v1 core (`hosts`, `sshKeys`, `tunnels`, `credentials`, `privateKeys`, `keyPassphrases`) keeps
 *   the names and types of the 1.0.1 DTO: a 1.0.x reader must still decode a v2 file, read
 *   `version == 2` and refuse it cleanly (otherwise it would show and log the raw decoder message).
 * - Evolutions are additive: new top-level sections stay in version 2; the reader reports the
 *   unknown ones. Only a change of the meaning of the core bumps to 3.
 * - Never log content: counts and class names only.
 */
object BackupFormat {

    /** Major version written by this codec (always written, see [BackupDocument.version]). */
    const val CURRENT_VERSION: Int = 2

    /** Version assumed when the `version` key is absent (files written by 1.0.x never contain it). */
    const val DEFAULT_VERSION: Int = 1

    /** Highest version this reader accepts. */
    const val MAX_READABLE_VERSION: Int = CURRENT_VERSION

    /** Top-level keys a v2 reader knows. Anything else is reported as newer data. */
    val KNOWN_TOP_LEVEL_KEYS: Set<String> = setOf(
        "version",
        "exportedAt",
        "producer",
        "hosts",
        "sshKeys",
        "tunnels",
        "credentials",
        "privateKeys",
        "keyPassphrases",
        "certificates",
        "snippets",
        "customThemes",
        "knownHosts",
        "settings",
    )

    /** SSH key types accepted for portable known hosts (the SSH wire names). */
    val KNOWN_HOST_KEY_TYPES: Set<String> = setOf(
        "ssh-ed25519",
        "ssh-rsa",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
    )

    /** Names of the built-in terminal presets: a custom theme id must never collide with one. */
    val TERMINAL_PRESET_NAMES: Set<String> = setOf(
        "TECHTICAL_DARK",
        "TECHTICAL_LIGHT",
        "SOLARIZED_DARK",
        "DRACULA",
        "NORD",
        "MONOKAI",
        "GRUVBOX_DARK",
        "SOLARIZED_LIGHT",
        "HIGH_CONTRAST",
    )
}

/** Platform that wrote or reads a backup; selects the platform section of the settings. */
enum class BackupPlatform { ANDROID, DESKTOP }
