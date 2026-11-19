// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.StringResourceFile
import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The texts the SFTP manager and the SFTP use cases return are fixed French literals, said in the
 * language of the user at display time ([sftpMessageRes]), the way the tunnels do it. None of them
 * carries a path, a size, a session identifier or the message of an exception.
 */
class SftpMessagesTest {

    /** A fixed text, the resource it maps to, and the name of that resource. */
    private data class Entry(val message: String, val res: Int, val name: String)

    private val entries = listOf(
        Entry(SFTP_SESSION_NOT_FOUND_MESSAGE, R.string.sftp_error_session_not_found, "sftp_error_session_not_found"),
        Entry(SFTP_OPEN_FAILED_MESSAGE, R.string.sftp_error_open_failed, "sftp_error_open_failed"),
        Entry(SFTP_NO_SESSION_MESSAGE, R.string.sftp_transfer_no_session, "sftp_transfer_no_session"),
        Entry(SFTP_INVALID_PATH_MESSAGE, R.string.sftp_error_invalid_path, "sftp_error_invalid_path"),
        Entry(SFTP_PERMISSION_DENIED_MESSAGE, R.string.sftp_error_permission, "sftp_error_permission"),
        Entry(SFTP_NOT_FOUND_MESSAGE, R.string.sftp_error_not_found, "sftp_error_not_found"),
        Entry(SFTP_UNKNOWN_MESSAGE, R.string.sftp_error_unknown, "sftp_error_unknown"),
        Entry(SFTP_CONNECTION_LOST_MESSAGE, R.string.sftp_error_connection, "sftp_error_connection"),
        Entry(SFTP_FOLDER_NAME_INVALID_MESSAGE, R.string.sftp_error_folder_name_invalid, "sftp_error_folder_name_invalid"),
        Entry(SFTP_FOLDER_EXISTS_MESSAGE, R.string.sftp_error_folder_exists, "sftp_error_folder_exists"),
        Entry(SFTP_MKDIR_FAILED_MESSAGE, R.string.sftp_error_mkdir_failed, "sftp_error_mkdir_failed"),
        Entry(SFTP_CREATE_FILE_FAILED_MESSAGE, R.string.sftp_error_create_file_failed, "sftp_error_create_file_failed"),
        Entry(SFTP_DELETE_FAILED_MESSAGE, R.string.sftp_error_delete_failed, "sftp_error_delete_failed"),
        Entry(SFTP_NAME_INVALID_MESSAGE, R.string.sftp_error_name_invalid, "sftp_error_name_invalid"),
        Entry(SFTP_ENTRY_NAME_INVALID_MESSAGE, R.string.sftp_error_entry_name_invalid, "sftp_error_entry_name_invalid"),
        Entry(SFTP_NAME_EXISTS_MESSAGE, R.string.sftp_error_name_exists, "sftp_error_name_exists"),
        Entry(SFTP_RENAME_FAILED_MESSAGE, R.string.sftp_error_rename_failed, "sftp_error_rename_failed"),
        Entry(SFTP_CHMOD_FAILED_MESSAGE, R.string.sftp_error_chmod_failed, "sftp_error_chmod_failed"),
        Entry(SFTP_PERMISSIONS_INVALID_MESSAGE, R.string.sftp_error_permissions_invalid, "sftp_error_permissions_invalid"),
        Entry(SFTP_NAME_EMPTY_MESSAGE, R.string.sftp_error_name_empty, "sftp_error_name_empty"),
        Entry(SFTP_PREVIEW_TOO_LARGE_MESSAGE, R.string.sftp_preview_too_large, "sftp_preview_too_large"),
        Entry(SFTP_READ_FAILED_MESSAGE, R.string.sftp_error_read_failed, "sftp_error_read_failed"),
        Entry(SFTP_DOWNLOAD_FAILED_MESSAGE, R.string.sftp_error_download_failed, "sftp_error_download_failed"),
        Entry(SFTP_NO_SPACE_MESSAGE, R.string.sftp_error_no_space, "sftp_error_no_space"),
        Entry(SFTP_UPLOAD_FAILED_MESSAGE, R.string.sftp_error_upload_failed, "sftp_error_upload_failed"),
        Entry(SFTP_DESTINATION_UNAVAILABLE_MESSAGE, R.string.sftp_error_destination_unavailable, "sftp_error_destination_unavailable"),
        Entry(SFTP_SOURCE_UNAVAILABLE_MESSAGE, R.string.sftp_error_source_unavailable, "sftp_error_source_unavailable"),
        Entry(SFTP_SOURCE_TRUNCATED_MESSAGE, R.string.sftp_error_source_truncated, "sftp_error_source_truncated"),
        Entry(SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE, R.string.sftp_error_source_truncated_in_place, "sftp_error_source_truncated_in_place"),
        Entry(SFTP_SOURCE_VIRTUAL_MESSAGE, R.string.sftp_error_source_virtual, "sftp_error_source_virtual"),
        Entry(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, R.string.sftp_transfer_target_exists, "sftp_transfer_target_exists"),
        Entry(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, R.string.sftp_transfer_target_changed, "sftp_transfer_target_changed"),
        Entry(SFTP_REPLACE_FAILED_KEPT_MESSAGE, R.string.sftp_transfer_replace_failed_kept, "sftp_transfer_replace_failed_kept"),
    )

