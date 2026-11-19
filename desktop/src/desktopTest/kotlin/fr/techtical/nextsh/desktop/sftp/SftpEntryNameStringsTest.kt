// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_entry_name_invalid
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_control
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_invisible_format
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_has_separator
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_reserved
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_name_too_long
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sentences of the name dialogs and of the rename refusal: each key exists in French and in English,
 * is translated, holds no placeholder, and follows the typographic conventions of the neighbouring keys
 * (no long dash, plain spaces, guillemets in French, curly quotes in English, "vous" and never "tu").
 *
 * The French and the English files are read as XML, from the source tree, so that both languages are
 * checked whatever the language of the machine running the tests. The long dashes are built from their
 * code points so that this file itself holds none.
 */
class SftpEntryNameStringsTest {

    private companion object {
        const val EM_DASH_CODE_POINT = 0x2014
        const val EN_DASH_CODE_POINT = 0x2013
        const val NO_BREAK_SPACE_CODE_POINT = 0x00A0
        const val NARROW_NO_BREAK_SPACE_CODE_POINT = 0x202F
        const val RIGHT_SINGLE_QUOTE_CODE_POINT = 0x2019

        val KEYS: List<String> = listOf(
            Res.string.sftp_error_name_reserved.key,
            Res.string.sftp_error_name_has_separator.key,
            Res.string.sftp_error_name_has_control.key,
            Res.string.sftp_error_name_has_invisible_format.key,
            Res.string.sftp_error_name_too_long.key,
            Res.string.sftp_error_entry_name_invalid.key,
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

    /** As written in the file, backslashes included. */
    private val french: Map<String, String> by lazy { readStrings("values/strings.xml") }
    private val english: Map<String, String> by lazy { readStrings("values-en/strings.xml") }

    /** What Compose Resources makes of a raw text for what concerns a backslash: `\\` becomes `\`. */
    private fun resolved(raw: String): String = raw.replace("\\\\", "\\")

    private fun frenchText(key: String) = resolved(french.getValue(key))

    private fun englishText(key: String) = resolved(english.getValue(key))

    // ── Presence and typography ──────────────────────────────────────────────────

    @Test
    fun `every sentence exists in French and in English, translated and not empty`() {
        for (key in KEYS) {
            assertFalse(french[key].isNullOrBlank(), "$key is missing or blank in values")
            assertFalse(english[key].isNullOrBlank(), "$key is missing or blank in values-en")
            assertNotEquals(french.getValue(key), english.getValue(key), "$key is not translated")
        }
    }

    @Test
    fun `no sentence holds a placeholder`() {
        for (key in KEYS) {
            for (text in listOf(french.getValue(key), english.getValue(key))) {
                assertFalse(text.contains('%') || text.contains('$'), "$key holds a placeholder: $text")
            }
        }
    }

    @Test
    fun `no sentence holds an em dash or an en dash`() {
        val longDashes = listOf(Char(EM_DASH_CODE_POINT), Char(EN_DASH_CODE_POINT))

        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in KEYS) {
                val text = strings.getValue(key)
                assertTrue(longDashes.none { it in text }, "long dash in $language/$key: $text")
            }
        }
    }

