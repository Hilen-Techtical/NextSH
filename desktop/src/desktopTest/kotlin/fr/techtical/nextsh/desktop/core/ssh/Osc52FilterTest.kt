// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [Osc52Filter]: remote clipboard writes are extracted from the
 * terminal stream, everything else passes through byte for byte.
 *
 * Every scenario goes through [feed], which feeds the chunks with non-zero
 * offsets on both buffers and an output sized at exactly `heldCount + length`,
 * so an out-of-range read or write fails the test.
 */
class Osc52FilterTest {

    private data class Result(val output: String, val writes: List<String>)

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun osc52(pc: String, text: String, terminator: String = BEL): String =
        "$ESC]52;$pc;${b64(text)}$terminator"

    private fun feed(vararg chunks: String): Result = feed(chunks.toList())

    private fun feed(chunks: List<String>, sink: (String) -> Unit = {}): Result {
        val writes = mutableListOf<String>()
        val filter = Osc52Filter { text ->
            writes += text
            sink(text)
        }
        val output = StringBuilder()
        for (chunk in chunks) {
            val heldBefore = filter.heldCount
            // 3 sentinel characters around the input range, 2 before the output
            // range and 3 after the maximum output size.
            val input = CharArray(chunk.length + 6) { 'X' }
            chunk.toCharArray(input, 3)
            val out = CharArray(2 + heldBefore + chunk.length + 3) { '#' }
            val n = filter.filter(input, 3, chunk.length, out, 2)
            assertTrue(n <= heldBefore + chunk.length, "output larger than heldCount + length")
            for (k in 2 + n until out.size) {
                assertEquals('#', out[k], "filter wrote past its output range")
            }
            output.append(out, 2, n)
        }
        return Result(output.toString(), writes)
    }

    /** Runs [input] unsplit, split at every position, per character, and at every pair of positions. */
    private fun assertSameWhateverTheSplit(input: String, expectedOutput: String, expectedWrites: List<String>) {
        val whole = feed(input)
        assertEquals(expectedOutput, whole.output, "unsplit output")
        assertEquals(expectedWrites, whole.writes, "unsplit writes")

        for (cut in 0..input.length) {
            val r = feed(input.substring(0, cut), input.substring(cut))
            assertEquals(expectedOutput, r.output, "output when cut at $cut")
            assertEquals(expectedWrites, r.writes, "writes when cut at $cut")
        }

        val perChar = feed(input.map { it.toString() })
        assertEquals(expectedOutput, perChar.output, "output char by char")
        assertEquals(expectedWrites, perChar.writes, "writes char by char")

        for (a in 0..input.length) {
            for (b in a..input.length) {
                val r = feed(input.substring(0, a), input.substring(a, b), input.substring(b))
                assertEquals(expectedOutput, r.output, "output when cut at $a and $b")
                assertEquals(expectedWrites, r.writes, "writes when cut at $a and $b")
            }
        }
    }

    // ── Terminators ──────────────────────────────────────────────────────────

    @Test
    fun `BEL terminated sequence is stripped and its text is written`() {
        val r = feed("before" + osc52("c", "hello") + "after")
        assertEquals("beforeafter", r.output)
        assertEquals(listOf("hello"), r.writes)
    }

    @Test
    fun `ST terminated sequence is stripped and its text is written`() {
        val r = feed("before" + osc52("c", "hello", ST) + "after")
        assertEquals("beforeafter", r.output)
        assertEquals(listOf("hello"), r.writes)
    }

    @Test
    fun `sequence alone yields an empty output`() {
        val r = feed(osc52("c", "hello"))
        assertEquals("", r.output)
        assertEquals(listOf("hello"), r.writes)
    }

    @Test
    fun `ordinary text and other escape sequences pass through untouched`() {
        val text = "plain ${ESC}[31mred${ESC}[0m${ESC}[?1h${ESC}= done\r\n"
        val r = feed(text)
        assertEquals(text, r.output)
        assertEquals(emptyList(), r.writes)
    }

