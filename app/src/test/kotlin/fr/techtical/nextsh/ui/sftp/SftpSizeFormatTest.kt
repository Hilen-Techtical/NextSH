// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.StringResourceFile
import fr.techtical.nextsh.ui.SourceGuardSupport
import fr.techtical.nextsh.ui.components.dateLocale
import fr.techtical.nextsh.ui.components.formatAbsoluteDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The one rule that writes a size in the browser (the list and the preview of an unknown file):
 * base 1024, a number written the way the language of the user does, one decimal above the bytes,
 * and a unit that comes from the string resources.
 */
class SftpSizeFormatTest {

    private fun units(file: StringResourceFile) = SizeUnits(
        bytes = StringResourceFile.unescape(file.strings.getValue("sftp_size_bytes")),
        kilobytes = StringResourceFile.unescape(file.strings.getValue("sftp_size_kilobytes")),
        megabytes = StringResourceFile.unescape(file.strings.getValue("sftp_size_megabytes")),
        gigabytes = StringResourceFile.unescape(file.strings.getValue("sftp_size_gigabytes")),
    )

    private val french = units(StringResourceFile("values/strings.xml"))
    private val english = units(StringResourceFile("values-en/strings.xml"))

    private fun fr(bytes: Long) = formatFileSize(bytes, french, Locale.FRANCE)
    private fun en(bytes: Long) = formatFileSize(bytes, english, Locale.US)

    private val kb = 1024L
    private val mb = 1024L * 1024
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `French writes a comma and the French units`() {
        assertEquals("1,5 Ko", fr(kb + kb / 2))
        assertEquals("2,5 Mo", fr(2 * mb + mb / 2))
        assertEquals("1,5 Go", fr(gb + gb / 2))
    }

    @Test
    fun `English writes a point and the English units`() {
        assertEquals("1.5 KB", en(kb + kb / 2))
        assertEquals("2.5 MB", en(2 * mb + mb / 2))
        assertEquals("1.5 GB", en(gb + gb / 2))
    }

    @Test
    fun `the bounds of each unit`() {
        assertEquals("0 o", fr(0))
        assertEquals("1 o", fr(1))
        assertEquals("1023 o", fr(1023))
        assertEquals("1,0 Ko", fr(1024))
        assertEquals("1,0 Mo", fr(1_048_576))
        assertEquals("1,0 Go", fr(1_073_741_824))
        assertEquals("1023 B", en(1023))
        assertEquals("1.0 KB", en(1024))
        assertEquals("1.0 MB", en(1_048_576))
        assertEquals("1.0 GB", en(1_073_741_824))
    }

    @Test
    fun `gigabytes have one decimal like the other units, the list and the preview used to differ`() {
        // The list wrote two decimals for the gigabytes and the preview one: there is one rule now.
        assertEquals("1,3 Go", fr(gb + 300L * mb))
        assertEquals("1.3 GB", en(gb + 300L * mb))
        assertEquals("5,0 Go", fr(5 * gb))
    }

    @Test
    fun `a size that rounds up to 1024 moves to the next unit`() {
        assertEquals("1,0 Mo", fr(1_048_575))
        assertEquals("1,0 Go", fr(1_073_741_823))
        assertEquals("1.0 MB", en(1_048_575))
        // Just under the threshold of the rounding: still the unit below.
        assertEquals("1023,9 Ko", fr(1_048_524))
    }

    @Test
    fun `the bytes have no grouping separator`() {
        assertEquals("1000 o", fr(1000))
        assertEquals("1000 B", en(1000))
    }

    @Test
    fun `a size above the last unit stays in gigabytes`() {
        assertEquals("1024,0 Go", fr(1024 * gb))
        assertEquals("2048.0 GB", en(2048 * gb))
    }

    @Test
    fun `a negative size is written as zero`() {
        assertEquals("0 o", fr(-5))
        assertEquals("0 B", en(-1))
    }

