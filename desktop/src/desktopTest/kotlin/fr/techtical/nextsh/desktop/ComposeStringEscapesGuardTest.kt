// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guard against backslash escapes that Compose resources do not understand.
 *
 * Unlike Android resources, the Compose resources plugin (1.7.3, `handleSpecialCharacters`) only
 * turns `\n`, `\t`, `\uXXXX` and `\\` into their character. Any other escape is shown as it is
 * written: `L\'application` was displayed with its backslash. An apostrophe or a double quote
 * needs no escape in these files.
 *
 * Every `string` and plural `item` of every `values*` folder is read as XML, from the source tree.
 */
class ComposeStringEscapesGuardTest {

    private fun resourcesDir(): File {
        val candidates = listOf("src/desktopMain/composeResources", "desktop/src/desktopMain/composeResources")
        return candidates.map { File(it) }.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error(
                "composeResources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                    "tried $candidates)",
            )
    }

    private fun stringFiles(): List<File> =
        resourcesDir().listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty()
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.path }

    /** The texts of [file], keyed by "folder/name", plural items with their quantity. */
    private fun readTexts(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(file)
        val folder = file.parentFile.name
        val texts = mutableMapOf<String, String>()
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val node = strings.item(index)
            texts["$folder/${node.attributes.getNamedItem("name").nodeValue}"] = node.textContent
        }
        val items = document.getElementsByTagName("item")
        for (index in 0 until items.length) {
            val node = items.item(index)
            val parent = node.parentNode.attributes?.getNamedItem("name")?.nodeValue ?: "?"
            val quantity = node.attributes?.getNamedItem("quantity")?.nodeValue ?: index.toString()
            texts["$folder/$parent[$quantity]"] = node.textContent
        }
        return texts
    }

    /** The escapes of [text] that the plugin leaves as they are, empty when there is none. */
    private fun unsupportedEscapes(text: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '\\') {
                index++
                continue
            }
            val next = text.getOrNull(index + 1)
            when {
                next == '\\' || next == 'n' || next == 't' -> index += 2
                next == 'u' && text.length >= index + 6 &&
                    text.substring(index + 2, index + 6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                    index += 6
                else -> {
                    found += text.substring(index, minOf(text.length, index + 2))
                    index++
                }
            }
        }
        return found
    }

    @Test
    fun `the string files are found`() {
        val folders = stringFiles().map { it.parentFile.name }

        assertTrue("values" in folders && "values-en" in folders, "string files found: $folders")
    }

    @Test
    fun `no text holds an escape that Compose resources would show as it is`() {
        val offenders = stringFiles()
            .flatMap { file -> readTexts(file).entries }
            .mapNotNull { (key, text) -> unsupportedEscapes(text).takeIf { it.isNotEmpty() }?.let { "$key $it" } }

        assertTrue(
            offenders.isEmpty(),
            "escapes left as they are by the plugin (write the character itself): $offenders",
        )
    }

    @Test
    fun `the check accepts the escapes the plugin handles and refuses the others`() {
        assertTrue(unsupportedEscapes("""a\nb\tcéd\\e""").isEmpty())
        assertTrue(unsupportedEscapes("""L\'application""") == listOf("""\'"""))
        assertTrue(unsupportedEscapes("""dit \"oui\"""").size == 2)
        assertTrue(unsupportedEscapes("""\u00g9""") == listOf("""\u"""))
        assertTrue(unsupportedEscapes("""fin\""") == listOf("""\"""))
    }
}
