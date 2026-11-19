// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

/** One "label: count" line of the import summary. The UI maps [row] to a localized label. */
enum class BackupReportRow {
    HOSTS,
    SSH_KEYS,
    TUNNELS,
    PASSWORDS,
    PRIVATE_KEYS,
    KEY_PASSPHRASES,
    CERTIFICATES,
    SNIPPETS,
    CUSTOM_THEMES,
    KNOWN_HOSTS_ADDED,
    KNOWN_HOSTS_ALREADY_KNOWN,
    SETTINGS_APPLIED,
}

/** A restore step that runs after the database and can fail on its own. */
enum class BackupRestoreStep {
    /** Passwords, private keys, passphrases and certificates written to the vault. */
    VAULT_SECRETS,
    KNOWN_HOSTS,
    SETTINGS,
}

/** A warning line shown after the rows of the import summary. */
enum class BackupReportWarning {
    /** Some steps after the database failed ([BackupImportReport.failedSteps]); the rest was restored. */
    PARTIAL_RESTORE,

    /**
     * Known hosts of the file not imported because their `host:port` is the address, in the file, of a
     * host already on the device ([BackupImportReport.knownHostsHeld]).
     */
    KNOWN_HOST_HELD,

    /** Tunnels whose host is neither in the backup nor on this device ([BackupImportReport.tunnelsSkipped]). */
    TUNNELS_SKIPPED,

    /** Known hosts whose key differs from the local one; the local key was kept. */
    KNOWN_HOST_CONFLICTS,

    /** Entries ignored because they are invalid (v1 core, secrets and new sections). */
    INVALID_IGNORED,

    /** The file has top-level data written by a newer NextSH. */
    NEWER_DATA_NOT_RESTORED,

    /** The language was changed and applies at the next start (Desktop). */
    LANGUAGE_AFTER_RESTART,

    /**
     * Public keys that did not match their private key were replaced by the proven derived one
     * ([BackupImportReport.publicKeysRecomputed]): a copy of the old one on a server should be removed.
     */
    PUBLIC_KEYS_RECOMPUTED,

    /** Keys already on this device kept their own key, the file offered another one ([BackupImportReport.localKeysKept]). */
    LOCAL_KEYS_KEPT,
}

/**
 * Counters of an import, identical on both platforms so that the two UIs show the same summary.
 * The platform importer fills it; the UI reads [rows] and [warnings]. Contains counts and at most
 * [MAX_NAMED_CONFLICTS] `host:port` names, never keys, secrets or other content.
 */
