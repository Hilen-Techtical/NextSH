// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SftpEntryNameProblem
import fr.techtical.nextsh.core.ssh.isValidEntryName
import fr.techtical.nextsh.core.ssh.replaceInvisibleCharacters
import fr.techtical.nextsh.core.ssh.sftpEntryNameProblem

/**
 * What the name dialogs (new folder, new file, rename) make of the text typed so far.
 *
 * @property name the text as it will be sent: [checkEntryName] trims the edge spaces, as the field
 *   always did, and nothing else is changed.
 * @property canConfirm whether the confirm button may be enabled: [name] is a valid entry name.
 * @property helpRes the sentence that says what is wrong with [name], or null when there is nothing to
 *   say (nothing typed yet, a valid name, or a name that may still become valid).
 */
internal data class EntryNameCheck(
    val name: String,
    val canConfirm: Boolean,
    @StringRes val helpRes: Int?,
)

/**
 * Checks [typed] against the rule the manager enforces ([isValidEntryName]), so the dialog says why a
 * name is refused instead of letting the server answer with a failure that tells nothing.
 *
 * A single "." says nothing: it is the first character of ".bashrc", and scolding the user mid-word
 * would be noise. The button stays disabled all the same, since "." is not a name.
 */
internal fun checkEntryName(typed: String): EntryNameCheck {
    val name = typed.trim()
    val help = if (name == ".") null else sftpEntryNameProblem(name)?.messageRes()
    return EntryNameCheck(name = name, canConfirm = isValidEntryName(name), helpRes = help)
}

/**
 * Localized message for a refused name, understandable without technical knowledge.
 * Exhaustive on purpose: a new [SftpEntryNameProblem] value must get its own message.
 */
@StringRes
internal fun SftpEntryNameProblem.messageRes(): Int = when (this) {
    SftpEntryNameProblem.RESERVED -> R.string.sftp_error_name_reserved
    SftpEntryNameProblem.SEPARATOR -> R.string.sftp_error_name_has_separator
    SftpEntryNameProblem.CONTROL_CHARACTER -> R.string.sftp_error_name_has_control
    SftpEntryNameProblem.INVISIBLE_FORMAT -> R.string.sftp_error_name_has_invisible_format
    SftpEntryNameProblem.TOO_LONG -> R.string.sftp_error_name_too_long
}

/**
 * [name] as it may be shown: every control and invisible format character becomes U+FFFD, so a
 * name cannot reorder or hide its own display (a right-to-left override, a zero-width space).
 *
 * For display only. Never send this text to the server and never put it in an input field: the
 * server keeps the raw name, and the rename field must start from it so that the check sees the
 * character.
 */
internal fun displayEntryName(name: String): String = replaceInvisibleCharacters(name, REPLACEMENT_CHARACTER)

/** U+FFFD, built from its code so that no invisible or special character is written raw in the source. */
private val REPLACEMENT_CHARACTER: Char = 0xFFFD.toChar()

/**
 * The name to suggest for the local file when downloading [name]: every control and invisible
 * format character becomes an underscore, so the file does not land on the device with a name that
 * lies about its extension. The remote name itself is not changed.
 */
internal fun downloadEntryName(name: String): String = replaceInvisibleCharacters(name, '_')
