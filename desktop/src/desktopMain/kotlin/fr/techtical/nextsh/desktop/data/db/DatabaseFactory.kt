// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.desktop.db.NextShDatabase
import java.io.File

class DesktopDatabase internal constructor(
    val db: NextShDatabase,
    val driver: SqlDriver,
) {
    /** Truncate every table: used by wipeVault so SQL metadata matches the emptied vault. */
    fun wipeAllTables() {
        driver.execute(null, "DELETE FROM pending_conflicts", 0)
        driver.execute(null, "DELETE FROM enrolled_devices", 0)
        driver.execute(null, "DELETE FROM tunnels", 0)
        driver.execute(null, "DELETE FROM hosts", 0)
        driver.execute(null, "DELETE FROM ssh_keys", 0)
        driver.execute(null, "DELETE FROM snippets", 0)
        driver.execute(null, "DELETE FROM custom_terminal_themes", 0)
    }
}

object DatabaseFactory {

    fun create(): DesktopDatabase {
        StartupTrace.mark("DatabaseFactory.create() entered")
        val dbPath = resolveDbPath()
        val dbFile = File(dbPath)
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        val parent = dbFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Impossible de créer le répertoire du vault : ${parent.absolutePath}")
        }

        val driverT0 = System.nanoTime()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
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

        StartupTrace.mark("DatabaseFactory.create() done")
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
