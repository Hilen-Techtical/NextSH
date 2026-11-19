// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import fr.techtical.nextsh.shared.core.ssh.provenKey
import fr.techtical.nextsh.shared.domain.model.SshKey

/** The private key ALREADY in this device's vault under the id of an SSH key of the file. */
enum class LocalPrivateKey {
    /** No private key under this id. */
    ABSENT,

    /** A private key equal to the file's one ([BackupKeyRules.localPrivateKeyState]): writing it changes nothing. */
    SAME_AS_FILE,

    /**
     * A private key that is not the file's one, or whose content was not compared (the file has no private
     * key for this id, or the vault cannot be read). The safe value when unsure.
     */
    PRESENT,
}

/** What a restore does with one SSH key id of the file ([BackupKeyRules.decide]). Never carries a secret. */
class SshKeyRestoreDecision(
    /**
     * The row to save, or null when there is no row to save: the file has no row for this id and the local
     * row is unchanged.
     */
    val key: SshKey?,
    /** Write the private key of the file to the vault. */
    val writePrivateKey: Boolean,
    /** Write the key passphrase of the file to the vault. */
    val writePassphrase: Boolean,
    /** The local key kept its own key although the file offered another one: count it in [BackupImportReport.localKeysKept]. */
    val localKeyKept: Boolean,
    /**
     * The public line a user may have copied (the local one, or the file's when the device has none) did not
     * match its private key and was replaced by the proven derived one: count it in
     * [BackupImportReport.publicKeysRecomputed].
     */
    val publicKeyRecomputed: Boolean,
) {
    override fun toString(): String =
        "SshKeyRestoreDecision(key=${key != null}, writePrivateKey=$writePrivateKey, writePassphrase=$writePassphrase, " +
            "localKeyKept=$localKeyKept, publicKeyRecomputed=$publicKeyRecomputed)"
}

/**
 * "Add only" rules of the SSH keys of a restore, identical on both platforms. Pure: no state, no log.
 *
 * A key that is ALREADY on the device (same id, live row) keeps its two halves:
 * - its private key is never replaced by the file's one (a private key may exist nowhere else);
 * - its public key is replaced only by the public key DERIVED from a private key of the file that is
 *   really written, and PROVEN by a signature ([SshPublicKeyDerivation.Derived]).
 * - a hardware key (biometric or FIDO2, [SshKeyRules.isHardwareKey]) keeps everything: no private key of
 *   the vault can be its private key.
 * The file still renames the key (label and every field that is not key material).
 *
 * A key that is NOT on the device (no row, or a deleted one) is taken from the file as before, its public
 * line replaced by the proven derived one when it does not match its private key.
 *
 * How to use it, per SSH key id of the file (ids of `sshKeys`, `privateKeys` and `keyPassphrases`, so that a
 * private key without a row of the file never overwrites a local one either):
 * 1. read the live local row (`local`) and, when the file has a private key for the id and the vault holds
 *    one, compare them with [localPrivateKeyState] (never decrypt the whole vault to do so);
 * 2. when [shouldDerive] says so, BEFORE any write and outside any database transaction, run the deriver on the
 *    file's private key WITHOUT passphrase (no key derivation function at import: an encrypted key gives
 *    `NeedsPassphrase`, which proves nothing and changes nothing);
 * 3. call [decide] and save [SshKeyRestoreDecision.key] (when not null), then write the secrets it allows.
 */
object BackupKeyRules {

    /**
     * [LocalPrivateKey] of an id from the vault value [localPrivateKey] (null when the vault has none) and the
     * file's value [filePrivateKey] (null when the file has none). Equal means the same lines once line ends
     * and whitespace around each line are ignored.
     */
    fun localPrivateKeyState(localPrivateKey: String?, filePrivateKey: String?): LocalPrivateKey = when {
        localPrivateKey == null -> LocalPrivateKey.ABSENT
        filePrivateKey != null && samePrivateKeyText(localPrivateKey, filePrivateKey) -> LocalPrivateKey.SAME_AS_FILE
        else -> LocalPrivateKey.PRESENT
    }

    /**
     * True when [decide] needs a derivation of the file's private key: in every case where that key could be
     * written, that is no live local row (whatever the file's row, even none or a hardware one), or a live
     * software row whose vault slot is [LocalPrivateKey.ABSENT] or [LocalPrivateKey.SAME_AS_FILE]. The result
     * may prove the line, and a [SshPublicKeyDerivation.TooCostly] one keeps the key out of the vault.
     * [incoming] does not change the answer.
     */
    fun shouldDerive(incoming: SshKey?, local: SshKey?, filePrivateKey: Boolean, localPrivateKey: LocalPrivateKey): Boolean {
        if (!filePrivateKey) return false
        if (local == null) return true
        return !SshKeyRules.isHardwareKey(local) && localPrivateKey != LocalPrivateKey.PRESENT
    }

