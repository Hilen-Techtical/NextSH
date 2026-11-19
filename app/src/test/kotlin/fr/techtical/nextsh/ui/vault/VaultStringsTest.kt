// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.StringResourceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the Vault says about the deletion of a key, the generation and the import of one, and the
 * import of a backup comes from resources, in French (the default) and in English: these used to
 * be literals in the code, French only, some of them followed by what an exception said. A string
 * that exists in one language only would show the wrong language, a formatted one that differs
 * would crash, and a plural with a missing form would crash for that count.
 */
class VaultStringsTest {

    /** Name to the placeholders the code formats it with. */
    private val strings: Map<String, Set<String>> = mapOf(
        "vault_key_pem_empty" to emptySet(),
        "vault_key_generated" to setOf("%1\$s", "%2\$s"),
        "vault_key_generate_failed" to emptySet(),
        "vault_key_imported" to setOf("%1\$s"),
        "vault_key_import_failed" to emptySet(),
        "vault_key_import_unreadable" to emptySet(),
        "vault_key_import_wrong_passphrase" to emptySet(),
        "vault_key_deleted" to emptySet(),
        "vault_key_delete_failed" to emptySet(),
        "vault_error_auth_expired" to emptySet(),
        "vault_import_file_unreadable" to emptySet(),
        "vault_import_file_too_large" to emptySet(),
        "vault_import_failed" to emptySet(),
        "dialog_delete_key_usage_unknown" to emptySet(),
        "key_import_passphrase_title" to emptySet(),
        "key_import_passphrase_message" to emptySet(),
        "key_import_passphrase_unlock" to emptySet(),
    )

    /** Name to the placeholders every form of the plural is formatted with. */
    private val plurals: Map<String, Set<String>> = mapOf(
        "dialog_delete_key_used_by_hosts" to setOf("%1\$d", "%2\$s", "%3\$s"),
        "dialog_delete_key_hosts_and_more" to setOf("%1\$s", "%2\$d"),
        "dialog_delete_key_tunnels_one_host" to setOf("%1\$d"),
        "dialog_delete_key_tunnels_many_hosts" to setOf("%1\$d"),
        "dialog_delete_key_cannot_connect" to emptySet(),
        "vault_keys_subtitle" to setOf("%1\$d"),
    )

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
            assertNotEquals(fr, en, "$name is not translated")
        }
        plurals.keys.forEach { name ->
            listOf("one", "other").forEach { quantity ->
                val fr = frenchPlurals.getValue(name).getValue(quantity)
                val en = englishPlurals.getValue(name).getValue(quantity)
                assertFalse(fr.isBlank() || en.isBlank(), "$name/$quantity must not be empty")
                // Only the "one" and "other" forms that hold no word of their own (a number, a list)
                // may be the same in both languages: none of these does.
                assertNotEquals(fr, en, "$name/$quantity is not translated")
            }
        }
    }

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
    fun `the English texts are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis")
        allTexts().filter { it.first == "en" }.forEach { (_, name, text) ->
            british.forEach { word -> assertFalse(text.contains(word, ignoreCase = true), "$name uses the British \"$word\"") }
        }
    }

    @Test
    fun `the warning says what happens to the hosts and what to do about it`() {
        val fr = frenchPlurals.getValue("dialog_delete_key_cannot_connect")
        val en = englishPlurals.getValue("dialog_delete_key_cannot_connect")

        // The subject is named, not a bare pronoun: this host, these hosts. The pronouns only
        // come after, for the object (lui, leur / it, them).
        assertEquals(
            "Cet hôte ne pourra plus se connecter tant que vous ne lui aurez pas choisi une autre clé.",
            fr.getValue("one"),
        )
        assertEquals(
            "Ces hôtes ne pourront plus se connecter tant que vous ne leur aurez pas choisi une autre clé.",
            fr.getValue("other"),
        )
        assertEquals(
            "This host will no longer be able to connect until you choose another key for it.",
            StringResourceFile.unescape(en.getValue("one")),
        )
        assertEquals(
            "These hosts will no longer be able to connect until you choose another key for them.",
            StringResourceFile.unescape(en.getValue("other")),
        )
    }

    @Test
    fun `the line for a failed check says what could not be done, the deletion staying possible`() {
        assertEquals("Impossible de vérifier si des hôtes utilisent cette clé.", frenchStrings.getValue("dialog_delete_key_usage_unknown"))
        assertEquals("Could not check whether any hosts use this key.", englishStrings.getValue("dialog_delete_key_usage_unknown"))
    }

    @Test
    fun `the new failure sentences say try again in the short form of the neighbors`() {
        listOf("vault_key_generate_failed", "vault_key_import_failed", "vault_key_delete_failed", "vault_import_failed").forEach { name ->
            assertTrue(frenchStrings.getValue(name).endsWith("Réessayez."), "$name (fr)")
            assertTrue(englishStrings.getValue(name).endsWith("Try again."), "$name (en)")
            assertFalse(frenchStrings.getValue(name).contains("Veuillez"), "$name (fr)")
            assertFalse(englishStrings.getValue(name).contains("Please"), "$name (en)")
        }
    }

    @Test
    fun `an empty key name reuses the sentence of the biometric key, there is no second one`() {
        assertFalse("vault_key_name_empty" in frenchStrings)
        assertFalse("vault_key_name_empty" in englishStrings)
        assertTrue("vault_biometric_name_empty" in frenchStrings)
        assertTrue("vault_biometric_name_empty" in englishStrings)
    }

    @Test
    fun `the plain deletion sentence is unchanged`() {
        assertEquals("« %s » sera supprimée définitivement du vault.", frenchStrings.getValue("dialog_delete_key_message"))
        assertEquals("\\\"%s\\\" will be permanently deleted from the vault.", englishStrings.getValue("dialog_delete_key_message"))
    }

    @Test
    fun `a failure that needs the vault unlocked again says so, without sending the user to the recent apps`() {
        // The process outlives a swipe from the recent apps (the terminal service keeps it): closing
        // the app there no longer relocks anything. The failure locks the app instead, and the
        // sentence stays under the lock screen: it still reads right once the app is unlocked.
        assertTrue(frenchStrings.getValue("vault_error_auth_expired").contains("une fois NextSH déverrouillé"))
        assertTrue(englishStrings.getValue("vault_error_auth_expired").contains("once NextSH is unlocked"))
        assertFalse(frenchStrings.getValue("vault_error_auth_expired").contains("récentes"))
        assertFalse(englishStrings.getValue("vault_error_auth_expired").contains("recent apps"))
    }

    @Test
    fun `no failure sentence repeats a message of an exception or a placeholder for one`() {
        // The screen never shows what an exception says: none of the failure sentences takes an argument.
        listOf(
            "vault_key_generate_failed", "vault_key_import_failed", "vault_key_import_unreadable",
            "vault_key_import_wrong_passphrase", "vault_key_delete_failed", "vault_error_auth_expired",
            "vault_import_file_unreadable", "vault_import_file_too_large", "vault_import_failed",
        ).forEach { name ->
            assertEquals(emptySet<String>(), placeholders(frenchStrings.getValue(name)), "$name (fr)")
            assertEquals(emptySet<String>(), placeholders(englishStrings.getValue(name)), "$name (en)")
        }
    }
}
