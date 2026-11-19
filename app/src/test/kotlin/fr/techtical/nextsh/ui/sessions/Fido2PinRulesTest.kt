// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.auth.PinPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * What a PIN has to be for the dialog to let it through. The key library throws before sending a
 * PIN of under 4 characters or over 63 bytes (UTF-8), and that used to end the connection as an
 * unexpected error. All values here are test values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fido2PinRulesTest {

    private fun accepts(pin: String) = isFidoPinAcceptable(pin.toList())

    // ── The limits ────────────────────────────────────────────────────────────

    @Test
    fun `a PIN of 3 characters is not accepted`() {
        assertFalse(accepts("abc"))
        assertFalse(accepts("a"))
        assertFalse(accepts(""))
    }

    @Test
    fun `4 characters are enough`() {
        assertTrue(accepts("abcd"))
    }

    @Test
    fun `63 bytes are accepted and 64 are not`() {
        assertTrue(accepts("a".repeat(63)))
        assertFalse(accepts("a".repeat(64)))
    }

    @Test
    fun `the length is counted in bytes, not in characters`() {
        // 31 two-byte characters make 62 bytes, 32 make 64.
        assertTrue(accepts("é".repeat(31)))
        assertFalse(accepts("é".repeat(32)))
        // 21 three-byte characters make exactly 63 bytes.
        assertTrue(accepts("€".repeat(21)))
        assertFalse(accepts("€".repeat(22)))
    }

    @Test
    fun `a surrogate pair is one character of 4 bytes`() {
        val emoji = "😀" // 2 chars, 4 bytes in UTF-8
        assertTrue(accepts(emoji.repeat(15))) // 30 chars, 60 bytes
        assertFalse(accepts(emoji.repeat(16))) // 32 chars, 64 bytes
        assertEquals(4, fidoPinUtf8Length(emoji.toList()))
    }

    @Test
    fun `a lone surrogate counts for the byte the encoder replaces it with`() {
        assertEquals(1, fidoPinUtf8Length(listOf('\uD83D')))
        assertEquals(1, fidoPinUtf8Length(listOf('\uDE00')))
        // A low surrogate before a high one is not a pair.
        assertEquals(2, fidoPinUtf8Length(listOf('\uDE00', '\uD83D')))
    }

    @Test
    fun `the length is the one the library will measure`() {
        // ClientPin.preparePin encodes the characters with Charset.encode: the count here must be
        // that one for any mix of characters, or the limit would be wrong for some PINs.
        val random = Random(20261019)
        val pool = listOf('a', 'Z', '7', '~', 'é', 'ß', '€', '漢', '\uD83D', '\uDE00', 'ࠀ', '߿')
        repeat(500) {
            val chars = List(random.nextInt(0, 40)) { pool[random.nextInt(pool.size)] }
            val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars.toCharArray()))
            assertEquals(encoded.limit() - encoded.position(), fidoPinUtf8Length(chars), "for $chars")
        }
    }

    // ── A PIN nobody takes is wiped ───────────────────────────────────────────

    private fun request(deferred: CompletableDeferred<CharArray?> = CompletableDeferred()) =
        PinRequest(PinPrompt(triesRemaining = 8), deferred) to deferred

    @Test
    fun `a PIN given to a waiting request is handed over as it is`() {
        val (request, deferred) = request()
        val pin = "right".toCharArray()

        request.answer(pin)

        assertSame(pin, deferred.getCompleted(), "the authenticator owns the array now")
        assertEquals("right", String(pin), "and zeroes it itself")
    }

    @Test
    fun `a PIN given to a request that is already over is wiped`() {
        val (request, deferred) = request()
        deferred.cancel() // the prompt ended while the dialog was up
        val pin = "right".toCharArray()

        request.answer(pin)

        assertTrue(pin.all { it == '\u0000' }, "a PIN nobody takes must not stay in memory")
    }

    @Test
    fun `a second answer is wiped`() {
        val (request, _) = request()
        request.answer("first".toCharArray())
        val second = "second".toCharArray()

        request.answer(second)

        assertTrue(second.all { it == '\u0000' })
    }

    @Test
    fun `a cancellation is handed over as null`() {
        val (request, deferred) = request()

        request.answer(null)

        assertNull(deferred.getCompleted())
    }

    // ── Waiting for the PIN ───────────────────────────────────────────────────

    @Test
    fun `a confirmed PIN gives the prompt its time again before it is returned`() = runTest {
        val (request, _) = request()
        val order = mutableListOf<String>()
        val waiting = async { request.await(onConfirmed = { order += "extended" }).also { order += "returned" } }
        runCurrent()
        assertTrue(order.isEmpty(), "nothing before the user answers")

        request.answer("right".toCharArray())
        runCurrent()

        assertEquals("right", String(waiting.await()!!))
        assertEquals(listOf("extended", "returned"), order)
    }

    @Test
    fun `a cancelled dialog does not give the prompt more time`() = runTest {
        val (request, _) = request()
        var extended = 0
        val waiting = async { request.await(onConfirmed = { extended++ }) }
        runCurrent()

        request.answer(null)
        runCurrent()

        assertNull(waiting.await())
        assertEquals(0, extended)
    }

    @Test
    fun `a PIN confirmed as the prompt ends is wiped`() = runTest {
        val (request, _) = request()
        val waiting = async { request.await(onConfirmed = {}) }
        runCurrent()
        val pin = "right".toCharArray()

        // Confirmed and cancelled in the same instant: the wait is cancelled with a PIN in hand.
        request.answer(pin)
        waiting.cancel()
        runCurrent()

        assertTrue(pin.all { it == '\u0000' }, "a PIN with no taker must not stay in memory")
    }
}
