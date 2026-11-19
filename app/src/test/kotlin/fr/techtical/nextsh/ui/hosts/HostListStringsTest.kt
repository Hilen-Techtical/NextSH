// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.testutil.AccentedLiteralScanner
import fr.techtical.nextsh.testutil.ProjectFiles
import fr.techtical.nextsh.testutil.StringResourceFile
import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the host list says about the terminal tabs, in French and English: the "Resume" banner with
 * its disconnected part, and what a tap on a host card does. The sentence is assembled from a plural
 * for the total, a plural for the dead ones and a template with the separator, so the plural forms
 * and the separator are checked on the resources the screen reads.
 *
 * It also guards the words of the list that used to be literals in the code, in French: the count
 * under the title ("1 hôte" on an English phone), the description of the auth type of a card
 * (TalkBack read "SSH_KEY") and the description of the icon of the bar (it said "Settings" while a
 * tap opened the conflicts screen).
 */
class HostListStringsTest {

    private val french = resource("values/strings.xml").readText()
    private val english = resource("values-en/strings.xml").readText()
    private val frenchRes = StringResourceFile("values/strings.xml")
    private val englishRes = StringResourceFile("values-en/strings.xml")

    /** A resource file of the app. */
    private fun resource(path: String): File = ProjectFiles.appFile("src/main/res/$path")

