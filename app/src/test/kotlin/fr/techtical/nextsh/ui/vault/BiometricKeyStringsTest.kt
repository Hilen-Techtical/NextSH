// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the user reads to create a biometric key and to pick one for a host comes from resources,
 * in French and in English: the host form shows the chip, the list and the help line, the Vault
 * the reason a key cannot be created. A string that exists in one language only would show the
 * wrong language, a formatted one that differs would crash.
 */
class BiometricKeyStringsTest {

    private val names = listOf(
        "auth_type_biometric_key",
        "biometric_no_keys",
        "biometric_key_hint",
        "vault_biometric_name_empty",
        "vault_biometric_generated",
        "vault_biometric_failed",
        "vault_biometric_no_sensor",
        "vault_biometric_not_enrolled",
        "vault_biometric_unavailable",
    )

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** The strings of [names] in a resource file, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        return Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .filter { it.groupValues[1] in names }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    @Test
    fun `every biometric key string exists in French and in English`() {
        assertEquals(names.toSet(), french.keys, "in French")
        assertEquals(names.toSet(), english.keys, "in English")
    }

    @Test
    fun `a formatted string takes the same arguments in both languages`() {
        names.forEach { name ->
            assertEquals(placeholders(french.getValue(name)), placeholders(english.getValue(name)), "placeholders of $name")
        }
        assertEquals(setOf("%1\$s"), placeholders(french.getValue("vault_biometric_generated")))
    }

    @Test
    fun `the strings are translated, not empty, and hold no dash`() {
        names.forEach { name ->
            val fr = french.getValue(name)
            val en = english.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            assertNotEquals(fr, en, "$name is not translated")
            assertFalse(holdsDash(fr) || holdsDash(en), "$name holds a dash")
        }
    }

    @Test
    fun `the user with no fingerprint is sent to the phone settings, the one with a busy sensor is told to try again`() {
        // The first is told where to add a fingerprint, and that the PIN is not enough.
        assertTrue(french.getValue("vault_biometric_not_enrolled").contains("réglages du téléphone"))
        assertTrue(english.getValue("vault_biometric_not_enrolled").contains("phone settings"))
        assertTrue(french.getValue("vault_biometric_unavailable").contains("Réessayez"))
        assertTrue(english.getValue("vault_biometric_unavailable").contains("Try again"))
    }

    @Test
    fun `a creation that failed after the biometric check says to try again and does not blame the fingerprint`() {
        // It is shown only once the strong biometric check passed: sending the user to check
        // their fingerprint would be wrong, the sentence stays neutral.
        val fr = french.getValue("vault_biometric_failed")
        val en = english.getValue("vault_biometric_failed")

        assertTrue(fr.contains("Réessayez") && fr.contains("redémarrez le téléphone"), fr)
        assertTrue(en.contains("Try again") && en.contains("restart the phone"), en)
        assertFalse(fr.contains("empreinte") || fr.contains("réglages"), fr)
        assertFalse(en.contains("fingerprint") || en.contains("settings"), en)
    }

    @Test
    fun `the empty list and the help line send the user to the Vault, where the key is created`() {
        assertTrue(french.getValue("biometric_no_keys").contains("Vault"))
        assertTrue(english.getValue("biometric_no_keys").contains("Vault"))
        // The fingerprint is asked at each connection, and the public key goes to the server.
        assertTrue(french.getValue("biometric_key_hint").contains("empreinte"))
        assertTrue(french.getValue("biometric_key_hint").contains("authorized_keys"))
        assertTrue(english.getValue("biometric_key_hint").contains("fingerprint"))
        assertTrue(english.getValue("biometric_key_hint").contains("authorized_keys"))
    }
}
