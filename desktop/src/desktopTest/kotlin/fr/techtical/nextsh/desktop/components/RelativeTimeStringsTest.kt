// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sentences of the relative times and the guard that keeps a single implementation of them.
 *
 * The resources are read as XML from the source tree, so both languages are checked whatever the
 * language of the machine running the tests. The sources are read from the same tree.
 */
class RelativeTimeStringsTest {

    private fun srcDir(): File {
        val candidates = listOf("src", "desktop/src")
        return candidates.map { File(it) }.firstOrNull { File(it, "desktopMain/composeResources/values/strings.xml").isFile }
            ?: error("desktop sources not found (working directory ${File(".").absoluteFile.normalize()})")
    }

    private fun readStrings(relative: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(File(srcDir(), "desktopMain/composeResources/$relative"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val french: Map<String, String> by lazy { readStrings("values/strings.xml") }
    private val english: Map<String, String> by lazy { readStrings("values-en/strings.xml") }

    private val keys = listOf(
        "relative_date_language",
        "relative_just_now",
        "conflicts_detected_at",
        "conflicts_detected_on",
        "statusbar_sync_ok_on",
        "statusbar_relative_minutes_ago",
        "statusbar_relative_hours_ago",
        "statusbar_relative_days_ago",
    )

    @Test
    fun `every key exists in French and in English and is translated`() {
        for (key in keys) {
            assertTrue(key in french, "$key missing in French")
            assertTrue(key in english, "$key missing in English")
            if (key != "relative_date_language") {
                assertNotEquals(french.getValue(key), english.getValue(key), "$key is not translated")
            }
        }
    }

    @Test
    fun `the language of the dates is the language of the file`() {
        assertEquals("fr", french["relative_date_language"])
        assertEquals("en", english["relative_date_language"])
    }

    @Test
    fun `the sentences that take a date use the positional specifier`() {
        for (key in listOf("conflicts_detected_on", "statusbar_sync_ok_on")) {
            assertTrue("%1\$s" in french.getValue(key), "$key (fr) has no positional specifier")
            assertTrue("%1\$s" in english.getValue(key), "$key (en) has no positional specifier")
        }
    }

    @Test
    fun `the replaced duplicates are gone from both languages`() {
        val gone = listOf(
            "settings_relative_just_now", "hosts_relative_just_now", "transfers_relative_just_now",
            "relative_time_seconds", "relative_time_minutes", "relative_time_hours", "relative_time_days",
            "statusbar_relative_seconds_ago",
        )
        for (key in gone) {
            assertFalse(key in french, "$key still in French")
            assertFalse(key in english, "$key still in English")
        }
    }

    // The thresholds and the unit sentences live in components/RelativeTimeText.kt only.
    @Test
    fun `no screen computes its own relative time`() {
        val central = "desktopMain/kotlin/fr/techtical/nextsh/desktop/components/RelativeTimeText.kt"
        val src = srcDir()
        val files = File(src, "desktopMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 100, "only ${files.size} sources under $src: wrong directory?")
        val forbidden = listOf("86_400", "86400", "3_600_000", "statusbar_relative_days_ago", "statusbar_relative_hours_ago")
        val offenders = files
            .filter { it.relativeTo(src).invariantSeparatorsPath != central }
            .filter { file -> file.readText(Charsets.UTF_8).let { text -> forbidden.any { it in text } } }
            .map { it.relativeTo(src).invariantSeparatorsPath }
        assertTrue(offenders.isEmpty(), "relative time computed outside $central: $offenders")
    }

    // A date shown to the user follows the language of the loaded texts (dateLocale), never the JVM
    // locale nor a hard-coded one.
    @Test
    fun `no screen formats a date with the JVM locale or a fixed one`() {
        val src = srcDir()
        val offenders = File(src, "desktopMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readText(Charsets.UTF_8).let { "SimpleDateFormat" in it || "Locale.FRANCE" in it }
            }
            .map { it.relativeTo(src).invariantSeparatorsPath }.toList()
        assertTrue(offenders.isEmpty(), "date formatted outside components/RelativeTimeText.kt: $offenders")
    }
}
