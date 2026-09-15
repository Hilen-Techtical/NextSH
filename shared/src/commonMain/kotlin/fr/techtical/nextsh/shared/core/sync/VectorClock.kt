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

    companion object {
        val EMPTY = VectorClock(emptyMap())
    }
}
