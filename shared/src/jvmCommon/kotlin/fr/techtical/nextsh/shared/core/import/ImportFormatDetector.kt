// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

/**
 * Sniffs which importer should parse a given export file's content.
 *
 * This replaces the ad-hoc "first non-whitespace char is `{`/`[` → Termius,
 * else → KeePassXC" check that used to be duplicated in the Android and Desktop
 * host-import ViewModels. It is a light **sniff**, not a full parse: it looks at
 * the first non-whitespace character and, for JSON content, a couple of marker
 * substrings, rather than fully parsing the document twice (once to detect,
 * once to import).
 *
 * Detection order:
 *  1. Content that doesn't start with `{` or `[` isn't JSON at all → [ImportFormat.KEEPASSXC]
 *     (KeePassXC's importer internally sniffs CSV vs KeePass 2 XML).
 *  2. JSON containing the `"format":"nextsh-hosts"` marker (see [NextShImporter])
 *     → [ImportFormat.NEXTSH]. This is authoritative: it is NextSH's own,
 *     deliberately-set format tag.
 *  3. JSON containing a Termius-only signature key **in key position** (
 *     `identities`, `ssh_keys`, `connections`, `parent_group`, `remote_address`,
 *     `keys`, `sshKeys`, `key_id`, `user_name`, `ssh_port`, `group_id`, see
 *     [TermiusImporter]) → [ImportFormat.TERMIUS]. Key position (`"key": `,
 *     via regex) rather than a plain substring check, so none of these names
 *     appearing as a VALUE elsewhere in the document (e.g. a snippet field
 *     `"script": "hostname"`) can trigger a false match.
 *  4. Otherwise, JSON that looks like NextSH's own shape: a `"hostname"` field
 *     **in key position**, which is NextSH's primary field name (Termius
 *     favours `address`/`host`) → [ImportFormat.NEXTSH]. This covers a
 *     hand-edited/older native export that lost its `format` marker, and a
 *     bare-array native export (no `hosts` envelope at all).
 *  5. Any remaining JSON → [ImportFormat.TERMIUS], matching the pre-existing
 *     fallback behaviour (Termius is the long-standing default JSON target).
 *
 * This is inherently a heuristic on untrusted, possibly hand-edited text: step 4
 * can misclassify a Termius export that happens to use the `hostname` alias and
 * carries none of the signature collections above as NEXTSH. That is an accepted
 * ambiguity: [NextShImporter] and [TermiusImporter] share enough field aliases
 * that a minimal single-host document is valid input to either parser, so a
 * misdetection in that corner still produces a sane import, just via the other
 * importer's alias table.
 */
object ImportFormatDetector {

    /** Which importer should parse a given file's content. */
    enum class ImportFormat { NEXTSH, TERMIUS, KEEPASSXC }

    /**
     * Defensive cap on the size of a file a caller should read into memory before
     * detection/import. Applied by the UI layer (Android/Desktop file pickers),
     * not by this object: [detect] and the importers themselves operate on an
     * already-decoded [String] and have no way to enforce a byte cap retroactively.
     */
    const val MAX_IMPORT_FILE_BYTES: Long = 10 * 1024 * 1024

    /** True when [sizeBytes] exceeds [MAX_IMPORT_FILE_BYTES] and should be rejected before reading. */
    fun isFileTooLarge(sizeBytes: Long): Boolean = sizeBytes > MAX_IMPORT_FILE_BYTES

    private val NEXTSH_MARKER_REGEX = Regex(""""format"\s*:\s*"nextsh-hosts"""")

    // "hostname" must appear in KEY position ("hostname": ...) to count as
    // NextSH's own field: a plain substring check false-positives on a
    // Termius export whose payload merely contains the word as a VALUE
    // (e.g. a snippet field `"script":"hostname"`), silently misrouting real
    // hosts into the wrong importer. See ImportFormatDetectorTest.
    private val HOSTNAME_KEY_REGEX = Regex(""""hostname"\s*:""")

    // Field names TermiusImporter recognises that NextSH's own schema does not
    // use: presence signals "this is a Termius export", not ours. Kept in sync
    // with TermiusImporter's *_KEYS lists; see that file for the sourced schema.
    // Same key-position requirement as [HOSTNAME_KEY_REGEX] above: a bare
    // substring check would false-positive on any of these names appearing as
    // a VALUE rather than a field name.
    private val TERMIUS_SIGNATURE_KEYS = listOf(
        "identities",
        "ssh_keys",
        "connections",
        "parent_group",
        "remote_address",
        "keys",
        "sshKeys",
        "key_id",
        "user_name",
        "ssh_port",
        "group_id",
    )

    private val TERMIUS_SIGNATURE_KEY_REGEXES = TERMIUS_SIGNATURE_KEYS.map { key ->
        Regex("\"" + Regex.escape(key) + "\"\\s*:")
    }

    /**
     * Detects the import format for [content]. Never throws: worst case, content
     * that cannot be sniffed confidently is routed to [ImportFormat.KEEPASSXC] or
     * [ImportFormat.TERMIUS] per the fallback rules documented on the class.
     */
    fun detect(content: String): ImportFormat {
        val trimmed = content.trimStart('﻿', ' ', '\t', '\r', '\n')
        val firstChar = trimmed.firstOrNull()
        if (firstChar != '{' && firstChar != '[') {
            return ImportFormat.KEEPASSXC
        }
        if (NEXTSH_MARKER_REGEX.containsMatchIn(trimmed)) {
            return ImportFormat.NEXTSH
        }
        if (TERMIUS_SIGNATURE_KEY_REGEXES.any { it.containsMatchIn(trimmed) }) {
            return ImportFormat.TERMIUS
        }
        if (HOSTNAME_KEY_REGEX.containsMatchIn(trimmed)) {
            return ImportFormat.NEXTSH
        }
        return ImportFormat.TERMIUS
    }
}
