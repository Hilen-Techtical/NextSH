// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The auto-start list leaves out the tunnels whose host is deleted: they cannot start and used to fail with
 * an error at every launch. Foreign keys ON, as in production.
 */
class DesktopTunnelRepositoryAutoStartTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var repository: DesktopTunnelRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.enableForeignKeys()
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        repository = DesktopTunnelRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `auto-start returns the auto-start tunnels of live hosts, ordered by label`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t-b", hostId = "h1", autoStart = true)
        db.insertTunnelRow("t-a", hostId = "h1", autoStart = true)

        assertEquals(listOf("t-a", "t-b"), repository.getAutoStartTunnels().map { it.id })
    }

    @Test
    fun `auto-start ignores a tunnel whose host is deleted`() = runTest {
        db.insertHostRow("h-live")
        db.insertHostRow("h-gone")
        db.insertTunnelRow("t-ok", hostId = "h-live", autoStart = true)
        db.insertTunnelRow("t-orphan", hostId = "h-gone", autoStart = true)
        db.tombstoneHostRowOnly("h-gone")

        assertEquals(listOf("t-ok"), repository.getAutoStartTunnels().map { it.id })
    }

    @Test
    fun `auto-start ignores tombstoned tunnels and tunnels without the auto-start flag`() = runTest {
        db.insertHostRow("h1")
        db.insertTunnelRow("t-ok", hostId = "h1", autoStart = true)
        db.insertTunnelRow("t-manual", hostId = "h1", autoStart = false)
        db.insertTunnelRow("t-deleted", hostId = "h1", autoStart = true)
        repository.delete("t-deleted")

        assertEquals(listOf("t-ok"), repository.getAutoStartTunnels().map { it.id })
    }
}
