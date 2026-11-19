// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import fr.techtical.nextsh.testutil.AccentedLiteralScanner
import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.StringResourceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the SFTP browser says comes from resources, in French (the default) and in English: the
 * messages that were literals in the code, the errors of the manager that are said at display time,
 * the sizes, the sort labels, and the counts that were "(s)" in a single string and are plurals now.
 * A string that exists in one language only would show the wrong language, a formatted one that
 * differs would crash, and a plural with a missing form would crash for that count.
 */
class SftpStringsTest {

    /** Name to the placeholders the code formats it with. */
    private val strings: Map<String, Set<String>> = mapOf(
        "sftp_success_rename" to emptySet(),
        "sftp_sort_option" to setOf("%1\$s", "%2\$s"),
        "sftp_size_bytes" to setOf("%1\$s"),
        "sftp_size_kilobytes" to setOf("%1\$s"),
        "sftp_size_megabytes" to setOf("%1\$s"),
        "sftp_size_gigabytes" to setOf("%1\$s"),
        "sftp_multi_delete_partial" to setOf("%1\$s", "%2\$s"),
        "sftp_multi_delete_none" to emptySet(),
        "sftp_transfer_download_started" to setOf("%1\$s"),
        "sftp_transfer_upload_started" to setOf("%1\$s"),
        // The errors of the manager and of the use cases, said at display time (SftpMessages.kt).
        "sftp_error_session_not_found" to emptySet(),
        "sftp_error_open_failed" to emptySet(),
        "sftp_error_invalid_path" to emptySet(),
        "sftp_error_unknown" to emptySet(),
        "sftp_error_folder_name_invalid" to emptySet(),
        "sftp_error_folder_exists" to emptySet(),
        "sftp_error_mkdir_failed" to emptySet(),
        "sftp_error_delete_failed" to emptySet(),
        "sftp_error_name_exists" to emptySet(),
        "sftp_error_rename_failed" to emptySet(),
        "sftp_error_chmod_failed" to emptySet(),
        "sftp_error_permissions_invalid" to emptySet(),
        "sftp_error_read_failed" to emptySet(),
        "sftp_error_download_failed" to emptySet(),
        "sftp_error_no_space" to emptySet(),
        "sftp_error_upload_failed" to emptySet(),
        "sftp_error_destination_unavailable" to emptySet(),
        "sftp_error_source_unavailable" to emptySet(),
        "sftp_error_source_truncated" to emptySet(),
        "sftp_error_source_truncated_in_place" to emptySet(),
        "sftp_error_source_virtual" to emptySet(),
        // Already there, now reached through a fixed text of the manager.
        "sftp_error_permission" to emptySet(),
        "sftp_error_not_found" to emptySet(),
        "sftp_error_connection" to emptySet(),
        "sftp_error_name_invalid" to emptySet(),
        "sftp_error_name_empty" to emptySet(),
        "sftp_transfer_no_session" to emptySet(),
        // No size in it: the manager measures the file it opens, not the one the listing showed.
        "sftp_preview_too_large" to emptySet(),
        // The panel of a browser whose SSH session is gone (full screen, then the pane of a split).
        "sftp_session_ended" to emptySet(),
        "sftp_session_ended_pane" to emptySet(),
        // The notification of the service while the system file picker is open.
        "sftp_picker_notification_title" to emptySet(),
        // The "+" menu (new folder, new file), the dialog of the new file and the ⋮ of the selection bar.
        "sftp_action_new_folder" to emptySet(),
        "sftp_action_new_file" to emptySet(),
        "sftp_action_more" to emptySet(),
        "sftp_dialog_new_file_title" to emptySet(),
        "sftp_dialog_new_file_hint" to emptySet(),
        "sftp_success_create_file" to emptySet(),
        "sftp_error_create_file_failed" to emptySet(),
        // The upload of a file whose name is taken: the dialog of the replacement, the two refusals and
        // the failure the transfer reports when the server refuses the open (no argument, SftpMessages.kt).
        "sftp_upload_replace_title" to emptySet(),
        // The name of the file and the folder it is to go to (the destination of the pick, which is not
        // always the folder on screen).
        "sftp_upload_replace_message" to setOf("%1\$s", "%2\$s"),
        "sftp_upload_replace_warning" to emptySet(),
        "sftp_upload_replace_action" to emptySet(),
        "sftp_upload_target_is_folder" to setOf("%1\$s"),
        "sftp_upload_target_not_replaceable" to setOf("%1\$s"),
        "sftp_transfer_target_exists" to emptySet(),
        // The replacement of a file goes through a temporary file: the target that is no longer a file,
        // and the failure that leaves the old file as it was (no argument, SftpMessages.kt).
        "sftp_transfer_target_changed" to emptySet(),
        "sftp_transfer_replace_failed_kept" to emptySet(),
        // The leftovers of an interrupted replacement: the badge, the detail line, the banner, the dialog of the deletion.
        "sftp_leftover_badge" to emptySet(),
        "sftp_leftover_detail" to setOf("%1\$s"),
        "sftp_leftover_banner_dismiss" to emptySet(),
        "sftp_leftover_delete_message_single" to setOf("%1\$s"),
        "sftp_leftover_delete_message_multi" to emptySet(),
        "sftp_leftover_delete_warning" to emptySet(),
    )

