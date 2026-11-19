// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

object VectorClockCodec {
    /** Canonical JSON representation of an empty clock. Used as DEFAULT in DB columns. */
    val EMPTY_JSON: String = json.encodeToString(VectorClock.serializer(), VectorClock.EMPTY)

    fun encode(clock: VectorClock): String = json.encodeToString(VectorClock.serializer(), clock)

    /**
     * SECURITY: a kotlinx.serialization error message embeds an excerpt of the JSON it failed on
     * (here the device ids of the clock). The failure is rethrown as a [SerializationException]
     * carrying only the cause's class name, with no cause, so no caller can log the excerpt.
     */
    fun decode(raw: String): VectorClock =
        when {
            raw.isBlank() || raw == "{}" -> VectorClock.EMPTY
            else -> try {
                json.decodeFromString(VectorClock.serializer(), raw)
            } catch (e: Exception) {
                throw SerializationException("Vector clock decoding failed (${e.safeTypeName()})")
            }
        }
}
