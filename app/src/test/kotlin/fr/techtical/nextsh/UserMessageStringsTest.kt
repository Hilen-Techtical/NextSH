// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh

import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sentences the user reads when a connection, an enrollment or a settings action fails come from
 * resources, in French (the default) and in English: they used to be literals in the code, in French
 * only, some of them followed by what an exception said. A string that exists in one language only
 * would show the wrong language, or crash a formatted one.
 */
class UserMessageStringsTest {

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** Every `<string>` of the file, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        return Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    /** Name to the placeholders it takes, for the sentences this work moved out of the code. */
    private val movedOut: Map<String, Set<String>> = mapOf(
        // ConnectSessionUseCase
        "connect_error_password_missing" to setOf("%1\$s"),
        "connect_error_key_missing" to setOf("%1\$s"),
        "connect_error_certificate_missing" to setOf("%1\$s"),
        "connect_error_fido2_key_missing" to setOf("%1\$s"),
        "connect_error_fido2_key_invalid" to setOf("%1\$s"),
        "connect_error_passkey_key_missing" to setOf("%1\$s"),
        "connect_error_biometric_key_missing" to setOf("%1\$s"),
        "connect_error_vault_auth_expired" to emptySet(),
        "connect_error_unexpected" to emptySet(),
        // SshSessionManager
        "shell_error_session_not_found" to emptySet(),
        "shell_error_open_failed" to emptySet(),
        "auth_error_prompt_unavailable" to emptySet(),
        // EnrollmentScanViewModel, through EnrollmentError
        "enrollment_error_invalid_qr" to emptySet(),
        "enrollment_error_invalid_response" to emptySet(),
        "enrollment_error_connect_timeout" to emptySet(),
        "enrollment_error_unreachable" to setOf("%1\$s", "%2\$d"),
        "enrollment_error_refused" to emptySet(),
        "enrollment_error_server" to emptySet(),
        "enrollment_error_submit_timeout" to emptySet(),
        "enrollment_error_connection_lost" to emptySet(),
        "enrollment_error_network" to emptySet(),
        "enrollment_error_vault_locked" to emptySet(),
        "enrollment_error_not_saved" to emptySet(),
        // The security key used to connect (HwKeyError.PinNotSet)
        "fido2_error_pin_not_set" to emptySet(),
        "settings_subtitle" to emptySet(),
        "section_danger_zone" to emptySet(),
        "settings_danger_zone_hint" to emptySet(),
    )

    @Test
    fun `every moved sentence exists in French and in English`() {
        assertEquals(emptySet<String>(), movedOut.keys - french.keys, "missing from French")
        assertEquals(emptySet<String>(), movedOut.keys - english.keys, "missing from English")
    }

    @Test
    fun `a moved sentence takes the arguments it is called with, in both languages`() {
        movedOut.forEach { (name, expected) ->
            assertEquals(expected, placeholders(french.getValue(name)), "placeholders of $name in French")
            assertEquals(expected, placeholders(english.getValue(name)), "placeholders of $name in English")
        }
    }

    @Test
    fun `a moved sentence is written for the user, translated, and not empty`() {
        movedOut.keys.forEach { name ->
            val fr = french.getValue(name)
            val en = english.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            assertNotEquals(fr, en, "$name is not translated")
        }
    }

    @Test
    fun `no moved sentence holds an em dash or an en dash`() {
        movedOut.keys.forEach { name ->
            assertFalse(holdsDash(french.getValue(name)), "$name (fr) holds a dash")
            assertFalse(holdsDash(english.getValue(name)), "$name (en) holds a dash")
        }
    }

    @Test
    fun `the French sentences address the user as vous, never tu`() {
        movedOut.keys.forEach { name ->
            val text = french.getValue(name)
            val tu = FrenchTuForm.find(text)
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): $text")
        }
    }

    @Test
    fun `the apostrophes of the moved sentences are escaped for aapt2`() {
        movedOut.keys.forEach { name ->
            listOf("fr" to french, "en" to english).forEach { (language, strings) ->
                val text = strings.getValue(name)
                assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name ($language) has an unescaped apostrophe: $text")
            }
        }
    }

    @Test
    fun `a key with no PIN set is told to set one, in the words of the other PIN sentences`() {
        assertEquals("Aucun PIN n\\'est défini sur cette clé. Définissez-en un, puis réessayez.", french.getValue("fido2_error_pin_not_set"))
        assertEquals("No PIN is set on this key. Set one, then try again.", english.getValue("fido2_error_pin_not_set"))
    }

    @Test
    fun `the notification channel of the tunnels is named and described in French in the default language`() {
        assertEquals("Tunnels SSH", french.getValue("tunnel_channel_name"))
        assertEquals("SSH Tunnels", english.getValue("tunnel_channel_name"))
        assertEquals("Connexions de tunnels SSH actives", french.getValue("tunnel_channel_description"))
        assertEquals("Active SSH tunnel connections", english.getValue("tunnel_channel_description"))
    }

    @Test
    fun `the title, the section and the hint of the settings that used to be literals are translated`() {
        for (name in listOf("settings_subtitle", "section_danger_zone", "settings_danger_zone_hint")) {
            assertNotEquals(french.getValue(name), english.getValue(name), "$name is not translated")
        }
        assertEquals("Zone dangereuse", french.getValue("section_danger_zone"))
        assertEquals("Danger zone", english.getValue("section_danger_zone"))
    }

    @Test
    fun `a message about the vault window gives an action that works, not a button that does not exist`() {
        // The window is renewed by unlocking NextSH: the lock screen comes up over the screen the
        // user is on, and the message is read under it once it is passed. So the sentences say what
        // happened and to try again once NextSH is unlocked, not to close it from the recent apps.
        for (name in listOf("connect_error_vault_auth_expired", "enrollment_error_vault_locked")) {
            assertTrue(french.getValue(name).contains("NextSH déverrouillé"), "$name (fr)")
            assertTrue(english.getValue(name).contains("NextSH is unlocked"), "$name (en)")
        }
        // The Desktop is done with a QR code once the device was sent: a new one is asked for.
        for (name in listOf("enrollment_error_vault_locked", "enrollment_error_not_saved")) {
            assertTrue(french.getValue(name).contains("nouveau QR"), "$name (fr)")
            assertTrue(english.getValue(name).contains("new QR code"), "$name (en)")
        }
    }

    @Test
    fun `the English sentences are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis")
        movedOut.keys.forEach { name ->
            british.forEach { word ->
                assertFalse(english.getValue(name).contains(word, ignoreCase = true), "$name uses the British \"$word\"")
            }
        }
    }
}
