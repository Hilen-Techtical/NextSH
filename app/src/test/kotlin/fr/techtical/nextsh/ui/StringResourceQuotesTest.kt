// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui

import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A double quote in a text of the app is written `\"`. aapt2 reads a bare `"` as the start or the end
 * of a quoted run and drops it: `Key "%1$s" added` is shown as `Key %1$s added`, with no error
 * and no warning. The other tests read the resource files as plain text, so they cannot see it: this one
 * reads the raw XML and fails on any `"` of a value that is not escaped.
 *
 * A value wrapped as a whole in quotes (the form that keeps its blanks) is a bare quote pair here too:
 * the app writes its texts without it.
 */
class StringResourceQuotesTest {

    /** The `values*` folders of the app: the French default and the other languages. */
    private fun valueFiles(): List<File> {
        val res = ProjectFiles.appDir("src/main/res")
        return res.listFiles { dir -> dir.isDirectory && dir.name.startsWith("values") }!!
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }!!.toList() }
            .sortedBy { it.path }
    }

    /** The texts of a resource file as written in the XML: every `<string>` and every `<item>` (plurals, arrays). */
    private fun textsOf(xml: String): List<String> {
        val withoutComments = xml.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<(string|item)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(withoutComments)
            // The tags inside a text (<b>, <xliff:g id="n">) carry quotes of their own, in their attributes.
            .map { it.groupValues[2].replace(Regex("""<[^>]*>"""), "") }
            .toList()
    }

    private fun nameOf(xml: String, text: String): String {
        val tag = Regex("""<(?:string|item)\b([^>]*)>\s*${Regex.escape(text)}""").find(xml)
        return Regex("name=\"([^\"]*)\"").find(tag?.groupValues?.get(1).orEmpty())?.groupValues?.get(1) ?: text.take(40)
    }

    @Test
    fun `no text of the app holds a double quote that is not escaped`() {
        val files = valueFiles()
        assertTrue(files.size >= 2, "the French and English string files are found: $files")

        val offenders = files.flatMap { file ->
            val xml = file.readText()
            textsOf(xml).filter { bareDoubleQuotes(it) > 0 }.map { "${file.parentFile?.name}: ${nameOf(xml, it)}" }
        }

        assertEquals(emptyList<String>(), offenders, "write the quotes as \\\" : aapt2 drops a bare one")
    }

    @Test
    fun `the check finds a bare quote and lets an escaped one through`() {
        // The texts below are what the resource file holds, backslashes included.
        assertEquals(0, bareDoubleQuotes("Key \\\"x\\\" added"), "escaped on both sides")
        assertEquals(2, bareDoubleQuotes("Key \"x\" added"), "bare on both sides")
        assertEquals(1, bareDoubleQuotes("Default \\\"ssh:\" is"), "escaped then bare")
        assertEquals(1, bareDoubleQuotes("a\\\\\"b"), "a backslash that is itself escaped does not escape the quote after it")
        assertEquals(0, bareDoubleQuotes("It\\'s fine"), "an escaped apostrophe holds no quote")
    }

    @Test
    fun `the texts of the app are read from the raw XML, comments and tags aside`() {
        val xml = """
            <resources>
                <!-- "quoted" in a comment is not a text -->
                <string name="a">One "bare"</string>
                <string name="b">Two <xliff:g id="x" example="y">%1${'$'}s</xliff:g> done</string>
                <plurals name="p"><item quantity="one">Item \"one\"</item></plurals>
            </resources>
        """.trimIndent()

        assertEquals(
            listOf("One \"bare\"", "Two %1\$s done", "Item \\\"one\\\""),
            textsOf(xml),
        )
    }
}

/** How many `"` of [value] are not escaped by a backslash (a backslash escapes the character after it). */
private fun bareDoubleQuotes(value: String): Int {
    var bare = 0
    var escaped = false
    for (c in value) {
        when {
            escaped -> escaped = false
            c == '\\' -> escaped = true
            c == '"' -> bare++
        }
    }
    return bare
}