    /**
     * The decision for one id.
     *
     * @param incoming the row of the file, as validated ([BackupValidator]); null when the file has none.
     * @param local the LIVE local row with the same id; null when absent or deleted.
     * @param filePrivateKey the (validated) file has a private key for this id.
     * @param filePassphrase the (validated) file has a key passphrase for this id.
     * @param localPrivateKey the vault state for this id ([localPrivateKeyState]); ignored without a live local row.
     * @param derivation the deriver's result on the FILE's private key, or null when not run. Only a proven key
     * ([SshPublicKeyDerivation.Derived], or the real key behind an `Inconsistent` file) and
     * [SshPublicKeyDerivation.TooCostly] (the private key and its passphrase are never written) have an effect.
     *
     * Decision table (L = local row, F = file row, D = proven derived key):
     *
     * | Local row | Private key of the file | Vault | Row saved | Private / passphrase | Counted |
     * |---|---|---|---|---|---|
     * | any | too costly to open ([SshPublicKeyDerivation.TooCostly]) | any | as if the file had no private key | not written / not written | only by the rules of the public line |
     * | none or deleted | any | any | F, its line replaced by D when F's key differs from D (software F only) | written / written | recomputed when F's line was not empty |
     * | hardware | any | any | F with L's key material | not written / not written | kept when the file brings a private key or another public key |
     * | software | proven D, written | ABSENT or SAME | F with D as key (line of F or L when equal to D, else D with F's or L's comment), software flags | written / written | recomputed when L's non-empty line differs from D, or L is empty and F's non-empty line differs from D |
     * | software, line not empty | not proven, F's line the same key as L | ABSENT or SAME | F with L's key material | written / written | |
     * | software, line not empty | not proven, F's line another key or empty | ABSENT | F with L's key material | not written / not written (as PRESENT) | kept when F's line is another key |
     * | software, line not empty | not proven, F's line another key | SAME | F with L's key material | written (same text) / written | kept |
     * | software, line not empty | none | any | F with L's key material | none / written only if the vault is ABSENT | kept when F's line is another key |
     * | software, line empty | written | ABSENT or SAME | F (D when proven) | written / written | |
     * | software, line empty | none | ABSENT | F | none / written | |
     * | software, line empty | none | PRESENT | F with L's key material | none / not written | |
     * | software | present | PRESENT | F with L's key material | not written / not written | kept |
     *
     * Key material = `keyType`, `publicKey`, `isBiometric`, `keystoreAlias`, `fido2CredentialId`, `fido2RpId`.
     * Everything else (label first) comes from the file. Comments never count as a different key.
     */
    fun decide(
        incoming: SshKey?,
        local: SshKey?,
        filePrivateKey: Boolean,
        filePassphrase: Boolean,
        localPrivateKey: LocalPrivateKey,
        derivation: SshPublicKeyDerivation?,
    ): SshKeyRestoreDecision {
        // SECURITY: a private key too costly to open never reaches the vault, nor its passphrase: SSHJ would run
        // its key derivation function at every connection (billions of iterations, one core frozen with no way
        // to cancel). Everything else is decided as if the file brought no private key; the refusal itself is
        // not counted as a kept key. An `UnsupportedEncryption` key is bounded in cost and goes on as unproven.
        if (filePrivateKey && derivation == SshPublicKeyDerivation.TooCostly) {
            return decide(
                incoming = incoming,
                local = local,
                filePrivateKey = false,
                filePassphrase = false,
                localPrivateKey = if (localPrivateKey == LocalPrivateKey.SAME_AS_FILE) LocalPrivateKey.PRESENT else localPrivateKey,
                derivation = null,
            )
        }
        val proven = if (filePrivateKey) derivation?.provenKey() else null
        val fileLine = incoming?.publicKey.orEmpty()

        // Not on the device: the file wins, its line repaired when proven wrong.
        if (local == null) {
            val usable = if (incoming != null && !SshKeyRules.isHardwareKey(incoming)) proven else null
            val wrong = usable != null && SshPublicKeyLine.blobKey(fileLine) != usable.blobKey
            val key = if (incoming != null && usable != null && wrong) {
                softwareKey(incoming, usable, usable.line(SshPublicKeyLine.comment(fileLine)))
            } else {
                incoming
            }
            return SshKeyRestoreDecision(
                key = key,
                writePrivateKey = filePrivateKey,
                writePassphrase = filePassphrase,
                localKeyKept = false,
                publicKeyRecomputed = wrong && fileLine.isNotBlank(),
            )
        }

        // A hardware key keeps everything: the file only renames it.
        if (SshKeyRules.isHardwareKey(local)) {
            return SshKeyRestoreDecision(
                key = incoming?.let { keepMaterial(it, local) },
                writePrivateKey = false,
                writePassphrase = false,
                localKeyKept = filePrivateKey || (incoming != null && offersAnotherKey(incoming, local)),
                publicKeyRecomputed = false,
            )
        }

        val localLine = local.publicKey
        // An empty vault slot next to a public line, and a private key of the file that nothing proves to be
        // that line's key (encrypted, unreadable) while the file's own line says another key or nothing: the
        // private key is not written, as if the slot were taken.
        val unprovenOtherKey = filePrivateKey && localPrivateKey == LocalPrivateKey.ABSENT && proven == null &&
            localLine.isNotBlank() && SshPublicKeyLine.blobKey(fileLine) != SshPublicKeyLine.blobKey(localLine)
        val vault = if (unprovenOtherKey) LocalPrivateKey.PRESENT else localPrivateKey
        val writePrivate = filePrivateKey && vault != LocalPrivateKey.PRESENT
        val writePassphrase = filePassphrase &&
            (writePrivate || (!filePrivateKey && vault == LocalPrivateKey.ABSENT))
        val base = incoming ?: local
        // Counted only when the file offered another key for sure: another private key for a taken slot, or
        // (below) another public line. A refused unproven private key with an empty line of the file is not.
        var kept = filePrivateKey && !writePrivate && !unprovenOtherKey
        var recomputed = false
        val key: SshKey = when {
            writePrivate && proven != null -> {
                val line = when {
                    SshPublicKeyLine.blobKey(fileLine) == proven.blobKey -> fileLine
                    SshPublicKeyLine.blobKey(localLine) == proven.blobKey -> localLine
                    else -> proven.line(SshPublicKeyLine.comment(fileLine) ?: SshPublicKeyLine.comment(localLine))
                }
                // Counted when what the user may have copied changes: a local line of another key, or, without
                // a local line, a wrong line of the file. A right local line and a wrong file line count nothing.
                recomputed = if (localLine.isNotBlank()) differsFrom(localLine, proven) else differsFrom(fileLine, proven)
                softwareKey(base, proven, line)
            }
            localLine.isNotBlank() -> {
                if (incoming != null && offersAnotherKey(incoming, local)) kept = true
                keepMaterial(base, local)
            }
            // No public line to protect: the file's key goes with the file's private key, or with nothing.
            writePrivate || vault == LocalPrivateKey.ABSENT -> base
            // A local private key without a public line is never paired with an unproven line of the file.
            else -> keepMaterial(base, local)
        }
        return SshKeyRestoreDecision(
            key = if (incoming == null && key == local) null else key,
            writePrivateKey = writePrivate,
            writePassphrase = writePassphrase,
            localKeyKept = kept,
            publicKeyRecomputed = recomputed,
        )
    }

