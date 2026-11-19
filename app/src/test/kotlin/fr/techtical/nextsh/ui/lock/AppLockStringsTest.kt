// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.lock

import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the user reads about the lock, in French (the default) and in English: the unlock dialog, the
 * messages of the unlock screen, the setting of the automatic lock, and the four messages that used
 * to tell the user to close NextSH from the recent apps and now tell them to unlock it.
 */
class AppLockStringsTest {

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** Every `<string>` of the file, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        return Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) = Regex("""%\d?\$?[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    /** The strings this work added. */
    private val added: Map<String, Set<String>> = mapOf(
        "vault_auth_unlock_title" to emptySet(),
        "vault_auth_unlock_subtitle" to emptySet(),
        "vault_unlock_error_auth_expired" to emptySet(),
        "vault_unlock_error_key_invalidated" to emptySet(),
        "vault_unlock_error_prompt_failed" to emptySet(),
        "vault_unlock_error_lockout" to emptySet(),
        "vault_unlock_error_lockout_permanent" to emptySet(),
        "vault_unlock_error_none_enrolled" to emptySet(),
        "vault_unlock_error_sensor_unavailable" to emptySet(),
        "vault_unlock_error_no_sensor" to emptySet(),
        "vault_unlock_error_open_failed" to emptySet(),
        "label_auto_lock" to emptySet(),
        "settings_auto_lock_immediately" to emptySet(),
        "settings_auto_lock_minutes" to setOf("%1\$d"),
        "settings_auto_lock_hint" to emptySet(),
        "action_lock_now" to emptySet(),
    )

    /**
     * The messages that were rewritten: the vault is unlocked from the lock screen now, and the
     * messages of the host save say "vault" like the rest of the app.
     */
    private val rewritten = listOf(
        "connect_error_vault_auth_expired",
        "tunnel_error_vault_auth_expired",
        "enrollment_error_vault_locked",
        "host_save_confirm_subtitle",
        "host_save_vault_locked",
        "host_save_vault_unavailable",
    )

    @Test
    fun `every string of the lock exists in French and in English`() {
        val names = added.keys + rewritten
        assertEquals(emptySet<String>(), names - french.keys, "missing from French")
        assertEquals(emptySet<String>(), names - english.keys, "missing from English")
    }

    @Test
    fun `a formatted string takes the same arguments in both languages`() {
        added.forEach { (name, expected) ->
            assertEquals(expected, placeholders(french.getValue(name)), "placeholders of $name in French")
            assertEquals(expected, placeholders(english.getValue(name)), "placeholders of $name in English")
        }
    }

    @Test
    fun `the strings are written for the user, translated, and not empty`() {
        // Same in both languages, and meant to be: a number of minutes.
        val sameInBoth = setOf("settings_auto_lock_minutes")
        (added.keys + rewritten).filterNot { it in sameInBoth }.forEach { name ->
            val fr = french.getValue(name)
            val en = english.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            assertNotEquals(fr, en, "$name is not translated")
        }
    }

    @Test
    fun `no message about the vault window sends the user to the recent apps`() {
        // They used to say to close NextSH from the recent apps and open it again: the lock screen
        // does that job now. Nothing in the resources says it any more.
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.forEach { (name, text) ->
                assertFalse(text.contains("récentes", ignoreCase = true), "$name ($language): $text")
                assertFalse(text.contains("recent apps", ignoreCase = true), "$name ($language): $text")
            }
        }
    }

    @Test
    fun `the messages left under the screen after the unlock do not tell the user to unlock again`() {
        // The lock screen has covered the screen and been passed by the time they are read: they say
        // what happened and to try again once NextSH is unlocked, not "unlock NextSH".
        for (name in listOf("connect_error_vault_auth_expired", "enrollment_error_vault_locked")) {
            assertFalse(french.getValue(name).contains("Déverrouillez", ignoreCase = true), "$name (fr): ${french.getValue(name)}")
            assertFalse(english.getValue(name).contains("Unlock NextSH", ignoreCase = true), "$name (en): ${english.getValue(name)}")
            assertTrue(french.getValue(name).contains("NextSH déverrouillé"), "$name (fr)")
            assertTrue(english.getValue(name).contains("NextSH is unlocked"), "$name (en)")
            assertTrue(french.getValue(name).contains("verrouillé"), "$name (fr) says it was locked")
            assertTrue(english.getValue(name).contains("was locked"), "$name (en) says it was locked")
        }
    }

    @Test
    fun `the message of a tunnel is read with the app closed, it tells to open NextSH and unlock it`() {
        val fr = french.getValue("tunnel_error_vault_auth_expired")
        val en = english.getValue("tunnel_error_vault_auth_expired")

        assertTrue(fr.contains("ouvrez NextSH") && fr.contains("déverrouillez-le"), fr)
        assertTrue(en.contains("open NextSH") && en.contains("unlock it"), en)
    }

    @Test
    fun `the messages are the ones the owner chose`() {
        assertEquals(
            "Le vault était verrouillé. Réessayez une fois NextSH déverrouillé.",
            french.getValue("connect_error_vault_auth_expired"),
        )
        assertEquals("The vault was locked. Try again once NextSH is unlocked.", english.getValue("connect_error_vault_auth_expired"))
        assertEquals(
            "Vault verrouillé : ouvrez NextSH et déverrouillez-le, puis relancez le tunnel.",
            french.getValue("tunnel_error_vault_auth_expired"),
        )
        assertEquals(
            "Le vault était verrouillé. Une fois NextSH déverrouillé, recommencez l\\'enrôlement avec un nouveau QR.",
            french.getValue("enrollment_error_vault_locked"),
        )
        assertEquals(
            "The vault was locked. Once NextSH is unlocked, start the enrollment again with a new QR code.",
            english.getValue("enrollment_error_vault_locked"),
        )
    }

    @Test
    fun `each reason a vault dialog can fail for has its own sentence, in both languages`() {
        val reasons = listOf("lockout", "lockout_permanent", "none_enrolled", "sensor_unavailable", "no_sensor")
        val sentences = reasons.map { french.getValue("vault_unlock_error_$it") }

        assertEquals(reasons.size, sentences.toSet().size, "no two reasons share a sentence")
        assertFalse(sentences.contains(french.getValue("vault_unlock_error_prompt_failed")), "none is the generic one")
        // The sentence of a sensor locked until the phone code is typed says so, as the one of the fingerprint does.
        assertTrue(french.getValue("vault_unlock_error_lockout_permanent").contains("votre code"))
        assertTrue(english.getValue("vault_unlock_error_lockout_permanent").contains("passcode"))
    }

    @Test
    fun `the minutes of the delay are a numbered argument`() {
        assertEquals("%1\$d min", french.getValue("settings_auto_lock_minutes"))
        assertEquals("%1\$d min", english.getValue("settings_auto_lock_minutes"))
    }

    @Test
    fun `the French texts say vault, as the rest of the app does, never coffre`() {
        french.forEach { (name, text) ->
            assertFalse(text.contains("coffre", ignoreCase = true), "$name: $text")
        }
    }

    @Test
    fun `the dialog of the unlock is named and explained`() {
        assertEquals("Déverrouiller NextSH", french.getValue("vault_auth_unlock_title"))
        assertEquals("Unlock NextSH", english.getValue("vault_auth_unlock_title"))
        assertEquals("Authentification requise pour accéder au vault", french.getValue("vault_auth_unlock_subtitle"))
        assertEquals("Authentication required to access the vault", english.getValue("vault_auth_unlock_subtitle"))
    }

    @Test
    fun `the setting of the automatic lock is named, with its immediate choice and a lock now button`() {
        assertEquals("Verrouillage automatique", french.getValue("label_auto_lock"))
        assertEquals("Immédiatement", french.getValue("settings_auto_lock_immediately"))
        assertEquals("Immediately", english.getValue("settings_auto_lock_immediately"))
        assertEquals("Verrouiller maintenant", french.getValue("action_lock_now"))
        assertEquals("Lock now", english.getValue("action_lock_now"))
    }

    @Test
    fun `no string of the lock holds an em dash or an en dash`() {
        (added.keys + rewritten).forEach { name ->
            assertFalse(holdsDash(french.getValue(name)), "$name (fr) holds a dash")
            assertFalse(holdsDash(english.getValue(name)), "$name (en) holds a dash")
        }
    }

    @Test
    fun `the French sentences address the user as vous, never tu`() {
        (added.keys + rewritten).forEach { name ->
            val tu = FrenchTuForm.find(french.getValue(name))
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): ${french.getValue(name)}")
        }
    }

    @Test
    fun `the apostrophes of the French strings are escaped for aapt2`() {
        (added.keys + rewritten).forEach { name ->
            listOf("fr" to french, "en" to english).forEach { (language, strings) ->
                assertFalse(
                    Regex("""(?<!\\)'""").containsMatchIn(strings.getValue(name)),
                    "$name ($language) has an unescaped apostrophe: ${strings.getValue(name)}",
                )
            }
        }
    }

    @Test
    fun `the English sentences are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis")
        (added.keys + rewritten).forEach { name ->
            british.forEach { word ->
                assertFalse(english.getValue(name).contains(word, ignoreCase = true), "$name uses the British \"$word\"")
            }
        }
    }
}