    @Test
    fun `the sentences use plain spaces, as the neighbouring keys do`() {
        val special = listOf(Char(NO_BREAK_SPACE_CODE_POINT), Char(NARROW_NO_BREAK_SPACE_CODE_POINT))

        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in KEYS) {
                val text = strings.getValue(key)
                assertTrue(special.none { it in text }, "non-breaking space in $language/$key: $text")
            }
        }
    }

    @Test
    fun `the French sentences use the straight apostrophe`() {
        val typographic = Char(RIGHT_SINGLE_QUOTE_CODE_POINT)

        for (key in KEYS) {
            assertFalse(typographic in french.getValue(key), "typographic apostrophe in values/$key")
        }
    }

    @Test
    fun `the reserved names are between guillemets in French and curly quotes in English`() {
        val reserved = Res.string.sftp_error_name_reserved.key

        val inFrench = french.getValue(reserved)
        assertTrue(inFrench.contains("« . »"), "the single dot is not between guillemets: $inFrench")
        assertTrue(inFrench.contains("« .. »"), "the double dot is not between guillemets: $inFrench")

        val inEnglish = english.getValue(reserved)
        assertTrue(inEnglish.contains("“.”"), "the single dot is not between curly quotes: $inEnglish")
        assertTrue(inEnglish.contains("“..”"), "the double dot is not between curly quotes: $inEnglish")
    }

    @Test
    fun `the French sentences address the user as vous, never tu`() {
        // (?U): word characters are Unicode, otherwise the boundary falls inside a word with an accent.
        val informal = Regex("""(?U)\b(?:tu|ton|ta|tes|toi)\b""", RegexOption.IGNORE_CASE)

        for (key in KEYS) {
            assertFalse(informal.containsMatchIn(frenchText(key)), "$key: ${frenchText(key)}")
        }
    }

    // ── The texts ────────────────────────────────────────────────────────────────

    @Test
    fun `the French sentences are the expected ones`() {
        assertEquals(
            "Les noms « . » et « .. » sont réservés, choisissez-en un autre",
            frenchText(Res.string.sftp_error_name_reserved.key),
        )
        assertEquals("Un nom ne peut contenir ni / ni \\", frenchText(Res.string.sftp_error_name_has_separator.key))
        assertEquals(
            "Le nom contient un caractère invisible non autorisé",
            frenchText(Res.string.sftp_error_name_has_control.key),
        )
        assertEquals(
            "Le nom contient un caractère invisible qui peut fausser son affichage, retapez-le",
            frenchText(Res.string.sftp_error_name_has_invisible_format.key),
        )
        assertEquals("Le nom est trop long", frenchText(Res.string.sftp_error_name_too_long.key))
        assertEquals("Nom invalide", frenchText(Res.string.sftp_error_entry_name_invalid.key))
    }

    @Test
    fun `the English sentences are the expected ones`() {
        assertEquals(
            "The names “.” and “..” are reserved, choose another one",
            englishText(Res.string.sftp_error_name_reserved.key),
        )
        assertEquals("A name cannot contain / or \\", englishText(Res.string.sftp_error_name_has_separator.key))
        assertEquals(
            "The name contains an invisible character that is not allowed",
            englishText(Res.string.sftp_error_name_has_control.key),
        )
        assertEquals(
            "The name contains an invisible character that can change how it looks, type it again",
            englishText(Res.string.sftp_error_name_has_invisible_format.key),
        )
        assertEquals("The name is too long", englishText(Res.string.sftp_error_name_too_long.key))
        assertEquals("Invalid name", englishText(Res.string.sftp_error_entry_name_invalid.key))
    }

    @Test
    fun `the backslash is written twice in the XML so that Compose Resources keeps one`() {
        // A lone backslash would be taken as the start of an escape: the file has to hold two of them.
        val key = Res.string.sftp_error_name_has_separator.key
        assertTrue(french.getValue(key).endsWith("\\\\"), "values/$key does not end with two backslashes")
        assertTrue(english.getValue(key).endsWith("\\\\"), "values-en/$key does not end with two backslashes")
    }

    @Test
    fun `the sentence about separators reaches the screen with a single backslash`() = runTest {
        // The text really given by the resource library, not the reading of the XML above.
        val text = getString(Res.string.sftp_error_name_has_separator)

        assertTrue(text.endsWith("\\"), "no backslash at the end: $text")
        assertFalse(text.endsWith("\\\\"), "two backslashes at the end: $text")
    }

    @Test
    fun `the old rename message about the new path is gone`() {
        assertFalse("sftp_error_invalid_new_path" in french, "still in values")
        assertFalse("sftp_error_invalid_new_path" in english, "still in values-en")
    }
}
