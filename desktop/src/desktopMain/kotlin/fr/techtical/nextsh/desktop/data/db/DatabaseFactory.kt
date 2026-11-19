// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.desktop.db.NextShDatabase
import java.io.File
import java.util.Properties

class DesktopDatabase internal constructor(
    val db: NextShDatabase,
    val driver: SqlDriver,
) {
    /**
     * Empties every table (known hosts aside): used by wipeVault so SQL metadata matches the emptied vault.
     *
     * One transaction: a failure in the middle leaves the tables as they were, never half erased. Goes through
     * the generated `deleteAll` queries and not raw SQL: only those notify the SQLDelight listeners, so the
     * flows that observe the tables (host list, tunnel watches, conflict badge) see the empty list at once
     * instead of keeping the old content. Tunnels go before hosts (a foreign key cascades from one to the
     * other, the order only spares the cascade).
     */
    fun wipeAllTables() {
        db.transaction {
            db.pendingConflictQueries.deleteAll()
            db.enrolledDeviceQueries.deleteAll()
            db.tunnelQueries.deleteAll()
            db.hostQueries.deleteAll()
            db.sshKeyQueries.deleteAll()
            db.snippetQueries.deleteAll()
            db.customTerminalThemeQueries.deleteAll()
        }
    }
}

object DatabaseFactory {

    fun create(): DesktopDatabase {
        StartupTrace.mark("DatabaseFactory.create() entered")
        val database = openAt(resolveDbPath())
        StartupTrace.mark("DatabaseFactory.create() done")
        return database
    }

    /**
     * Opens (creating or migrating when needed) the database file at [dbPath]. Split from [create]
     * so a test can run the real startup path, migrations included, on a file of its own.
     *
     * Every connection has foreign keys ENFORCED (`tunnels.hostId` references `hosts.id`, ON DELETE
     * CASCADE), migrations included.
     *
     * WARNING for a future migration that rebuilds a table other tables point at (`hosts` today): with
     * foreign keys on, `DROP TABLE hosts` deletes every tunnel through the cascade. SQLite cannot switch
     * `foreign_keys` off inside a transaction either, and this driver's connections are all opened with it
     * on. Such a rebuild must run on a separate connection opened WITHOUT the property (the
     * `PRAGMA foreign_keys = OFF` / rebuild / `PRAGMA foreign_key_check` / `ON` recipe of the SQLite
     * documentation), before the driver below is created.
     */
    internal fun openAt(dbPath: String): DesktopDatabase {
        val dbFile = File(dbPath)
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        val parent = dbFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Impossible de créer le répertoire du vault : ${parent.absolutePath}")
        }

        val driverT0 = System.nanoTime()
        // Foreign keys are a PER-CONNECTION setting in SQLite, off by default. A file-based JdbcSqliteDriver
        // opens a new connection for each statement outside a transaction, so a `PRAGMA foreign_keys = ON`
        // executed once on the driver only ever reached one throwaway connection. The sqlite-jdbc property
        // is applied by SQLiteConfig on EVERY connection the driver opens.
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath", Properties().apply { put("foreign_keys", "true") })
        StartupTrace.async("JdbcSqliteDriver open (isNew=$isNew)", (System.nanoTime() - driverT0) / 1_000_000)

        if (isNew) {
            val schemaT0 = System.nanoTime()
            NextShDatabase.Schema.create(driver)
            setUserVersion(driver, NextShDatabase.Schema.version.toInt())
            StartupTrace.async("Schema.create v${NextShDatabase.Schema.version}", (System.nanoTime() - schemaT0) / 1_000_000)
        } else {
            val currentVersion = queryUserVersion(driver)
            val targetVersion = NextShDatabase.Schema.version.toInt()
            if (currentVersion < targetVersion) {
                val migrateT0 = System.nanoTime()
                NextShDatabase.Schema.migrate(driver, currentVersion.toLong(), targetVersion.toLong())
                setUserVersion(driver, targetVersion)
                StartupTrace.async("Schema.migrate v$currentVersion→v$targetVersion", (System.nanoTime() - migrateT0) / 1_000_000)
            } else {
                StartupTrace.mark("Schema up to date (v$currentVersion)")
            }
        }

        return DesktopDatabase(NextShDatabase(driver), driver)
    }

    private fun queryUserVersion(driver: SqlDriver): Int {
        var version = 0
        driver.executeQuery(null, "PRAGMA user_version", parameters = 0, mapper = { cursor ->
            if (cursor.next().value) {
                version = cursor.getLong(0)?.toInt() ?: 0
            }
            app.cash.sqldelight.db.QueryResult.Unit
        })
        return version
    }

    private fun setUserVersion(driver: SqlDriver, version: Int) {
        // PRAGMA user_version = N cannot use bound parameters: inline the value directly.
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    private fun resolveDbPath(): String {
        val override = System.getenv("NEXTSH_HOME")
        val dir = if (override != null && override.isNotBlank()) override
        else "${System.getProperty("user.home")}/.nextsh"
        return "$dir/nextsh.db"
    }
}
