// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.SshKeyType

/**
 * Text rules of the OpenSSH public key line of an SSH key (`<type> <base64> [comment]`), shared by the
 * backup, the sync and the "Copy the public key" action of both platforms. Pure text: nothing here reads
 * the key (see the jvmCommon `SshPublicKeyDeriver` for that) and nothing is logged.
 */
object SshPublicKeyLine {

    /** Comment of a line built for a key whose own comment is unknown or empty. */
    const val DEFAULT_COMMENT: String = "nextsh-imported"

    /**
     * The line the way it is stored: whitespace and line ends around it removed, then every control char
     * (C0, DEL, C1) inside replaced by a space, so that the value always holds on ONE line (a second key
     * pasted after a line break becomes comment text). Null only when the trimmed line is over
     * [BackupLimits.MAX_SSH_PUBLIC_KEY_BYTES] of UTF-8 (a larger string is refused on its raw length,
     * without a copy). Empty stays empty. The type and the base64 are not checked: a `sk-*` line, a
     * certificate line or a type of a future version is never emptied.
     */
    fun normalize(line: String): String? {
        val max = BackupLimits.MAX_SSH_PUBLIC_KEY_BYTES
        val trimmed = BackupValidator.trimBounded(line, max)
        if (BackupValidator.utf8SizeUpTo(trimmed, max) > max) return null
        if (trimmed.none { it.isISOControl() }) return trimmed
        return BackupValidator.replaceControlChars(trimmed).trim()
    }

    /**
     * The key without its comment, `"<type> <base64>"`, or null when [line] does not have two fields.
     * Two lines are the same key when their [blobKey] are equal (comments do not count).
     */
    fun blobKey(line: String): String? {
        val type = field(line, 0) ?: return null
        val blob = field(line, 1) ?: return null
        return "$type $blob"
    }

    /** The comment of [line] (everything after the second field), cleaned like [cleanComment]; null when none. */
    fun comment(line: String): String? {
        var i = 0
        repeat(2) {
            while (i < line.length && isSeparator(line[i])) i++
            if (i >= line.length) return null
            while (i < line.length && !isSeparator(line[i])) i++
        }
        return cleanComment(line.substring(i))
    }

    /**
     * A comment the way it goes into a built line: control chars and invisible format chars (bidi
     * controls, zero-width chars, see [isInvisibleFormat]) become a space, the text is trimmed and cut to
     * [BackupLimits.MAX_FREE_TEXT_LENGTH] chars. Null when nothing is left.
     */
    fun cleanComment(comment: String?): String? {
        if (comment == null) return null
        var end = minOf(comment.length, BackupLimits.MAX_FREE_TEXT_LENGTH)
        if (end < comment.length && end > 0 && comment[end - 1].isHighSurrogate()) end-- // never cut a surrogate pair
        val cleaned = buildString(end) {
            for (i in 0 until end) {
                val c = comment[i]
                append(if (c.isISOControl() || c.isInvisibleFormat()) ' ' else c)
            }
        }.trim()
        return cleaned.ifEmpty { null }
    }

    /** `"<sshType> <blobBase64> <comment>"`, the comment cleaned by [cleanComment] or [DEFAULT_COMMENT]. */
    fun build(sshType: String, blobBase64: String, comment: String?): String =
        "$sshType $blobBase64 ${cleanComment(comment) ?: DEFAULT_COMMENT}"

    /**
     * The [SshKeyType] of an SSH wire type name, or null for a type NextSH does not store. Every RSA key
     * is [SshKeyType.RSA_4096] whatever its size (same mapping as the key import of both platforms).
     */
    fun keyTypeOf(sshType: String): SshKeyType? = when (sshType) {
        "ssh-ed25519" -> SshKeyType.ED25519
        "ssh-rsa" -> SshKeyType.RSA_4096
        "ecdsa-sha2-nistp256" -> SshKeyType.ECDSA_256
        "ecdsa-sha2-nistp384" -> SshKeyType.ECDSA_384
        "ecdsa-sha2-nistp521" -> SshKeyType.ECDSA_521
        "sk-ssh-ed25519@openssh.com" -> SshKeyType.SK_ED25519
        "sk-ecdsa-sha2-nistp256@openssh.com" -> SshKeyType.SK_ECDSA_256
        else -> null
    }

    private fun isSeparator(c: Char): Boolean = c == ' ' || c == '\t'

    /** The field number [index] (0 based) of [line], fields separated by spaces or tabs; null when absent. */
    private fun field(line: String, index: Int): String? {
        var i = 0
        var n = 0
        while (true) {
            while (i < line.length && isSeparator(line[i])) i++
            if (i >= line.length) return null
            val start = i
            while (i < line.length && !isSeparator(line[i])) i++
            if (n == index) return line.substring(start, i)
            n++
        }
    }
}