    /** Same private key text: same non-empty lines once each line is trimmed (line ends of any kind ignored). */
    internal fun samePrivateKeyText(a: String, b: String): Boolean = keyLines(a) == keyLines(b)

    private fun keyLines(s: String): List<String> = s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun differsFrom(line: String, proven: SshPublicKeyDerivation.Derived): Boolean =
        line.isNotBlank() && SshPublicKeyLine.blobKey(line) != proven.blobKey

    /** The file's line is not empty and is another key than the local line (comments ignored). */
    private fun offersAnotherKey(incoming: SshKey, local: SshKey): Boolean =
        incoming.publicKey.isNotBlank() &&
            SshPublicKeyLine.blobKey(incoming.publicKey) != SshPublicKeyLine.blobKey(local.publicKey)

    private fun keepMaterial(base: SshKey, local: SshKey): SshKey = base.copy(
        keyType = local.keyType,
        publicKey = local.publicKey,
        isBiometric = local.isBiometric,
        keystoreAlias = local.keystoreAlias,
        fido2CredentialId = local.fido2CredentialId,
        fido2RpId = local.fido2RpId,
    )

    private fun softwareKey(base: SshKey, proven: SshPublicKeyDerivation.Derived, line: String): SshKey = base.copy(
        keyType = proven.keyType,
        publicKey = line,
        isBiometric = false,
        keystoreAlias = null,
        fido2CredentialId = null,
        fido2RpId = null,
    )
}
