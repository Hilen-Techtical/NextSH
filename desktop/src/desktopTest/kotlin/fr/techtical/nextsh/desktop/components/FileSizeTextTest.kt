// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSizeTextTest {

    @Test
    fun `bytes keep their count`() {
        assertEquals(FileSizeUnit.BYTES to "1023", fileSizeParts(1023, Locale.US))
    }

    @Test
    fun `the decimal separator follows the given locale and not the JVM one`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(FileSizeUnit.KB to "1.5", fileSizeParts(1536, Locale.US))
            assertEquals(FileSizeUnit.KB to "1,5", fileSizeParts(1536, Locale.FRANCE))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `units step up at 1024 and the gigabytes use two decimals`() {
        assertEquals(FileSizeUnit.MB to "1.0", fileSizeParts(1024L * 1024, Locale.US))
        assertEquals(FileSizeUnit.GB to "2.50", fileSizeParts(2560L * 1024 * 1024, Locale.US))
    }
}
