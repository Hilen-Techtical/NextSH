// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_confirm
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_download_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_host
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_upload_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_window_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_target_is_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_write_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_replace_failed_kept
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_changed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_is_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_not_file
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strings of the replacement confirmation and of the transfer errors that go with it:
 * each key exists in French and in English, takes the same positional token in both, and
 * follows the typographic conventions of the neighbouring keys (no long dash, plain spaces,
 * straight apostrophes in French).
 *
 * The French and the English files are read as XML, from the source tree, so that both
 * languages are checked whatever the language of the machine running the tests. The long
 * dashes are built from their code points so that this file itself holds none.
 */
class SftpReplaceStringsTest {

    private companion object {
        const val EM_DASH_CODE_POINT = 0x2014
        const val EN_DASH_CODE_POINT = 0x2013
        const val NO_BREAK_SPACE_CODE_POINT = 0x00A0
        const val NARROW_NO_BREAK_SPACE_CODE_POINT = 0x202F
        const val RIGHT_SINGLE_QUOTE_CODE_POINT = 0x2019
        const val GUILLEMET_OPEN_CODE_POINT = 0x00AB
        const val GUILLEMET_CLOSE_CODE_POINT = 0x00BB
        const val CURLY_OPEN_CODE_POINT = 0x201C
        const val CURLY_CLOSE_CODE_POINT = 0x201D

        val NO_TOKEN: List<String> = emptyList()

        /** Each new key with the positional tokens its text must carry, in both languages. */
        val EXPECTED_TOKENS: Map<String, List<String>> = mapOf(
            Res.string.sftp_dialog_replace_window_title.key to NO_TOKEN,
            // The title no longer carries the name of the file: the dialog shows it on its own line.
            Res.string.sftp_dialog_replace_title.key to NO_TOKEN,
            // The host the question is about: the label is the one token.
            Res.string.sftp_dialog_replace_host.key to listOf("%1\$s"),
            Res.string.sftp_dialog_replace_upload_warning.key to NO_TOKEN,
            Res.string.sftp_dialog_replace_download_warning.key to NO_TOKEN,
            Res.string.sftp_dialog_replace_confirm.key to NO_TOKEN,
            // The refusal of an upload names the file and the folder of the server it was meant for.
            Res.string.sftp_error_upload_target_is_folder.key to listOf("%1\$s", "%2\$s"),
            Res.string.sftp_error_upload_target_not_file.key to listOf("%1\$s", "%2\$s"),
            // The two messages of a replacement that did not happen: they name no file and take no argument.
            Res.string.sftp_error_upload_target_changed.key to NO_TOKEN,
            Res.string.sftp_error_replace_failed_kept.key to NO_TOKEN,
            Res.string.sftp_error_download_target_is_folder.key to listOf("%1\$s"),
            Res.string.sftp_error_local_exists.key to NO_TOKEN,
            Res.string.sftp_error_local_read_failed.key to NO_TOKEN,
            Res.string.sftp_error_local_write_failed.key to NO_TOKEN,
        )
    }

