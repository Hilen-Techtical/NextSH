// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import fr.techtical.nextsh.testutil.AccentedLiteralScanner
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The count under the title of the known hosts screen was a French literal ("1 empreinte
 * enregistrée" on an English phone). It is a plural of the resources now, with the empty case kept
 * on its own string: in French "one" covers 0 as well.
 */
class KnownHostsStringsTest {

    private val french = resource("values/strings.xml").readText()
    private val english = resource("values-en/strings.xml").readText()

    /** A resource file of the app. */
    private fun resource(path: String): File = ProjectFiles.appFile("src/main/res/$path")

    private fun plural(xml: String, name: String, quantity: String): String {
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: error("plurals $name not found")
        return Regex("""<item quantity="$quantity">(.*?)</item>""").find(block)?.groupValues?.get(1)
            ?: error("plurals $name has no $quantity")
    }

    @Test
    fun `the count of known hosts is a plural in both languages`() {
        assertEquals("%1\$d empreinte enregistrée", plural(french, "known_hosts_count", "one"))
        assertEquals("%1\$d empreintes enregistrées", plural(french, "known_hosts_count", "other"))
        assertEquals("%1\$d saved fingerprint", plural(english, "known_hosts_count", "one"))
        assertEquals("%1\$d saved fingerprints", plural(english, "known_hosts_count", "other"))
    }

    @Test
    fun `the known hosts screen has no French literal left`() {
        assertEquals(emptyList<String>(), AccentedLiteralScanner.scanFile("ui/settings/KnownHostsScreen.kt"))
    }
}
