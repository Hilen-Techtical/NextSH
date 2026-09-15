// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

object VectorClockCodec {
    /** Canonical JSON representation of an empty clock. Used as DEFAULT in DB columns. */
    val EMPTY_JSON: String = json.encodeToString(VectorClock.serializer(), VectorClock.EMPTY)

    fun encode(clock: VectorClock): String = json.encodeToString(VectorClock.serializer(), clock)

    fun decode(raw: String): VectorClock =
        when {
            raw.isBlank() || raw == "{}" -> VectorClock.EMPTY
            else -> json.decodeFromString(VectorClock.serializer(), raw)
        }
}