    /** Name to the placeholders every form of the plural is formatted with. */
    private val plurals: Map<String, Set<String>> = mapOf(
        "sftp_selection_count" to setOf("%1\$d"),
        "sftp_dialog_multi_delete_title" to setOf("%1\$d"),
        "sftp_transfer_more" to setOf("%1\$d"),
        "sftp_preview_line_count" to setOf("%1\$d"),
        "sftp_multi_delete_done" to setOf("%1\$d"),
        "sftp_multi_delete_failed" to setOf("%1\$d"),
        "sftp_leftover_banner" to setOf("%1\$d"),
        "sftp_leftover_delete_title" to setOf("%1\$d"),
    )

    /** The texts that are the same in both languages because nothing in them is a word. */
    private val sameInBothLanguages = setOf("sftp_multi_delete_partial", "sftp_sort_option")

    /** The plurals whose English forms are the same for one and for many: nothing in them changes with the count. */
    private val englishFormsAlike = setOf("sftp_selection_count", "sftp_multi_delete_failed")

    private val french = StringResourceFile("values/strings.xml")
    private val english = StringResourceFile("values-en/strings.xml")
    private val frenchStrings = french.strings
    private val englishStrings = english.strings
    private val frenchPlurals = french.plurals
    private val englishPlurals = english.plurals

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    /** Every text of the lot, in both languages, labelled for the failure message. */
    private fun allTexts(): List<Triple<String, String, String>> = buildList {
        strings.keys.forEach { name ->
            add(Triple("fr", name, frenchStrings.getValue(name)))
            add(Triple("en", name, englishStrings.getValue(name)))
        }
        plurals.keys.forEach { name ->
            frenchPlurals.getValue(name).forEach { (quantity, text) -> add(Triple("fr", "$name/$quantity", text)) }
            englishPlurals.getValue(name).forEach { (quantity, text) -> add(Triple("en", "$name/$quantity", text)) }
        }
    }

    /** The form CLDR picks: French reads 0 and 1 as singular, English reads 1 only. */
    private fun quantityOf(count: Int, frenchRule: Boolean) =
        if (count == 1 || (frenchRule && count == 0)) "one" else "other"

    private fun plural(name: String, count: Int, inFrench: Boolean): String {
        val forms = if (inFrench) frenchPlurals else englishPlurals
        return StringResourceFile.unescape(forms.getValue(name).getValue(quantityOf(count, inFrench)))
            .replace("%1\$d", "$count")
    }

    /** What the browser says after the deletion of several entries, built the way the view model builds it. */
    private fun summary(deleted: Int, failed: Int, inFrench: Boolean): String {
        val strs = if (inFrench) frenchStrings else englishStrings
        val done = plural("sftp_multi_delete_done", deleted, inFrench)
        return when {
            failed == 0 -> done
            deleted == 0 -> StringResourceFile.unescape(strs.getValue("sftp_multi_delete_none"))
            else -> StringResourceFile.unescape(strs.getValue("sftp_multi_delete_partial"))
                .replace("%1\$s", done)
                .replace("%2\$s", plural("sftp_multi_delete_failed", failed, inFrench))
        }
    }

