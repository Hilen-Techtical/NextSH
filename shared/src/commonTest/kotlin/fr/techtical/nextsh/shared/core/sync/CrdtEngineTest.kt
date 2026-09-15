// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Serializable
data class StubPayload(val value: String)

class CrdtEngineTest {

    private fun entry(
        id: String = "e1",
        value: String? = "v",
        clock: VectorClock = VectorClock.EMPTY,
        deleted: Boolean = false,
        deletedAt: Long? = null,
        updatedAt: Long = 0L,
    ) = SyncEntry(
        id = id,
        payload = if (value != null) StubPayload(value) else null,
        clock = clock,
        deleted = deleted,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `merge clean when only local exists`() {
        val local = entry(value = "local")
        val result = CrdtEngine.merge(local, null)
        assertIs<MergeResult.Clean<StubPayload>>(result)
        assertEquals(local, result.entry)
    }

    @Test
    fun `merge clean when local clock dominates remote`() {
        val base = VectorClock.EMPTY
        val remoteEntry = entry(clock = base.tick("A", 100))
        val localEntry = entry(
            clock = base.tick("A", 100).tick("A", 200),
            value = "newer",
        )
        val result = CrdtEngine.merge(localEntry, remoteEntry)
        assertIs<MergeResult.Clean<StubPayload>>(result)
        assertEquals(localEntry, result.entry)
    }

    @Test
    fun `merge detects conflict when clocks are concurrent`() {
        val base = VectorClock.EMPTY
        val localEntry = entry(clock = base.tick("A", 100), value = "from-A")
        val remoteEntry = entry(clock = base.tick("B", 100), value = "from-B")
        val result = CrdtEngine.merge(localEntry, remoteEntry)
        assertIs<MergeResult.Conflict<StubPayload>>(result)
        assertEquals(localEntry, result.local)
        assertEquals(remoteEntry, result.remote)
    }

    @Test
    fun `merge propagates soft delete when delete clock dominates`() {
        val base = VectorClock.EMPTY.tick("A", 100)
        val localEntry = entry(clock = base, value = "alive")
        val remoteEntry = entry(
            clock = base.tick("A", 200),
            value = null,
            deleted = true,
            deletedAt = 200L,
            updatedAt = 200L,
        )
        val result = CrdtEngine.merge(localEntry, remoteEntry)
        assertIs<MergeResult.Clean<StubPayload>>(result)
        assertTrue(result.entry.deleted)
        assertEquals(remoteEntry, result.entry)
    }

    @Test
    fun `merge is idempotent`() {
        val base = VectorClock.EMPTY
        val localEntry = entry(clock = base.tick("A", 100), value = "a")
        val remoteEntry = entry(clock = base.tick("A", 200), value = "b")

        val first = CrdtEngine.merge(localEntry, remoteEntry)
        val second = CrdtEngine.merge(localEntry, remoteEntry)
        assertEquals(first, second)

        assertIs<MergeResult.Clean<StubPayload>>(first)
        val resolved = first.entry
        val remerged = CrdtEngine.merge(resolved, remoteEntry)
        assertIs<MergeResult.Clean<StubPayload>>(remerged)
        assertEquals(resolved, remerged.entry)
    }

    @Test
    fun `concurrent clocks with identical payloads collapse to clean`() {
        val base = VectorClock.EMPTY
        val same = StubPayload("same-value")
        val localEntry = SyncEntry(
            id = "e1",
            payload = same,
            clock = base.tick("A", 100),
            deleted = false,
            deletedAt = null,
            updatedAt = 100,
        )
        val remoteEntry = SyncEntry(
            id = "e1",
            payload = same,
            clock = base.tick("B", 100),
            deleted = false,
            deletedAt = null,
            updatedAt = 100,
        )
        val result = CrdtEngine.merge(localEntry, remoteEntry)
        assertIs<MergeResult.Clean<StubPayload>>(result)
        // Both device counters preserved in the merged clock (union of entries)
        assertTrue(result.entry.clock.entries.keys.containsAll(setOf("A", "B")))
    }

    @Test
    fun `concurrent clocks with identical tombstones collapse to clean`() {
        val base = VectorClock.EMPTY
        val localEntry = SyncEntry<StubPayload>(
            id = "e1",
            payload = null,
            clock = base.tick("A", 100),
            deleted = true,
            deletedAt = 100,
            updatedAt = 100,
        )
        val remoteEntry = SyncEntry<StubPayload>(
            id = "e1",
            payload = null,
            clock = base.tick("B", 100),
            deleted = true,
            deletedAt = 100,
            updatedAt = 100,
        )
        val result = CrdtEngine.merge(localEntry, remoteEntry)
        assertIs<MergeResult.Clean<StubPayload>>(result)
    }
}
