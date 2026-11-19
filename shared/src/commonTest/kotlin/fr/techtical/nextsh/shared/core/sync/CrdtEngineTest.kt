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

    // ── keepLocal ──────────────────────────────────────────────────────────

    private fun concurrentPair(): Pair<SyncEntry<StubPayload>, SyncEntry<StubPayload>> {
        val base = VectorClock.EMPTY
        val local = entry(clock = base.tick("A", 100), value = "from-A", updatedAt = 100)
        val remote = entry(clock = base.tick("B", 100), value = "from-B", updatedAt = 100)
        assertIs<MergeResult.Conflict<StubPayload>>(CrdtEngine.merge(local, remote))
        return local to remote
    }

    @Test
    fun `keepLocal produces a clock that dominates both versions`() {
        val (local, remote) = concurrentPair()

        val kept = CrdtEngine.keepLocal(local, remote.clock, "A", 500)

        assertTrue(kept.clock.dominates(local.clock), "must dominate the local clock")
        assertTrue(kept.clock.dominates(remote.clock), "must dominate the remote clock")
    }

    @Test
    fun `keepLocal keeps the payload and refreshes only the clock and updatedAt`() {
        val (local, remote) = concurrentPair()

        val kept = CrdtEngine.keepLocal(local, remote.clock, "A", 500)

        assertEquals(local.id, kept.id)
        assertEquals(local.payload, kept.payload)
        assertEquals(local.deleted, kept.deleted)
        assertEquals(local.deletedAt, kept.deletedAt)
        assertEquals(500L, kept.updatedAt)
    }

    @Test
    fun `merge of keepLocal against the remote is clean and carries the local payload`() {
        val (local, remote) = concurrentPair()
        val kept = CrdtEngine.keepLocal(local, remote.clock, "A", 500)

        // Device A side: no conflict re-detected, the kept version stays.
        val onA = CrdtEngine.merge(kept, remote)
        assertIs<MergeResult.Clean<StubPayload>>(onA)
        assertEquals(StubPayload("from-A"), onA.entry.payload)
        assertEquals(kept, onA.entry)

        // Device B side: the kept version arrives as a remote entry and wins cleanly.
        val onB = CrdtEngine.merge(remote, kept)
        assertIs<MergeResult.Clean<StubPayload>>(onB)
        assertEquals(StubPayload("from-A"), onB.entry.payload)
        assertEquals(kept, onB.entry)
    }

    @Test
    fun `keepLocal on a tombstone keeps the deletion and wins over a live remote`() {
        val base = VectorClock.EMPTY
        val local = entry(
            clock = base.tick("A", 100),
            value = null,
            deleted = true,
            deletedAt = 100L,
            updatedAt = 100L,
        )
        val remote = entry(clock = base.tick("B", 100), value = "edited-on-B", updatedAt = 100)
        assertIs<MergeResult.Conflict<StubPayload>>(CrdtEngine.merge(local, remote))

        val kept = CrdtEngine.keepLocal(local, remote.clock, "A", 700)

        assertTrue(kept.deleted)
        assertEquals(100L, kept.deletedAt)
        assertEquals(null, kept.payload)
        assertEquals(700L, kept.updatedAt)
        val onB = CrdtEngine.merge(remote, kept)
        assertIs<MergeResult.Clean<StubPayload>>(onB)
        assertTrue(onB.entry.deleted, "the peer must accept the deletion")
        val onA = CrdtEngine.merge(kept, remote)
        assertIs<MergeResult.Clean<StubPayload>>(onA)
        assertTrue(onA.entry.deleted)
    }

    @Test
    fun `keepLocal still strictly increases when the device counter is ahead of the wall clock`() {
        val ahead = VectorClock(mapOf("A" to 10_000L))
        val local = entry(clock = ahead, value = "from-A")
        val remote = entry(clock = VectorClock(mapOf("B" to 50L)), value = "from-B")

        // Wall clock far behind the counter (clock skew): the tick must use counter + 1.
        val kept = CrdtEngine.keepLocal(local, remote.clock, "A", 500)

        assertEquals(10_001L, kept.clock.entries["A"])
        assertEquals(50L, kept.clock.entries["B"])
        assertTrue(kept.clock.dominates(local.clock))
        assertTrue(kept.clock.dominates(remote.clock))
    }

    @Test
    fun `keepLocal ticks even when the local clock already dominates the remote one`() {
        val remote = entry(clock = VectorClock.EMPTY.tick("A", 100), value = "old")
        val local = entry(clock = remote.clock.tick("A", 200), value = "new")

        val kept = CrdtEngine.keepLocal(local, remote.clock, "A", 300)

        assertTrue(kept.clock.dominates(local.clock), "the tick makes the result strictly newer")
    }

    @Test
    fun `two simultaneous keepLocal choices stay concurrent instead of diverging silently`() {
        val (a, b) = concurrentPair()

        val keptByA = CrdtEngine.keepLocal(a, b.clock, "A", 500)
        val keptByB = CrdtEngine.keepLocal(b, a.clock, "B", 500)

        // Without the tick both clocks would be equal while the payloads differ, and merge
        // would answer Clean(local) on both sides: permanent silent divergence.
        assertTrue(keptByA.clock != keptByB.clock)
        assertTrue(keptByA.clock.concurrentWith(keptByB.clock))
        assertIs<MergeResult.Conflict<StubPayload>>(CrdtEngine.merge(keptByA, keptByB))
    }
}
