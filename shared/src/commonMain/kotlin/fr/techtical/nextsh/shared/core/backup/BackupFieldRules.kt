// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

/** Why a text field of a snippet or a theme is refused. The UI maps it to a localized reason. */
enum class BackupFieldProblem {
    /** Empty or blank where a value is required. */
    EMPTY,

    /** Over the maximum length (characters, or UTF-8 bytes for a command). */
    TOO_LONG,

    /** Contains a control character (C0, DEL, C1; for a command, anything but a newline and a tab). */
    CONTROL_CHARACTER,

    /** Contains an invisible format character (bidi controls, zero-width, and so on). */
    INVISIBLE_FORMAT,
}

/**
 * The single source of truth for the text rules of the fields a backup carries. The import validator
 * AND the snippet and theme editors of both platforms call these functions, so what the editor accepts
 * is exactly what a restore accepts. Each function returns the first problem found, or null.
 * Check order: [BackupFieldProblem.EMPTY], [BackupFieldProblem.TOO_LONG],
 * [BackupFieldProblem.CONTROL_CHARACTER], [BackupFieldProblem.INVISIBLE_FORMAT].
 */
object BackupFieldRules {

    /** Snippet label: up to 256 chars. Emptiness is not checked here (the editor may require a value itself). */
    fun snippetLabelProblem(label: String): BackupFieldProblem? = textProblem(label, requireNotBlank = false)

    /** Snippet category: up to 256 chars; null or empty means "no category" and is fine. */
    fun snippetCategoryProblem(category: String?): BackupFieldProblem? =
        if (category == null) null else textProblem(category, requireNotBlank = false)

    /** Theme name: not blank, up to 256 chars. */
    fun themeNameProblem(name: String): BackupFieldProblem? = textProblem(name, requireNotBlank = true)

    /**
     * Snippet command: up to 64 KiB of UTF-8, a newline and a tab allowed, any other control character
     * (carriage return and escape included) and any invisible format character refused: a disguised
     * command would be sent to a terminal.
     */
    fun snippetCommandProblem(command: String): BackupFieldProblem? {
        if (command.encodeToByteArray().size > BackupLimits.MAX_COMMAND_BYTES) return BackupFieldProblem.TOO_LONG
        if (command.any { it.isISOControl() && it != '\n' && it != '\t' }) return BackupFieldProblem.CONTROL_CHARACTER
        if (command.any { it.isInvisibleFormat() }) return BackupFieldProblem.INVISIBLE_FORMAT
        return null
    }

    private fun textProblem(text: String, requireNotBlank: Boolean): BackupFieldProblem? {
        if (requireNotBlank && text.isBlank()) return BackupFieldProblem.EMPTY
        if (text.length > BackupLimits.MAX_TEXT_LENGTH) return BackupFieldProblem.TOO_LONG
        if (text.any { it.isISOControl() }) return BackupFieldProblem.CONTROL_CHARACTER
        if (text.any { it.isInvisibleFormat() }) return BackupFieldProblem.INVISIBLE_FORMAT
        return null
    }
}
