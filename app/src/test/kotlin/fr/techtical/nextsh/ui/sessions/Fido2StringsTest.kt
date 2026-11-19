// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.auth.HwKeyStatus
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the user reads while a security key is used comes from resources, in French and English.
 * The sheet turns a reason or a status into one of these strings: a string that exists in one
 * language only would show the wrong language, or crash a formatted one.
 */
class Fido2StringsTest {

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** The `fido2_*` strings of a resource file, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        return Regex("""<string name="(fido2_[a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    @Test
    fun `every FIDO2 string exists in French and in English`() {
        // Both ways, with the names that differ in the message, and no count to keep up to date.
        assertEquals(emptySet<String>(), french.keys - english.keys, "in French only")
        assertEquals(emptySet<String>(), english.keys - french.keys, "in English only")
    }

    @Test
    fun `a formatted string takes the same arguments in both languages`() {
        french.forEach { (name, text) ->
            assertEquals(placeholders(text), placeholders(english.getValue(name)), "placeholders of $name")
        }
    }

    @Test
    fun `the status line has a sentence for every status`() {
        // keyStatusMessageRes has no else branch: a new status will not compile without a string.
        val sentences = HwKeyStatus.entries.map { keyStatusMessageRes(it) }
        assertEquals(sentences.size, sentences.toSet().size, "two statuses must not say the same thing")
        assertTrue(sentences.all { it != 0 })
    }

    @Test
    fun `the title of the sheet does not ask for a touch before the key is there`() {
        // The title comes first, and the line under it says "plug it in" when the key is not there
        // yet: "Touch your key" above "Plug in your key" contradicts itself. It stays neutral.
        val fr = french.getValue("fido2_tap_key")
        val en = english.getValue("fido2_tap_key")

        assertFalse(fr.contains("touch", ignoreCase = true), fr)
        assertFalse(en.contains("touch", ignoreCase = true) || en.contains("tap", ignoreCase = true), en)
        assertEquals("Utilisez votre clé de sécurité", fr)
        assertEquals("Use your security key", en)
    }

    @Test
    fun `the message for a key that does not hold the credential says which key to use`() {
        val fr = french.getValue("fido2_error_no_matching_credential")
        val en = english.getValue("fido2_error_no_matching_credential")

        assertEquals("Utilisez la clé de sécurité avec laquelle cette clé SSH a été créée.", fr)
        assertEquals("Use the security key this SSH key was created with.", en)
    }

    @Test
    fun `the sentence for a USB key waiting on Android reads as a step to take, not as a refusal`() {
        // YubiKit reports the key as soon as it is plugged in, while the system prompt that grants
        // the access is still on screen: the user is in front of it, so "Android did not allow
        // NextSH" would be false. Accepting comes first, unplugging only if it never appeared.
        val fr = french.getValue("fido2_status_usb_denied")
        val en = english.getValue("fido2_status_usb_denied")

        assertTrue(fr.startsWith("Acceptez la demande d"), fr)
        assertTrue(en.startsWith("Accept the Android prompt"), en)
        assertFalse(fr.contains("pas autoris"), fr)
        assertFalse(en.contains("did not allow", ignoreCase = true), en)
        assertTrue(fr.indexOf("Acceptez") < fr.indexOf("débranchez"), fr)
        assertTrue(en.indexOf("Accept") < en.indexOf("unplug"), en)
    }

    @Test
    fun `the PIN hint tells the minimum the Confirm button waits for, in plain text`() {
        // Confirm stays grey below 4 characters and above 63 bytes. The upper limit is rarely met
        // and would only make the label heavy: the minimum is what a user can fall short of. The
        // range was written with an en dash, which has no place in a sentence meant to be read.
        val fr = french.getValue("fido2_pin_hint")
        val en = english.getValue("fido2_pin_hint")

        assertEquals("PIN (4 caractères minimum)", fr)
        assertEquals("PIN (at least 4 characters)", en)
        assertFalse(holdsDash(fr) || holdsDash(en), "$fr / $en")
    }

    @Test
    fun `the English strings are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis")
        english.forEach { (name, text) ->
            british.forEach { word ->
                assertFalse(text.contains(word, ignoreCase = true), "$name uses the British \"$word\": $text")
            }
        }
    }

    /** The sentences of the FIDO2 request, besides the `fido2_status_*` ones, that the user reads while a key is used. */
    private val requestSentences = setOf(
        "fido2_pin_nfc_hint", "fido2_error_no_matching_credential", "fido2_error_pin_incorrect",
        "fido2_error_pin_incorrect_tries", "fido2_error_pin_auth_blocked", "fido2_error_pin_not_set",
        "fido2_error_key_removed", "fido2_error_unsupported_key",
        "fido2_error_touch_timeout", "fido2_error_unexpected", "fido2_error_refused",
    )

    @Test
    fun `the sentences of the request are written for the user, in both languages`() {
        // Names, not a count: a string that is renamed or removed fails here instead of silently
        // leaving the set, and a new fido2_status_ string is covered without touching this test.
        val missing = requestSentences - french.keys
        assertTrue(missing.isEmpty(), "strings this test expects are gone or renamed: $missing")
        val sentences = french.keys.filter { it.startsWith("fido2_status_") || it in requestSentences }

        assertTrue(sentences.size >= requestSentences.size, "the status strings are found too: $sentences")
        sentences.forEach { name ->
            val fr = french.getValue(name)
            val en = english.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            assertNotEquals(fr, en, "$name is not translated")
        }
    }

    @Test
    fun `no FIDO2 string holds an em dash or an en dash`() {
        // A dash has no place in a sentence meant to be read, in any of the FIDO2 strings.
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.forEach { (name, text) ->
                assertFalse(holdsDash(text), "$name ($language) holds a dash")
            }
        }
    }
}