    // ── Splitting between calls ──────────────────────────────────────────────

    @Test
    fun `result is identical wherever a BEL terminated sequence is split`() {
        val input = "pre${ESC}[1m" + osc52("c", "héllo") + "mid" + osc52("p", "x") + "${ESC}]5x" + "post"
        assertSameWhateverTheSplit(
            input,
            expectedOutput = "pre${ESC}[1mmid${ESC}]5xpost",
            expectedWrites = listOf("héllo", "x"),
        )
    }

    @Test
    fun `result is identical wherever an ST terminated sequence is split`() {
        val input = "a" + osc52("s0", "one", ST) + "b" + osc52("", "two", ST) + "c"
        assertSameWhateverTheSplit(
            input,
            expectedOutput = "abc",
            expectedWrites = listOf("one", "two"),
        )
    }

    @Test
    fun `ESC at the end of a chunk inside the payload followed by a backslash terminates`() {
        val r = feed("x${ESC}]52;c;${b64("hello")}$ESC", "\\y")
        assertEquals("xy", r.output)
        assertEquals(listOf("hello"), r.writes)
    }

    @Test
    fun `heldCount reports the partial prefix and the pending ESC`() {
        val filter = Osc52Filter {}
        fun push(s: String): Int {
            val a = s.toCharArray()
            val out = CharArray(filter.heldCount + a.size)
            return filter.filter(a, 0, a.size, out, 0)
        }
        assertEquals(0, filter.heldCount)
        assertEquals(3, push("abc$ESC"))
        assertEquals(1, filter.heldCount)
        assertEquals(0, push("]52"))
        assertEquals(4, filter.heldCount)
        // The longest held prefix is exactly what SshTtyConnector's spill
        // buffer is sized for.
        assertEquals(Osc52Filter.MAX_HELD_CHARS, filter.heldCount)
        assertEquals(0, push(";c;QUFB"))
        assertEquals(0, filter.heldCount)
        assertEquals(0, push(ESC))
        assertEquals(1, filter.heldCount)
        assertEquals(0, push("\\"))
        assertEquals(0, filter.heldCount)
    }

    // ── Read requests and malformed payloads ─────────────────────────────────

    @Test
    fun `read request is ignored without any write or output`() {
        val bel = feed("a${ESC}]52;c;?${BEL}b")
        assertEquals("ab", bel.output)
        assertEquals(emptyList(), bel.writes)

        val st = feed("a${ESC}]52;;?${ST}b")
        assertEquals("ab", st.output)
        assertEquals(emptyList(), st.writes)
    }

    @Test
    fun `invalid base64 is ignored`() {
        for (pd in listOf("A", "QUFBQ", "QUFBQUFBQ")) {
            val r = feed("a${ESC}]52;c;$pd${BEL}b")
            assertEquals("ab", r.output, "output for $pd")
            assertEquals(emptyList(), r.writes, "writes for $pd")
        }
    }

    @Test
    fun `empty Pd is ignored`() {
        val r = feed("a${ESC}]52;c;${BEL}b${ESC}]52;;${ST}c")
        assertEquals("abc", r.output)
        assertEquals(emptyList(), r.writes)
    }

    @Test
    fun `payload without a separator is ignored`() {
        val r = feed("a${ESC}]52;c${BEL}b")
        assertEquals("ab", r.output)
        assertEquals(emptyList(), r.writes)
    }

    @Test
    fun `any Pc is accepted`() {
        for (pc in listOf("", "c", "p", "s0", "cps", "01234567")) {
            val r = feed("[" + osc52(pc, "hello") + "]")
            assertEquals("[]", r.output, "output for Pc='$pc'")
            assertEquals(listOf("hello"), r.writes, "writes for Pc='$pc'")
        }
    }

    @Test
    fun `line breaks inside the base64 are tolerated`() {
        val encoded = b64("foobar")
        val wrapped = encoded.substring(0, 4) + "\r\n" + encoded.substring(4)
        val r = feed("${ESC}]52;c;$wrapped$BEL")
        assertEquals("", r.output)
        assertEquals(listOf("foobar"), r.writes)
    }