    private val french = StringResourceFile("values/strings.xml")
    private val english = StringResourceFile("values-en/strings.xml")

    @Test
    fun `every fixed text maps to its own string resource`() {
        entries.forEach { entry ->
            assertEquals(entry.res, sftpMessageRes(entry.message), entry.message)
        }
    }

    @Test
    fun `the name of each resource is the one of the compiled R class`() {
        entries.forEach { entry ->
            assertEquals(entry.res, R.string::class.java.getField(entry.name).getInt(null), entry.name)
        }
    }

    @Test
    fun `every resource exists in French and in English`() {
        entries.forEach { entry ->
            assertTrue(entry.name in french.strings, "${entry.name} missing from French")
            assertTrue(entry.name in english.strings, "${entry.name} missing from English")
            assertFalse(french.strings.getValue(entry.name).isBlank(), "${entry.name} is empty in French")
            assertFalse(english.strings.getValue(entry.name).isBlank(), "${entry.name} is empty in English")
        }
    }

    @Test
    fun `no two fixed texts are equal and no two share a resource`() {
        val messages = entries.map { it.message }
        assertEquals(messages.size, messages.toSet().size, "a text is declared twice: the second would be unreachable")
        val resources = entries.map { it.res }
        assertEquals(resources.size, resources.toSet().size, "a resource is shared by two texts")
    }

    /**
     * The String constants of SftpMessages.kt, read from the compiled class: a `const val` is a public
     * static final field of `SftpMessagesKt`, whatever the way it is declared in the source.
     */
    private fun declaredMessages(): Map<String, String> =
        Class.forName("fr.techtical.nextsh.core.ssh.SftpMessagesKt").declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { field ->
                field.isAccessible = true
                field.name to (field.get(null) as String)
            }

    @Test
    fun `no fixed text is missing from the mapping, whatever its name`() {
        val declared = declaredMessages()
        assertTrue(declared.size >= 20, "the reflection finds ${declared.size} constants: it reads the wrong class")
        declared.forEach { (name, value) ->
            assertNotNull(sftpMessageRes(value), "$name has no resource in sftpMessageRes")
        }
    }

    @Test
    fun `no fixed text is missing from the table above`() {
        assertEquals(declaredMessages().values.toSet(), entries.map { it.message }.toSet())
    }

    @Test
    fun `an unknown message is not mapped`() {
        // A text with the data the old literals carried, an exception message, a resolved text, nothing.
        assertNull(sftpMessageRes("Session SSH introuvable : 3f2a-uuid"))
        assertNull(sftpMessageRes("Client SFTP non initialisé pour 3f2a-uuid"))
        assertNull(sftpMessageRes("Chemin invalide : /home/user/secret"))
        assertNull(sftpMessageRes("Fichier trop volumineux pour l'aperçu (700000 octets)"))
        assertNull(sftpMessageRes("Valeur de permissions invalide (attendu 0 à 511)"))
        assertNull(sftpMessageRes("channel is closed"))
        assertNull(sftpMessageRes("Permission denied"))
        assertNull(sftpMessageRes(""))
    }

    @Test
    fun `the fixed texts carry no dash, no digit and no placeholder`() {
        entries.forEach { entry ->
            assertFalse(entry.message.any { it.code == 0x2014 || it.code == 0x2013 }, "${entry.message} holds a dash")
            assertFalse(entry.message.any { it.isDigit() }, "${entry.message} holds a number")
            assertFalse(entry.message.contains('$') || entry.message.contains('%'), "${entry.message} holds a placeholder")
        }
    }

