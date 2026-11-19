// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.lock

import fr.techtical.nextsh.data.preferences.SettingsDataStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The setting of the automatic lock: minutes 0, 1, 5, 15 and 30, five by default, and a value that
 * is none of them reads as the default, not as the shortest or the longest delay.
 */
class AutoLockDelayTest {

    @Test
    fun `the delays are 0, 1, 5, 15 and 30 minutes, in that order`() {
        assertEquals(listOf(0, 1, 5, 15, 30), AutoLockDelay.entries.map { it.minutes })
    }

    @Test
    fun `the default is five minutes`() {
        assertEquals(AutoLockDelay.FIVE_MINUTES, AutoLockDelay.DEFAULT)
        assertEquals(5, AutoLockDelay.DEFAULT.minutes)
        assertEquals(5 * 60_000L, AutoLockDelay.DEFAULT.millis)
    }

    @Test
    fun `a stored value that names a delay reads as that delay`() {
        for (delay in AutoLockDelay.entries) {
            assertEquals(delay, AutoLockDelay.fromMinutes(delay.minutes), "${delay.minutes}")
        }
    }

    @Test
    fun `a value that is none of the delays falls back on five minutes`() {
        for (unknown in listOf(-1, 2, 3, 10, 29, 31, 60, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(AutoLockDelay.FIVE_MINUTES, AutoLockDelay.fromMinutes(unknown), "$unknown")
        }
    }

    @Test
    fun `nothing stored reads as five minutes`() {
        assertEquals(AutoLockDelay.FIVE_MINUTES, AutoLockDelay.fromMinutes(null))
    }

    @Test
    fun `the delay is in milliseconds, for the clock of the lock`() {
        assertEquals(0L, AutoLockDelay.IMMEDIATELY.millis)
        assertEquals(60_000L, AutoLockDelay.ONE_MINUTE.millis)
        assertEquals(30 * 60_000L, AutoLockDelay.THIRTY_MINUTES.millis)
    }

    @Test
    fun `the settings start with the default delay`() {
        assertEquals(AutoLockDelay.FIVE_MINUTES, SettingsDataStore.Settings().autoLockDelay)
    }

    @Test
    fun `the delay of the settings can be chosen`() {
        val settings = SettingsDataStore.Settings(autoLockDelay = AutoLockDelay.IMMEDIATELY)

        assertEquals(AutoLockDelay.IMMEDIATELY, settings.autoLockDelay)
        assertEquals(AutoLockDelay.THIRTY_MINUTES, settings.copy(autoLockDelay = AutoLockDelay.THIRTY_MINUTES).autoLockDelay)
    }
}
