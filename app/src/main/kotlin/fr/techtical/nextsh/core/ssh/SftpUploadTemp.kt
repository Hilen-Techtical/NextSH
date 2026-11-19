// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SftpFile
import java.util.UUID

// The temporary file an upload writes to when it replaces a file ([SftpManager.writeFile]): a hidden
// name next to the file to replace, `.report.pdf.nextsh-part-1a2b3c4d`. Same shape as the temporary
// file of a Desktop download, so that a leftover is recognized for what it is (an unfinished NextSH
// transfer) whichever side made it.

/** What every temporary name of an upload holds between the part of the file's own name and the suffix. */
internal const val UPLOAD_TEMP_MARKER = ".nextsh-part-"

/**
 * Most bytes of the file's own name kept in the name of its temporary file. Bytes and not characters:
 * file systems limit a name in bytes (255 on ext4, xfs, btrfs) and a CJK character takes three of them.
 * With the leading dot, the marker and the suffix, a temporary name stays at 142 bytes at the most.
 */
internal const val TEMP_NAME_MAX_BYTES = 120

/** Length of the random suffix that tells two temporary files of the same name apart, in hex chars. */
internal const val TEMP_ID_CHARS = 8

/** How many names are tried when the server says that the one drawn is taken. */
internal const val TEMP_CREATE_ATTEMPTS = 3

/**
 * The permissions a temporary file is created with: its owner only (0600). It may replace a private
 * file, and the content that is only partly sent must not be readable by anyone else meanwhile. Kotlin
 * has no octal literal.
 */
internal const val TEMP_PERMISSIONS = 0b110_000_000

/** How many names of vanished temporary files the manager remembers for the listings that were running. */
internal const val TEMP_NAMES_GONE_KEPT = 256

/** A fresh random suffix for a temporary name: the first [TEMP_ID_CHARS] hex chars of a random UUID. */
internal fun newUploadTempId(): String = UUID.randomUUID().toString().take(TEMP_ID_CHARS)

/**
 * The beginning of [name] that fits in [maxBytes] bytes of UTF-8, cut between two code points: never
 * inside a surrogate pair (Linux rejects the resulting name), and never past the byte limit of the file
 * system, which a name of three-byte characters (CJK) reaches long before a limit counted in characters.
 */
internal fun temporaryNamePart(name: String, maxBytes: Int = TEMP_NAME_MAX_BYTES): String {
    var bytes = 0
    var end = 0
    while (end < name.length) {
        val codePoint = name.codePointAt(end)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return name.substring(0, end)
}

/**
 * The path of the temporary file of an upload that replaces [targetName] in [parentDir]:
 * `<parentDir>/.<start of the name>.nextsh-part-<id>`. The temporary file is in the folder of the file
 * it replaces, since a rename only replaces atomically inside one file system. [parentDir] may be the
 * root (`/`), which does not give a double slash.
 *
 * Only the name of the file and a random [id] go in it, both already checked (the name by
 * [isValidEntryName] or `uploadEntryName`, the id comes from the application).
 */
internal fun uploadTempPath(parentDir: String, targetName: String, id: String): String =
    "${parentDir.trimEnd('/')}/.${temporaryNamePart(targetName)}$UPLOAD_TEMP_MARKER$id"

/**
 * What the name of a leftover temporary file tells: [original] is the start of the name of the file the
 * upload was to replace (at most [TEMP_NAME_MAX_BYTES] bytes of it), and [cut] whether that start may be
 * shorter than the real name (see [uploadLeftoverName]).
 */
internal data class UploadLeftoverName(val original: String, val cut: Boolean)

/**
 * Reads [name], the last segment of an entry of a listing, as the name of a temporary file left by an
 * interrupted upload ([uploadTempPath]), or returns null when it is not one. The shape is strict, and
 * the same as on Desktop: a dot, 1 to 120 bytes of UTF-8 that are not `.` or `..` and hold no slash,
 * backslash or control character, the marker, then exactly [TEMP_ID_CHARS] lowercase hex chars, and
 * nothing else. Written without a regex on purpose: no `$` that would accept a final line break, and a
 * linear read.
 *
 * A name refused here is a leftover that goes unreported, never a file reported by mistake: the only
 * mistake a strict shape can make is on the safe side. The last marker wins (a name that holds the
 * marker twice gives everything before the last one as [UploadLeftoverName.original]).
 *
 * [UploadLeftoverName.cut] is true when the start is 117 bytes or more: a start that long may have been
 * cut by [temporaryNamePart] (a code point takes 4 bytes at most), one of 116 or less is whole. A real
 * name of 117 to 120 bytes is shown with the cut mark too: cosmetic, and nothing looks up the target.
 */
internal fun uploadLeftoverName(name: String): UploadLeftoverName? {
    val idStart = name.length - TEMP_ID_CHARS
    val markerStart = idStart - UPLOAD_TEMP_MARKER.length
    if (markerStart < 2 || name[0] != '.') return null
    if (!name.startsWith(UPLOAD_TEMP_MARKER, markerStart)) return null
    if (!name.substring(idStart).all { it in '0'..'9' || it in 'a'..'f' }) return null
    val part = name.substring(1, markerStart)
    if (part == "." || part == "..") return null
    if (part.any { it == '/' || it == '\\' || it.isISOControl() }) return null
    if (temporaryNamePart(part) != part) return null
    return UploadLeftoverName(part, cut = temporaryNamePart(part, maxBytes = 116) != part)
}

/**
 * Whether [file] is reported as the leftover of an interrupted upload: an ordinary file (neither a
 * folder nor a link) whose name has the shape of [uploadLeftoverName]. A folder or a link of that name
 * is nothing NextSH made, and a deletion of it must never be offered.
 */
internal fun isUploadLeftover(file: SftpFile): Boolean =
    !file.isDirectory && !file.isSymlink && uploadLeftoverName(file.name) != null
