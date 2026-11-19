// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import fr.techtical.nextsh.testutil.ProjectFiles
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the one mistake that wipes the user's whole database: bumping
 * `AppDatabase.version` without registering the matching migration.
 *
 * `DatabaseModule` builds Room with `.fallbackToDestructiveMigration()`. When no registered
 * migration path leads from the installed version to the current one, Room does not fail, it
 * silently DROPS every table and recreates them empty: hosts, tunnels, keys, snippets, all
 * gone, with no error and no log. The registration cannot be seen from a mocked repository
 * test, and there is no instrumented in-memory Room test (no `androidTest` source set), so
 * this is a cheap source-level check, same compromise as [fr.techtical.nextsh.data.db.dao.SyncUpsertGuardTest]:
 * a source that cannot be found FAILS the test (see [ProjectFiles]), so the guard can never be
 * silently off.
 *
 * A second group of tests runs the migration itself against a mocked database and checks
 * the SQL it issues, so the DDL is pinned without needing Room's schema JSON.
 */
class AppDatabaseMigrationGuardTest {

    private val appDatabasePath = "src/main/kotlin/fr/techtical/nextsh/data/db/AppDatabase.kt"
    private val databaseModulePath = "src/main/kotlin/fr/techtical/nextsh/core/di/DatabaseModule.kt"

    private val whyItMatters =
        "DatabaseModule uses fallbackToDestructiveMigration(): a missing migration path does not " +
            "crash, it silently wipes the user's database on upgrade."

    /** Source text without comments, so a commented-out registration never counts. */
    private fun readSource(fromModuleRoot: String): String =
        ProjectFiles.appFile(fromModuleRoot).readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")

    private fun databaseVersion(appDatabase: String): Int {
        val match = Regex("""@Database\([\s\S]*?\bversion\s*=\s*(\d+)""").find(appDatabase)
        assertTrue(match != null, "could not read the version of @Database in AppDatabase.kt")
        return match!!.groupValues[1].toInt()
    }

    /** `val MIGRATION_a_b = object : Migration(a, b)`, as (name pair, constructor pair). */
    private data class Declared(val nameFrom: Int, val nameTo: Int, val ctorFrom: Int, val ctorTo: Int)

    private fun declaredMigrations(appDatabase: String): List<Declared> =
        Regex("""val\s+MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
            .findAll(appDatabase)
            .map { Declared(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt()) }
            .toList()

    /** The `AppDatabase.MIGRATION_a_b` references inside `.addMigrations( ... )`. */
    private fun registeredMigrations(databaseModule: String): List<Pair<Int, Int>> {
        val block = Regex("""\.addMigrations\(([^)]*)\)""").find(databaseModule)
        assertTrue(block != null, "no .addMigrations(...) call found in DatabaseModule.kt. $whyItMatters")
        return Regex("""AppDatabase\.MIGRATION_(\d+)_(\d+)""")
            .findAll(block!!.groupValues[1])
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
    }

    @Test
    fun `every version up to the current one is reachable through registered migrations`() {
        val version = databaseVersion(readSource(appDatabasePath))
        val registered = registeredMigrations(readSource(databaseModulePath)).toSet()

        val missing = (1 until version).filter { (it to it + 1) !in registered }

        assertTrue(
            missing.isEmpty(),
            "AppDatabase is at version $version but DatabaseModule.addMigrations has no " +
                "MIGRATION_n_(n+1) for n = $missing. $whyItMatters",
        )
    }

    @Test
    fun `registered migrations are single steps that stay within the current version`() {
        val version = databaseVersion(readSource(appDatabasePath))
        val registered = registeredMigrations(readSource(databaseModulePath))

        val odd = registered.filter { (from, to) -> to != from + 1 || from < 1 || to > version }

        assertTrue(
            odd.isEmpty(),
            "registered migrations must each be one version step within 1..$version, found $odd",
        )
    }

    @Test
    fun `no registered migration is listed twice`() {
        val registered = registeredMigrations(readSource(databaseModulePath))

        assertEquals(registered.toSet().size, registered.size, "duplicate registration in $registered")
    }

    @Test
    fun `every migration declared in AppDatabase is registered in DatabaseModule`() {
        val declared = declaredMigrations(readSource(appDatabasePath)).map { it.nameFrom to it.nameTo }
        val registered = registeredMigrations(readSource(databaseModulePath)).toSet()

        val unregistered = declared.filter { it !in registered }

        assertTrue(
            unregistered.isEmpty(),
            "declared in AppDatabase but missing from DatabaseModule.addMigrations: $unregistered. $whyItMatters",
        )
    }

    @Test
    fun `a migration is named after the versions it actually migrates`() {
        val declared = declaredMigrations(readSource(appDatabasePath))

        assertTrue(declared.isNotEmpty(), "no migration found in AppDatabase.kt: the guard regex is stale")
        val misnamed = declared.filter { it.nameFrom != it.ctorFrom || it.nameTo != it.ctorTo }
        assertTrue(
            misnamed.isEmpty(),
            "MIGRATION_a_b must be Migration(a, b), otherwise the chain check above lies: $misnamed",
        )
    }

    @Test
    fun `the current version has a migration declared for it`() {
        val appDatabase = readSource(appDatabasePath)
        val version = databaseVersion(appDatabase)

        val declared = declaredMigrations(appDatabase).map { it.nameFrom to it.nameTo }

        assertTrue(
            (version - 1 to version) in declared,
            "AppDatabase is at version $version but MIGRATION_${version - 1}_$version is not declared. $whyItMatters",
        )
    }

    // ── Behaviour of the migration itself ───────────────────────────────────

    @Test
    fun `MIGRATION_12_13 migrates from 12 to 13`() {
        assertEquals(12, AppDatabase.MIGRATION_12_13.startVersion)
        assertEquals(13, AppDatabase.MIGRATION_12_13.endVersion)
    }

    @Test
    fun `MIGRATION_12_13 issues the browserPath ALTER and nothing else`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        AppDatabase.MIGRATION_12_13.migrate(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE tunnels ADD COLUMN browserPath TEXT NOT NULL DEFAULT '/'")
        }
        verify(exactly = 1) { db.execSQL(any()) }
    }
}