    @Test
    fun `the number follows the locale it is given, not the one of the machine`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1,5 Ko", fr(1536))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the templates of the units take the number once, in both languages`() {
        listOf(french, english).forEach { u ->
            listOf(u.bytes, u.kilobytes, u.megabytes, u.gigabytes).forEach { template ->
                // Split in two parts: the placeholder is there once.
                assertEquals(2, template.split("%1\$s").size, template)
                assertFalse(template.any { it.code == 0x2014 || it.code == 0x2013 }, template)
            }
        }
    }

    @Test
    fun `the units are translated`() {
        assertNotEquals(french.bytes, english.bytes)
        assertNotEquals(french.kilobytes, english.kilobytes)
        assertNotEquals(french.megabytes, english.megabytes)
        assertNotEquals(french.gigabytes, english.gigabytes)
        assertEquals("%1\$s o", french.bytes)
        assertEquals("%1\$s B", english.bytes)
    }

    // ── The sources: one rule only ───────────────────────────────────────────────

    /** The source of a file of the browser, comments blanked. A miss fails, wherever the tests run. */
    private fun sourceOf(fileName: String): String =
        blankComments(SourceGuardSupport.locate("src/main/kotlin/fr/techtical/nextsh/ui/sftp/$fileName").readText())

    @Test
    fun `the list, the preview and the view model write a size with the one function`() {
        listOf("SftpFileItem.kt", "SftpPreviewOverlay.kt", "SftpBrowserViewModel.kt", "SftpBrowserScreen.kt").forEach { file ->
            val code = sourceOf(file)
            assertTrue(code.length > 1_000, "$file: the guard reads nothing")
            // No unit written by hand, no decimal format of a size beside the shared function.
            assertFalse(Regex("""["']\s*(?:KB|MB|GB)\b""").containsMatchIn(code), "$file writes a unit by hand")
            assertFalse(Regex("""%\.\d+f""").containsMatchIn(code), "$file formats a number of its own")
            assertFalse(Regex("""fun format(?:File)?Size\(""").containsMatchIn(code), "$file defines a formatter of its own")
        }
    }

    @Test
    fun `a phone in German with French texts writes sizes and dates in French`() {
        // The phone is German, the texts loaded are the French ones (fallback on values/).
        val locale = dateLocale(Locale.GERMANY, "fr")
        assertEquals("fr", locale.language)
        assertEquals("1,5 Ko", formatFileSize(1536, french, locale))
        assertEquals("5 mars 2027", formatAbsoluteDate(1_804_248_000_000L, locale, ZoneOffset.UTC))
        // Same phone, English texts: English sizes and dates.
        val en = dateLocale(Locale.GERMANY, "en")
        assertEquals("1.5 KB", formatFileSize(1536, english, en))
        assertEquals("Mar 5, 2027", formatAbsoluteDate(1_804_248_000_000L, en, ZoneOffset.UTC))
    }

    @Test
    fun `the list and the sizes take the locale of the loaded texts, not the one of the configuration`() {
        val item = sourceOf("SftpFileItem.kt")
        val size = sourceOf("SftpSizeFormat.kt")
        assertTrue(item.contains("resourceLocale()"), "the date does not use the locale of the loaded texts")
        assertTrue(size.contains("resourceLocale()"))
        assertFalse(item.contains("LocalConfiguration"), "the date reads the configuration again")
        // The configuration is read once, inside resourceLocale, to be handed to dateLocale.
        assertEquals(1, Regex("""LocalConfiguration\.current""").findAll(size).count())
    }

    @Test
    fun `the date of the list follows the language, it is not a pattern fixed at the load of the file`() {
        val code = sourceOf("SftpFileItem.kt")
        assertFalse(code.contains("ofPattern"), "a fixed pattern is back")
        assertTrue(code.contains("ofLocalizedDate(FormatStyle.MEDIUM)"))
        assertFalse(Regex("""^private val \w*FORMATTER""", RegexOption.MULTILINE).containsMatchIn(code), "a top-level formatter keeps the first locale")
    }
}
