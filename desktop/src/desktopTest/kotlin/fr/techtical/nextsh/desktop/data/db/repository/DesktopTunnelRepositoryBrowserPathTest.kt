// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `TunnelConfig.browserPath` must round-trip through every write and read path of the repository. */
class DesktopTunnelRepositoryBrowserPathTest {

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
        db.insertHostRow("h1")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun tunnel(browserPath: String) = TunnelConfig(
        id = "t1",
        label = "Grafana",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 3000,
        remoteHost = "grafana.internal",
        remotePort = 3001,
        autoStart = true,
        openBrowserOnConnect = true,
        keepAliveAfterBrowserClose = false,
        isFavorite = true,
        browserPath = browserPath,
    )

    @Test
    fun `save then read keeps every field including a non default browserPath`() = runTest {
        val config = tunnel("/d/abc?orgId=1#top")

        repository.save(config)

        assertEquals(config, repository.getById("t1"))
    }

    @Test
    fun `a default tunnel is stored with the root path`() = runTest {
        repository.save(tunnel("/"))

        assertEquals("/", db.tunnelQueries.selectById("t1").executeAsOne().browserPath)
    }

    @Test
    fun `update changes the browserPath and leaves the other fields alone`() = runTest {
        repository.save(tunnel("/old"))

        repository.update(tunnel("/new?x=1"))

        assertEquals(tunnel("/new?x=1"), repository.getById("t1"))
    }

    @Test
    fun `every read path maps the browserPath`() = runTest {
        repository.save(tunnel("/admin"))

        assertEquals("/admin", repository.getAutoStartTunnels().single().browserPath)
        assertEquals("/admin", repository.getAllSyncEntries().single().payload?.browserPath)
    }

    @Test
    fun `setFavorite leaves the browserPath alone`() = runTest {
        repository.save(tunnel("/admin").copy(isFavorite = false))

        repository.setFavorite("t1", true)

        assertEquals("/admin", repository.getById("t1")?.browserPath)
    }
}
