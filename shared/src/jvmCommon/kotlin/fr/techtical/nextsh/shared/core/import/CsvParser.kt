// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

/**
 * Minimal RFC-4180-style CSV parser, hand-rolled to avoid a new dependency in the
 * `:shared` `jvmCommon` source set (which must stay free of Android SDK deps and
 * carries no CSV library).
 *
 * Handles:
 *  - comma-separated fields, one record per line;
 *  - double-quoted fields that may contain commas, CR/LF, and escaped quotes (`""`);
 *  - mixed quoted / unquoted fields on the same row;
 *  - CRLF, LF, and bare-CR line endings;
 *  - a trailing newline (does not emit a spurious empty final record);
 *  - a leading UTF-8 BOM on the first field.
 *
 * It is deliberately permissive: stray characters after a closing quote are
 * appended rather than rejected, so a slightly malformed export still parses.
 */
internal object CsvParser {

    private const val QUOTE = '"'
    private const val DELIMITER = ','

    /** Parses [content] into a list of records, each a list of field strings. */
    fun parse(content: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false // distinguishes a real empty cell from a blank line
        var i = 0
        val n = content.length

        fun endField() {
            fields.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }

        fun endRecord() {
            endField()
            // Drop a record that is a single empty field produced by a blank line.
            if (!(fields.size == 1 && fields[0].isEmpty())) {
                records.add(fields)
            }
            fields = ArrayList()
        }

        while (i < n) {
            val c = content[i]
            if (inQuotes) {
                when (c) {
                    QUOTE -> {
                        // Lookahead: doubled quote → literal quote, else close quote.
                        if (i + 1 < n && content[i + 1] == QUOTE) {
                            field.append(QUOTE)
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    QUOTE -> {
                        inQuotes = true
                        fieldStarted = true
                    }
                    DELIMITER -> endField()
                    '\r' -> {
                        // Treat CRLF and bare CR identically.
                        if (i + 1 < n && content[i + 1] == '\n') i++
                        endRecord()
                    }
                    '\n' -> endRecord()
                    else -> {
                        field.append(c)
                        fieldStarted = true
                    }
                }
            }
            i++
        }

        // Flush trailing field/record if the file did not end on a newline.
        if (field.isNotEmpty() || fields.isNotEmpty() || fieldStarted) {
            endRecord()
        }

        return records
    }
}
