// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

/**
 * A `strings.xml` of the app read as text, for the tests that check what the user reads: the
 * `<string>` entries and the `<plurals>` entries, name to raw text (the escapes of the resource
 * compiler, `\'` and `\"`, are still there: see [unescape]).
 *
 * [path] is relative to `src/main/res`, for instance `values/strings.xml` (French, the default)
 * or `values-en/strings.xml`.
 */
internal class StringResourceFile(path: String) {

    private val xml: String = ProjectFiles.appFile("src/main/res/$path").readText()

    /** Every `<string>`, name to raw text. */
    val strings: Map<String, String> =
        Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    /** Every `<plurals>`, name to (quantity to raw text). */
    val plurals: Map<String, Map<String, String>> =
        Regex("""<plurals name="([a-z0-9_]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { block ->
                block.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(block.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            }

    companion object {
        /** What the Android resource compiler does to a text: the escapes of the apostrophe and the quote are removed. */
        fun unescape(text: String): String = text.replace("\\'", "'").replace("\\\"", "\"")
    }
}
