// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorClockCodecTest {

    @Test
    fun `encode decode round trip`() {
        val clock = VectorClock(mapOf("device-a" to 1000L, "device-b" to 2000L))
        val encoded = VectorClockCodec.encode(clock)
        val decoded = VectorClockCodec.decode(encoded)
        assertEquals(clock, decoded)
    }

    @Test
    fun `decode accepts empty string as empty clock`() {
        val decoded = VectorClockCodec.decode("")
        assertEquals(VectorClock.EMPTY, decoded)
    }

    // The marker stands for a device id inside a clock that cannot be decoded.
    private val malformedClocks = mapOf(
        "truncated json" to """{"entries":{"$LEAK_MARKER":5""",
        "wrong value type" to """{"entries":{"$LEAK_MARKER":"not-a-long"}}""",
        "not json at all" to "$LEAK_MARKER is not a clock",
    )

    // kotlinx also throws a plain IllegalArgumentException (not a SerializationException) for some
    // malformed maps, so the codec must not rely on catching SerializationException only.
    private val plainIllegalArgumentClock = """{"entries":{"$LEAK_MARKER":"""

    @Test
    fun `malformed clock json premise - raw kotlinx error does expose the payload`() {
        // Guards the test below against passing vacuously if kotlinx stops quoting the input.
        malformedClocks.forEach { (name, text) ->
            val raw = assertFailsWith<IllegalArgumentException>(name) {
                Json.decodeFromString(VectorClock.serializer(), text)
            }
            assertTrue(raw.message.orEmpty().contains(LEAK_MARKER), "$name: premise broken, raw message: ${raw.message}")
        }
    }

    @Test
    fun `decode of malformed clock json throws SerializationException that does not leak the payload`() {
        (malformedClocks + ("key without value" to plainIllegalArgumentClock)).forEach { (name, text) ->
            val e = assertFailsWith<SerializationException>(name) { VectorClockCodec.decode(text) }
            assertNoPayloadLeak(e)
            assertTrue(e.message.orEmpty().startsWith("Vector clock decoding failed ("), "$name: ${e.message}")
            assertNull(e.cause, "$name: the failure must not keep a cause")
        }
    }
}