    // ── Presence and shape ───────────────────────────────────────────────────────

    @Test
    fun `every string and every plural exists in French and in English`() {
        assertEquals(emptySet<String>(), strings.keys - frenchStrings.keys, "strings missing from French")
        assertEquals(emptySet<String>(), strings.keys - englishStrings.keys, "strings missing from English")
        assertEquals(emptySet<String>(), plurals.keys - frenchPlurals.keys, "plurals missing from French")
        assertEquals(emptySet<String>(), plurals.keys - englishPlurals.keys, "plurals missing from English")
    }

    @Test
    fun `every plural has its one and other forms in both languages, and nothing else`() {
        plurals.keys.forEach { name ->
            assertEquals(setOf("one", "other"), frenchPlurals.getValue(name).keys, "$name in French")
            assertEquals(setOf("one", "other"), englishPlurals.getValue(name).keys, "$name in English")
        }
    }

    @Test
    fun `every string takes the arguments it is called with, in both languages`() {
        strings.forEach { (name, expected) ->
            assertEquals(expected, placeholders(frenchStrings.getValue(name)), "placeholders of $name in French")
            assertEquals(expected, placeholders(englishStrings.getValue(name)), "placeholders of $name in English")
        }
    }

    @Test
    fun `every form of every plural takes the arguments it is called with, in both languages`() {
        plurals.forEach { (name, expected) ->
            listOf("one", "other").forEach { quantity ->
                assertEquals(expected, placeholders(frenchPlurals.getValue(name).getValue(quantity)), "$name/$quantity in French")
                assertEquals(expected, placeholders(englishPlurals.getValue(name).getValue(quantity)), "$name/$quantity in English")
            }
        }
    }

    @Test
    fun `the texts are translated and not empty`() {
        strings.keys.forEach { name ->
            val fr = frenchStrings.getValue(name)
            val en = englishStrings.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            if (name !in sameInBothLanguages) assertNotEquals(fr, en, "$name is not translated")
        }
        plurals.keys.forEach { name ->
            listOf("one", "other").forEach { quantity ->
                val fr = frenchPlurals.getValue(name).getValue(quantity)
                val en = englishPlurals.getValue(name).getValue(quantity)
                assertFalse(fr.isBlank() || en.isBlank(), "$name/$quantity must not be empty")
                assertNotEquals(fr, en, "$name/$quantity is not translated")
            }
        }
    }

    @Test
    fun `the texts that are the same in both languages hold no word`() {
        assertEquals("%1\$s, %2\$s", frenchStrings.getValue("sftp_multi_delete_partial"))
        assertEquals("%1\$s, %2\$s", englishStrings.getValue("sftp_multi_delete_partial"))
        assertEquals("%1\$s (%2\$s)", frenchStrings.getValue("sftp_sort_option"))
        assertEquals("%1\$s (%2\$s)", englishStrings.getValue("sftp_sort_option"))
    }

    // ── Writing rules ────────────────────────────────────────────────────────────

    @Test
    fun `no text holds an em dash or an en dash`() {
        allTexts().forEach { (language, name, text) -> assertFalse(holdsDash(text), "$name ($language) holds a dash") }
    }

    @Test
    fun `the apostrophes are escaped for aapt2`() {
        allTexts().forEach { (language, name, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name ($language) has an unescaped apostrophe: $text")
        }
    }

    @Test
    fun `the French texts address the user as vous, never tu`() {
        allTexts().filter { it.first == "fr" }.forEach { (_, name, text) ->
            val tu = FrenchTuForm.find(text)
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): $text")
        }
    }

    @Test
    fun `the French texts say vault and never coffre`() {
        allTexts().filter { it.first == "fr" }.forEach { (_, name, text) ->
            assertFalse(text.contains("coffre", ignoreCase = true), "$name: $text")
        }
    }

