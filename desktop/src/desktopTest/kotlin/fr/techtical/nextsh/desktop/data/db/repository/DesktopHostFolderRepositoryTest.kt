// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHostFolderRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: NextShDatabase
    private lateinit var hostRepository: DesktopHostRepository
    private lateinit var folderRepository: DesktopHostFolderRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NextShDatabase.Schema.create(driver)
        db = NextShDatabase(driver)
        hostRepository = DesktopHostRepository(db)
        folderRepository = DesktopHostFolderRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `observeAllWithHosts groups by host group sorted alphabetically`() = runTest {
        hostRepository.save(makeHost("h1", "Mairie", group = "Collectivites"))
        hostRepository.save(makeHost("h2", "CC Rhone", group = "Collectivites"))
        hostRepository.save(makeHost("h3", "Anti-Spiral", group = "Production"))

        val result = folderRepository.observeAllWithHosts().first()

        assertEquals(2, result.size)
        assertEquals("Collectivites", result[0].folder?.name)
        assertEquals(listOf("CC Rhone", "Mairie"), result[0].hosts.map { it.label })
        assertEquals("Production", result[1].folder?.name)
        assertEquals(listOf("Anti-Spiral"), result[1].hosts.map { it.label })
    }

    @Test
    fun `observeAllWithHosts puts ungrouped hosts in orphan bucket first`() = runTest {
        hostRepository.save(makeHost("h1", "Alpha", group = null))
        hostRepository.save(makeHost("h2", "Beta", group = "Production"))
        hostRepository.save(makeHost("h3", "Gamma", group = ""))

        val result = folderRepository.observeAllWithHosts().first()

        assertEquals(2, result.size)
        assertNull(result[0].folder)
        assertEquals(listOf("Alpha", "Gamma"), result[0].hosts.map { it.label })
        assertEquals("Production", result[1].folder?.name)
    }

    @Test
    fun `observeAllWithHosts omits orphan bucket when all hosts have a group`() = runTest {
        hostRepository.save(makeHost("h1", "Alpha", group = "Production"))
        hostRepository.save(makeHost("h2", "Beta", group = "Production"))

        val result = folderRepository.observeAllWithHosts().first()

        assertEquals(1, result.size)
        assertEquals("Production", result[0].folder?.name)
    }

    @Test
    fun `observeAllWithHosts emits empty list when no hosts exist`() = runTest {
        val result = folderRepository.observeAllWithHosts().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `observeAllGroups returns distinct non-blank groups`() = runTest {
        hostRepository.save(makeHost("h1", "A", group = "Production"))
        hostRepository.save(makeHost("h2", "B", group = "Production"))
        hostRepository.save(makeHost("h3", "C", group = "Dev"))
        hostRepository.save(makeHost("h4", "D", group = null))
        hostRepository.save(makeHost("h5", "E", group = ""))

        val groups = folderRepository.observeAllGroups().first()

        assertEquals(setOf("Production", "Dev"), groups.toSet())
    }

    private fun makeHost(id: String, label: String, group: String?): Host = Host(
        id = id,
        label = label,
        hostname = "$id.local",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        group = group,
        keepAliveSeconds = 30,
        autoReconnect = true,
        terminalTheme = "TECHTICAL_DARK",
        fido2Mode = null,
        isFavorite = false,
    )
}
