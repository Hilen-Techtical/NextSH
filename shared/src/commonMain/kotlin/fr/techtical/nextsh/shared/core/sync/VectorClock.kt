// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

@Serializable
data class VectorClock(val entries: Map<String, Long>) {

    /** Increments the counter for [deviceId]; value = max(current + 1, nowMs). */
    fun tick(deviceId: String, nowMs: Long): VectorClock {
        val current = entries[deviceId] ?: 0L
        return copy(entries = entries + (deviceId to maxOf(current + 1, nowMs)))
    }

    /** True if this clock is strictly greater than [other] on at least one key, and >= on all. */
    fun dominates(other: VectorClock): Boolean {
        val allKeys = entries.keys + other.entries.keys
        var hasStrictlyGreater = false
        for (k in allKeys) {
            val a = entries[k] ?: 0L
            val b = other.entries[k] ?: 0L
            if (a < b) return false
            if (a > b) hasStrictlyGreater = true
        }
        return hasStrictlyGreater
    }

    /** True if neither clock dominates the other and they are not equal. */
    fun concurrentWith(other: VectorClock): Boolean =
        this != other && !dominates(other) && !other.dominates(this)

    /** Per-key max of both clocks: used to collapse a "same-value concurrent write" into a single clock. */
    fun mergedWith(other: VectorClock): VectorClock {
        val allKeys = entries.keys + other.entries.keys
        val merged = allKeys.associateWith { key ->
            maxOf(entries[key] ?: 0L, other.entries[key] ?: 0L)
        }
        return VectorClock(merged)
    }

    /**
     * Clock of a version that replaces both this one and [other]: [mergedWith] then [tick]
     * of [deviceId]. The result strictly dominates both clocks, whether they were
     * concurrent or one already dominated the other.
     *
     * The tick is mandatory. A plain merge equals the dominating clock when one side
     * already dominates, and two devices resolving the same conflict with a plain merge
     * would end on EQUAL clocks holding DIFFERENT payloads. `CrdtEngine.merge` treats
     * equal clocks as Clean(local), so each side would keep its own payload forever.
     * The tick adds a counter that only [deviceId] owns, so the peer applies the version
     * as a clean update and two simultaneous resolutions stay concurrent (a visible
     * conflict instead of a silent divergence).
     */
    fun supersede(other: VectorClock, deviceId: String, nowMs: Long): VectorClock =
        mergedWith(other).tick(deviceId, nowMs)

    companion object {
        val EMPTY = VectorClock(emptyMap())
    }
}
