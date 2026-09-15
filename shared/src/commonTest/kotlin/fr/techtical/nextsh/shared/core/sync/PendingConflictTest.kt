// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingConflictTest {

    @Test
    fun `pending conflict serializes all fields to json`() {
        val conflict = PendingConflict(
            id = "conflict-uuid-1",
            entityType = SyncableEntityType.HOST,
            entityId = "host-42",
            localJson = """{"id":"host-42"}""",
            remoteJson = """{"id":"host-42","label":"updated"}""",
            detectedAt = 1_700_000_000_000L,
        )

        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(PendingConflict.serializer(), conflict)
        val deserialized = json.decodeFromString(PendingConflict.serializer(), serialized)

        assertEquals(conflict, deserialized)
    }
}
