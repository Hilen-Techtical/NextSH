// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-level guard for the one annotation that carries the sync fix: `HostDao.upsert` and
 * `TunnelDao.upsert` must be Room's `@Upsert` (UPDATE in place on primary-key conflict), never
 * `@Insert(onConflict = REPLACE)`.
 *
 * The mocked-DAO repository tests cannot see the annotation, and reflection cannot either:
 * `@Upsert` has CLASS retention, so it is absent at runtime. Same compromise as
 * `SshSessionManagerConnectTimeoutTest.productionStillRoutesThroughTheResolver`: a cheap source
 * check. A file that cannot be found FAILS the test (see [ProjectFiles]), so it can never be
 * silently off. A real cascade test would need an instrumented in-memory Room database, and there
 * is no `androidTest` source set.
 */
class SyncUpsertGuardTest {

    private fun assertUpsertDoesNotReplace(fileName: String) {
        val text = ProjectFiles.mainSource("data/db/dao/$fileName").readText()
        val why = "REPLACE = delete + insert: on a host it cascades to its tunnels " +
            "(tunnels.hostId is ON DELETE CASCADE) at every sync pull. Tunnels follow the same rule."

        assertTrue(
            Regex("""@Upsert\s+suspend fun upsert\(""").containsMatchIn(text),
            "$fileName: upsert must be annotated with Room's @Upsert. $why",
        )
        assertFalse(
            Regex("""REPLACE\)\s*suspend fun upsert\(""").containsMatchIn(text),
            "$fileName: upsert must not be @Insert(onConflict = REPLACE). $why",
        )
        // The REPLACE inserts that used to sit next to upsert were removed (no caller left):
        // none may come back, a REPLACE insert is one stray call away from the cascade.
        assertFalse(
            text.contains("OnConflictStrategy.REPLACE"),
            "$fileName: no @Insert may use OnConflictStrategy.REPLACE. $why",
        )
    }

    @Test
    fun `HostDao upsert is a Room Upsert and not a REPLACE insert`() {
        assertUpsertDoesNotReplace("HostDao.kt")
    }

    @Test
    fun `TunnelDao upsert is a Room Upsert and not a REPLACE insert`() {
        assertUpsertDoesNotReplace("TunnelDao.kt")
    }

    // ssh_keys has no foreign key child today, so REPLACE would not cascade; the key save path
    // updates in place anyway (it keeps createdAt and the Keystore binding), and a future foreign
    // key on ssh_keys must not start deleting rows silently.
    @Test
    fun `SshKeyDao upsert is a Room Upsert and not a REPLACE insert`() {
        assertUpsertDoesNotReplace("SshKeyDao.kt")
    }
}