    @Test
    fun `UTF-8 text with accents and emoji round-trips`() {
        val text = "héllo wörld 😀 日本語\nligne 2"
        val r = feed("a" + osc52("c", text) + "b")
        assertEquals("ab", r.output)
        assertEquals(listOf(text), r.writes)
    }

    @Test
    fun `two sequences in the same chunk produce two writes in order`() {
        val r = feed("1" + osc52("c", "first") + "2" + osc52("c", "second", ST) + "3")
        assertEquals("123", r.output)
        assertEquals(listOf("first", "second"), r.writes)
    }

    // ── Payload cap ──────────────────────────────────────────────────────────

    private fun inChunks(s: String, size: Int): List<String> = s.chunked(size)

    @Test
    fun `payload above the cap is dropped and the stream resumes after the terminator`() {
        val big = "A".repeat(Osc52Filter.MAX_PAYLOAD_CHARS + 10)
        for (terminator in listOf(BEL, ST)) {
            val input = "head${ESC}]52;c;$big${terminator}tail"
            val whole = feed(input)
            assertEquals("headtail", whole.output)
            assertEquals(emptyList(), whole.writes)

            val chunked = feed(inChunks(input, 8192))
            assertEquals("headtail", chunked.output)
            assertEquals(emptyList(), chunked.writes)
        }
    }

    @Test
    fun `payload of exactly the cap is accepted and one more character is rejected`() {
        // Pc = "cps" (3) + ";" (1) + 262143 * 4 base64 characters = 1 Mi.
        val units = (Osc52Filter.MAX_PAYLOAD_CHARS - 4) / 4
        val pd = "QUFB".repeat(units)
        val atCap = feed(inChunks("${ESC}]52;cps;$pd$BEL", 4096))
        assertEquals("", atCap.output)
        assertEquals(listOf("AAA".repeat(units)), atCap.writes)

        val overCap = feed(inChunks("${ESC}]52;cps;${pd}QUFB${BEL}tail", 4096))
        assertEquals("tail", overCap.output)
        assertEquals(emptyList(), overCap.writes)
    }

    @Test
    fun `a rejected sequence can be aborted by ESC and the next sequence still works`() {
        val big = "A".repeat(Osc52Filter.MAX_PAYLOAD_CHARS + 10)
        val r = feed(inChunks("${ESC}]52;c;$big${ESC}[0mtail" + osc52("c", "ok"), 8192))
        assertEquals("${ESC}[0mtail", r.output)
        assertEquals(listOf("ok"), r.writes)
    }

    @Test
    fun `a rejected sequence aborted by CAN swallows the CAN`() {
        val big = "A".repeat(Osc52Filter.MAX_PAYLOAD_CHARS + 10)
        val r = feed(inChunks("${ESC}]52;c;$big${CAN}tail", 8192))
        assertEquals("tail", r.output)
        assertEquals(emptyList(), r.writes)
    }

    @Test
    fun `ESC at the end of a chunk while rejecting is resolved by the next chunk`() {
        val big = "A".repeat(Osc52Filter.MAX_PAYLOAD_CHARS + 10)
        val st = feed("${ESC}]52;c;$big$ESC", "\\tail")
        assertEquals("tail", st.output)

        val abort = feed("${ESC}]52;c;$big$ESC", "[0mtail")
        assertEquals("${ESC}[0mtail", abort.output)
        assertEquals(emptyList(), abort.writes)
    }

    // ── Other sequences pass through ─────────────────────────────────────────