    private fun string(xml: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: error("string $name not found")

    private fun plural(xml: String, name: String, quantity: String): String {
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: error("plurals $name not found")
        return Regex("""<item quantity="$quantity">(.*?)</item>""").find(block)?.groupValues?.get(1)
            ?: error("plurals $name has no $quantity")
    }

    /** What the banner shows: the total, then the disconnected ones when there are some. */
    private fun banner(xml: String, total: Int, disconnected: Int): String {
        // CLDR: French uses "one" for 0 and 1, English for 1 only.
        val oneIsZeroToo = xml == french
        fun quantityOf(n: Int) = if (n == 1 || (oneIsZeroToo && n == 0)) "one" else "other"
        val opened = plural(xml, "host_list_open_sessions", quantityOf(total)).replace("%1\$d", "$total")
        if (disconnected == 0) return opened
        val dead = plural(xml, "host_list_disconnected_sessions", quantityOf(disconnected)).replace("%1\$d", "$disconnected")
        return string(xml, "host_list_open_sessions_summary").replace("%1\$s", opened).replace("%2\$s", dead)
    }

    @Test
    fun `the French banner gives the total and then the disconnected ones`() {
        assertEquals("2 sessions ouvertes · 1 déconnectée", banner(french, total = 2, disconnected = 1))
        assertEquals("3 sessions ouvertes · 2 déconnectées", banner(french, total = 3, disconnected = 2))
        assertEquals("1 session ouverte · 1 déconnectée", banner(french, total = 1, disconnected = 1))
    }

    @Test
    fun `the English banner gives the total and then the disconnected ones`() {
        assertEquals("2 sessions open · 1 disconnected", banner(english, total = 2, disconnected = 1))
        assertEquals("3 sessions open · 3 disconnected", banner(english, total = 3, disconnected = 3))
        assertEquals("1 session open · 1 disconnected", banner(english, total = 1, disconnected = 1))
    }

    @Test
    fun `without a disconnected tab the banner is the total alone, as before`() {
        assertEquals("2 sessions ouvertes", banner(french, total = 2, disconnected = 0))
        assertEquals("1 session ouverte", banner(french, total = 1, disconnected = 0))
        assertEquals("2 sessions open", banner(english, total = 2, disconnected = 0))
    }

    @Test
    fun `the plural of the disconnected ones agrees in French`() {
        assertEquals("%1\$d déconnectée", plural(french, "host_list_disconnected_sessions", "one"))
        assertEquals("%1\$d déconnectées", plural(french, "host_list_disconnected_sessions", "other"))
    }

    @Test
    fun `the separator is a middle dot, in both languages`() {
        for (xml in listOf(french, english)) {
            val summary = string(xml, "host_list_open_sessions_summary")
            assertEquals("%1\$s · %2\$s", summary)
        }
    }

    @Test
    fun `the new strings hold no em dash nor en dash`() {
        val names = listOf(
            "host_list_open_sessions_summary", "host_card_action_connect", "host_card_action_reopen_session",
        )
        for (xml in listOf(french, english)) {
            for (name in names) {
                val text = string(xml, name)
                assertFalse(text.any { it.code == 0x2014 || it.code == 0x2013 }, "$name: $text")
            }
            for (quantity in listOf("one", "other")) {
                val text = plural(xml, "host_list_disconnected_sessions", quantity)
                assertFalse(text.any { it.code == 0x2014 || it.code == 0x2013 }, text)
            }
        }
    }

    @Test
    fun `a tap on the card of a host says what it does, whether a tab exists or not`() {
        assertEquals("Se connecter à cet hôte", string(french, "host_card_action_connect"))
        assertEquals("Rouvrir la session de cet hôte", string(french, "host_card_action_reopen_session"))
        assertEquals("Connect to this host", string(english, "host_card_action_connect"))
        assertEquals("Reopen the session of this host", string(english, "host_card_action_reopen_session"))
    }

    @Test
    fun `the two labels of the card tell the two effects apart`() {
        for (xml in listOf(french, english)) {
            assertNotEquals(string(xml, "host_card_action_connect"), string(xml, "host_card_action_reopen_session"))
            assertTrue(string(xml, "host_card_action_connect").isNotBlank())
        }
    }

    // ── The count under the title ──────────────────────────────────────────────

    @Test
    fun `the count of hosts has a one and an other form in French, with the number as an argument`() {
        assertEquals(
            mapOf("one" to "%1\$d hôte", "other" to "%1\$d hôtes"),
            frenchRes.plurals["host_list_count"],
        )
    }

    @Test
    fun `the count of hosts has a one and an other form in English, with the number as an argument`() {
        assertEquals(
            mapOf("one" to "%1\$d host", "other" to "%1\$d hosts"),
            englishRes.plurals["host_list_count"],
        )
    }

    @Test
    fun `no host is a short count of its own, not the sentence of the empty state`() {
        // Zero never reaches the plural: in French "one" covers 0 and 1, so the screen says it apart.
        assertEquals("Aucun hôte", frenchRes.strings["host_list_count_zero"])
        assertEquals("No hosts", englishRes.strings["host_list_count_zero"])
        // The sentence of the empty state stays the title of the empty state. It was the count too, and
        // it overflowed the bar beside "Sync hors ligne" at 360 dp, repeated the empty state under it, and
        // was wrong when a group filter showed nothing while hosts existed.
        assertEquals("Aucun hôte configuré", frenchRes.strings["empty_no_hosts_title"])
        assertEquals("No hosts configured", englishRes.strings["empty_no_hosts_title"])
        for (res in listOf(frenchRes, englishRes)) {
            val count = res.strings.getValue("host_list_count_zero")
            assertTrue(count.length < res.strings.getValue("empty_no_hosts_title").length, count)
            assertFalse(count.any { it.code == 0x2014 || it.code == 0x2013 }, count)
        }
    }

    @Test
    fun `the bar reads the short count for zero and keeps one line for the count and the sync state`() {
        val code = SourceGuardSupport.mainSource("ui/hosts/HostListScreen.kt")
        val label = code.substringAfter("val hostsLabel = ").substringBefore("val pendingConflicts")
        assertTrue(label.contains("R.string.host_list_count_zero"), "the zero count is not the short string: $label")
        assertFalse(label.contains("empty_no_hosts_title"), "the count is the sentence of the empty state again: $label")
        // One line each, an ellipsis at the end: the title of the bar must not grow a second line.
        listOf("text = hostsLabel,", "text = syncLabel,").forEach { start ->
            // Up to the end of the call: the next composable of the bar starts with Spacer( or IconButton(.
            val call = code.substringAfter(start, missingDelimiterValue = "").take(800)
                .substringBefore("Spacer(").substringBefore("IconButton(")
            assertTrue(call.isNotEmpty(), "$start not found in the bar")
            assertTrue(call.contains("maxLines = 1"), "$start has no maxLines = 1")
            assertTrue(call.contains("TextOverflow.Ellipsis"), "$start has no ellipsis")
        }
    }

    // ── The auth type of a card ────────────────────────────────────────────────

    @Test
    fun `every auth type has a word of its own`() {
        val labels = AuthType.entries.map { authTypeLabelRes(it) }
        assertEquals(labels.size, labels.toSet().size, "two auth types share a word: $labels")
    }

    @Test
    fun `the words of the auth types are the ones of the form`() {
        assertEquals(R.string.auth_type_password, authTypeLabelRes(AuthType.PASSWORD))
        assertEquals(R.string.auth_type_ssh_key, authTypeLabelRes(AuthType.SSH_KEY))
        assertEquals(R.string.auth_type_certificate, authTypeLabelRes(AuthType.CERTIFICATE))
        assertEquals(R.string.auth_type_fido2, authTypeLabelRes(AuthType.FIDO2))
        assertEquals(R.string.auth_type_biometric_key, authTypeLabelRes(AuthType.BIOMETRIC_KEY))
    }

    @Test
    fun `the words of the auth types exist in both languages and are not the enum names`() {
        val names = listOf(
            "auth_type_password", "auth_type_ssh_key", "auth_type_certificate", "auth_type_fido2", "auth_type_biometric_key",
        )
        for (res in listOf(frenchRes, englishRes)) {
            for (name in names) {
                val text = res.strings[name]
                assertTrue(!text.isNullOrBlank(), "missing or empty string $name")
                // FIDO2 is the name of the standard, the same word as the enum in both languages.
                if (name == "auth_type_fido2") continue
                assertTrue(AuthType.entries.none { it.name == text }, "$name reads like an enum name: $text")
            }
        }
    }

    @Test
    fun `the card says the auth type once, through its badge, and not through its icon`() {
        // The Server icon is the same for every host: described, TalkBack read the auth type before
        // the name of the host, then read the badge text ("PWD", "SSH_KEY") as well.
        val code = SourceGuardSupport.mainSource("ui/hosts/HostListScreen.kt")
        val icon = Regex("""Lucide\.Server,\s*contentDescription\s*=\s*([^,\n]+),""").find(code)
        assertNotNull(icon, "the Server icon of the host card is not found")
        assertEquals("null", icon!!.groupValues[1].trim(), "the icon of the card must be decorative")

        // The badge replaces its visible code by the label of the auth type, read before the lambda.
        val badge = code.substringAfter("private fun AuthBadge(", missingDelimiterValue = "")
        assertTrue(badge.isNotEmpty(), "AuthBadge not found")
        assertTrue(
            Regex("""val \w+ = stringResource\(authTypeLabelRes\(authType\)\)""").containsMatchIn(badge),
            "the label of the auth type is not read before the semantics",
        )
        assertTrue(
            Regex("""clearAndSetSemantics \{ contentDescription = \w+ \}""").containsMatchIn(badge),
            "the badge does not replace its text by the label of the auth type",
        )
        // Nowhere else in the screen: a second description would be read twice again.
        assertEquals(1, Regex("""authTypeLabelRes\(""").findAll(code).count())
    }

    // ── The icon of the bar ────────────────────────────────────────────────────

    @Test
    fun `the icon of the bar says it resolves the conflicts when some wait, in both languages`() {
        assertEquals(
            mapOf(
                "one" to "Résoudre %1\$d conflit de synchronisation",
                "other" to "Résoudre %1\$d conflits de synchronisation",
            ),
            frenchRes.plurals["host_list_resolve_conflicts"],
        )
        assertEquals(
            mapOf("one" to "Resolve %1\$d sync conflict", "other" to "Resolve %1\$d sync conflicts"),
            englishRes.plurals["host_list_resolve_conflicts"],
        )
    }

    @Test
    fun `the icon of the bar keeps the settings word when no conflict waits`() {
        for (res in listOf(frenchRes, englishRes)) {
            assertTrue(!res.strings["action_settings"].isNullOrBlank(), "action_settings is missing or empty")
        }
    }

    @Test
    fun `the new plurals write no number by hand and hold no em dash nor en dash`() {
        for (res in listOf(frenchRes, englishRes)) {
            for (name in listOf("host_list_count", "host_list_resolve_conflicts")) {
                val forms = res.plurals[name] ?: error("plurals $name not found")
                assertEquals(setOf("one", "other"), forms.keys, name)
                for ((quantity, text) in forms) {
                    assertTrue(text.contains("%1\$d"), "$name/$quantity has no argument: $text")
                    assertFalse(text.replace("%1\$d", "").any { it.isDigit() }, "$name/$quantity writes a number: $text")
                    assertFalse(text.any { it.code == 0x2014 || it.code == 0x2013 }, "$name/$quantity: $text")
                }
            }
        }
    }

    // ── No French literal left in the hosts screens ────────────────────────────

    @Test
    fun `no French word is written in the code of the hosts screens`() {
        // Nothing is allowed: what is left in ui/hosts is ASCII (the badge codes, SSH, SFTP) or
        // not a letter (the middle dot of the subtitle).
        assertEquals(
            emptyList<String>(),
            AccentedLiteralScanner.scan("ui/hosts"),
            "a French literal in a hosts screen: move it to the resources",
        )
    }

    @Test
    fun `the scanner flags an accented literal and ignores comments, logs and what is not a letter`() {
        fun scan(source: String, allowed: Set<String> = emptySet()) =
            AccentedLiteralScanner.scanSource("X.kt", source, allowed)

        assertEquals(listOf("X.kt:1: Arrêté"), scan("val a = \"Arrêté\""))
        // The line number counts the lines of the block comment before.
        assertEquals(listOf("X.kt:3: é"), scan("/* é\n é */\nval a = \"é\""))
        assertEquals(emptyList<String>(), scan("// val a = \"Démarrer\""))
        assertEquals(emptyList<String>(), scan("/* \"Arrêté\" */ val x = 1"))
        assertEquals(emptyList<String>(), scan("Timber.w(\"Démarrage refusé\")"))
        assertEquals(emptyList<String>(), scan("Timber.w(\n    \"Démarrage refusé\",\n)"))
        assertEquals(emptyList<String>(), scan("val quote = '\"'; val é = 1"))
        assertEquals(emptyList<String>(), scan("val a = \"Loading…\" + \"•\" + \"·\""))
        // An escape spells the letter, it does not hide it.
        assertEquals(listOf("X.kt:1: R\\u00e9sum\\u00e9"), scan("val a = \"R\\u00e9sum\\u00e9\""))
        // The literals inside a template are read, the code of the template is not text.
        assertEquals(listOf("X.kt:1: é"), scan("val a = \"\${if (x) \"é\" else \"b\"}\""))
        assertEquals(emptyList<String>(), scan("val a = \"Résumé\"", allowed = setOf("Résumé")))
    }

    @Test
    fun `the scanner reads raw strings, on one line and over several, and literals that look like comments`() {
        fun scan(source: String) = AccentedLiteralScanner.scanSource("X.kt", source)

        // A raw string on one line is a literal like any other.
        assertEquals(listOf("X.kt:1: Arrêté"), scan("val a = \"\"\"Arrêté\"\"\""))
        // Over several lines it is reported at the line of its opening quotes, with the line breaks
        // shown as \n, and the count of the lines goes on after it (the last literal is on line 5).
        assertEquals(
            listOf("X.kt:2: un\\nArrêté\\ndeux", "X.kt:5: é"),
            scan("val x = 1\nval a = \"\"\"un\nArrêté\ndeux\"\"\"\nval b = \"é\""),
        )
        // The comment markers inside a literal open nothing: the accented literal that follows on the
        // same line is still read.
        assertEquals(listOf("X.kt:1: Arrêté"), scan("val a = \"*/*\"; val b = \"Arrêté\""))
        assertEquals(listOf("X.kt:1: Arrêté"), scan("val a = \"https://x\"; val b = \"Arrêté\""))
    }

    @Test
    fun `the scanner fails on a directory or a file it cannot find`() {
        assertThrows(AssertionError::class.java) { AccentedLiteralScanner.scan("ui/no-such-directory") }
        assertThrows(AssertionError::class.java) { AccentedLiteralScanner.scanFile("ui/hosts/NoSuchScreen.kt") }
    }
}
