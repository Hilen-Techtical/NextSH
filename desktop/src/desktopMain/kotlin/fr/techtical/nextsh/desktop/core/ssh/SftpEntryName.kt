// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

/** Maximum length of an entry name, in UTF-8 bytes: the limit of a name on common file systems. */
private const val MAX_NAME_BYTES = 255

/**
 * What keeps a typed name (new folder, new file, rename) from being an entry name.
 *
 * The name is refused and said so, never corrected: `a/b` does not become `ab`. No Compose and no
 * resources here, so that the manager and the dialogs share one rule; the dialogs turn each value
 * into a sentence.
 */
internal enum class SftpEntryNameProblem {
    /** `.` or `..`: the folder itself or its parent, never an entry. */
    RESERVED,

    /** A `/` or a `\`: the name would designate a path, not an entry. */
    SEPARATOR,

    /** A control character (C0, DEL or C1), invisible on screen. */
    CONTROL_CHARACTER,

    /**
     * An invisible format character (see [isInvisibleFormat]), which hides in the name or changes how it is
     * displayed. Written as code points, never as raw characters:
     * - bidirectional controls (the Unicode `Bidi_Control` property, the Trojan Source list): U+061C, U+200E,
     *   U+200F, U+202A to U+202E, U+2066 to U+2069. They reorder the display: a name ending in `.exe` can look
     *   like it ends in `.jpg`;
     * - invisible characters with no width and no role in a script: U+200B, U+2060 to U+2064, U+206A to
     *   U+206F, U+FEFF, U+FFF9 to U+FFFB. Two different names look identical;
     * - the soft hyphen U+00AD, invisible outside a line break;
     * - the Khmer inherent vowels U+17B4 and U+17B5, invisible and never to be used according to Unicode;
     * - the line and paragraph separators U+2028 and U+2029, which [Char.isISOControl] does not catch.
     *
     * Still allowed: the zero width joiner U+200D and non-joiner U+200C (Persian spelling, Indic conjuncts,
     * composed emoji), the variation selectors U+FE00 to U+FE0F, the Mongolian U+180B to U+180F, and the format characters that
     * belong to a script (Arabic number signs, tags of the subdivision flags...). Not the whole `Cf` category:
     * it would refuse those too, and it depends on the Unicode version of the runtime. Same list as Android.
     */
    INVISIBLE_FORMAT,

    /** More than 255 bytes in UTF-8, the limit of a name on common file systems. */
    TOO_LONG,
}

/**
 * The defect of an entry name, or null when there is none to report.
 *
 * Null for an empty name too: as long as nothing is typed there is nothing to object to (the confirm
 * button stays disabled on its own, see [isValidEntryName]). Edge spaces are not judged here either:
 * the dialogs strip them before asking, and [isValidEntryName] refuses them.
 *
 * Control characters are those of [Char.isISOControl] (C0, DEL and C1), invisible format characters those
 * of [isInvisibleFormat]; the sender of a local file replaces both with `_` ([replaceInvisibleCharacters]).
 * The length is counted in UTF-8 bytes, not in characters, because it is the limit of the server's file
 * system: 128 `é` (256 bytes) are too long, and an emoji counts for 4 bytes.
 *
 * Order of the checks: reserved, separator, control, invisible format, length.
 */
internal fun sftpEntryNameProblem(name: String): SftpEntryNameProblem? = when {
    name.isEmpty() -> null
    name == "." || name == ".." -> SftpEntryNameProblem.RESERVED
    name.any { it == '/' || it == '\\' } -> SftpEntryNameProblem.SEPARATOR
    name.any { it.isISOControl() } -> SftpEntryNameProblem.CONTROL_CHARACTER
    name.any { it.isInvisibleFormat() } -> SftpEntryNameProblem.INVISIBLE_FORMAT
    name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> SftpEntryNameProblem.TOO_LONG
    else -> null
}

/**
 * True when [name] is an entry name exactly as the server will receive it: not blank, no edge space, and
 * no defect in the sense of [sftpEntryNameProblem].
 *
 * Strict: a name is refused, never corrected. Glued to a parent folder, an accepted name comes out
 * unchanged from [DesktopSftpManager.sanitizePath]: a `+`, a `%` or a `%2F` stay characters of the name.
 */
internal fun isValidEntryName(name: String): Boolean =
    name.isNotBlank() && name == name.trim() && sftpEntryNameProblem(name) == null

/**
 * True for an invisible format character that a name may not hold (see the KDoc of
 * [SftpEntryNameProblem.INVISIBLE_FORMAT] for the list and the reasons). Written as escapes, never raw.
 * All of them are in the basic plane: a `Char` test is enough, surrogates fall in no range.
 */
internal fun Char.isInvisibleFormat(): Boolean =
    this == '\u00AD' || this == '\u061C' || this == '\u200B' || this == '\u200E' || this == '\u200F' ||
        this == '\u2028' || this == '\u2029' || this == '\uFEFF' ||
        this in '\u17B4'..'\u17B5' || this in '\u202A'..'\u202E' || this in '\u2060'..'\u206F' ||
        this in '\uFFF9'..'\uFFFB'

/**
 * [name] where every control character ([Char.isISOControl]) and invisible format character becomes
 * [replacement]. Returns [name] itself when there is none. Replaces, never removes: the trace stays visible.
 */
internal fun replaceInvisibleCharacters(name: String, replacement: Char): String {
    if (name.none { it.isHiddenInName() }) return name
    return buildString(name.length) { name.forEach { append(if (it.isHiddenInName()) replacement else it) } }
}

/** True for a character the lists show as U+FFFD: a control ([Char.isISOControl]) or an invisible format character. */
internal fun Char.isHiddenInName(): Boolean = isISOControl() || isInvisibleFormat()
