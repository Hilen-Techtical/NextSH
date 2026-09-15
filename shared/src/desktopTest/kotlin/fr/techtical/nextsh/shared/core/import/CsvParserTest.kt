// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvParserTest {

    @Test
    fun `parses simple comma separated rows`() {
        val rows = CsvParser.parse("a,b,c\n1,2,3\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test
    fun `handles quoted field with embedded comma`() {
        val rows = CsvParser.parse("\"a,b\",c\n")
        assertEquals(listOf("a,b", "c"), rows[0])
    }

    @Test
    fun `handles escaped double quotes inside quoted field`() {
        val rows = CsvParser.parse("\"say \"\"hi\"\"\",x\n")
        assertEquals(listOf("say \"hi\"", "x"), rows[0])
    }

    @Test
    fun `handles embedded newline inside quoted field`() {
        val rows = CsvParser.parse("\"line1\nline2\",b\n")
        assertEquals(1, rows.size)
        assertEquals(listOf("line1\nline2", "b"), rows[0])
    }

    @Test
    fun `handles crlf and bare cr line endings`() {
        val rows = CsvParser.parse("a,b\r\nc,d\re,f")
        assertEquals(3, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("c", "d"), rows[1])
        assertEquals(listOf("e", "f"), rows[2])
    }

    @Test
    fun `preserves empty cells`() {
        val rows = CsvParser.parse("a,,c\n")
        assertEquals(listOf("a", "", "c"), rows[0])
    }

    @Test
    fun `drops blank lines but keeps single-empty-quoted cell`() {
        val rows = CsvParser.parse("a,b\n\n\"\",x\n")
        assertEquals(2, rows.size, "the truly blank line is dropped")
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("", "x"), rows[1])
    }

    @Test
    fun `flushes final record without trailing newline`() {
        val rows = CsvParser.parse("a,b,c")
        assertEquals(1, rows.size)
        assertEquals(listOf("a", "b", "c"), rows[0])
    }
}
