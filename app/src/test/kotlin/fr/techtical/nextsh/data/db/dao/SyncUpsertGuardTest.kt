// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db.dao

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-level guard for the one annotation that carries the sync fix: `HostDao.upsert` and
 * `TunnelDao.upsert` must be Room's `@Upsert` (UPDATE in place on primary-key conflict), never
 * `@Insert(onConflict = REPLACE)`.
 *
 * The mocked-DAO repository tests cannot see the annotation, and reflection cannot either:
 * `@Upsert` has CLASS retention, so it is absent at runtime. Same compromise as
 * `SshSessionManagerConnectTimeoutTest.productionStillRoutesThroughTheResolver`: a cheap source
 * check, skipped (not failed) when the file cannot be located. A real cascade test would need an
 * instrumented in-memory Room database, and there is no `androidTest` source set.
 */
class SyncUpsertGuardTest {

    /**
     * Locates a DAO source by walking up from the test's working directory, which Gradle sets to
     * the module directory (`app/`) but which other runners may point at the repository root.
     */
    private fun locateDaoSource(fileName: String): File? {
        val fromModuleRoot = "src/main/kotlin/fr/techtical/nextsh/data/db/dao/$fileName"
        val fromRepoRoot = "app/$fromModuleRoot"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, fromModuleRoot).takeIf { it.isFile }?.let { return it }
            File(dir, fromRepoRoot).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun assertUpsertDoesNotReplace(fileName: String) {
        val source = locateDaoSource(fileName)
        assumeTrue(
            source != null,
            "$fileName not found from working dir ${System.getProperty("user.dir")}: guard skipped",
        )
        val text = source!!.readText()
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
    }

    @Test
    fun `HostDao upsert is a Room Upsert and not a REPLACE insert`() {
        assertUpsertDoesNotReplace("HostDao.kt")
    }

    @Test
    fun `TunnelDao upsert is a Room Upsert and not a REPLACE insert`() {
        assertUpsertDoesNotReplace("TunnelDao.kt")
    }
}
