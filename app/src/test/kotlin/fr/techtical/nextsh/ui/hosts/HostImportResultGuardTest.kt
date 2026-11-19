// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Result message of the host import: plurals in both languages, and a single way back. */
class HostImportResultGuardTest {

    private val screen = SourceGuardSupport.mainSource("ui/hosts/HostImportScreen.kt")

    private fun resources(folder: String): String =
        SourceGuardSupport.locate("src/main/res/$folder/strings.xml").readText()

    @Test
    fun `the counts of the result are plurals in French and in English`() {
        for (folder in listOf("values", "values-en")) {
            val xml = resources(folder)
            for (name in listOf("host_import_imported_count", "host_import_skipped_count")) {
                val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1)
                assertTrue(block != null, "$folder: $name")
                assertTrue(block!!.contains("quantity=\"one\""), "$folder: $name one")
                assertTrue(block.contains("quantity=\"other\""), "$folder: $name other")
            }
            // The sentence only joins the two counts: no hard-coded number format left.
            assertTrue(xml.contains("<string name=\"host_import_result\">%1\$s, %2\$s.</string>"), "$folder: sentence")
        }
    }

    @Test
    fun `the French singular says one host imported, not 1 importes`() {
        val french = resources("values")

        assertTrue(french.contains("<item quantity=\"one\">%1\$d importé</item>"), "one imported")
        assertTrue(french.contains("<item quantity=\"one\">%1\$d ignoré</item>"), "one skipped")
    }

    @Test
    fun `the screen asks the plurals for both counts`() {
        assertTrue(screen.contains("pluralStringResource(R.plurals.host_import_imported_count"), "imported")
        assertTrue(screen.contains("pluralStringResource(R.plurals.host_import_skipped_count"), "skipped")
    }

    @Test
    fun `the way back is taken once, whatever comes first of the arrow and the end of the message`() {
        // The arrow, then the end of the delay of the message, would pop the back stack twice and
        // remove the list of hosts too.
        assertTrue(screen.contains("var leaving by remember"), "a flag for the way back")
        assertEquals(1, Regex("""\bonBack\(\)""").findAll(screen).count(), "onBack is called from one place only")
        assertTrue(screen.contains("IconButton(onClick = leave)"), "the arrow goes through the flag")
        assertTrue(screen.contains("leave()"), "the end of the message goes through the flag")
        // The system back and the predictive gesture too: otherwise the NavHost pops alone and the
        // end of the message pops a second time. Always enabled, never behind an `if`.
        assertTrue(screen.contains("BackHandler(enabled = true) { leave() }"), "the system back goes through the flag")
        val handler = screen.indexOf("BackHandler(enabled = true)")
        assertTrue(!screen.substring(0, handler).trimEnd().endsWith("if (leaving) {"), "no BackHandler behind an if")
    }
}
