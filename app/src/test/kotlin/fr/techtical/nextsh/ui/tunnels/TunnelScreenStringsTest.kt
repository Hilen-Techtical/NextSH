// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The words of the tunnels screens (status, type, the three nodes of the diagram, the helper texts of
 * the form, the errors of the tunnel browser) used to be literals in the code, in French: an English
 * phone showed "Arrêté", "Démarrer" and "Distant". They come from resources now, in French (the
 * default) and in English, and this guards that they stay there.
 */
class TunnelScreenStringsTest {

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** Every `<string>` of the file, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = resource(path)
        return Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The `<plurals>` of the file, name to quantity to raw text. */
    private fun plurals(path: String): Map<String, Map<String, String>> {
        val text = resource(path).readText()
        return Regex("""<plurals name="([a-z0-9_]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .associate { block ->
                block.groupValues[1] to Regex("""<item quantity="(\w+)">(.*?)</item>""").findAll(block.groupValues[2])
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }
    }

    private fun resource(path: String): File = ProjectFiles.appFile("src/main/res/$path")

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    /** Name to the placeholders it takes. */
    private val tunnelStrings: Map<String, Set<String>> = mapOf(
        "tunnels_subtitle" to setOf("%1\$s", "%2\$s"),
        "action_dismiss_tunnel_error" to emptySet(),
        "tunnel_status_active" to emptySet(),
        "tunnel_status_starting" to emptySet(),
        "tunnel_status_reconnecting" to emptySet(),
        "tunnel_status_error" to emptySet(),
        "tunnel_status_stopped" to emptySet(),
        "tunnel_type_local" to emptySet(),
        "tunnel_type_remote" to emptySet(),
        "tunnel_type_socks5" to emptySet(),
        "tunnel_node_local" to emptySet(),
        "tunnel_node_gateway" to emptySet(),
        "tunnel_node_remote" to emptySet(),
        "tunnel_node_multi_target" to emptySet(),
        "tunnel_node_localhost" to emptySet(),
        "tunnel_node_via_socks5" to emptySet(),
        "helper_tunnel_type_local" to emptySet(),
        "helper_tunnel_type_remote" to emptySet(),
        "helper_tunnel_type_socks5" to emptySet(),
        "helper_tunnel_socks5_exposed" to setOf("%1\$s"),
        "browser_error_load" to setOf("%1\$d", "%2\$s"),
        "browser_error_http" to setOf("%1\$d", "%2\$s"),
        "placeholder_remote_bind_host" to emptySet(),
        "placeholder_remote_forward_local_port" to emptySet(),
        "tunnel_error_no_answer" to emptySet(),
        "tunnel_error_network_lost" to emptySet(),
    )

    /**
     * The ones that read the same in both languages: a protocol name, a host name, a value. They are
     * resources all the same (as the other placeholders are), so that nothing of the screen is a
     * literal; they are the only ones allowed to be equal in French and in English.
     */
    private val sameInBothLanguages = setOf(
        "tunnels_subtitle",
        "tunnel_type_local",
        "tunnel_type_socks5",
        "tunnel_node_local",
        "tunnel_node_localhost",
        "tunnel_node_via_socks5",
        "placeholder_remote_bind_host",
        "placeholder_remote_forward_local_port",
    )

    @Test
    fun `every tunnel string exists in French and in English`() {
        assertEquals(emptySet<String>(), tunnelStrings.keys - french.keys, "missing from French")
        assertEquals(emptySet<String>(), tunnelStrings.keys - english.keys, "missing from English")
    }

    @Test
    fun `a tunnel string takes the arguments it is called with, in both languages`() {
        tunnelStrings.forEach { (name, expected) ->
            assertEquals(expected, placeholders(french.getValue(name)), "placeholders of $name in French")
            assertEquals(expected, placeholders(english.getValue(name)), "placeholders of $name in English")
        }
    }

    @Test
    fun `a tunnel string is not empty, and is translated unless it reads the same in both languages`() {
        tunnelStrings.keys.forEach { name ->
            val fr = french.getValue(name)
            val en = english.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            if (name in sameInBothLanguages) {
                assertEquals(fr, en, "$name is listed as the same in both languages")
            } else {
                assertNotEquals(fr, en, "$name is not translated")
            }
        }
    }

    @Test
    fun `the words the product owner saw in French on an English phone are in English`() {
        // "Arrêté", "Démarrer"/"Démarrage" and "Distant" were shown as they were written in the code.
        assertEquals("Stopped", english.getValue("tunnel_status_stopped"))
        assertEquals("Starting", english.getValue("tunnel_status_starting"))
        assertEquals("Start", english.getValue("action_start"))
        assertEquals("Stop", english.getValue("action_stop"))
        assertEquals("Remote", english.getValue("tunnel_node_remote"))
        assertEquals("Remote", english.getValue("tunnel_type_remote"))
        assertEquals("SSH gateway", english.getValue("tunnel_node_gateway"))
        // And the French ones are written in French (the card used to say "REMOTE", "Stopper").
        assertEquals("Arrêté", french.getValue("tunnel_status_stopped"))
        assertEquals("Distant", french.getValue("tunnel_type_remote"))
        assertEquals("Arrêter", french.getValue("action_stop"))
    }

    @Test
    fun `the reasons of a reconnection say what is wrong, in the words of the product`() {
        assertEquals("Le serveur ne répond plus", french.getValue("tunnel_error_no_answer"))
        assertEquals("Réseau indisponible", french.getValue("tunnel_error_network_lost"))
        assertEquals("The server stopped responding", english.getValue("tunnel_error_no_answer"))
        assertEquals("Network unavailable", english.getValue("tunnel_error_network_lost"))
        // Never the word "coffre": it is the vault, in French as in English.
        listOf("tunnel_error_no_answer", "tunnel_error_network_lost").forEach { name ->
            assertFalse(french.getValue(name).contains("coffre", ignoreCase = true), name)
        }
    }

    @Test
    fun `no tunnel string holds an em dash or an en dash`() {
        tunnelStrings.keys.forEach { name ->
            assertFalse(holdsDash(french.getValue(name)), "$name (fr) holds a dash")
            assertFalse(holdsDash(english.getValue(name)), "$name (en) holds a dash")
        }
    }

    @Test
    fun `the French tunnel strings address the user as vous, never tu`() {
        tunnelStrings.keys.forEach { name ->
            val text = french.getValue(name)
            val tu = FrenchTuForm.find(text)
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): $text")
        }
    }

    @Test
    fun `the apostrophes of the tunnel strings are escaped for aapt2`() {
        tunnelStrings.keys.forEach { name ->
            listOf("fr" to french, "en" to english).forEach { (language, strings) ->
                val text = strings.getValue(name)
                assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name ($language) has an unescaped apostrophe: $text")
            }
        }
    }

    @Test
    fun `the counts of the subtitle are plurals with the number as argument, in both languages`() {
        for (language in listOf("values/strings.xml", "values-en/strings.xml")) {
            val all = plurals(language)
            for (name in listOf("tunnels_count_total", "tunnels_count_active")) {
                val items = all.getValue(name)
                assertTrue("one" in items && "other" in items, "$name ($language) needs one and other: ${items.keys}")
                items.forEach { (quantity, text) ->
                    assertEquals(setOf("%1\$d"), placeholders(text), "$name/$quantity ($language)")
                    assertFalse(holdsDash(text), "$name/$quantity ($language) holds a dash")
                }
            }
        }
        // French reads 0 and 1 as singular: "0 actif", "1 tunnel".
        assertEquals("%1\$d actif", plurals("values/strings.xml").getValue("tunnels_count_active").getValue("one"))
        assertEquals("%1\$d actifs", plurals("values/strings.xml").getValue("tunnels_count_active").getValue("other"))
    }

    // ── The labels derived from the enums of :shared ──────────────────────────

    @Test
    fun `every status and every type has a word of its own`() {
        val statuses = TunnelStatus.entries.map { tunnelStatusLabelRes(it) }
        val types = TunnelType.entries.map { tunnelTypeLabelRes(it) }
        val helpers = TunnelType.entries.map { tunnelTypeHelperRes(it) }

        assertEquals(statuses.size, statuses.toSet().size, "two statuses share a word: $statuses")
        assertEquals(types.size, types.toSet().size, "two types share a word: $types")
        assertEquals(helpers.size, helpers.toSet().size, "two types share a helper text: $helpers")
    }

    // ── No French literal left in the tunnels and browser screens ─────────────

    /** Letters that a French word has and an English one, a protocol name or a code does not. */
    private val accented = "àâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ"

    /**
     * The lines of [source] that hold an accented letter inside a string literal, outside the comments
     * and outside a log call (a log line is text for the developer, in English).
     *
     * A letter is inside a literal when an odd number of unescaped double quotes comes before it on its
     * line; a quote written as a character (`'"'`) is not one. Good enough for these files, which write
     * their texts on one line, and stable: it reads text, it runs nothing.
     */
    private fun accentedLiterals(source: String): List<String> {
        val flagged = mutableListOf<String>()
        blankComments(source).lines().forEachIndexed { index, line ->
            if (line.contains("Timber.") || line.contains("Logger.")) return@forEachIndexed
            val code = line.replace("'\"'", "''")
            var inside = false
            var escaped = false
            for (c in code) {
                when {
                    escaped -> escaped = false
                    c == '\\' && inside -> escaped = true
                    c == '"' -> inside = !inside
                    inside && c in accented -> {
                        flagged += "${index + 1}: ${line.trim()}"
                        break
                    }
                }
            }
        }
        return flagged
    }

    @Test
    fun `the scanner flags an accented literal and ignores comments, log lines and code`() {
        assertEquals(1, accentedLiterals("""Text(text = "Arrêté", color = c)""").size)
        assertEquals(1, accentedLiterals("val label = \"Distant\" + \"Démarrage\"").size)
        assertEquals(0, accentedLiterals("""Text(text = stringResource(R.string.tunnel_status_stopped))""").size)
        assertEquals(0, accentedLiterals("// Démarrer : le bouton principal").size)
        assertEquals(0, accentedLiterals("/* Arrêté */ val x = 1").size)
        assertEquals(0, accentedLiterals("""Timber.w("Démarrage refusé")""").size)
        assertEquals(0, accentedLiterals("""val quote = '"'; val é = 1""").size)
    }

    @Test
    fun `no French word is written in the code of the tunnels and browser screens`() {
        val dirs = listOf("ui/tunnels", "ui/browser").map { ProjectFiles.mainSourceDir(it) }
        val files = dirs.flatMap { dir -> dir.listFiles { f -> f.extension == "kt" }.orEmpty().toList() }
        assertTrue(files.size >= 8, "the screens are really scanned: ${files.map { it.name }}")

        val problems = files.flatMap { file ->
            accentedLiterals(file.readText()).map { "${file.name}:$it" }
        }

        assertEquals(emptyList<String>(), problems, "a French literal in a screen: move it to the resources")
    }
}