data class BackupImportReport(
    val hosts: Int = 0,
    val sshKeys: Int = 0,
    val tunnels: Int = 0,
    val passwords: Int = 0,
    val privateKeys: Int = 0,
    val keyPassphrases: Int = 0,
    val certificates: Int = 0,
    val snippets: Int = 0,
    val customThemes: Int = 0,
    val knownHostsAdded: Int = 0,
    val knownHostsAlreadyKnown: Int = 0,
    /** Known hosts with a key different from the local one (the local key was kept). */
    val knownHostConflicts: Int = 0,
    /** The first [MAX_NAMED_CONFLICTS] `host:port` of the conflicts, in file order. */
    val knownHostConflictNames: List<String> = emptyList(),
    val settingsApplied: Int = 0,
    /** Entries ignored because invalid (v1 core, secrets and new sections, see [ValidatedBackup.invalidTotal]). */
    val invalidIgnored: Int = 0,
    /** Tunnels ignored because their host is unknown. */
    val tunnelsSkipped: Int = 0,
    /** True when the file has top-level keys of a newer version ([BackupDecodeResult.Decoded.hasUnknownSections]). */
    val newerDataSkipped: Boolean = false,
    /** True when the Desktop language setting was applied (effective at the next start). */
    val languageChanged: Boolean = false,
    /** Steps after the database that failed; empty when everything went through. */
    val failedSteps: Set<BackupRestoreStep> = emptySet(),
    /** Known hosts of the file left out because their `host:port` belongs to a host already on the device (see [BackupTrustRules.heldDestinations]). */
    val knownHostsHeld: Int = 0,
    /** The first [MAX_NAMED_CONFLICTS] held back `host:port`, in file order. */
    val knownHostHeldNames: List<String> = emptyList(),
    /** SSH keys whose public key was replaced by the proven derived one ([SshKeyRestoreDecision.publicKeyRecomputed]). */
    val publicKeysRecomputed: Int = 0,
    /** SSH keys already on the device that kept their own key ([SshKeyRestoreDecision.localKeyKept]). */
    val localKeysKept: Int = 0,
) {
    // Hand written: a host:port must never reach a log through string templates.
    override fun toString(): String = "BackupImportReport(rows=${rows().size}, warnings=${warnings().size})"

    /** The non-zero rows, in display order. */
    fun rows(): List<Pair<BackupReportRow, Int>> = listOf(
        BackupReportRow.HOSTS to hosts,
        BackupReportRow.SSH_KEYS to sshKeys,
        BackupReportRow.TUNNELS to tunnels,
        BackupReportRow.PASSWORDS to passwords,
        BackupReportRow.PRIVATE_KEYS to privateKeys,
        BackupReportRow.KEY_PASSPHRASES to keyPassphrases,
        BackupReportRow.CERTIFICATES to certificates,
        BackupReportRow.SNIPPETS to snippets,
        BackupReportRow.CUSTOM_THEMES to customThemes,
        BackupReportRow.KNOWN_HOSTS_ADDED to knownHostsAdded,
        BackupReportRow.KNOWN_HOSTS_ALREADY_KNOWN to knownHostsAlreadyKnown,
        BackupReportRow.SETTINGS_APPLIED to settingsApplied,
    ).filter { it.second > 0 }

    /** The warnings that apply, in display order. */
    fun warnings(): List<BackupReportWarning> = buildList {
        if (failedSteps.isNotEmpty()) add(BackupReportWarning.PARTIAL_RESTORE)
        if (tunnelsSkipped > 0) add(BackupReportWarning.TUNNELS_SKIPPED)
        if (knownHostConflicts > 0) add(BackupReportWarning.KNOWN_HOST_CONFLICTS)
        if (knownHostsHeld > 0) add(BackupReportWarning.KNOWN_HOST_HELD)
        if (publicKeysRecomputed > 0) add(BackupReportWarning.PUBLIC_KEYS_RECOMPUTED)
        if (localKeysKept > 0) add(BackupReportWarning.LOCAL_KEYS_KEPT)
        if (invalidIgnored > 0) add(BackupReportWarning.INVALID_IGNORED)
        if (newerDataSkipped) add(BackupReportWarning.NEWER_DATA_NOT_RESTORED)
        if (languageChanged) add(BackupReportWarning.LANGUAGE_AFTER_RESTART)
    }

    /** Conflicts not named in [knownHostConflictNames] ("and N others"). */
    val knownHostConflictsNotNamed: Int
        get() = (knownHostConflicts - knownHostConflictNames.size).coerceAtLeast(0)

    /** Copy with the conflicts set from the full list of conflicting `host:port` (only the first 3 are kept). */
    fun withKnownHostConflicts(hostPorts: List<String>): BackupImportReport = copy(
        knownHostConflicts = hostPorts.size,
        knownHostConflictNames = hostPorts.take(MAX_NAMED_CONFLICTS),
    )

    /** Held back known hosts not named in [knownHostHeldNames] ("and N others"). */
    val knownHostsHeldNotNamed: Int
        get() = (knownHostsHeld - knownHostHeldNames.size).coerceAtLeast(0)

    /** Copy with the held back known hosts set from the full list of `host:port` (only the first 3 are named). */
    fun withHeldKnownHosts(hostPorts: List<String>): BackupImportReport = copy(
        knownHostsHeld = hostPorts.size,
        knownHostHeldNames = hostPorts.take(MAX_NAMED_CONFLICTS),
    )

    /** Copy with [steps] recorded as failed (added to those already recorded). */
    fun withFailedSteps(steps: Set<BackupRestoreStep>): BackupImportReport = copy(failedSteps = failedSteps + steps)

    /** Everything is zero: nothing was restored and nothing is worth a warning. */
    val isEmpty: Boolean get() = rows().isEmpty() && warnings().isEmpty()

    companion object {
        const val MAX_NAMED_CONFLICTS: Int = 3
    }
}