    @Test
    fun `other OSC sequences and look-alikes are transmitted identically`() {
        val samples = listOf(
            "${ESC}]0;window title$BEL",
            "${ESC}]2;other title$ST",
            "${ESC}]8;;https://example.com$ST link ${ESC}]8;;$ST",
            "${ESC}]10;?$BEL",
            "${ESC}]5;x$BEL",
            "${ESC}]520;c;${b64("hello")}$BEL",
            "${ESC}]52x",
            "${ESC}]52",
            "${ESC}]5$BEL",
            "${ESC}]${ESC}[0m",
            "${ESC}${ESC}${ESC}",
        )
        for (sample in samples) {
            val text = "a${sample}b"
            val whole = feed(text)
            assertEquals(text, whole.output, "whole: ${sample.replace(ESC, "<ESC>")}")
            assertEquals(emptyList(), whole.writes)
            val perChar = feed(text.map { it.toString() })
            assertEquals(text, perChar.output, "per char: ${sample.replace(ESC, "<ESC>")}")
            assertEquals(emptyList(), perChar.writes)
        }
    }

    @Test
    fun `held prefix followed by a mismatch is re-emitted verbatim and in order`() {
        val r = feed("abc${ESC}]5", "x def")
        assertEquals("abc${ESC}]5x def", r.output)

        val r2 = feed("abc${ESC}]52", "0;c;${b64("no")}$BEL!")
        assertEquals("abc${ESC}]520;c;${b64("no")}$BEL!", r2.output)
        assertEquals(emptyList(), r2.writes)

        val r3 = feed("abc$ESC", "[2Jdef")
        assertEquals("abc${ESC}[2Jdef", r3.output)
    }

    @Test
    fun `held ESC followed by a real sequence emits the ESC then writes`() {
        val r = feed("a$ESC", "${ESC}]52;c;${b64("foo")}${BEL}z")
        assertEquals("a${ESC}z", r.output)
        assertEquals(listOf("foo"), r.writes)
    }

    // ── Abort rules ──────────────────────────────────────────────────────────

    @Test
    fun `ESC followed by a bracket aborts the sequence and the CSI passes intact`() {
        val r = feed("${ESC}]52;c;${b64("hello").dropLast(2)}${ESC}[31mred${ESC}[0m")
        assertEquals("${ESC}[31mred${ESC}[0m", r.output)
        assertEquals(emptyList(), r.writes)
    }

    @Test
    fun `abort by ESC then a complete new sequence writes only the second`() {
        val r = feed("${ESC}]52;c;${b64("first")}$ESC" + osc52("c", "second") + "tail")
        // The aborting ESC is re-processed as ordinary stream, so it is
        // transmitted verbatim before the second (stripped) sequence.
        assertEquals("${ESC}tail", r.output)
        assertEquals(listOf("second"), r.writes)
    }

    @Test
    fun `abort rules hold whatever the split`() {
        val input = "${ESC}]52;c;${b64("first")}${ESC}[0m${ESC}]52;c;${b64("good")}${BEL}${ESC}]52;c;${b64("lost")}${CAN}end"
        assertSameWhateverTheSplit(
            input,
            expectedOutput = "${ESC}[0mend",
            expectedWrites = listOf("good"),
        )
    }

    @Test
    fun `CAN aborts without a write and is consumed`() {
        val r = feed("a${ESC}]52;c;${b64("hello")}${CAN}b")
        assertEquals("ab", r.output)
        assertEquals(emptyList(), r.writes)
    }

    @Test
    fun `SUB aborts without a write and is consumed`() {
        val r = feed("a${ESC}]52;c;${b64("hello")}${SUB}b")
        assertEquals("ab", r.output)
        assertEquals(emptyList(), r.writes)
    }

    // ── Callback robustness ──────────────────────────────────────────────────

    @Test
    fun `a throwing callback never breaks the stream`() {
        var calls = 0
        val r = feed(listOf("a" + osc52("c", "one") + "b" + osc52("c", "two") + "c")) {
            calls++
            if (calls == 1) throw IllegalStateException("clipboard unavailable")
        }
        assertEquals("abc", r.output)
        assertEquals(listOf("one", "two"), r.writes)
    }

    private companion object {
        const val ESC = "\u001b"
        const val BEL = "\u0007"
        const val CAN = "\u0018"
        const val SUB = "\u001a"
        const val ST = "\u001b\\"
    }
}
