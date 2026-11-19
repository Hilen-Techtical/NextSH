// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.SftpEntryNameProblem
import fr.techtical.nextsh.desktop.core.ssh.isHiddenInName
import fr.techtical.nextsh.desktop.core.ssh.isValidEntryName
import fr.techtical.nextsh.desktop.core.ssh.replaceInvisibleCharacters
import fr.techtical.nextsh.desktop.core.ssh.sftpEntryNameProblem
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_control
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_invisible_format
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_separator
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_reserved
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_too_long
import org.jetbrains.compose.resources.StringResource

/**
 * What the name dialogs (new folder, new file, rename) make of the text typed so far.
 *
 * @property name the text as it will be sent: [checkEntryName] trims the edge spaces, as the field always
 *   did, and changes nothing else.
 * @property canConfirm whether the confirm button may be enabled: [name] is a valid entry name.
 * @property help the sentence that says what is wrong with [name], or null when there is nothing to say
 *   (nothing typed yet, a valid name, or a name that may still become valid).
 */
internal data class EntryNameCheck(
    val name: String,
    val canConfirm: Boolean,
    val help: StringResource?,
)

/**
 * Checks [typed] against the rule the manager enforces ([isValidEntryName]), so that the dialog says why a
 * name is refused before the server has a say.
 *
 * A single "." says nothing: it is the first character of ".bashrc", and scolding the user in the middle
 * of a word would be noise. The button stays disabled all the same, since "." is not a name.
 */
internal fun checkEntryName(typed: String): EntryNameCheck {
    val name = typed.trim()
    val help = if (name == ".") null else sftpEntryNameProblem(name)?.messageRes()
    return EntryNameCheck(name = name, canConfirm = isValidEntryName(name), help = help)
}

/**
 * Sentence for a refused name, understandable without technical knowledge.
 * Exhaustive on purpose: a new [SftpEntryNameProblem] value must get its own sentence. Pure, so that it
 * can be tested without a composition.
 */
internal fun SftpEntryNameProblem.messageRes(): StringResource = when (this) {
    SftpEntryNameProblem.RESERVED -> Res.string.sftp_error_name_reserved
    SftpEntryNameProblem.SEPARATOR -> Res.string.sftp_error_name_has_separator
    SftpEntryNameProblem.CONTROL_CHARACTER -> Res.string.sftp_error_name_has_control
    SftpEntryNameProblem.INVISIBLE_FORMAT -> Res.string.sftp_error_name_has_invisible_format
    SftpEntryNameProblem.TOO_LONG -> Res.string.sftp_error_name_too_long
}

/**
 * [name] as shown on screen: every control and invisible format character becomes U+FFFD, so that it can be
 * seen and cannot reorder the display. Display only: never send this text to the server, which gets the raw
 * name. The rename field keeps the raw name too, or the check could not see the character.
 */
internal fun displayEntryName(name: String): String = replaceInvisibleCharacters(name, REPLACEMENT_CHARACTER)

/**
 * [path] when it can go to the clipboard as it is: no character that the lists show as U+FFFD (the same test as
 * [displayEntryName]), in the name or in a parent folder. Null otherwise: pasted into a terminal, a line break
 * would run a command, and the clipboard would not hold what the list shows. Never corrected, only refused.
 */
internal fun copyablePath(path: String): String? = path.takeIf { p -> p.none { it.isHiddenInName() } }

/** U+FFFD, built by code so that no raw character sits in the source. */
private val REPLACEMENT_CHARACTER: Char = 0xFFFD.toChar()