    private fun resourcesDir(): File {
        val candidates = listOf("src/desktopMain/composeResources", "desktop/src/desktopMain/composeResources")
        return candidates.map { File(it) }.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error(
                "composeResources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                    "tried $candidates)",
            )
    }

    private fun readStrings(relative: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(File(resourcesDir(), relative))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val french: Map<String, String> by lazy { readStrings("values/strings.xml") }
    private val english: Map<String, String> by lazy { readStrings("values-en/strings.xml") }

    private val positionalToken = Regex("""%\d+\$[sd]""")

    private fun tokens(text: String): List<String> = positionalToken.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every new key exists in French and in English and is not blank`() {
        val missing = EXPECTED_TOKENS.keys.filter { french[it].isNullOrBlank() || english[it].isNullOrBlank() }

        assertTrue(missing.isEmpty(), "keys missing or blank in values or values-en: $missing")
    }

    @Test
    fun `each new key takes the same positional tokens in both languages and none is bare`() {
        for ((key, expected) in EXPECTED_TOKENS) {
            val inFrench = french.getValue(key)
            val inEnglish = english.getValue(key)

            assertEquals(expected, tokens(inFrench), "tokens of $key (values)")
            assertEquals(expected, tokens(inEnglish), "tokens of $key (values-en)")
            // Compose resources only fill positional tokens: a bare %s would be shown as it is.
            assertFalse(positionalToken.replace(inFrench, "").contains('%'), "bare token in $key (values)")
            assertFalse(positionalToken.replace(inEnglish, "").contains('%'), "bare token in $key (values-en)")
        }
    }

    @Test
    fun `the new strings hold no em dash and no en dash`() {
        val longDashes = listOf(Char(EM_DASH_CODE_POINT), Char(EN_DASH_CODE_POINT))

        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in EXPECTED_TOKENS.keys) {
                val text = strings.getValue(key)
                assertTrue(longDashes.none { it in text }, "long dash in $language/$key: $text")
            }
        }
    }

    @Test
    fun `the new strings use plain spaces, as the neighbouring keys do`() {
        val special = listOf(Char(NO_BREAK_SPACE_CODE_POINT), Char(NARROW_NO_BREAK_SPACE_CODE_POINT))

        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in EXPECTED_TOKENS.keys) {
                val text = strings.getValue(key)
                assertTrue(special.none { it in text }, "non-breaking space in $language/$key: $text")
            }
        }
    }

    @Test
    fun `the French strings use the straight apostrophe`() {
        val typographic = Char(RIGHT_SINGLE_QUOTE_CODE_POINT)

        for (key in EXPECTED_TOKENS.keys) {
            assertFalse(typographic in french.getValue(key), "typographic apostrophe in values/$key")
        }
    }

    @Test
    fun `a quoted name is between French guillemets in French and curly quotes in English`() {
        val frenchQuoted = Regex(Char(GUILLEMET_OPEN_CODE_POINT) + " %\\d\\$" + "s " + Char(GUILLEMET_CLOSE_CODE_POINT))
        val englishQuoted = Regex(Char(CURLY_OPEN_CODE_POINT) + "%\\d\\$" + "s" + Char(CURLY_CLOSE_CODE_POINT))
        val quoting = listOf(
            Res.string.sftp_error_upload_target_is_folder.key,
            Res.string.sftp_error_upload_target_not_file.key,
            Res.string.sftp_error_download_target_is_folder.key,
        )

        for (key in quoting) {
            assertTrue(frenchQuoted.containsMatchIn(french.getValue(key)), "guillemets around the name in values/$key")
            assertTrue(englishQuoted.containsMatchIn(english.getValue(key)), "curly quotes around the name in values-en/$key")
        }
        // The folder of the server is quoted too in the two messages that name it.
        for (key in quoting.take(2)) {
            assertEquals(2, frenchQuoted.findAll(french.getValue(key)).count(), "two quoted names in values/$key")
            assertEquals(2, englishQuoted.findAll(english.getValue(key)).count(), "two quoted names in values-en/$key")
        }
    }

    @Test
    fun `the warnings tell where the existing file is`() {
        // The dialog names the file on its own line: the upload warning speaks of the server, the download one of the computer.
        assertTrue(french.getValue(Res.string.sftp_dialog_replace_upload_warning.key).contains("serveur"))
        assertTrue(french.getValue(Res.string.sftp_dialog_replace_download_warning.key).contains("ordinateur"))
        assertTrue(english.getValue(Res.string.sftp_dialog_replace_upload_warning.key).contains("server"))
        assertTrue(english.getValue(Res.string.sftp_dialog_replace_download_warning.key).contains("computer"))
    }

    @Test
    fun `the host line says on which server, with the label as its only variable part`() {
        val key = Res.string.sftp_dialog_replace_host.key

        assertEquals("Sur %1\$s", french.getValue(key))
        assertEquals("On %1\$s", english.getValue(key))
    }
}
