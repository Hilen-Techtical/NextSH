// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the user reads when a fingerprint is asked for, or when it fails, comes from resources, in
 * French (the default) and English. A string that exists in one language only would show the wrong
 * language, or crash a formatted one.
 */
class BiometricStringsTest {

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** The strings of the fingerprint prompt and of its failures, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        return Regex(
            """<string name="((?:(?:auth_error_biometric_|biometric_sign_)[a-z0-9_]+)|auth_error_failed_biometric)">(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    /** The failure sentences added for the fingerprint, which have no English twin in the old strings. */
    private val newSentences = setOf(
        "auth_error_biometric_timeout", "auth_error_biometric_lockout", "auth_error_biometric_lockout_permanent",
        "auth_error_biometric_none_enrolled", "auth_error_biometric_sensor_unavailable", "auth_error_biometric_error",
        "auth_error_biometric_key_unusable", "auth_error_failed_biometric", "auth_error_biometric_no_sensor",
    )

    @Test
    fun `every fingerprint string exists in French and in English`() {
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
    fun `the failure sentences are written for the user, translated, and not empty`() {
        val missing = newSentences - french.keys
        assertTrue(missing.isEmpty(), "strings this test expects are gone or renamed: $missing")
        newSentences.forEach { name ->
            val fr = french.getValue(name)
            val en = english.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            assertNotEquals(fr, en, "$name is not translated")
        }
    }

    @Test
    fun `the dialog names the host in its title and the key in its subtitle`() {
        // %1$s is the label of the host, then of the key: user text, shown by the system dialog only.
        assertEquals("Connexion à %1\$s", french.getValue("biometric_sign_title"))
        assertEquals("Clé biométrique : %1\$s", french.getValue("biometric_sign_subtitle"))
        assertEquals("Connecting to %1\$s", english.getValue("biometric_sign_title"))
        assertEquals("Biometric key: %1\$s", english.getValue("biometric_sign_subtitle"))
        // A regular space before the colon, as the rest of the French strings write it.
        assertFalse(french.getValue("biometric_sign_subtitle").contains(' '))
        assertFalse(french.getValue("biometric_sign_subtitle").contains(' '))
    }

    @Test
    fun `a Keystore failure that proves nothing never tells the user to regenerate the key`() {
        // Regenerating a healthy key cuts the access to every server it is authorized on.
        assertEquals("Le téléphone n'a pas pu utiliser la clé biométrique. Réessayez.", french.getValue("auth_error_biometric_key_unusable").replace("\\'", "'"))
        assertEquals("The phone could not use the biometric key. Try again.", english.getValue("auth_error_biometric_key_unusable"))
        assertFalse(french.getValue("auth_error_biometric_key_unusable").contains("régénér"))
        assertFalse(english.getValue("auth_error_biometric_key_unusable").contains("regenerate", ignoreCase = true))
        // The sentence that does say it is still there, for the signs that prove the key is gone.
        assertTrue(french.getValue("auth_error_biometric_key_invalid").contains("régénérez"))
    }

    @Test
    fun `the refusal of a biometric key hints that the public key must be in authorized_keys`() {
        assertTrue(french.getValue("auth_error_failed_biometric").contains("authorized_keys"))
        assertTrue(english.getValue("auth_error_failed_biometric").contains("authorized_keys"))
    }

    @Test
    fun `no fingerprint string holds an em dash or an en dash`() {
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.forEach { (name, text) ->
                assertFalse(holdsDash(text), "$name ($language) holds a dash")
            }
        }
    }

    @Test
    fun `the French sentences address the user as vous, never tu`() {
        french.forEach { (name, text) ->
            val tu = FrenchTuForm.find(text)
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): $text")
        }
        assertTrue(french.getValue("auth_error_biometric_lockout_permanent").contains("votre code"))
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

    @Test
    fun `the apostrophes of the French strings are escaped for aapt2`() {
        french.forEach { (name, text) ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name has an unescaped apostrophe: $text")
        }
    }
}