    @Test
    fun `the English texts are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis", "cancelled")
        allTexts().filter { it.first == "en" }.forEach { (_, name, text) ->
            british.forEach { word -> assertFalse(text.contains(word, ignoreCase = true), "$name uses the British \"$word\"") }
        }
    }

    // ── The counts are plurals now ───────────────────────────────────────────────

    @Test
    fun `no SFTP text writes a plural as a pseudo-plural in parentheses`() {
        listOf(frenchStrings, englishStrings).forEach { texts ->
            texts.filterKeys { it.startsWith("sftp_") }.forEach { (name, text) ->
                assertFalse(text.contains("(s)"), "$name: $text")
            }
        }
        (frenchPlurals.values + englishPlurals.values).flatMap { it.values }.forEach { text ->
            assertFalse(text.contains("(s)"), text)
        }
    }

    @Test
    fun `the four counts that were one string with a pseudo-plural are plurals, and no string of that name is left`() {
        listOf("sftp_selection_count", "sftp_dialog_multi_delete_title", "sftp_transfer_more", "sftp_preview_line_count").forEach { name ->
            assertFalse(name in frenchStrings, "$name is still a string in French")
            assertFalse(name in englishStrings, "$name is still a string in English")
            assertTrue(name in frenchPlurals && name in englishPlurals, "$name is not a plural")
        }
    }

    @Test
    fun `the forms of a plural agree with the count in French and in English`() {
        // French reads 0 and 1 as singular: a count of zero is never "0 lignes".
        assertEquals("0 ligne", plural("sftp_preview_line_count", 0, inFrench = true))
        assertEquals("1 ligne", plural("sftp_preview_line_count", 1, inFrench = true))
        assertEquals("2 lignes", plural("sftp_preview_line_count", 2, inFrench = true))
        assertEquals("1 line", plural("sftp_preview_line_count", 1, inFrench = false))
        assertEquals("0 lines", plural("sftp_preview_line_count", 0, inFrench = false))
        assertEquals("1 sélectionné", plural("sftp_selection_count", 1, inFrench = true))
        assertEquals("3 sélectionnés", plural("sftp_selection_count", 3, inFrench = true))
        assertEquals("3 selected", plural("sftp_selection_count", 3, inFrench = false))
        assertEquals("Supprimer 1 élément ?", plural("sftp_dialog_multi_delete_title", 1, inFrench = true))
        assertEquals("Supprimer 4 éléments ?", plural("sftp_dialog_multi_delete_title", 4, inFrench = true))
        assertEquals("Delete 1 item?", plural("sftp_dialog_multi_delete_title", 1, inFrench = false))
        assertEquals("Delete 4 items?", plural("sftp_dialog_multi_delete_title", 4, inFrench = false))
        assertEquals("+1 autre transfert", plural("sftp_transfer_more", 1, inFrench = true))
        assertEquals("+2 autres transferts", plural("sftp_transfer_more", 2, inFrench = true))
        assertEquals("+1 more transfer", plural("sftp_transfer_more", 1, inFrench = false))
        assertEquals("+2 more transfers", plural("sftp_transfer_more", 2, inFrench = false))
        assertEquals("1 fichier temporaire laissé par un envoi interrompu", plural("sftp_leftover_banner", 1, inFrench = true))
        assertEquals("3 fichiers temporaires laissés par des envois interrompus", plural("sftp_leftover_banner", 3, inFrench = true))
        assertEquals("1 temporary file left by an interrupted upload", plural("sftp_leftover_banner", 1, inFrench = false))
        assertEquals("3 temporary files left by interrupted uploads", plural("sftp_leftover_banner", 3, inFrench = false))
        assertEquals("Supprimer 1 fichier temporaire ?", plural("sftp_leftover_delete_title", 1, inFrench = true))
        assertEquals("Supprimer 2 fichiers temporaires ?", plural("sftp_leftover_delete_title", 2, inFrench = true))
        assertEquals("Delete 1 temporary file?", plural("sftp_leftover_delete_title", 1, inFrench = false))
        assertEquals("Delete 2 temporary files?", plural("sftp_leftover_delete_title", 2, inFrench = false))
    }

    @Test
    fun `the leftovers of an interrupted replacement are named in both languages, and the deletion says the original is not touched`() {
        fun fr(name: String) = StringResourceFile.unescape(frenchStrings.getValue(name))
        fun en(name: String) = StringResourceFile.unescape(englishStrings.getValue(name))
        assertEquals("Envoi interrompu", fr("sftp_leftover_badge"))
        assertEquals("Copie incomplète de « %1\$s »", fr("sftp_leftover_detail"))
        assertEquals("Ignorer", fr("sftp_leftover_banner_dismiss"))
        assertEquals(
            "Ce fichier est une copie incomplète de « %1\$s », laissée par un envoi interrompu. Le supprimer ne touche pas au fichier « %1\$s ».",
            fr("sftp_leftover_delete_message_single"),
        )
        assertEquals(
            "Ces fichiers sont des copies incomplètes, laissées par des envois interrompus. Les supprimer ne touche pas aux fichiers d'origine.",
            fr("sftp_leftover_delete_message_multi"),
        )
        assertEquals(
            "Si un envoi est encore en cours depuis un autre appareil, il échouera, sans modifier le fichier d'origine.",
            fr("sftp_leftover_delete_warning"),
        )
        assertEquals("Interrupted upload", en("sftp_leftover_badge"))
        assertEquals("Incomplete copy of \"%1\$s\"", en("sftp_leftover_detail"))
        assertEquals("Dismiss", en("sftp_leftover_banner_dismiss"))
        assertEquals(
            "This file is an incomplete copy of \"%1\$s\", left by an interrupted upload. Deleting it does not touch the file \"%1\$s\".",
            en("sftp_leftover_delete_message_single"),
        )
        assertEquals(
            "These files are incomplete copies, left by interrupted uploads. Deleting them does not touch the original files.",
            en("sftp_leftover_delete_message_multi"),
        )
        assertEquals(
            "If an upload is still running from another device, it will fail, without changing the original file.",
            en("sftp_leftover_delete_warning"),
        )
    }

    @Test
    fun `one and other differ where a word agrees with the count`() {
        plurals.keys.forEach { name ->
            assertNotEquals(
                frenchPlurals.getValue(name).getValue("one"), frenchPlurals.getValue(name).getValue("other"),
                "$name in French",
            )
            if (name !in englishFormsAlike) {
                assertNotEquals(
                    englishPlurals.getValue(name).getValue("one"), englishPlurals.getValue(name).getValue("other"),
                    "$name in English",
                )
            } else {
                assertEquals(
                    englishPlurals.getValue(name).getValue("one"), englishPlurals.getValue(name).getValue("other"),
                    "$name in English",
                )
            }
        }
    }

    // ── The deletion of several entries ──────────────────────────────────────────

    @Test
    fun `the deletion of several entries says the ones that went, then the ones that did not`() {
        assertEquals("2 éléments supprimés", summary(2, 0, inFrench = true))
        assertEquals("1 élément supprimé", summary(1, 0, inFrench = true))
        assertEquals("2 éléments supprimés, 1 n'a pas pu être supprimé", summary(2, 1, inFrench = true))
        assertEquals("1 élément supprimé, 3 n'ont pas pu être supprimés", summary(1, 3, inFrench = true))
        assertEquals("2 items deleted", summary(2, 0, inFrench = false))
        assertEquals("1 item deleted", summary(1, 0, inFrench = false))
        assertEquals("2 items deleted, 1 could not be deleted", summary(2, 1, inFrench = false))
        assertEquals("1 item deleted, 3 could not be deleted", summary(1, 3, inFrench = false))
    }

    @Test
    fun `when none could be deleted the sentence does not start with zero deleted`() {
        assertEquals("Aucun élément n'a pu être supprimé", summary(0, 2, inFrench = true))
        assertEquals("No item could be deleted", summary(0, 2, inFrench = false))
        assertEquals("Aucun élément n'a pu être supprimé", summary(0, 1, inFrench = true))
    }

    // ── Other texts of the lot ───────────────────────────────────────────────────

    @Test
    fun `a folder that is renamed is an item, not a file`() {
        assertEquals("Élément renommé", frenchStrings.getValue("sftp_success_rename"))
        assertEquals("Item renamed", englishStrings.getValue("sftp_success_rename"))
    }

    @Test
    fun `the start of a transfer says what starts, with the name of the file`() {
        assertEquals("Téléchargement de %1\$s démarré", frenchStrings.getValue("sftp_transfer_download_started"))
        assertEquals("Envoi de %1\$s démarré", frenchStrings.getValue("sftp_transfer_upload_started"))
        assertEquals("Download of %1\$s started", englishStrings.getValue("sftp_transfer_download_started"))
        assertEquals("Upload of %1\$s started", englishStrings.getValue("sftp_transfer_upload_started"))
    }

    @Test
    fun `the units of a size are French in French and English in English`() {
        assertEquals("%1\$s o", frenchStrings.getValue("sftp_size_bytes"))
        assertEquals("%1\$s Ko", frenchStrings.getValue("sftp_size_kilobytes"))
        assertEquals("%1\$s Mo", frenchStrings.getValue("sftp_size_megabytes"))
        assertEquals("%1\$s Go", frenchStrings.getValue("sftp_size_gigabytes"))
        assertEquals("%1\$s B", englishStrings.getValue("sftp_size_bytes"))
        assertEquals("%1\$s KB", englishStrings.getValue("sftp_size_kilobytes"))
        assertEquals("%1\$s MB", englishStrings.getValue("sftp_size_megabytes"))
        assertEquals("%1\$s GB", englishStrings.getValue("sftp_size_gigabytes"))
    }

    @Test
    fun `the session that is gone says what to do, and no identifier`() {
        val fr = StringResourceFile.unescape(frenchStrings.getValue("sftp_error_session_not_found"))
        val en = StringResourceFile.unescape(englishStrings.getValue("sftp_error_session_not_found"))
        assertEquals("La connexion SSH est fermée. Reconnectez-vous à l'hôte.", fr)
        assertEquals("The SSH connection is closed. Reconnect to the host.", en)
    }

    @Test
    fun `a browser whose session is gone says what to do, with the way back, and no identifier`() {
        val fr = StringResourceFile.unescape(frenchStrings.getValue("sftp_session_ended"))
        val en = StringResourceFile.unescape(englishStrings.getValue("sftp_session_ended"))
        assertEquals("La connexion à l'hôte a été coupée. Revenez en arrière puis rouvrez le SFTP.", fr)
        assertEquals("The connection to the host was lost. Go back, then open SFTP again.", en)
        // The browser opens from the host list or from the terminal, and the button goes back to
        // where it came from: the text names neither of them.
        assertFalse(fr.contains("liste", ignoreCase = true), fr)
        assertFalse(fr.contains("terminal", ignoreCase = true), fr)
        assertFalse(en.contains("list", ignoreCase = true), en)
        assertFalse(en.contains("terminal", ignoreCase = true), en)
        // The button is the common "Back", and the string of the old button is gone.
        assertEquals("Retour", frenchStrings.getValue("action_back"))
        assertEquals("Back", englishStrings.getValue("action_back"))
        assertFalse("sftp_back_to_list" in frenchStrings, "sftp_back_to_list is still in French")
        assertFalse("sftp_back_to_list" in englishStrings, "sftp_back_to_list is still in English")
    }

    @Test
    fun `the pane of a split says to reconnect the terminal, there is no list behind it`() {
        val fr = StringResourceFile.unescape(frenchStrings.getValue("sftp_session_ended_pane"))
        val en = StringResourceFile.unescape(englishStrings.getValue("sftp_session_ended_pane"))
        assertEquals("La connexion à l'hôte a été coupée. Reconnectez le terminal pour retrouver vos fichiers.", fr)
        assertEquals("The connection to the host was cut. Reconnect the terminal to get your files back.", en)
        assertFalse(fr.contains("liste"), fr)
        assertFalse(en.contains("list"), en)
    }

    @Test
    fun `the notification of the file picker names what the user is doing`() {
        assertEquals("Choix du fichier en cours", frenchStrings.getValue("sftp_picker_notification_title"))
        assertEquals("Choosing a file", englishStrings.getValue("sftp_picker_notification_title"))
    }

    @Test
    fun `the creation of a file says the same words as the creation of a folder, with file in place of folder`() {
        assertEquals("Nouveau fichier", frenchStrings.getValue("sftp_action_new_file"))
        assertEquals("Nouveau fichier", frenchStrings.getValue("sftp_dialog_new_file_title"))
        assertEquals("Nom du fichier", frenchStrings.getValue("sftp_dialog_new_file_hint"))
        assertEquals("Fichier créé", frenchStrings.getValue("sftp_success_create_file"))
        assertEquals("Impossible de créer le fichier", frenchStrings.getValue("sftp_error_create_file_failed"))
        assertEquals("New file", englishStrings.getValue("sftp_action_new_file"))
        assertEquals("New file", englishStrings.getValue("sftp_dialog_new_file_title"))
        assertEquals("File name", englishStrings.getValue("sftp_dialog_new_file_hint"))
        assertEquals("File created", englishStrings.getValue("sftp_success_create_file"))
        assertEquals("Cannot create the file", englishStrings.getValue("sftp_error_create_file_failed"))
        // The menu entry of the folder keeps the words of the dialog of the folder.
        assertEquals(frenchStrings.getValue("sftp_dialog_mkdir_title"), frenchStrings.getValue("sftp_action_new_folder"))
        assertEquals(englishStrings.getValue("sftp_dialog_mkdir_title"), englishStrings.getValue("sftp_action_new_folder"))
    }

    @Test
    fun `the label of the more actions button of the selection bar is said in both languages`() {
        assertEquals("Plus d'actions", StringResourceFile.unescape(frenchStrings.getValue("sftp_action_more")))
        assertEquals("More actions", englishStrings.getValue("sftp_action_more"))
    }

    @Test
    fun `the label of the select all button of the selection bar says what it does, not just all`() {
        // The button is an icon alone now: its label is what a screen reader says, and "Tout" says nothing.
        assertEquals("Tout sélectionner", frenchStrings.getValue("sftp_select_all"))
        assertEquals("Select all", englishStrings.getValue("sftp_select_all"))
    }

    @Test
    fun `a file too big for the preview says so without a size, and a download that cannot be saved says where`() {
        assertEquals("Fichier trop volumineux pour l'aperçu", StringResourceFile.unescape(frenchStrings.getValue("sftp_preview_too_large")))
        assertEquals("File too large for preview", StringResourceFile.unescape(englishStrings.getValue("sftp_preview_too_large")))
        assertEquals(
            "Impossible d'enregistrer le fichier sur le téléphone",
            StringResourceFile.unescape(frenchStrings.getValue("sftp_error_destination_unavailable")),
        )
        assertEquals(
            "Cannot save the file on this device",
            StringResourceFile.unescape(englishStrings.getValue("sftp_error_destination_unavailable")),
        )
    }

    @Test
    fun `a document that only exists online says to export it first`() {
        // The upload does not export anything: the sentence tells the user to do it from the app of the document.
        assertEquals(
            "Ce document n'existe qu'en ligne et ne peut pas être envoyé tel quel. Exportez-le d'abord depuis son application (en PDF, par exemple).",
            StringResourceFile.unescape(frenchStrings.getValue("sftp_error_source_virtual")),
        )
        assertEquals(
            "This document only exists online and cannot be sent as is. Export it from its app first (as a PDF, for example).",
            StringResourceFile.unescape(englishStrings.getValue("sftp_error_source_virtual")),
        )
    }

    @Test
    fun `the replacement of a file that has the name asks first, names the file, and says on its own line that the content is lost`() {
        fun fr(name: String) = StringResourceFile.unescape(frenchStrings.getValue(name))
        fun en(name: String) = StringResourceFile.unescape(englishStrings.getValue(name))
        assertEquals("Remplacer le fichier ?", fr("sftp_upload_replace_title"))
        // It names the folder: "ce dossier" would be wrong if the user went elsewhere during the check.
        assertEquals("Un fichier nommé « %1\$s » existe déjà dans « %2\$s ».", fr("sftp_upload_replace_message"))
        // The irreversible consequence is a line of its own, red, like the one of the dialog of a deletion.
        assertEquals("Son contenu actuel sera définitivement remplacé.", fr("sftp_upload_replace_warning"))
        assertEquals("Remplacer", fr("sftp_upload_replace_action"))
        assertEquals("Replace the file?", en("sftp_upload_replace_title"))
        assertEquals("A file named \"%1\$s\" already exists in \"%2\$s\".", en("sftp_upload_replace_message"))
        assertEquals("Its current content will be permanently replaced.", en("sftp_upload_replace_warning"))
        assertEquals("Replace", en("sftp_upload_replace_action"))
    }

    @Test
    fun `the refusals of an upload say what holds the name and that the file was not sent`() {
        fun fr(name: String) = StringResourceFile.unescape(frenchStrings.getValue(name))
        fun en(name: String) = StringResourceFile.unescape(englishStrings.getValue(name))
        assertEquals("Un dossier porte déjà le nom « %1\$s » ici. Le fichier n'a pas été envoyé.", fr("sftp_upload_target_is_folder"))
        assertEquals(
            "Un élément nommé « %1\$s » existe déjà ici et ne peut pas être remplacé. Le fichier n'a pas été envoyé.",
            fr("sftp_upload_target_not_replaceable"),
        )
        assertEquals("A folder named \"%1\$s\" is already here. The file was not sent.", en("sftp_upload_target_is_folder"))
        assertEquals(
            "An item named \"%1\$s\" is already here and cannot be replaced. The file was not sent.",
            en("sftp_upload_target_not_replaceable"),
        )
    }

    @Test
    fun `an upload refused by the server for a name that is taken says nothing was replaced, and promises no way to choose`() {
        // No "send it again to replace it": sending it again meets the same refusal and the same message,
        // the dialog of the replacement only comes up when the check could see the file.
        assertEquals(
            "Un élément portant ce nom existe déjà sur le serveur. Rien n'a été remplacé.",
            StringResourceFile.unescape(frenchStrings.getValue("sftp_transfer_target_exists")),
        )
        assertEquals(
            "An item with this name already exists on the server. Nothing was replaced.",
            StringResourceFile.unescape(englishStrings.getValue("sftp_transfer_target_exists")),
        )
    }

    @Test
    fun `a replacement that fails says the old file was not changed, and one that meets another item says nothing was replaced`() {
        fun fr(name: String) = StringResourceFile.unescape(frenchStrings.getValue(name))
        fun en(name: String) = StringResourceFile.unescape(englishStrings.getValue(name))
        assertEquals("L'envoi a échoué. Le fichier existant n'a pas été modifié.", fr("sftp_transfer_replace_failed_kept"))
        assertEquals("The upload failed. The existing file was not changed.", en("sftp_transfer_replace_failed_kept"))
        assertEquals(
            "L'élément à remplacer a changé sur le serveur. Rien n'a été remplacé.",
            fr("sftp_transfer_target_changed"),
        )
        assertEquals("The item to replace changed on the server. Nothing was replaced.", en("sftp_transfer_target_changed"))
    }

    @Test
    fun `no error sentence repeats a message of an exception or a placeholder for one`() {
        strings.keys.filter { it.startsWith("sftp_error_") }.forEach { name ->
            assertEquals(emptySet<String>(), placeholders(frenchStrings.getValue(name)), "$name (fr)")
            assertEquals(emptySet<String>(), placeholders(englishStrings.getValue(name)), "$name (en)")
        }
    }

    // ── No French literal is left in the code of the lot ─────────────────────────

    @Test
    fun `no literal with an accent is left in the SFTP screens`() {
        assertEquals(emptyList<String>(), AccentedLiteralScanner.scan("ui/sftp"))
    }

    @Test
    fun `no literal with an accent is left in the manager or in the use cases of the browser`() {
        listOf(
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
        ).forEach { file ->
            // SftpMessages.kt is not scanned: the accents of the fixed texts are the point of it.
            assertEquals(emptyList<String>(), AccentedLiteralScanner.scanFile(file), file)
        }
    }
}
