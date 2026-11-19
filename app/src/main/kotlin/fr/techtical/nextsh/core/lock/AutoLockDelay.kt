// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.lock

/**
 * How long NextSH may stay out of the foreground before it locks itself again (see [AppLock]).
 *
 * [minutes] is what the settings store: the values are part of the stored format, so an entry is
 * never renumbered. A stored value that names no entry (an older or a newer version wrote it, or the
 * file is damaged) reads as [DEFAULT], not as the shortest or the longest delay.
 */
enum class AutoLockDelay(val minutes: Int) {
    /** Locks as soon as the app has left the screen. */
    IMMEDIATELY(0),
    ONE_MINUTE(1),
    FIVE_MINUTES(5),
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30);

    val millis: Long get() = minutes * 60_000L

    companion object {
        val DEFAULT = FIVE_MINUTES

        fun fromMinutes(minutes: Int?): AutoLockDelay =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}
