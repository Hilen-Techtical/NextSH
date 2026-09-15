// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
