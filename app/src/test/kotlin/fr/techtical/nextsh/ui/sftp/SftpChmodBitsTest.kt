// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SftpChmodBitsTest {

    @Test
    fun `setting a bit adds it`() {
        assertEquals(0b100_000_000, withPermissionBit(0, 0b100_000_000, on = true))
        assertEquals(0b111_101_101, withPermissionBit(0b111_101_100, 0b000_000_001, on = true))
    }

    @Test
    fun `clearing a bit removes only it`() {
        assertEquals(0b111_101_100, withPermissionBit(0b111_101_101, 0b000_000_001, on = false))
    }

    @Test
    fun `setting a bit already set or clearing a bit already clear changes nothing`() {
        assertEquals(0b110_100_100, withPermissionBit(0b110_100_100, 0b100_000_000, on = true))
        assertEquals(0b110_100_100, withPermissionBit(0b110_100_100, 0b000_000_001, on = false))
    }

    @Test
    fun `bits outside the nine permission bits are dropped`() {
        // setuid, setgid, sticky and the file type are not offered by the dialog.
        assertEquals(0b110_100_100, withPermissionBit(0b111_000_110_100_100, 0b000_000_000, on = true))
        assertEquals(0b110_100_100, withPermissionBit(0b110_100_100, 0b1_000_000_000, on = true))
    }

    @Test
    fun `the three triplets edit independently`() {
        var bits = 0
        bits = withPermissionBit(bits, 0b100_000_000, on = true)
        bits = withPermissionBit(bits, 0b000_010_000, on = true)
        bits = withPermissionBit(bits, 0b000_000_001, on = true)

        assertEquals(0b100_010_001, bits)
    }
}