    @Test
    fun `no sentence takes an argument, the one of a file too big for the preview included`() {
        // The manager measures the file it opens, which is not always the size the listing showed
        // (a symbolic link, a file that grows): the sentence carries no size, so no screen formats one.
        val placeholders = Regex("""%\d\$[sd]""")
        entries.forEach { entry ->
            assertEquals(emptySet<String>(), placeholders.findAll(french.strings.getValue(entry.name)).map { it.value }.toSet(), "${entry.name} (fr)")
            assertEquals(emptySet<String>(), placeholders.findAll(english.strings.getValue(entry.name)).map { it.value }.toSet(), "${entry.name} (en)")
        }
    }

    @Test
    fun `the message of the session that is gone has no identifier, the old one had`() {
        // The literal used to be "Session SSH introuvable : <session id>": the identifier is not for the user.
        assertEquals("Session SSH introuvable", SFTP_SESSION_NOT_FOUND_MESSAGE)
        assertEquals("Client SFTP non initialisé", SFTP_NO_SESSION_MESSAGE)
        assertEquals("Chemin invalide", SFTP_INVALID_PATH_MESSAGE)
    }

    // ── The sources: nothing but a fixed text is put in a result ─────────────────

    /** The source of a file under `nextsh/`. A miss fails, wherever the tests run (see [SourceGuardSupport.locate]). */
    private fun sourceOf(pathUnderNextSh: String): String =
        SourceGuardSupport.locate("src/main/kotlin/fr/techtical/nextsh/$pathUnderNextSh").readText()

    /** The files that make an `SshResult.Error` for the SFTP browser. */
    private val sources = listOf(
        "core/ssh/SftpManager.kt",
        "domain/usecase/ListDirectoryUseCase.kt",
        "domain/usecase/PreviewFileUseCase.kt",
        "domain/usecase/CreateDirectoryUseCase.kt",
        "domain/usecase/CreateFileUseCase.kt",
        "domain/usecase/RenameFileUseCase.kt",
        "domain/usecase/ChangePermissionsUseCase.kt",
        "domain/usecase/DownloadFileUseCase.kt",
        "domain/usecase/UploadFileUseCase.kt",
        "domain/usecase/DeleteFileUseCase.kt",
    )

    @Test
    fun `no result of the manager or of a use case holds a literal, only a constant`() {
        sources.forEach { file ->
            val code = blankComments(sourceOf(file))
            assertTrue(code.length > 500, "$file: the guard reads nothing")
            val offenders = Regex("""SshResult\.Error\(([^)]*)\)""").findAll(code)
                .map { it.groupValues[1] }.filter { it.contains('"') }.toList()
            assertTrue(offenders.isEmpty(), "$file puts a literal in an error: $offenders")
        }
    }

    @Test
    fun `no message reaches a result through a variable either, no branch and no pair holds a literal`() {
        // The manager builds its messages as `val msg = when { ... -> CONSTANT }` and passes `msg` to the
        // result: the check above, which reads the argument of the error, cannot see a literal there.
        // A literal after a branch arrow (`-> "..."`) or after `to` (`code to "..."`) is what such a
        // variable is made of, in these files that only carry constants.
        sources.forEach { file ->
            val code = blankComments(sourceOf(file))
            val arrows = Regex("->\\s*\"").findAll(code).map { it.value }.toList()
            assertTrue(arrows.isEmpty(), "$file puts a literal after a branch arrow: $arrows")
            val pairs = Regex("\\bto\\s+\"").findAll(code).map { it.value }.toList()
            assertTrue(pairs.isEmpty(), "$file puts a literal in a pair: $pairs")
        }
    }

    @Test
    fun `the manager never reads the message of an exception, but to tell one failure from another`() {
        val code = blankComments(sourceOf("core/ssh/SftpManager.kt"))
        // e.message?.contains("permission") sorts a failure; any other read of it would reach the screen.
        val offenders = Regex("""\b(?:e|ex|t|cause)\s*\??\.\s*(?:message|localizedMessage)\b(?!\s*\?\.\s*contains\()""")
            .findAll(code).map { it.value }.toList()
        assertTrue(offenders.isEmpty(), "SftpManager.kt reads the message of an exception: $offenders")
        assertFalse(code.contains("Erreur inconnue"), "the French fallback of a raw message is back")
    }

    @Test
    fun `the manager does not log the exception that made the SFTP channel fail to open`() {
        val code = blankComments(sourceOf("core/ssh/SftpManager.kt"))
        assertNotNull(Regex("""Timber\.e\("Cannot open the SFTP client[^"]*\(\$\{errorClass\(e\)\}\)"\)""").find(code))
    }
}
